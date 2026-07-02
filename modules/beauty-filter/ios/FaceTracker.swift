//
//  FaceTracker.swift
//  BeautyFilter
//
//  Runs Vision's VNDetectFaceLandmarksRequest on camera frames and publishes
//  temporally-smoothed, display-oriented landmarks to the renderer.
//
//  This mirrors the Android FaceTracker: single face, live-stream style
//  (one in-flight request at a time), exponential smoothing (alpha 0.55) with a
//  staleness reset so the mask/mustache stay stable but still snap on big moves.
//

import Foundation
import Vision
import CoreVideo
import CoreMedia

final class FaceTracker {

    /// Called (on an internal queue) whenever a new result is available.
    /// Passes nil when no face is detected.
    var onLandmarks: ((FaceLandmarks?) -> Void)?

    private let request: VNDetectFaceLandmarksRequest
    private let queue = DispatchQueue(label: "beautyfilter.facetracker")
    private var inFlight = false

    // Temporal smoothing state.
    private var smoothed: FaceLandmarks?
    private var lastResultAt: CFTimeInterval = 0
    private static let smoothingAlpha: CGFloat = 0.55
    private static let staleResetSeconds: CFTimeInterval = 0.3

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
                self.publish(nil)
            }
        }
    }

    private func handleResults() {
        guard let face = request.results?.first,
              let fresh = FaceTopology.makeLandmarks(from: face) else {
            smoothed = nil
            publish(nil)
            return
        }

        let now = CACurrentMediaTime()
        let prev = smoothed
        let out: FaceLandmarks

        if let prev = prev,
           now - lastResultAt <= Self.staleResetSeconds,
           Self.sameShape(prev, fresh) {
            out = Self.lerp(prev, fresh, Self.smoothingAlpha)
        } else {
            out = fresh
        }

        smoothed = out
        lastResultAt = now
        publish(out)
    }

    private func publish(_ landmarks: FaceLandmarks?) {
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

    private static func lerp(_ a: FaceLandmarks, _ b: FaceLandmarks, _ t: CGFloat) -> FaceLandmarks {
        func lp(_ p: CGPoint, _ q: CGPoint) -> CGPoint {
            CGPoint(x: p.x + (q.x - p.x) * t, y: p.y + (q.y - p.y) * t)
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
            mouthRight: lp(a.mouthRight, b.mouthRight)
        )
    }
}
