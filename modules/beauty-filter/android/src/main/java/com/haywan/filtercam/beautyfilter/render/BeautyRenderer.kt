package com.haywan.filtercam.beautyfilter.render

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.os.SystemClock
import com.haywan.filtercam.beautyfilter.gl.Framebuffer
import com.haywan.filtercam.beautyfilter.gl.ScreenQuad
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Orchestrates the per-frame beauty pipeline on the GL thread.
 *
 * **Frame-locked display + detection:** each camera frame arrives via
 * [submitFrame] *together with the landmarks detected on that exact frame*, and
 * the two are swapped in atomically on the GL thread. The warp therefore never
 * slides off the face during motion — the whole pipeline (image + effect) is
 * delayed together by the detect time, which is invisible, instead of the
 * effect lagging the image, which is not.
 *
 * Pipeline per frame:
 *  1. [CameraPass]     — frame texture -> scene FBO (cover-crop, y-flip).
 *  2. [BlurPass]       — scene -> quarter-res two-pass gaussian blur.
 *  3. [FaceMaskPass]   — two feathered masks: skin (oval minus eyes/brows/lips)
 *                        for smoothing, and the full oval for tone/light.
 *  4. [CompositePass]  — beauty adjustments into beautyFbo.
 *  5. [FaceReshapePass]— eyes/nose/jaw liquify -> finalFbo.
 *  6. [MustachePass]/[FaceMeshPass] — overlays onto finalFbo.
 *  7. [BlitPass]       — finalFbo -> screen (+ stream surface).
 */
internal class BeautyRenderer(
    private val onGlReady: () -> Unit,
) : GLSurfaceView.Renderer {

    // ---- beauty parameters (each an independent "filter", 0..1) ----
    @Volatile var smoothing = 0.5f
    @Volatile var glow = 0.3f
    @Volatile var clarity = 0.4f
    @Volatile var warmth = 0.15f
    @Volatile var eyeEnlarge = 0f
    @Volatile var noseSlim = 0f
    @Volatile var faceSlim = 0f
    @Volatile var mustacheEnabled = false
    @Volatile var faceMeshEnabled = false

    // ---- frame + its paired landmarks (submitted together) ----
    private val frameLock = Any()
    private var pendingBitmap: Bitmap? = null
    private var pendingFaces: Array<FloatArray> = emptyArray()
    private var faces: Array<FloatArray> = emptyArray() // GL thread only
    @Volatile private var pendingCapture: ((Bitmap) -> Unit)? = null

    private var frameTexture = 0
    private var frameWidth = 0
    private var frameHeight = 0

    private val viewport = Viewport()
    private val quad = ScreenQuad()

    // Passes build GL programs, so they are created on the GL thread (onSurfaceCreated).
    private lateinit var cameraPass: CameraPass
    private lateinit var blurPass: BlurPass
    private lateinit var maskPass: FaceMaskPass
    private lateinit var compositePass: CompositePass
    private lateinit var reshapePass: FaceReshapePass
    private lateinit var mustachePass: MustachePass
    private lateinit var meshPass: FaceMeshPass
    private lateinit var blitPass: BlitPass

    private var sceneFbo: Framebuffer? = null
    private var beautyFbo: Framebuffer? = null
    private var finalFbo: Framebuffer? = null
    private var blurA: Framebuffer? = null
    private var blurB: Framebuffer? = null
    private var maskA: Framebuffer? = null // final skin mask (smoothing)
    private var maskB: Framebuffer? = null // shared feather scratch
    private var maskC: Framebuffer? = null // final face mask (tone/light)

    // ---- streaming output (GPU-resident; see StreamOutput / docs/STREAMING.md) ----
    @Volatile private var pendingStreamSurface: android.view.Surface? = null
    @Volatile private var clearStream = false
    private var streamOutput: StreamOutput? = null

    /**
     * Deliver a camera frame (already rotated/mirrored to display orientation)
     * together with the landmarks detected on that exact frame. Called from the
     * analyzer thread every frame; uploaded + rendered on the GL thread.
     * Ownership of [bitmap] transfers here (recycled after upload).
     */
    fun submitFrame(bitmap: Bitmap, faces: Array<FloatArray>) {
        synchronized(frameLock) {
            pendingBitmap?.recycle() // drop an un-consumed previous frame
            pendingBitmap = bitmap
            pendingFaces = faces
        }
    }

    fun setStreamSurface(surface: android.view.Surface?) {
        if (surface == null) clearStream = true else pendingStreamSurface = surface
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        frameTexture = createFrameTexture()

        cameraPass = CameraPass()
        blurPass = BlurPass()
        maskPass = FaceMaskPass()
        compositePass = CompositePass()
        reshapePass = FaceReshapePass()
        mustachePass = MustachePass().apply { setup() }
        meshPass = FaceMeshPass()
        blitPass = BlitPass()

        onGlReady()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewport.setSize(width, height)
        releaseFbos()
        sceneFbo = Framebuffer(viewport.width, viewport.height)
        beautyFbo = Framebuffer(viewport.width, viewport.height)
        finalFbo = Framebuffer(viewport.width, viewport.height)
        val bw = (viewport.width / 4).coerceAtLeast(1)
        val bh = (viewport.height / 4).coerceAtLeast(1)
        blurA = Framebuffer(bw, bh)
        blurB = Framebuffer(bw, bh)
        maskA = Framebuffer(bw, bh)
        maskB = Framebuffer(bw, bh)
        maskC = Framebuffer(bw, bh)
    }

    override fun onDrawFrame(gl: GL10?) {
        // Upload the latest frame (if any) to the frame texture, adopting its
        // paired landmarks in the same swap. A redraw without a new frame (e.g.
        // takePicture) keeps the previous frame's landmarks — still matched.
        val bmp = synchronized(frameLock) {
            val b = pendingBitmap
            pendingBitmap = null
            if (b != null) faces = pendingFaces
            b
        }
        if (bmp != null) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameTexture)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            frameWidth = bmp.width
            frameHeight = bmp.height
            bmp.recycle()
        }
        if (frameWidth <= 0) return

        val scene = sceneFbo ?: return
        val beauty = beautyFbo ?: return
        val finalImage = finalFbo ?: return
        val bA = blurA ?: return
        val bB = blurB ?: return
        val mA = maskA ?: return
        val mB = maskB ?: return
        val mC = maskC ?: return

        viewport.updateCrop(0, frameWidth, frameHeight)
        val lms = faces
        val adjust = BeautyAdjustments.of(
            smoothing, glow, clarity, warmth, eyeEnlarge, noseSlim, faceSlim
        )

        // --- build the final filtered image into finalFbo ---
        cameraPass.draw(frameTexture, viewport, scene, quad)
        blurPass.blurBoth(scene.texture, bA, bB, quad)
        maskPass.draw(lms, viewport, mA, mC, mB, blurPass, quad)
        compositePass.draw(scene, bB, mA, mC, beauty, adjust, viewport, quad)
        reshapePass.draw(beauty, finalImage, lms, adjust, viewport, quad)
        if (mustacheEnabled && lms.isNotEmpty()) mustachePass.draw(lms, viewport)
        if (faceMeshEnabled && lms.isNotEmpty()) meshPass.draw(lms, viewport, finalImage)

        // --- present: to the screen, and to the stream surface if attached ---
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, viewport.width, viewport.height)
        blitPass.draw(finalImage.texture, viewport.width, viewport.height, quad)

        updateStreamOutput()
        streamOutput?.present(
            finalImage.texture, viewport.width, viewport.height,
            SystemClock.elapsedRealtimeNanos(), blitPass, quad
        )

        pendingCapture?.let { callback ->
            pendingCapture = null
            callback(readPixels())
        }
    }

    /** Applies a pending stream-surface change on the GL thread. */
    private fun updateStreamOutput() {
        if (clearStream) {
            streamOutput?.release()
            streamOutput = null
            clearStream = false
        }
        pendingStreamSurface?.let { surface ->
            streamOutput?.release()
            streamOutput = StreamOutput(surface)
            pendingStreamSurface = null
        }
    }

    /** Capture the next rendered frame (called from any thread). */
    fun captureNextFrame(callback: (Bitmap) -> Unit) {
        pendingCapture = callback
    }

    private fun createFrameTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun readPixels(): Bitmap {
        val w = viewport.width
        val h = viewport.height
        val buffer = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        buffer.position(0)
        bitmap.copyPixelsFromBuffer(buffer)
        // GL reads rows bottom-up; flip vertically.
        val m = android.graphics.Matrix().apply { setScale(1f, -1f) }
        val flipped = Bitmap.createBitmap(bitmap, 0, 0, w, h, m, false)
        bitmap.recycle()
        return flipped
    }

    private fun releaseFbos() {
        sceneFbo?.release(); sceneFbo = null
        beautyFbo?.release(); beautyFbo = null
        finalFbo?.release(); finalFbo = null
        blurA?.release(); blurA = null
        blurB?.release(); blurB = null
        maskA?.release(); maskA = null
        maskB?.release(); maskB = null
        maskC?.release(); maskC = null
    }
}
