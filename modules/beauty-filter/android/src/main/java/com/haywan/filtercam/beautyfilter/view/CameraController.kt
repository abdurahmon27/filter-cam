package com.haywan.filtercam.beautyfilter.view

import android.content.Context
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executor

/**
 * Owns the CameraX binding for the single-stream pipeline: a single
 * [ImageAnalysis] use case whose frames are both detected on and displayed.
 * There is no Preview use case — the filtered frames are what the user sees.
 *
 * @param onFrame receives each analysis frame; the callback owns closing it.
 */
internal class CameraController(
    private val context: Context,
    private val analysisExecutor: Executor,
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

                // ImageAnalysis defaults to a soft 640x480; this is what the user
                // both sees AND we detect on (single-stream). 1440x1080 is the
                // 4:3 sibling of 720p (what the reference apps stream at): its
                // upright width matches a 1080p screen 1:1, so the preview reads
                // sharp (the blit sharpen covers the mild height upscale), while
                // per-frame copy/prep/upload cost half of 1920x1440's. Detection
                // cost is unaffected (detect input is downscaled to
                // DETECT_LONG_SIDE) and rotation/mirror happen GPU-side in
                // CameraPass.
                val ratio43 = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1440, 1080),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()

                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(ratio43)
                    .setTargetRotation(Surface.ROTATION_0)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { proxy -> onFrame(proxy) }

                val camera = provider.bindToLifecycle(lifecycleOwner, selector, analysis)

                // Widen the field of view where the device allows it: some front
                // cameras report a minimum zoom below 1.0 (a wider selfie). This is
                // a genuine, borderless zoom-out; on cameras whose min is 1.0 it's
                // simply a no-op.
                val minZoom = camera.cameraInfo.zoomState.value?.minZoomRatio ?: 1f
                if (minZoom < 1f) {
                    camera.cameraControl.setZoomRatio(minZoom)
                    Log.i(TAG, "Widened FOV to min zoom $minZoom")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Camera bind failed", t)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun unbind() {
        cameraProvider?.unbindAll()
        cameraProvider = null
    }

    companion object {
        private const val TAG = "CameraController"
    }
}
