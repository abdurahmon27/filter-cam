package com.haywan.filtercam.beautyfilter.tracking

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.util.TreeMap
import kotlin.math.PI
import kotlin.math.abs

/**
 * Runs MediaPipe Face Landmarker (478-point face mesh), **frame-locked**:
 * every frame reaches the display together with the landmarks detected on
 * that exact frame, so the warp can never slide off the face — image and
 * effect are delayed together by the detect time (invisible), instead of the
 * effect lagging the image (very visible: "dancing" eye-enlarge during head
 * motion; a decoupled display + velocity-extrapolated landmarks was tried and
 * showed exactly that artifact, so don't).
 *
 * Throughput comes from MediaPipe's LIVE_STREAM mode: `detectAsync` pipelines
 * the detector/landmark stages internally across frames, and each result
 * arrives on a callback tagged with the submitted frame's timestamp. The
 * frame is parked in [pending] at submit time and married to its own result
 * in [onResult] — frame-lock preserved, but detection throughput is the
 * slowest *stage*, not the whole serial chain. Frames MediaPipe skips under
 * load (or that we skip when [MAX_IN_FLIGHT] is reached) are simply never
 * displayed: the fps dips rather than the warp misaligning.
 *
 * The CameraX analysis thread does only cheap work (pooled copy + small
 * upright detect input); inference runs inside MediaPipe's own threads and
 * results arrive on its callback thread. Detection cost is kept low by the
 * downscaled detect input ([DETECT_LONG_SIDE]), numFaces=1, and the GPU
 * delegate. End-to-end detect latency is logged periodically
 * ([LOG_EVERY_FRAMES]).
 *
 * A One Euro filter takes the residual sensor jitter off the landmarks at rest
 * while staying responsive in motion, and a grace period holds the last
 * landmarks when the face briefly drops out (finger over the face, extreme
 * pose, a shake) instead of snapping the filter off and on.
 */
internal class FaceTracker(
    context: Context,
    /** Pool the full-res frames come from; the renderer returns them to it. */
    private val pool: BitmapPool,
    /** Raw (un-rotated) frame, its rotation degrees, mirror flag, and landmarks. */
    private val onFrame: (Bitmap, Int, Boolean, Array<FloatArray>) -> Unit,
) {
    private val landmarker: FaceLandmarker

    @Volatile var isFrontCamera = true
    @Volatile private var released = false

    /** Frames waiting for their detection result, keyed by submit timestamp. */
    private class PendingFrame(val full: Bitmap, val rotation: Int, val mirror: Boolean)
    private val pendingLock = Any()
    private val pending = TreeMap<Long, PendingFrame>()

    // Analysis-thread-only.
    private var lastTimestampMs = 0L
    @Volatile private var copyEmaMs = 0f
    @Volatile private var prepEmaMs = 0f

    // MediaPipe-callback-thread-only.
    private val filter = OneEuroFilterBank()
    private var held: Array<FloatArray>? = null
    private var lastFaceSeenMs = 0L
    private var detectEmaMs = 0f
    private var frameCount = 0L
    private var lastLogUptimeMs = 0L

    init {
        // GPU delegate. The CPU delegate was tried (to dodge contention with
        // the render pipeline) and looked faster — but only face-down: the
        // cheap face *detector* runs quick on CPU, while the 478-landmark net
        // that runs once a face is present took 80ms+ on the big cores
        // (3-12fps in actual use). On the GPU it stays fast even while
        // time-slicing against rendering; the reshape scissor and the small
        // detect input keep that contention low.
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
            .setResultListener(::onResult)
            .setErrorListener { e -> Log.w(TAG, "detect error", e) }
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
     * On the CameraX analysis executor: copy the frame into a pooled bitmap,
     * build the small upright detect input, park the frame in [pending], and
     * hand the input to MediaPipe's async pipeline. The full-res frame stays
     * RAW (rotation/mirror happen GPU-side in CameraPass — rotating the
     * full-res bitmap on the CPU cost more per frame than detection itself
     * and capped the preview at single-digit fps). Only the small detect copy
     * is rotated upright here, which is cheap at DETECT_LONG_SIDE — so the
     * landmarks stay in upright display space, matching the renderer.
     */
    fun analyze(imageProxy: ImageProxy) {
        try {
            if (released) return
            val rotation = imageProxy.imageInfo.rotationDegrees
            val mirror = isFrontCamera

            val t0 = SystemClock.uptimeMillis()
            val src = copyToPooledBitmap(imageProxy)
            val t1 = SystemClock.uptimeMillis()

            // Scale FIRST (row-coherent reads, cache-friendly), then rotate the
            // tiny result (filter=false: 90° steps are pixel-exact). One matrix
            // that scales AND rotates reads the multi-MB source column-major —
            // every bilinear tap a cache miss — and measured ~5x slower.
            val scale = (DETECT_LONG_SIDE.toFloat() / maxOf(src.width, src.height)).coerceAtMost(1f)
            val sw = (src.width * scale).toInt().coerceAtLeast(1)
            val sh = (src.height * scale).toInt().coerceAtLeast(1)
            val small = Bitmap.createScaledBitmap(src, sw, sh, true)
            val matrix = Matrix().apply {
                postRotate(rotation.toFloat())
                if (mirror) postScale(-1f, 1f)
            }
            var detectBmp = Bitmap.createBitmap(small, 0, 0, sw, sh, matrix, false)
            if (detectBmp !== small && small !== src) small.recycle()
            if (detectBmp === src) {
                // Degenerate identity case: never hand the pooled frame itself
                // to the detector, it is released separately by the renderer.
                detectBmp = src.copy(Bitmap.Config.ARGB_8888, false)
            }
            val t2 = SystemClock.uptimeMillis()
            copyEmaMs += ((t1 - t0) - copyEmaMs) * 0.1f
            prepEmaMs += ((t2 - t1) - prepEmaMs) * 0.1f

            // detectAsync requires strictly increasing timestamps.
            val ts = if (t2 <= lastTimestampMs) lastTimestampMs + 1 else t2
            lastTimestampMs = ts

            synchronized(pendingLock) {
                if (pending.size >= MAX_IN_FLIGHT) {
                    // MediaPipe is saturated: drop THIS frame (keep-only-latest
                    // would starve the frames already inside the pipeline).
                    pool.release(src)
                    return
                }
                pending[ts] = PendingFrame(src, rotation, mirror)
            }
            try {
                // NOTE: detectBmp is intentionally NOT recycled — MediaPipe may
                // still read it asynchronously (and skipped frames never get a
                // callback). It is small; GC handles it.
                landmarker.detectAsync(BitmapImageBuilder(detectBmp).build(), ts)
            } catch (t: Throwable) {
                Log.w(TAG, "detectAsync failed", t)
                synchronized(pendingLock) { pending.remove(ts) }?.let { pool.release(it.full) }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "analyze failed", t)
        } finally {
            imageProxy.close()
        }
    }

    /**
     * On MediaPipe's callback thread: marry the result to the frame submitted
     * with the same timestamp and deliver both to the renderer. Frames the
     * pipeline skipped (older than this result, no callback coming) are
     * returned to the pool.
     */
    private fun onResult(result: FaceLandmarkerResult, @Suppress("UNUSED_PARAMETER") input: MPImage) {
        val ts = result.timestampMs()
        var frame: PendingFrame? = null
        synchronized(pendingLock) {
            val it = pending.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (e.key > ts) break
                if (e.key < ts) pool.release(e.value.full) else frame = e.value
                it.remove()
            }
        }
        val f = frame ?: return
        if (released) {
            pool.release(f.full)
            return
        }

        val now = SystemClock.uptimeMillis()
        detectEmaMs += ((now - ts) - detectEmaMs) * 0.1f
        if (++frameCount % LOG_EVERY_FRAMES == 0L) {
            val fps = if (lastLogUptimeMs > 0) {
                LOG_EVERY_FRAMES * 1000f / (now - lastLogUptimeMs)
            } else 0f
            lastLogUptimeMs = now
            Log.i(
                TAG,
                "pipeline copy %.1fms prep %.1fms detect %.1fms -> %.1f fps"
                    .format(copyEmaMs, prepEmaMs, detectEmaMs, fps)
            )
        }

        val faces = smoothAndHold(result, now, ts / 1000.0)
        onFrame(f.full, f.rotation, f.mirror, faces)
    }

    /**
     * RGBA copy of the proxy into a pooled bitmap — the androidx `toBitmap()`
     * allocates a fresh multi-MB bitmap per frame, and that GC churn is jank.
     */
    private fun copyToPooledBitmap(proxy: ImageProxy): Bitmap {
        val plane = proxy.planes[0]
        if (plane.pixelStride == 4 && plane.rowStride == proxy.width * 4) {
            val bmp = pool.acquire(proxy.width, proxy.height)
            val buf = plane.buffer
            buf.rewind()
            bmp.copyPixelsFromBuffer(buf)
            return bmp
        }
        // Row-padded buffer (device-dependent): fall back to the allocating
        // copy. The pool accepts these on release, so it still stabilizes.
        return proxy.toBitmap()
    }

    /**
     * One Euro smoothing + occlusion grace, on the MediaPipe callback thread.
     *
     * The filter NEVER pops off mid-use: a dropout inside the grace window
     * returns the last landmarks held perfectly still, so fast head motion or
     * a phone shake — where the detector is likeliest to briefly lose the
     * face — reads as the filter sticking to the face, not blinking off/on.
     */
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

    fun release() {
        released = true
        synchronized(pendingLock) {
            for (f in pending.values) pool.release(f.full)
            pending.clear()
        }
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

        // How many frames may sit inside MediaPipe's async pipeline at once.
        // 2 lets its detector/landmark stages overlap (the throughput win);
        // more just adds latency and memory.
        private const val MAX_IN_FLIGHT = 2

        // Detection input long-side. Landmarks are normalized, so this can be well
        // below the display resolution — keeps detection cheap without hurting
        // the sharp full-res preview. 384 keeps the loop 25fps+ on budget SoCs
        // (less GPU preprocessing to contend with the render pipeline);
        // MediaPipe's landmark model works on a 256px face crop anyway, and a
        // selfie face fills most of the frame.
        private const val DETECT_LONG_SIDE = 384

        // One Euro tuning (normalized 0..1 coordinates, ~30 Hz).
        // MIN_CUTOFF: smoothing at rest (lower = steadier, laggier).
        // BETA: how fast the cutoff rises with velocity (higher = snappier motion).
        // Keep in sync with the iOS FaceTracker.
        private const val MIN_CUTOFF = 1.0f
        private const val BETA = 15f
        private const val D_CUTOFF = 1.0f

        // Hold the last landmarks this long when the face briefly drops out —
        // generous on purpose: fast head motion / a phone shake is exactly when
        // the detector blips, and the filter visibly popping off and back on is
        // the tell that "it's a filter". Holding still through the blip is
        // invisible; only a real, sustained disappearance clears the filter.
        private const val OCCLUSION_GRACE_MS = 500L

        private const val LOG_EVERY_FRAMES = 300L
    }
}
