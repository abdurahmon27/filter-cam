package com.haywan.filtercam.beautyfilter.view

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executor

/**
 * Owns the CameraX binding: a [Preview] use case whose frames go to the GL
 * [SurfaceTexture], and an [ImageAnalysis] use case that feeds frames to the
 * face tracker. Decoupled from the view/renderer via callbacks.
 *
 * @param surfaceTextureProvider supplies the GL SurfaceTexture to render into.
 * @param onBufferSize reports the camera buffer resolution (width, height).
 * @param onFrame receives each analysis frame; the callback owns closing it.
 */
internal class CameraController(
    private val context: Context,
    private val analysisExecutor: Executor,
    private val surfaceTextureProvider: () -> SurfaceTexture?,
    private val onBufferSize: (Int, Int) -> Unit,
    private val onFrame: (ImageProxy) -> Unit,
) {
    private var cameraProvider: ProcessCameraProvider? = null

    fun bind(lifecycleOwner: LifecycleOwner, facingFront: Boolean) {
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
                analysis.setAnalyzer(analysisExecutor) { proxy -> onFrame(proxy) }

                provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
            } catch (t: Throwable) {
                Log.e(TAG, "Camera bind failed", t)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun unbind() {
        cameraProvider?.unbindAll()
        cameraProvider = null
    }

    private fun provideSurface(request: SurfaceRequest) {
        val surfaceTexture = surfaceTextureProvider()
        if (surfaceTexture == null) {
            request.willNotProvideSurface()
            return
        }
        onBufferSize(request.resolution.width, request.resolution.height)
        surfaceTexture.setDefaultBufferSize(request.resolution.width, request.resolution.height)
        val surface = Surface(surfaceTexture)
        request.provideSurface(surface, ContextCompat.getMainExecutor(context)) {
            surface.release()
        }
    }

    companion object {
        private const val TAG = "CameraController"
    }
}
