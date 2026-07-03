package com.haywan.filtercam.beautyfilter.tracking

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult

/**
 * Runs MediaPipe Face Landmarker (478-point face mesh), **decoupled from display**.
 *
 * Every camera frame is rotated upright and handed straight to [onFrame] for
 * display, so the preview runs at the full camera frame rate. Detection is *not*
 * on that path: a downscaled copy of each frame is dropped into a single-slot
 * buffer that a dedicated [detectThread] consumes as fast as it can, publishing
 * landmarks via [onFaces]. If detection can't keep up it simply processes the
 * latest frame and skips the rest — the display never waits for it.
 *
 * The detected landmarks are normalized (0..1), so detecting on a smaller copy
 * still maps 1:1 onto the full-resolution display frame. The trade-off vs the old
 * frame-locked design is that landmarks can lag the displayed frame by a frame or
 * two during fast motion; the per-frame smoothing keeps that from looking jittery.
 */
internal class FaceTracker(
    context: Context,
    private val onFrame: (Bitmap) -> Unit,
    private val onFaces: (Array<FloatArray>) -> Unit,
) {
    private val landmarker: FaceLandmarker
    private var smoothed: Array<FloatArray>? = null
    private var videoTs = 0L

    @Volatile var isFrontCamera = true

    // ---- background detection (decoupled from the display path) ----
    @Volatile private var running = true
    private val detectLock = Any()
    private var pendingDetect: Bitmap? = null
    private val detectThread = Thread({ detectLoop() }, "FaceDetect")

    init {
        landmarker = try {
            createLandmarker(context, Delegate.GPU)
        } catch (t: Throwable) {
            Log.w(TAG, "GPU delegate unavailable, falling back to CPU", t)
            createLandmarker(context, Delegate.CPU)
        }
        detectThread.start()
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
            .setMinFacePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .build()
        return FaceLandmarker.createFromOptions(context, options)
    }

    /**
     * Called on the CameraX analysis executor. Rotates the frame upright, sends it
     * to the display immediately, and queues a downscaled copy for detection.
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

            // Build the detection copy BEFORE the display takes ownership of
            // `upright` (the renderer recycles it after upload).
            submitForDetection(downscaleForDetection(upright))

            // Display this frame now — never blocked by detection.
            onFrame(upright)
        } catch (t: Throwable) {
            Log.w(TAG, "analyze failed", t)
        } finally {
            imageProxy.close()
        }
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

    /** Hand the latest detection frame to the detector, dropping any unconsumed one. */
    private fun submitForDetection(bmp: Bitmap) {
        synchronized(detectLock) {
            pendingDetect?.recycle()
            pendingDetect = bmp
            (detectLock as Object).notify()
        }
    }

    private fun detectLoop() {
        while (true) {
            val bmp = synchronized(detectLock) {
                while (running && pendingDetect == null) {
                    try { (detectLock as Object).wait() } catch (_: InterruptedException) {}
                }
                val b = pendingDetect
                pendingDetect = null
                b
            } ?: if (running) continue else break

            try {
                val result = landmarker.detectForVideo(
                    BitmapImageBuilder(bmp).build(), videoTs++
                )
                onFaces(smooth(result))
            } catch (t: Throwable) {
                Log.w(TAG, "detect failed", t)
            } finally {
                bmp.recycle()
            }
        }
    }

    /** Converts + lightly smooths the result to take the jitter off the landmarks. */
    private fun smooth(result: FaceLandmarkerResult): Array<FloatArray> {
        val faceList = result.faceLandmarks()
        if (faceList.isNullOrEmpty()) {
            smoothed = null
            return emptyArray()
        }

        val fresh = Array(faceList.size) { f ->
            val face = faceList[f]
            FloatArray(face.size * 2).also { out ->
                for (i in face.indices) {
                    out[i * 2] = face[i].x()
                    out[i * 2 + 1] = face[i].y()
                }
            }
        }

        val prev = smoothed
        val out: Array<FloatArray> =
            if (prev == null || prev.size != fresh.size) {
                fresh
            } else {
                Array(fresh.size) { f ->
                    val p = prev[f]
                    val n = fresh[f]
                    if (p.size != n.size) n
                    else FloatArray(n.size) { i ->
                        val d = n[i] - p[i]
                        val a = (SMOOTHING_BASE + kotlin.math.abs(d) * SMOOTHING_ADAPT).coerceAtMost(1f)
                        p[i] + d * a
                    }
                }
            }
        smoothed = out
        return Array(out.size) { out[it].copyOf() }
    }

    fun release() {
        running = false
        synchronized(detectLock) { (detectLock as Object).notifyAll() }
        try { detectThread.join(500) } catch (_: InterruptedException) {}
        synchronized(detectLock) { pendingDetect?.recycle(); pendingDetect = null }
        try {
            landmarker.close()
        } catch (t: Throwable) {
            Log.w(TAG, "close failed", t)
        }
    }

    companion object {
        private const val TAG = "FaceTracker"
        private const val MODEL_ASSET = "face_landmarker.task"
        private const val MAX_FACES = 5

        // Detection input long-side. Landmarks are normalized, so this can be well
        // below the display resolution — keeps detection cheap without hurting the
        // sharp full-res preview.
        private const val DETECT_LONG_SIDE = 640

        // Light jitter smoothing on the landmarks; snaps to the current frame
        // during real motion.
        private const val SMOOTHING_BASE = 0.6f
        private const val SMOOTHING_ADAPT = 25f
    }
}
