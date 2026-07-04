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
import kotlin.math.PI
import kotlin.math.abs

/**
 * Runs MediaPipe Face Landmarker (478-point face mesh), **frame-locked**.
 *
 * This is the architecture the major AR-filter apps use: detection runs
 * synchronously on each camera frame and the frame is delivered to the display
 * *together with the landmarks detected on it*. The warp can therefore never
 * slide off the face — image and effect are delayed together by the detect
 * time (invisible), instead of the effect lagging the image (very visible:
 * "dancing" eye-enlarge during head motion).
 *
 * If detection is slower than the camera rate, CameraX's KEEP_ONLY_LATEST
 * backpressure drops camera frames and the preview fps dips — the correct
 * trade: a slightly lower frame rate is imperceptible, a misaligned warp is
 * not. Detection cost is kept low by the downscaled detect input
 * ([DETECT_LONG_SIDE]), numFaces=1 (no per-frame re-detection), and the GPU
 * delegate. Average detect time is logged periodically ([LOG_EVERY_FRAMES]).
 *
 * A One Euro filter takes the residual sensor jitter off the landmarks at rest
 * while staying responsive in motion, and a short grace period holds the last
 * landmarks when the face briefly drops out (finger over the face, extreme
 * pose) instead of snapping the filter off and on.
 */
internal class FaceTracker(
    context: Context,
    private val onFrame: (Bitmap, Array<FloatArray>) -> Unit,
) {
    private val landmarker: FaceLandmarker

    @Volatile var isFrontCamera = true

    // All of the state below is touched only on the CameraX analysis thread.
    private var lastTimestampMs = 0L
    private val filter = OneEuroFilterBank()
    private var held: Array<FloatArray>? = null
    private var lastFaceSeenMs = 0L
    private var detectEmaMs = 0f
    private var frameCount = 0L

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
            .setRunningMode(RunningMode.VIDEO)
            .setNumFaces(MAX_FACES)
            .setMinFaceDetectionConfidence(0.5f)
            // Below the defaults so partial occlusion (a finger on the face) or
            // an extreme pose dips confidence without dropping the face outright.
            .setMinFacePresenceConfidence(0.3f)
            .setMinTrackingConfidence(0.3f)
            .build()
        return FaceLandmarker.createFromOptions(context, options)
    }

    /**
     * Called on the CameraX analysis executor. Rotates the frame upright, runs
     * detection on it synchronously, and delivers frame + landmarks together.
     */
    fun analyze(imageProxy: ImageProxy) {
        try {
            val src = imageProxy.toBitmap()
            val rotation = imageProxy.imageInfo.rotationDegrees
            val matrix = Matrix().apply {
                postRotate(rotation.toFloat())
                if (isFrontCamera) {
                    postScale(-1f, 1f, src.width / 2f, src.height / 2f)
                }
            }
            // filter=false: a 90/270° rotation (+ mirror) is pixel-exact.
            val upright = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, false)
            if (upright !== src) src.recycle()

            val faces = detect(upright)

            // Frame + its own landmarks, atomically. The renderer takes
            // ownership of `upright` (recycled after upload).
            onFrame(upright, faces)
        } catch (t: Throwable) {
            Log.w(TAG, "analyze failed", t)
        } finally {
            imageProxy.close()
        }
    }

    /** Synchronous detection on a downscaled copy of [upright]. */
    private fun detect(upright: Bitmap): Array<FloatArray> {
        val now = SystemClock.uptimeMillis()
        // detectForVideo requires strictly increasing timestamps.
        val ts = if (now <= lastTimestampMs) lastTimestampMs + 1 else now
        lastTimestampMs = ts

        val detectBmp = downscaleForDetection(upright)
        val result: FaceLandmarkerResult? = try {
            landmarker.detectForVideo(BitmapImageBuilder(detectBmp).build(), ts)
        } catch (t: Throwable) {
            Log.w(TAG, "detect failed", t)
            null
        } finally {
            detectBmp.recycle()
        }

        val detectMs = (SystemClock.uptimeMillis() - now).toFloat()
        detectEmaMs += (detectMs - detectEmaMs) * 0.1f
        if (++frameCount % LOG_EVERY_FRAMES == 0L) {
            Log.i(TAG, "detect avg ${"%.1f".format(detectEmaMs)}ms/frame")
        }

        return smoothAndHold(result, now, ts / 1000.0)
    }

    /** One Euro smoothing + occlusion grace, all on the analysis thread. */
    private fun smoothAndHold(
        result: FaceLandmarkerResult?,
        nowMs: Long,
        tsSec: Double,
    ): Array<FloatArray> {
        val faceList = result?.faceLandmarks()
        if (faceList.isNullOrEmpty()) {
            val h = held
            if (h != null && nowMs - lastFaceSeenMs <= OCCLUSION_GRACE_MS) {
                // Brief dropout: hold the last landmarks frozen. Filter state is
                // kept so reacquisition is a smooth continuation, not a snap.
                return Array(h.size) { h[it].copyOf() }
            }
            filter.reset()
            held = null
            return emptyArray()
        }
        lastFaceSeenMs = nowMs

        val face = faceList[0]
        val raw = FloatArray(face.size * 2).also { out ->
            for (i in face.indices) {
                out[i * 2] = face[i].x()
                out[i * 2 + 1] = face[i].y()
            }
        }
        val out = filter.apply(raw, tsSec)
        held = arrayOf(out)
        return arrayOf(out.copyOf())
    }

    /** A smaller, independently-owned copy for the detector (normalized coords match). */
    private fun downscaleForDetection(src: Bitmap): Bitmap {
        val longSide = maxOf(src.width, src.height)
        if (longSide <= DETECT_LONG_SIDE) {
            return src.copy(src.config ?: Bitmap.Config.ARGB_8888, false)
        }
        val scale = DETECT_LONG_SIDE.toFloat() / longSide
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, w, h, true)
        // createScaledBitmap can return the source unchanged; never share it.
        return if (scaled === src) src.copy(src.config ?: Bitmap.Config.ARGB_8888, false) else scaled
    }

    fun release() {
        try {
            landmarker.close()
        } catch (t: Throwable) {
            Log.w(TAG, "close failed", t)
        }
    }

    /**
     * One Euro filter (Casiez et al.) over a flat [x0,y0,x1,y1,...] landmark
     * array. At rest the cutoff sits at [MIN_CUTOFF] and jitter is strongly
     * damped; during motion the cutoff rises with the (low-passed) landmark
     * velocity so the filtered value tracks the fresh measurement with
     * near-zero lag. No extrapolation: landmarks are frame-locked to the
     * displayed image, so prediction would *create* misalignment, not fix it.
     */
    private class OneEuroFilterBank {
        private var x: FloatArray? = null   // filtered positions
        private var v: FloatArray? = null   // filtered velocities (units/s)
        private var lastTsSec = 0.0

        fun reset() {
            x = null
            v = null
        }

        fun apply(raw: FloatArray, tsSec: Double): FloatArray {
            val px = x
            val pv = v
            if (px == null || pv == null || px.size != raw.size) {
                x = raw.copyOf()
                v = FloatArray(raw.size)
                lastTsSec = tsSec
                return raw.copyOf()
            }
            val dt = (tsSec - lastTsSec).toFloat().coerceIn(1f / 120f, 0.25f)
            lastTsSec = tsSec

            val alphaV = alpha(dt, D_CUTOFF)
            val out = FloatArray(raw.size)
            for (i in raw.indices) {
                val rawV = (raw[i] - px[i]) / dt
                val vel = pv[i] + alphaV * (rawV - pv[i])
                pv[i] = vel
                val a = alpha(dt, MIN_CUTOFF + BETA * abs(vel))
                val f = px[i] + a * (raw[i] - px[i])
                px[i] = f
                out[i] = f
            }
            return out
        }

        private fun alpha(dt: Float, cutoff: Float): Float {
            val tau = 1f / (2f * PI.toFloat() * cutoff)
            return 1f / (1f + tau / dt)
        }
    }

    companion object {
        private const val TAG = "FaceTracker"
        private const val MODEL_ASSET = "face_landmarker.task"

        // 1 (not 5): with N tracked faces < numFaces, MediaPipe re-runs the full
        // face *detector* every frame looking for more — with numFaces=1 and a
        // face locked, only the cheap landmark model runs. Raise this only if
        // multi-face filtering becomes a product requirement.
        private const val MAX_FACES = 1

        // Detection input long-side. Landmarks are normalized, so this can be well
        // below the display resolution — keeps the synchronous detect cheap
        // without hurting the sharp full-res preview. Lower to 480 if the
        // logged per-frame detect time endangers 30fps on target devices.
        private const val DETECT_LONG_SIDE = 640

        // One Euro tuning (normalized 0..1 coordinates, ~30 Hz).
        // MIN_CUTOFF: smoothing at rest (lower = steadier, laggier).
        // BETA: how fast the cutoff rises with velocity (higher = snappier motion).
        // Keep in sync with the iOS FaceTracker.
        private const val MIN_CUTOFF = 1.0f
        private const val BETA = 15f
        private const val D_CUTOFF = 1.0f

        // Hold the last landmarks this long when the face briefly drops out.
        private const val OCCLUSION_GRACE_MS = 300L

        private const val LOG_EVERY_FRAMES = 300L
    }
}
