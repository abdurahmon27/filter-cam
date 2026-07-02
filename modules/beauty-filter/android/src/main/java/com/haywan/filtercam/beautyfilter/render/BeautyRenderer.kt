package com.haywan.filtercam.beautyfilter.render

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.SystemClock
import com.haywan.filtercam.beautyfilter.gl.Framebuffer
import com.haywan.filtercam.beautyfilter.gl.GlUtils
import com.haywan.filtercam.beautyfilter.gl.ScreenQuad
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Orchestrates the per-frame beauty pipeline on the GL thread. State is pushed
 * in from the main/analyzer threads via the `@Volatile` fields; the actual GL
 * work is delegated to focused passes.
 *
 * Pipeline per frame:
 *  1. [CameraPass]  — OES camera texture -> scene FBO (rotation + cover-crop).
 *  2. [BlurPass]    — scene -> quarter-res two-pass gaussian blur.
 *  3. [FaceMaskPass]— face-mesh polygons -> quarter-res mask (oval minus eyes/
 *                     brows/lips), softened, for every tracked face.
 *  4. [CompositePass]— mix(scene, smoothed, mask * strength) with a youthful glow.
 *  5. [MustachePass]— mustache sprite per face (when enabled).
 *  6. [FaceMeshPass]— debug landmark dots per face (when enabled).
 */
internal class BeautyRenderer(
    private val onSurfaceTextureReady: (SurfaceTexture) -> Unit,
) : GLSurfaceView.Renderer {

    // ---- state pushed from the outside (main thread / analyzer thread) ----
    @Volatile var smoothing = 0.6f
    @Volatile var mustacheEnabled = false
    @Volatile var faceMeshEnabled = false
    @Volatile var faces: Array<FloatArray> = emptyArray() // per face: 478 * 2 normalized, display-oriented
    @Volatile var facesAt = 0L
    @Volatile var cameraRotationDegrees = 90
    @Volatile var cameraBufferWidth = 0
    @Volatile var cameraBufferHeight = 0
    @Volatile private var pendingCapture: ((Bitmap) -> Unit)? = null

    var surfaceTexture: SurfaceTexture? = null
        private set

    private var oesTexture = 0

    private val viewport = Viewport()
    private val quad = ScreenQuad()
    private val surfaceTexMatrix = FloatArray(16)

    // Passes build GL programs, so they are created on the GL thread (onSurfaceCreated).
    private lateinit var cameraPass: CameraPass
    private lateinit var blurPass: BlurPass
    private lateinit var maskPass: FaceMaskPass
    private lateinit var compositePass: CompositePass
    private lateinit var mustachePass: MustachePass
    private lateinit var meshPass: FaceMeshPass

    private var sceneFbo: Framebuffer? = null
    private var blurA: Framebuffer? = null
    private var blurB: Framebuffer? = null
    private var maskA: Framebuffer? = null
    private var maskB: Framebuffer? = null

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        oesTexture = GlUtils.createExternalTexture()
        surfaceTexture = SurfaceTexture(oesTexture)

        cameraPass = CameraPass()
        blurPass = BlurPass()
        maskPass = FaceMaskPass()
        compositePass = CompositePass()
        mustachePass = MustachePass().apply { setup() }
        meshPass = FaceMeshPass()

        surfaceTexture?.let(onSurfaceTextureReady)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewport.setSize(width, height)
        releaseFbos()
        sceneFbo = Framebuffer(viewport.width, viewport.height)
        val bw = (viewport.width / 4).coerceAtLeast(1)
        val bh = (viewport.height / 4).coerceAtLeast(1)
        blurA = Framebuffer(bw, bh)
        blurB = Framebuffer(bw, bh)
        maskA = Framebuffer(bw, bh)
        maskB = Framebuffer(bw, bh)
    }

    override fun onDrawFrame(gl: GL10?) {
        val st = surfaceTexture ?: return
        st.updateTexImage()
        st.getTransformMatrix(surfaceTexMatrix)
        viewport.updateCrop(cameraRotationDegrees, cameraBufferWidth, cameraBufferHeight)

        val scene = sceneFbo ?: return
        val bA = blurA ?: return
        val bB = blurB ?: return
        val mA = maskA ?: return
        val mB = maskB ?: return

        val faceFresh = SystemClock.uptimeMillis() - facesAt < 400
        val lms = if (faceFresh) faces else emptyArray()

        cameraPass.draw(oesTexture, surfaceTexMatrix, cameraRotationDegrees, viewport, scene, quad)
        blurPass.blurBoth(scene.texture, bA, bB, quad)
        maskPass.draw(lms, viewport, mA, mB, blurPass, quad)
        compositePass.draw(scene, bB, mA, smoothing, glow = smoothing, viewport = viewport, quad = quad)
        if (mustacheEnabled && lms.isNotEmpty()) mustachePass.draw(lms, viewport)
        if (faceMeshEnabled && lms.isNotEmpty()) meshPass.draw(lms, viewport)

        pendingCapture?.let { callback ->
            pendingCapture = null
            callback(readPixels())
        }
    }

    /** Capture the next rendered frame (called from any thread). */
    fun captureNextFrame(callback: (Bitmap) -> Unit) {
        pendingCapture = callback
    }

    fun releaseSurfaceTexture() {
        surfaceTexture?.release()
        surfaceTexture = null
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
        blurA?.release(); blurA = null
        blurB?.release(); blurB = null
        maskA?.release(); maskA = null
        maskB?.release(); maskB = null
    }
}
