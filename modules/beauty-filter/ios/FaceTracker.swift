//
//  FaceTracker.swift
//  BeautyFilter
//
//  Runs Vision's VNDetectFaceLandmarksRequest and delivers each frame
//  TOGETHER with temporally-smoothed, display-oriented landmarks detected on
//  that exact frame.
//
//  Mirrors the Android FaceTracker's **frame-locked two-stage pipeline** (the
//  approach the major AR-filter apps use): the warp can never slide off the
//  face during motion — image and effect are delayed together by the detect
//  time (invisible); the effect lagging the image (a decoupled design, tried
//  on Android) is what reads as a "dancing" filter.
//
//  Stage 1 (camera sample queue): `analyze` parks the newest buffer in the
//  keep-only-latest slot — never blocks on detection.
//  Stage 2 (detect queue): Vision runs on the waiting buffer and the frame +
//  its own landmarks go out via `onFrame` together. While Vision handles
//  frame N the camera queue is already delivering N+1, so throughput is the
//  slower stage, not the sum. If detection lags the camera rate the pending
//  buffer is replaced (fps dips, never misaligns) — the same policy as
//  Android and as alwaysDiscardsLateVideoFrames.
//
//  Smoothing is a One Euro filter (no extrapolation — landmarks are already
//  matched to the displayed pixels, prediction would create misalignment), plus
//  a short grace period that holds the last landmarks when the face briefly
//  drops out (finger over the face, extreme pose). Tuning constants are kept
//  in sync with the Android FaceTracker — change them in both places or the
//  two platforms will feel different.
//

import Foundation
import Vision
import CoreVideo
import CoreMedia
import QuartzCore

final class FaceTracker {

    /// Frame + the landmarks detected on that exact frame (frame-locked).
    /// Called on the detect queue, once per processed frame.
    var onFrame: ((CVPixelBuffer, [FaceLandmarks]) -> Void)?

    private let request: VNDetectFaceLandmarksRequest

    // Stage 2 runs here so stage 1 (the camera sample queue) never waits on
    // Vision. Serial: all smoothing state below is confined to this queue.
    private let detectQueue = DispatchQueue(label: "beautyfilter.facetracker.detect")

    // Newest buffer waiting for the detect queue (keep-only-latest, like
    // CameraX's backpressure on Android). Holding it here + one in Vision +
    // one in the renderer keeps at most 3 buffers from the capture pool
    // outstanding; alwaysDiscardsLateVideoFrames degrades gracefully if the
    // pool ever runs dry.
    private let pendingLock = NSLock()
    private var pending: CVPixelBuffer?

    // Smoothing / hold state (touched only on the detect queue).
    private let filter = OneEuroFilterBank()
    private var held: [FaceLandmarks]?
    private var lastFaceSeen: CFTimeInterval = 0
    private var detectEmaMs: Double = 0
    private var frameCount: UInt64 = 0
    private var lastLogTime: CFTimeInterval = 0

    // Largest face only, matching Android's numFaces=1 (single-host streaming;
    // raise on both platforms together if multi-face becomes a requirement).
    private static let maxFaces = 1

    // One Euro tuning (normalized 0..1 coordinates) — keep in sync with the
    // Android FaceTracker companion constants.
    fileprivate static let minCutoff: CGFloat = 1.0
    fileprivate static let beta: CGFloat = 15
    fileprivate static let dCutoff: CGFloat = 1.0

    // Hold the last landmarks this long when the face briefly drops out —
    // generous on purpose (matches Android): fast head motion / a phone shake
    // is exactly when the detector blips, and the filter visibly popping off
    // and back on is the tell that "it's a filter". Holding still through the
    // blip is invisible; only a sustained disappearance clears the filter.
    private static let occlusionGraceSec: CFTimeInterval = 0.5

    init() {
        request = VNDetectFaceLandmarksRequest()
        // Vision's newest revision has the richest landmark constellation.
        // TODO(ios): pin a revision explicitly if the point counts must stay
        // stable across OS versions (region point counts can differ per rev).
        request.usesCPUOnly = false
    }

    /// Stage 1, on the camera sample queue: park the newest buffer and poke
    /// the detect queue. Never blocks on Vision.
    ///
    /// - Parameter pixelBuffer: an upright, front-mirrored BGRA buffer. Because
    ///   the buffer is already oriented for display, Vision runs with `.up`.
    func analyze(_ pixelBuffer: CVPixelBuffer) {
        pendingLock.lock()
        pending = pixelBuffer // replaces an unstarted older frame (ARC releases it)
        pendingLock.unlock()
        detectQueue.async { [weak self] in self?.drainOne() }
    }

    /// Stage 2, on the detect queue: detect on the waiting buffer and deliver
    /// frame + landmarks together.
    private func drainOne() {
        pendingLock.lock()
        let buffer = pending
        pending = nil
        pendingLock.unlock()
        guard let buffer = buffer else { return } // drained by a previous task

        let start = CACurrentMediaTime()
        let faces = detect(pixelBuffer: buffer)

        detectEmaMs += ((CACurrentMediaTime() - start) * 1000 - detectEmaMs) * 0.1
        frameCount += 1
        if frameCount % 300 == 0 {
            let now = CACurrentMediaTime()
            let fps = lastLogTime > 0 ? 300.0 / (now - lastLogTime) : 0
            lastLogTime = now
            NSLog("[BeautyFilter] pipeline detect %.1fms -> %.1f fps", detectEmaMs, fps)
        }

        onFrame?(buffer, faces)
    }

    /// Detect + smooth landmarks for a frame, on the detect queue; returns an
    /// empty array when no face is present (after the grace period).
    private func detect(pixelBuffer: CVPixelBuffer) -> [FaceLandmarks] {
        let now = CACurrentMediaTime()
        let handler = VNImageRequestHandler(cvPixelBuffer: pixelBuffer,
                                            orientation: .up,
                                            options: [:])
        do {
            try handler.perform([request])
        } catch {
            NSLog("[BeautyFilter] Vision perform failed: \(error)")
            filter.reset()
            held = nil
            return []
        }

        let observations = request.results ?? []
        // Largest faces first, capped at maxFaces, then converted to our model.
        let fresh: [FaceLandmarks] = observations
            .sorted { $0.boundingBox.width * $0.boundingBox.height >
                      $1.boundingBox.width * $1.boundingBox.height }
            .prefix(Self.maxFaces)
            .compactMap { FaceTopology.makeLandmarks(from: $0) }

        guard let face = fresh.first else {
            if let h = held, now - lastFaceSeen <= Self.occlusionGraceSec {
                // Brief dropout: hold the last landmarks frozen. Filter state is
                // kept so reacquisition is a smooth continuation, not a snap.
                return h
            }
            filter.reset()
            held = nil
            return []
        }
        lastFaceSeen = now

        let flat = Self.flatten(face)
        let smoothedPoints = filter.apply(flat.points, shape: flat.shape, tsSec: now)
        let out = Self.unflatten(smoothedPoints, like: face)

        held = [out]
        return [out]
    }

    // MARK: - Flattening

    // Vision can return a different number of points per region between frames
    // (e.g. when confidence drops). The filter bank resets whenever the shape
    // changes, so regions never get misaligned across frames.
    private static func flatten(_ f: FaceLandmarks) -> (points: [CGPoint], shape: [Int]) {
        var points: [CGPoint] = []
        points.reserveCapacity(f.faceOval.count + f.leftEye.count + f.rightEye.count +
                               f.leftBrow.count + f.rightBrow.count + f.outerLips.count +
                               f.allPoints.count + 5)
        points.append(contentsOf: f.faceOval)
        points.append(contentsOf: f.leftEye)
        points.append(contentsOf: f.rightEye)
        points.append(contentsOf: f.leftBrow)
        points.append(contentsOf: f.rightBrow)
        points.append(contentsOf: f.outerLips)
        points.append(contentsOf: f.allPoints)
        points.append(f.noseBottom)
        points.append(f.upperLipTop)
        points.append(f.mouthLeft)
        points.append(f.mouthRight)
        points.append(f.noseCenter)
        let shape = [f.faceOval.count, f.leftEye.count, f.rightEye.count,
                     f.leftBrow.count, f.rightBrow.count, f.outerLips.count,
                     f.allPoints.count]
        return (points, shape)
    }

    private static func unflatten(_ pts: [CGPoint], like f: FaceLandmarks) -> FaceLandmarks {
        var i = 0
        func take(_ n: Int) -> [CGPoint] {
            defer { i += n }
            return Array(pts[i..<(i + n)])
        }
        func one() -> CGPoint {
            defer { i += 1 }
            return pts[i]
        }
        return FaceLandmarks(
            faceOval: take(f.faceOval.count),
            leftEye: take(f.leftEye.count),
            rightEye: take(f.rightEye.count),
            leftBrow: take(f.leftBrow.count),
            rightBrow: take(f.rightBrow.count),
            outerLips: take(f.outerLips.count),
            allPoints: take(f.allPoints.count),
            noseBottom: one(),
            upperLipTop: one(),
            mouthLeft: one(),
            mouthRight: one(),
            noseCenter: one()
        )
    }
}

/// One Euro filter (Casiez et al.) over a flat landmark point list. At rest
/// the cutoff sits at `minCutoff` and jitter is strongly damped; during motion
/// the cutoff rises with the (low-passed) landmark velocity so the filtered
/// value tracks the fresh measurement with near-zero lag.
private final class OneEuroFilterBank {
    private var x: [CGPoint] = []       // filtered positions
    private var v: [CGPoint] = []       // filtered velocities (units/s)
    private var shape: [Int] = []
    private var lastTsSec: CFTimeInterval = 0

    func reset() {
        x = []
        v = []
        shape = []
    }

    func apply(_ raw: [CGPoint], shape rawShape: [Int], tsSec: CFTimeInterval) -> [CGPoint] {
        if x.count != raw.count || shape != rawShape {
            x = raw
            v = Array(repeating: .zero, count: raw.count)
            shape = rawShape
            lastTsSec = tsSec
            return raw
        }
        let dt = CGFloat(min(max(tsSec - lastTsSec, 1.0 / 120.0), 0.25))
        lastTsSec = tsSec

        let alphaV = Self.alpha(dt: dt, cutoff: FaceTracker.dCutoff)
        var out = raw
        for i in raw.indices {
            let rawVx = (raw[i].x - x[i].x) / dt
            let rawVy = (raw[i].y - x[i].y) / dt
            let vel = CGPoint(x: v[i].x + alphaV * (rawVx - v[i].x),
                              y: v[i].y + alphaV * (rawVy - v[i].y))
            v[i] = vel
            let ax = Self.alpha(dt: dt, cutoff: FaceTracker.minCutoff + FaceTracker.beta * abs(vel.x))
            let ay = Self.alpha(dt: dt, cutoff: FaceTracker.minCutoff + FaceTracker.beta * abs(vel.y))
            let f = CGPoint(x: x[i].x + ax * (raw[i].x - x[i].x),
                            y: x[i].y + ay * (raw[i].y - x[i].y))
            x[i] = f
            out[i] = f
        }
        return out
    }

    private static func alpha(dt: CGFloat, cutoff: CGFloat) -> CGFloat {
        let tau = 1.0 / (2.0 * .pi * cutoff)
        return 1.0 / (1.0 + tau / dt)
    }
}
