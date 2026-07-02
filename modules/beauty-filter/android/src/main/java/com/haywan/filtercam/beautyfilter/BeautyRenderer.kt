package com.haywan.filtercam.beautyfilter

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Renders the camera feed with a face-only beauty filter and a landmark-anchored
 * mustache.
 *
 * Pipeline per frame:
 *  1. Camera OES texture -> scene FBO (rotation + center-crop applied, so the
 *     FBO holds exactly what is displayed).
 *  2. Scene -> quarter-res gaussian blur (two passes).
 *  3. Face-mesh polygons -> quarter-res mask FBO (face oval filled, eyes /
 *     brows / lips punched out), then blurred for a soft edge.
 *  4. Composite to screen: mix(scene, smoothed, mask * strength).
 *  5. Mustache sprite, positioned from lip/nose landmarks, alpha-blended.
 */
class BeautyRenderer(
    private val onSurfaceTextureReady: (SurfaceTexture) -> Unit,
) : GLSurfaceView.Renderer {

    // ---- state pushed from the outside (main thread / analyzer thread) ----
    @Volatile var smoothing = 0.6f
    @Volatile var mustacheEnabled = false
    @Volatile var faceMeshEnabled = false
    @Volatile var landmarks: FloatArray? = null // 478 * 2, normalized, display-oriented
    @Volatile var landmarksAt = 0L
    @Volatile var cameraRotationDegrees = 90
    @Volatile var cameraBufferWidth = 0
    @Volatile var cameraBufferHeight = 0
    @Volatile private var pendingCapture: ((Bitmap) -> Unit)? = null

    var surfaceTexture: SurfaceTexture? = null
        private set

    private var oesTexture = 0
    private var mustacheTexture = 0
    private var viewWidth = 1
    private var viewHeight = 1

    private var cameraProgram = 0
    private var blurProgram = 0
    private var solidProgram = 0
    private var compositeProgram = 0
    private var spriteProgram = 0
    private var pointProgram = 0

    private var sceneFbo: Fbo? = null
    private var blurA: Fbo? = null
    private var blurB: Fbo? = null
    private var maskA: Fbo? = null
    private var maskB: Fbo? = null

    private val quadPos = GlUtils.floatBuffer(
        floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    )
    private val quadUv = GlUtils.floatBuffer(
        floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
    )

    private val texMatrix = FloatArray(16)
    private val rotMatrix = FloatArray(16)
    private val combinedMatrix = FloatArray(16)

    // Scratch buffers for polygon fans (largest ring + centroid + closing vertex).
    private val fanBuffer = ByteBuffer.allocateDirect((FaceTopology.FACE_OVAL.size + 2) * 2 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val spriteBuffer = ByteBuffer.allocateDirect(4 * 4 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
    // All 478 landmarks as NDC points for the debug face-mesh overlay.
    private val meshBuffer = ByteBuffer.allocateDirect(478 * 2 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()

    // crop of the upright camera frame that is visible on screen
    private var cropScaleX = 1f
    private var cropScaleY = 1f
    private var cropOffX = 0f
    private var cropOffY = 0f

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        oesTexture = GlUtils.createExternalTexture()
        surfaceTexture = SurfaceTexture(oesTexture)
        mustacheTexture = MustacheTexture.create()

        cameraProgram = GlUtils.buildProgram(CAMERA_VS, CAMERA_FS)
        blurProgram = GlUtils.buildProgram(PASS_VS, BLUR_FS)
        solidProgram = GlUtils.buildProgram(SOLID_VS, SOLID_FS)
        compositeProgram = GlUtils.buildProgram(PASS_VS, COMPOSITE_FS)
        spriteProgram = GlUtils.buildProgram(SPRITE_VS, SPRITE_FS)
        pointProgram = GlUtils.buildProgram(POINT_VS, POINT_FS)

        surfaceTexture?.let(onSurfaceTextureReady)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewWidth = width.coerceAtLeast(1)
        viewHeight = height.coerceAtLeast(1)
        releaseFbos()
        sceneFbo = Fbo(viewWidth, viewHeight)
        val bw = (viewWidth / 4).coerceAtLeast(1)
        val bh = (viewHeight / 4).coerceAtLeast(1)
        blurA = Fbo(bw, bh)
        blurB = Fbo(bw, bh)
        maskA = Fbo(bw, bh)
        maskB = Fbo(bw, bh)
    }

    override fun onDrawFrame(gl: GL10?) {
        val st = surfaceTexture ?: return
        st.updateTexImage()
        st.getTransformMatrix(texMatrix)
        updateCrop()

        val scene = sceneFbo ?: return
        val bA = blurA ?: return
        val bB = blurB ?: return
        val mA = maskA ?: return
        val mB = maskB ?: return

        val faceFresh = SystemClock.uptimeMillis() - landmarksAt < 400
        val lms = if (faceFresh) landmarks else null

        drawCameraToScene(scene)
        blurPass(scene.texture, bA, bB)
        drawFaceMask(lms, mA, mB)
        composite(scene, bB, mA)
        if (mustacheEnabled && lms != null) drawMustache(lms)
        if (faceMeshEnabled && lms != null) drawFaceMesh(lms)

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

    // ------------------------------------------------------------------ //

    private fun updateCrop() {
        val bw = cameraBufferWidth
        val bh = cameraBufferHeight
        if (bw <= 0 || bh <= 0) {
            cropScaleX = 1f; cropScaleY = 1f; cropOffX = 0f; cropOffY = 0f
            return
        }
        // Crop aspect follows the landmark/display orientation (the source of
        // truth), independent of the preview texture's rotation offset.
        val rotated = cameraRotationDegrees % 180 != 0
        val uprightW = if (rotated) bh else bw
        val uprightH = if (rotated) bw else bh
        val camAspect = uprightW.toFloat() / uprightH
        val viewAspect = viewWidth.toFloat() / viewHeight
        if (camAspect > viewAspect) {
            cropScaleX = viewAspect / camAspect
            cropScaleY = 1f
        } else {
            cropScaleX = 1f
            cropScaleY = camAspect / viewAspect
        }
        cropOffX = (1f - cropScaleX) / 2f
        cropOffY = (1f - cropScaleY) / 2f
    }

    private fun drawCameraToScene(scene: Fbo) {
        // Rotate sampling coordinates around the UV center so the buffer shows
        // upright, then apply the SurfaceTexture matrix.
        Matrix.setIdentityM(rotMatrix, 0)
        Matrix.translateM(rotMatrix, 0, 0.5f, 0.5f, 0f)
        Matrix.rotateM(rotMatrix, 0, -(cameraRotationDegrees + PREVIEW_ROTATION_OFFSET).toFloat(), 0f, 0f, 1f)
        Matrix.translateM(rotMatrix, 0, -0.5f, -0.5f, 0f)
        Matrix.multiplyMM(combinedMatrix, 0, texMatrix, 0, rotMatrix, 0)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, scene.framebuffer)
        GLES20.glViewport(0, 0, scene.width, scene.height)
        GLES20.glUseProgram(cameraProgram)
        bindQuad(cameraProgram)
        GLES20.glUniformMatrix4fv(
            GLES20.glGetUniformLocation(cameraProgram, "uTexMatrix"), 1, false, combinedMatrix, 0
        )
        GLES20.glUniform4f(
            GLES20.glGetUniformLocation(cameraProgram, "uCrop"),
            cropOffX, cropOffY, cropScaleX, cropScaleY
        )
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(cameraProgram, "uTex"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun blurPass(sourceTexture: Int, a: Fbo, b: Fbo) {
        runBlur(sourceTexture, a, 1.5f / a.width, 0f)
        runBlur(a.texture, b, 0f, 1.5f / a.height)
    }

    private fun runBlur(sourceTexture: Int, target: Fbo, dx: Float, dy: Float) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target.framebuffer)
        GLES20.glViewport(0, 0, target.width, target.height)
        GLES20.glUseProgram(blurProgram)
        bindQuad(blurProgram)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(blurProgram, "uDir"), dx, dy)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sourceTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(blurProgram, "uTex"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun drawFaceMask(lms: FloatArray?, mA: Fbo, mB: Fbo) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mA.framebuffer)
        GLES20.glViewport(0, 0, mA.width, mA.height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (lms == null) return

        drawFan(lms, FaceTopology.FACE_OVAL, 1f, 1f)
        drawFan(lms, FaceTopology.LEFT_EYE, 0f, 1.6f)
        drawFan(lms, FaceTopology.RIGHT_EYE, 0f, 1.6f)
        drawFan(lms, FaceTopology.LEFT_BROW, 0f, 1.4f)
        drawFan(lms, FaceTopology.RIGHT_BROW, 0f, 1.4f)
        drawFan(lms, FaceTopology.LIPS_OUTER, 0f, 1.1f)

        // Soften the mask edges (two blur passes, ending back in maskA).
        runBlur(mA.texture, mB, 2f / mA.width, 0f)
        runBlur(mB.texture, mA, 0f, 2f / mA.height)
    }

    /** Draws a landmark ring as a triangle fan, optionally inflated around its centroid. */
    private fun drawFan(lms: FloatArray, ring: IntArray, value: Float, inflate: Float) {
        var cx = 0f
        var cy = 0f
        for (idx in ring) {
            cx += lms[idx * 2]
            cy += lms[idx * 2 + 1]
        }
        cx /= ring.size
        cy /= ring.size

        fanBuffer.clear()
        fanBuffer.put(toNdcX(cx)).put(toNdcY(cy))
        for (i in 0..ring.size) {
            val idx = ring[i % ring.size]
            val x = cx + (lms[idx * 2] - cx) * inflate
            val y = cy + (lms[idx * 2 + 1] - cy) * inflate
            fanBuffer.put(toNdcX(x)).put(toNdcY(y))
        }
        fanBuffer.position(0)

        GLES20.glUseProgram(solidProgram)
        val posLoc = GLES20.glGetAttribLocation(solidProgram, "aPos")
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, fanBuffer)
        GLES20.glUniform4f(
            GLES20.glGetUniformLocation(solidProgram, "uColor"), value, value, value, 1f
        )
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, ring.size + 2)
    }

    private fun composite(scene: Fbo, blurred: Fbo, mask: Fbo) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, viewWidth, viewHeight)
        GLES20.glUseProgram(compositeProgram)
        bindQuad(compositeProgram)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, scene.texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(compositeProgram, "uScene"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, blurred.texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(compositeProgram, "uBlur"), 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mask.texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(compositeProgram, "uMask"), 2)
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(compositeProgram, "uStrength"),
            smoothing.coerceIn(0f, 1f)
        )
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun drawMustache(lms: FloatArray) {
        // Anchor: midway between the bottom of the nose and the top of the
        // upper lip, in view pixels (y grows downward).
        val ax = (px(lms, FaceTopology.NOSE_BOTTOM) + px(lms, FaceTopology.UPPER_LIP_TOP)) / 2f
        val ay = (py(lms, FaceTopology.NOSE_BOTTOM) + py(lms, FaceTopology.UPPER_LIP_TOP)) / 2f
        val lxp = px(lms, FaceTopology.MOUTH_LEFT)
        val lyp = py(lms, FaceTopology.MOUTH_LEFT)
        val rxp = px(lms, FaceTopology.MOUTH_RIGHT)
        val ryp = py(lms, FaceTopology.MOUTH_RIGHT)

        val mouthW = kotlin.math.hypot(rxp - lxp, ryp - lyp)
        if (mouthW < 1f) return
        val w = mouthW * 1.55f
        val h = w * MustacheTexture.ASPECT
        val angle = atan2(ryp - lyp, rxp - lxp)
        val ca = cos(angle)
        val sa = sin(angle)

        // Corner order matches a triangle strip: TL, TR, BL, BR.
        // UV v=0 is the top row of the bitmap.
        val corners = arrayOf(
            floatArrayOf(-w / 2f, -h / 2f, 0f, 0f),
            floatArrayOf(w / 2f, -h / 2f, 1f, 0f),
            floatArrayOf(-w / 2f, h / 2f, 0f, 1f),
            floatArrayOf(w / 2f, h / 2f, 1f, 1f),
        )
        spriteBuffer.clear()
        for (c in corners) {
            val x = ax + c[0] * ca - c[1] * sa
            val y = ay + c[0] * sa + c[1] * ca
            spriteBuffer.put(2f * x / viewWidth - 1f)
            spriteBuffer.put(1f - 2f * y / viewHeight)
            spriteBuffer.put(c[2]).put(c[3])
        }
        spriteBuffer.position(0)

        GLES20.glUseProgram(spriteProgram)
        val posLoc = GLES20.glGetAttribLocation(spriteProgram, "aPos")
        val uvLoc = GLES20.glGetAttribLocation(spriteProgram, "aUV")
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glEnableVertexAttribArray(uvLoc)
        spriteBuffer.position(0)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, spriteBuffer)
        spriteBuffer.position(2)
        GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 16, spriteBuffer)

        GLES20.glEnable(GLES20.GL_BLEND)
        // Android bitmaps upload premultiplied.
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mustacheTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(spriteProgram, "uTex"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /** Debug overlay: every landmark drawn as a round dot, in the same space as
     *  the mustache/beauty mask (so it reveals how landmarks line up with them). */
    private fun drawFaceMesh(lms: FloatArray) {
        val count = lms.size / 2
        meshBuffer.clear()
        for (i in 0 until count) {
            meshBuffer.put(toNdcX(lms[i * 2])).put(toNdcY(lms[i * 2 + 1]))
        }
        meshBuffer.position(0)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, viewWidth, viewHeight)
        GLES20.glUseProgram(pointProgram)
        val posLoc = GLES20.glGetAttribLocation(pointProgram, "aPos")
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, meshBuffer)
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(pointProgram, "uPointSize"),
            (viewWidth * 0.012f).coerceAtLeast(4f)
        )
        GLES20.glUniform4f(
            GLES20.glGetUniformLocation(pointProgram, "uColor"), 0.15f, 1f, 0.55f, 1f
        )
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, count)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun readPixels(): Bitmap {
        val buffer = ByteBuffer.allocateDirect(viewWidth * viewHeight * 4)
            .order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(
            0, 0, viewWidth, viewHeight,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer
        )
        val bitmap = Bitmap.createBitmap(viewWidth, viewHeight, Bitmap.Config.ARGB_8888)
        buffer.position(0)
        bitmap.copyPixelsFromBuffer(buffer)
        // GL reads rows bottom-up; flip vertically.
        val m = android.graphics.Matrix().apply { setScale(1f, -1f) }
        val flipped = Bitmap.createBitmap(bitmap, 0, 0, viewWidth, viewHeight, m, false)
        bitmap.recycle()
        return flipped
    }

    private fun bindQuad(program: Int) {
        val posLoc = GLES20.glGetAttribLocation(program, "aPos")
        val uvLoc = GLES20.glGetAttribLocation(program, "aUV")
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, quadPos)
        if (uvLoc >= 0) {
            GLES20.glEnableVertexAttribArray(uvLoc)
            GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 0, quadUv)
        }
    }

    // Landmarks are normalized in the full upright camera frame (y down);
    // map into the visible (cropped) region.
    private fun screenX(lms: FloatArray, idx: Int) = (lms[idx * 2] - cropOffX) / cropScaleX
    private fun screenY(lms: FloatArray, idx: Int) = (lms[idx * 2 + 1] - cropOffY) / cropScaleY
    private fun px(lms: FloatArray, idx: Int) = screenX(lms, idx) * viewWidth
    private fun py(lms: FloatArray, idx: Int) = screenY(lms, idx) * viewHeight
    private fun toNdcX(x: Float) = 2f * (x - cropOffX) / cropScaleX - 1f
    private fun toNdcY(y: Float) = 1f - 2f * (y - cropOffY) / cropScaleY

    private fun releaseFbos() {
        sceneFbo?.release(); sceneFbo = null
        blurA?.release(); blurA = null
        blurB?.release(); blurB = null
        maskA?.release(); maskA = null
        maskB?.release(); maskB = null
    }

    companion object {
        // Extra rotation applied to the camera preview texture so it matches the
        // upright landmark space. Determined empirically for the front sensor +
        // SurfaceTexture transform on this pipeline.
        private const val PREVIEW_ROTATION_OFFSET = 90

        private const val CAMERA_VS = """
            attribute vec2 aPos;
            attribute vec2 aUV;
            uniform mat4 uTexMatrix;
            uniform vec4 uCrop; // offset.xy, scale.xy
            varying vec2 vUV;
            void main() {
                gl_Position = vec4(aPos, 0.0, 1.0);
                vec2 uv = uCrop.xy + aUV * uCrop.zw;
                vUV = (uTexMatrix * vec4(uv, 0.0, 1.0)).xy;
            }
        """

        private val CAMERA_FS = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vUV;
            uniform samplerExternalOES uTex;
            void main() { gl_FragColor = texture2D(uTex, vUV); }
        """.trimIndent()

        private const val PASS_VS = """
            attribute vec2 aPos;
            attribute vec2 aUV;
            varying vec2 vUV;
            void main() { gl_Position = vec4(aPos, 0.0, 1.0); vUV = aUV; }
        """

        private const val BLUR_FS = """
            precision mediump float;
            varying vec2 vUV;
            uniform sampler2D uTex;
            uniform vec2 uDir;
            void main() {
                vec4 c = texture2D(uTex, vUV) * 0.227027;
                c += (texture2D(uTex, vUV + uDir * 1.3846) + texture2D(uTex, vUV - uDir * 1.3846)) * 0.316216;
                c += (texture2D(uTex, vUV + uDir * 3.2308) + texture2D(uTex, vUV - uDir * 3.2308)) * 0.070270;
                gl_FragColor = c;
            }
        """

        private const val SOLID_VS = """
            attribute vec2 aPos;
            void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
        """

        private const val SOLID_FS = """
            precision mediump float;
            uniform vec4 uColor;
            void main() { gl_FragColor = uColor; }
        """

        private const val COMPOSITE_FS = """
            precision mediump float;
            varying vec2 vUV;
            uniform sampler2D uScene;
            uniform sampler2D uBlur;
            uniform sampler2D uMask;
            uniform float uStrength;
            void main() {
                vec3 scene = texture2D(uScene, vUV).rgb;
                vec3 blurred = texture2D(uBlur, vUV).rgb;
                float m = texture2D(uMask, vUV).r * uStrength;
                vec3 smoothed = blurred + (scene - blurred) * 0.35;
                smoothed = min(smoothed * 1.04 + 0.015, 1.0);
                gl_FragColor = vec4(mix(scene, smoothed, m), 1.0);
            }
        """

        private const val SPRITE_VS = """
            attribute vec2 aPos;
            attribute vec2 aUV;
            varying vec2 vUV;
            void main() { gl_Position = vec4(aPos, 0.0, 1.0); vUV = aUV; }
        """

        private const val SPRITE_FS = """
            precision mediump float;
            varying vec2 vUV;
            uniform sampler2D uTex;
            void main() { gl_FragColor = texture2D(uTex, vUV); }
        """

        private const val POINT_VS = """
            attribute vec2 aPos;
            uniform float uPointSize;
            void main() {
                gl_Position = vec4(aPos, 0.0, 1.0);
                gl_PointSize = uPointSize;
            }
        """

        private const val POINT_FS = """
            precision mediump float;
            uniform vec4 uColor;
            void main() {
                // Carve each square point into a round dot.
                vec2 c = gl_PointCoord - vec2(0.5);
                if (dot(c, c) > 0.25) discard;
                gl_FragColor = uColor;
            }
        """
    }
}
