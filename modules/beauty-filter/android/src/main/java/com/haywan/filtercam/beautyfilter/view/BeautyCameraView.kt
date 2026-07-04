package com.haywan.filtercam.beautyfilter.view

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import com.haywan.filtercam.beautyfilter.render.BeautyRenderer
import com.haywan.filtercam.beautyfilter.tracking.FaceTracker
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.Promise
import expo.modules.kotlin.views.ExpoView
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The React-Native-facing view. Hosts a [GLSurfaceView] driven by
 * [BeautyRenderer], wires a [CameraController] to it, and runs a [FaceTracker]
 * that publishes landmarks to the renderer. Props and `takePicture` are routed
 * here from the Expo module definition.
 */
class BeautyCameraView(context: Context, appContext: AppContext) : ExpoView(context, appContext) {

    private val glView = GLSurfaceView(context)
    private val renderer = BeautyRenderer(::onGlReady)
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile private var tracker: FaceTracker? = null
    private val cameraController = CameraController(
        context = context,
        analysisExecutor = analysisExecutor,
        onFrame = { proxy -> tracker?.analyze(proxy) ?: proxy.close() },
    )

    private var surfaceReady = false
    private var facingFront = true

    init {
        glView.setEGLContextClientVersion(2)
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        addView(glView)
        // Model loading (~4 MB) happens off the main thread.
        analysisExecutor.execute {
            tracker = try {
                // Frame-locked: detection runs on each frame and the frame is
                // rendered together with its own landmarks, so the warp never
                // slides off the face during motion.
                FaceTracker(
                    context,
                    onFrame = { bitmap, faces ->
                        renderer.submitFrame(bitmap, faces)
                        glView.requestRender()
                    },
                ).also { it.isFrontCamera = facingFront }
            } catch (t: Throwable) {
                Log.e(TAG, "FaceTracker init failed; filters will be inactive", t)
                null
            }
        }
    }

    // React Native lays out only views it knows about; children added natively
    // must be measured and laid out by hand.
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val height = b - t
        glView.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
        glView.layout(0, 0, width, height)
    }

    // ---- props ----

    fun setFacing(facing: String) {
        val front = facing != "back"
        if (front != facingFront) {
            facingFront = front
            tracker?.isFrontCamera = front
            if (surfaceReady) bindCamera()
        }
    }

    fun setSmoothing(value: Float) {
        renderer.smoothing = value.coerceIn(0f, 1f)
    }

    fun setGlow(value: Float) {
        renderer.glow = value.coerceIn(0f, 1f)
    }

    fun setClarity(value: Float) {
        renderer.clarity = value.coerceIn(0f, 1f)
    }

    fun setWarmth(value: Float) {
        renderer.warmth = value.coerceIn(0f, 1f)
    }

    fun setEyeEnlarge(value: Float) {
        renderer.eyeEnlarge = value.coerceIn(0f, 1f)
    }

    fun setNoseSlim(value: Float) {
        renderer.noseSlim = value.coerceIn(0f, 1f)
    }

    fun setFaceSlim(value: Float) {
        renderer.faceSlim = value.coerceIn(0f, 1f)
    }

    /**
     * Attach a target surface (e.g. from a WebRTC SurfaceTextureHelper) to
     * receive every filtered frame GPU-side, or null to detach. This is the seam
     * a LiveKit custom video capturer uses to publish the filtered stream.
     * See docs/STREAMING.md.
     */
    fun setStreamSurface(surface: android.view.Surface?) {
        renderer.setStreamSurface(surface)
    }

    fun setMustache(enabled: Boolean) {
        renderer.mustacheEnabled = enabled
    }

    fun setFaceMesh(enabled: Boolean) {
        renderer.faceMeshEnabled = enabled
    }

    fun takePicture(promise: Promise) {
        renderer.captureNextFrame { bitmap ->
            ioExecutor.execute { saveBitmap(bitmap, promise) }
        }
        glView.requestRender()
    }

    // ---- lifecycle ----

    private fun onGlReady() {
        post {
            surfaceReady = true
            bindCamera()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cameraController.unbind()
        analysisExecutor.execute {
            tracker?.release()
            tracker = null
        }
        analysisExecutor.shutdown()
        ioExecutor.shutdown()
    }

    private fun bindCamera() {
        val lifecycleOwner = appContext.activityProvider?.currentActivity as? LifecycleOwner
        if (lifecycleOwner == null) {
            Log.e(TAG, "No LifecycleOwner activity; cannot start camera")
            return
        }
        cameraController.bind(lifecycleOwner, facingFront)
    }

    private fun saveBitmap(bitmap: Bitmap, promise: Promise) {
        try {
            val file = File.createTempFile("filtercam_", ".jpg", context.cacheDir)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            bitmap.recycle()
            promise.resolve("file://${file.absolutePath}")
        } catch (t: Throwable) {
            promise.reject("E_CAPTURE", "Failed to save capture", t)
        }
    }

    companion object {
        private const val TAG = "BeautyCameraView"
    }
}
