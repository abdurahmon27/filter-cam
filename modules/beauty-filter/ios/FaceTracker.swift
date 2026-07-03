//
//  FaceTracker.swift
//  BeautyFilter
//
//  Runs Vision's VNDetectFaceLandmarksRequest on camera frames and publishes
//  temporally-smoothed, display-oriented landmarks to the renderer.
//
//  Mirrors the Android FaceTracker: up to 5 faces (`MAX_FACES`), keep-only-latest
//  (one in-flight request at a time), and adaptive per-point smoothing -- a low
//  base factor takes the jitter off but the factor rises with motion so landmarks
//  snap to the current frame on big moves instead of dragging. The smoothing
//  state resets when the face count changes (face ordering isn't stable across
//  that boundary), exactly like Android.
//

import Foundation
import Vision
import CoreVideo
import CoreMedia

final class FaceTracker {

    /// Called (on an internal queue) whenever a new result is available. Passes
    /// an empty array when no face is detected.
    var onLandmarks: (([FaceLandmarks]) -> Void)?

    private let request: VNDetectFaceLandmarksRequest
    private let queue = DispatchQueue(label: "beautyfilter.facetracker")
    private var inFlight = false

    // Per-face smoothing state (nil until the first successful detection).
    private var smoothed: [FaceLandmarks]?

    private static let maxFaces = 5
    // Adaptive smoothing (matches Android SMOOTHING_BASE / SMOOTHING_ADAPT): the
    // applied fraction is base + |delta| * adapt, clamped to 1.
    private static let smoothingBase: CGFloat = 0.6
    private static let smoothingAdapt: CGFloat = 25

    init() {
        request = VNDetectFaceLandmarksRequest()
        // Vision's newest revision has the richest landmark constellation.
        // TODO(ios): pin a revision explicitly if the point counts must stay
        // stable across OS versions (region point counts can differ per rev).
        request.usesCPUOnly = false
    }

    /// Submit a frame for detection. Drops frames while a request is in flight
    /// (keep-only-latest), so this is cheap to call on every camera frame.
    ///
    /// - Parameter pixelBuffer: an upright, front-mirrored BGRA buffer. Because
    ///   the buffer is already oriented for display, Vision runs with `.up`.
    func track(pixelBuffer: CVPixelBuffer) {
        queue.async { [weak self] in
            guard let self = self, !self.inFlight else { return }
            self.inFlight = true
            defer { self.inFlight = false }

            let handler = VNImageRequestHandler(cvPixelBuffer: pixelBuffer,
                                                orientation: .up,
                                                options: [:])
            do {
                try handler.perform([self.request])
                self.handleResults()
            } catch {
                NSLog("[BeautyFilter] Vision perform failed: \(error)")
                self.smoothed = nil
                self.publish([])
            }
        }
    }

    private func handleResults() {
        let observations = request.results ?? []
        // Largest faces first, capped at MAX_FACES, then converted to our model.
        let fresh: [FaceLandmarks] = observations
            .sorted { $0.boundingBox.width * $0.boundingBox.height >
                      $1.boundingBox.width * $1.boundingBox.height }
            .prefix(Self.maxFaces)
            .compactMap { FaceTopology.makeLandmarks(from: $0) }

        guard !fresh.isEmpty else {
            smoothed = nil
            publish([])
            return
        }

        let prev = smoothed
        let out: [FaceLandmarks]
        if let prev = prev, prev.count == fresh.count {
            // Same face count: smooth per face index (verbatim on a shape change).
            out = zip(prev, fresh).map { p, f in
                Self.sameShape(p, f) ? Self.adaptiveSmooth(p, f) : f
            }
        } else {
            // Face count changed -> ordering isn't stable; take the fresh frame.
            out = fresh
        }

        smoothed = out
        publish(out)
    }

    private func publish(_ landmarks: [FaceLandmarks]) {
        onLandmarks?(landmarks)
    }

    // MARK: - Smoothing helpers

    /// Vision can return a different number of points per region between frames
    /// (e.g. when confidence drops). Only smooth when every region matches, else
    /// we take the fresh frame verbatim to avoid index mismatches.
    private static func sameShape(_ a: FaceLandmarks, _ b: FaceLandmarks) -> Bool {
        return a.faceOval.count == b.faceOval.count &&
            a.leftEye.count == b.leftEye.count &&
            a.rightEye.count == b.rightEye.count &&
            a.leftBrow.count == b.leftBrow.count &&
            a.rightBrow.count == b.rightBrow.count &&
            a.outerLips.count == b.outerLips.count &&
            a.allPoints.count == b.allPoints.count
    }

    /// Adaptive lerp from `a` toward `b`, per axis: fraction = base + |delta|*adapt.
    private static func adaptiveSmooth(_ a: FaceLandmarks, _ b: FaceLandmarks) -> FaceLandmarks {
        func ax(_ pc: CGFloat, _ qc: CGFloat) -> CGFloat {
            let d = qc - pc
            let t = min(smoothingBase + abs(d) * smoothingAdapt, 1)
            return pc + d * t
        }
        func lp(_ p: CGPoint, _ q: CGPoint) -> CGPoint {
            CGPoint(x: ax(p.x, q.x), y: ax(p.y, q.y))
        }
        func la(_ xs: [CGPoint], _ ys: [CGPoint]) -> [CGPoint] {
            zip(xs, ys).map { lp($0, $1) }
        }
        return FaceLandmarks(
            faceOval: la(a.faceOval, b.faceOval),
            leftEye: la(a.leftEye, b.leftEye),
            rightEye: la(a.rightEye, b.rightEye),
            leftBrow: la(a.leftBrow, b.leftBrow),
            rightBrow: la(a.rightBrow, b.rightBrow),
            outerLips: la(a.outerLips, b.outerLips),
            allPoints: la(a.allPoints, b.allPoints),
            noseBottom: lp(a.noseBottom, b.noseBottom),
            upperLipTop: lp(a.upperLipTop, b.upperLipTop),
            mouthLeft: lp(a.mouthLeft, b.mouthLeft),
            mouthRight: lp(a.mouthRight, b.mouthRight),
            noseCenter: lp(a.noseCenter, b.noseCenter)
        )
    }
}
