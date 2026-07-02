package com.haywan.filtercam.beautyfilter

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.Surface
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.Promise
import expo.modules.kotlin.views.ExpoView
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class BeautyCameraView(context: Context, appContext: AppContext) : ExpoView(context, appContext) {

    private val glView = GLSurfaceView(context)
    private val renderer = BeautyRenderer(::onSurfaceTextureReady)
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile private var tracker: FaceTracker? = null
    private var cameraProvider: ProcessCameraProvider? = null
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
                FaceTracker(context) { lms ->
                    renderer.landmarks = lms
                    renderer.landmarksAt = if (lms != null) android.os.SystemClock.uptimeMillis() else 0L
                }.also { it.isFrontCamera = facingFront }
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

    private fun onSurfaceTextureReady(surfaceTexture: android.graphics.SurfaceTexture) {
        surfaceTexture.setOnFrameAvailableListener { glView.requestRender() }
        post {
            surfaceReady = true
            bindCamera()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cameraProvider?.unbindAll()
        cameraProvider = null
        analysisExecutor.execute {
            tracker?.release()
            tracker = null
        }
        analysisExecutor.shutdown()
        ioExecutor.shutdown()
        renderer.releaseSurfaceTexture()
    }

    private fun bindCamera() {
        val lifecycleOwner = appContext.activityProvider?.currentActivity as? LifecycleOwner
        if (lifecycleOwner == null) {
            Log.e(TAG, "No LifecycleOwner activity; cannot start camera")
            return
        }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                provider.unbindAll()

                val selector = if (facingFront) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

                val ratio43 = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build()

                val preview = Preview.Builder()
                    .setResolutionSelector(ratio43)
                    .setTargetRotation(Surface.ROTATION_0)
                    .build()
                preview.setSurfaceProvider { request -> provideSurface(request) }

                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(ratio43)
                    .setTargetRotation(Surface.ROTATION_0)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { proxy ->
                    // Single source of truth for orientation: the camera texture
                    // and the face landmarks must use the SAME rotation, otherwise
                    // the preview and the filters disagree (video sideways while
                    // filters stay upright). imageInfo.rotationDegrees is what the
                    // FaceTracker already uses, so drive the renderer from it too.
                    renderer.cameraRotationDegrees = proxy.imageInfo.rotationDegrees
                    tracker?.analyze(proxy) ?: proxy.close()
                }

                provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
            } catch (t: Throwable) {
                Log.e(TAG, "Camera bind failed", t)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun provideSurface(request: SurfaceRequest) {
        val surfaceTexture = renderer.surfaceTexture
        if (surfaceTexture == null) {
            request.willNotProvideSurface()
            return
        }
        renderer.cameraBufferWidth = request.resolution.width
        renderer.cameraBufferHeight = request.resolution.height
        surfaceTexture.setDefaultBufferSize(request.resolution.width, request.resolution.height)
        val surface = Surface(surfaceTexture)
        request.provideSurface(surface, ContextCompat.getMainExecutor(context)) {
            surface.release()
        }
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
