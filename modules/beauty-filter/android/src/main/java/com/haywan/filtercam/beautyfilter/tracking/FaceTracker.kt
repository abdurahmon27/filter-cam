package com.haywan.filtercam.beautyfilter.tracking

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
 *
 * Multi-face: up to [MAX_FACES] faces are tracked. Each detected face is emitted
 * as its own `FloatArray` of `478 * 2` normalized (x, y) coordinates; the whole
 * frame is delivered as an `Array<FloatArray>` (empty array = no faces).
 * Smoothing is applied per face index and reset whenever the face count changes.
 */
internal class FaceTracker(
    context: Context,
    private val onFaces: (Array<FloatArray>) -> Unit,
) {
    private val landmarker: FaceLandmarker
    private var smoothed: Array<FloatArray>? = null
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
            .setNumFaces(MAX_FACES)
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
        val faces = result.faceLandmarks()
        if (faces.isNullOrEmpty()) {
            smoothed = null
            onFaces(emptyArray())
            return
        }

        val now = SystemClock.uptimeMillis()
        val fresh = Array(faces.size) { f ->
            val face = faces[f]
            FloatArray(face.size * 2).also { out ->
                for (i in face.indices) {
                    out[i * 2] = face[i].x()
                    out[i * 2 + 1] = face[i].y()
                }
            }
        }

        val prev = smoothed
        // Reset smoothing when the number of faces changes or after a gap, since
        // face ordering is not guaranteed stable across those boundaries.
        val out: Array<FloatArray> =
            if (prev == null || prev.size != fresh.size || now - lastResultAt > 300) {
                fresh
            } else {
                Array(fresh.size) { f ->
                    val p = prev[f]
                    val n = fresh[f]
                    if (p.size != n.size) {
                        n
                    } else {
                        FloatArray(n.size) { i -> p[i] + (n[i] - p[i]) * SMOOTHING_ALPHA }
                    }
                }
            }

        smoothed = out
        lastResultAt = now
        // Deep-copy so the renderer never reads a buffer we mutate next frame.
        onFaces(Array(out.size) { out[it].copyOf() })
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
        // Higher alpha = the mask/mustache follow the face faster (less lag).
        // 0.8 keeps enough smoothing to avoid jitter without trailing the face.
        private const val SMOOTHING_ALPHA = 0.8f
        private const val MAX_FACES = 5
    }
}
