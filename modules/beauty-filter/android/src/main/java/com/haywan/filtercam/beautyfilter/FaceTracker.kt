package com.haywan.filtercam.beautyfilter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult

/**
 * Runs MediaPipe Face Landmarker (478-point face mesh) on camera frames in
 * live-stream mode and publishes temporally smoothed, display-oriented
 * normalized landmarks to the renderer.
 */
class FaceTracker(
    context: Context,
    private val onLandmarks: (FloatArray?) -> Unit,
) {
    private val landmarker: FaceLandmarker
    private var smoothed: FloatArray? = null
    private var lastResultAt = 0L

    @Volatile var isFrontCamera = true

    init {
        landmarker = try {
            createLandmarker(context, Delegate.GPU)
        } catch (t: Throwable) {
            Log.w(TAG, "GPU delegate unavailable, falling back to CPU", t)
            createLandmarker(context, Delegate.CPU)
        }
    }

    private fun createLandmarker(context: Context, delegate: Delegate): FaceLandmarker {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET)
            .setDelegate(delegate)
            .build()
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(0.5f)
            .setMinFacePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, _ -> handleResult(result) }
            .setErrorListener { e -> Log.w(TAG, "FaceLandmarker error", e) }
            .build()
        return FaceLandmarker.createFromOptions(context, options)
    }

    /** Called on the ImageAnalysis executor. Closes the proxy when done. */
    fun analyze(imageProxy: ImageProxy) {
        try {
            val bitmap = imageProxy.toBitmap()
            val rotation = imageProxy.imageInfo.rotationDegrees
            val matrix = Matrix().apply {
                postRotate(rotation.toFloat())
                if (isFrontCamera) {
                    // Match the mirrored preview that the user sees.
                    postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f)
                }
            }
            val upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            landmarker.detectAsync(BitmapImageBuilder(upright).build(), SystemClock.uptimeMillis())
        } catch (t: Throwable) {
            Log.w(TAG, "analyze failed", t)
        } finally {
            imageProxy.close()
        }
    }

    private fun handleResult(result: FaceLandmarkerResult) {
        val face = result.faceLandmarks().firstOrNull()
        if (face == null) {
            smoothed = null
            onLandmarks(null)
            return
        }

        val now = SystemClock.uptimeMillis()
        val fresh = FloatArray(face.size * 2)
        for (i in face.indices) {
            fresh[i * 2] = face[i].x()
            fresh[i * 2 + 1] = face[i].y()
        }

        val prev = smoothed
        val out: FloatArray
        if (prev == null || prev.size != fresh.size || now - lastResultAt > 300) {
            out = fresh
        } else {
            // Exponential smoothing to keep the mask and mustache stable.
            out = FloatArray(fresh.size)
            for (i in fresh.indices) {
                out[i] = prev[i] + (fresh[i] - prev[i]) * SMOOTHING_ALPHA
            }
        }
        smoothed = out
        lastResultAt = now
        onLandmarks(out.copyOf())
    }

    fun release() {
        try {
            landmarker.close()
        } catch (t: Throwable) {
            Log.w(TAG, "close failed", t)
        }
    }

    companion object {
        private const val TAG = "FaceTracker"
        private const val MODEL_ASSET = "face_landmarker.task"
        private const val SMOOTHING_ALPHA = 0.55f
    }
}
