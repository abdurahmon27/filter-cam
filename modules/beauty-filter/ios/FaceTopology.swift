//
//  FaceTopology.swift
//  BeautyFilter
//
//  Maps Apple Vision's landmark regions onto the roles the beauty pipeline
//  needs: a face-oval polygon (skin region) plus eyes / brows / lips polygons
//  to punch out of the smoothing mask, and a handful of anchor points for the
//  mustache sprite.
//
//  Vision vs. MediaPipe (Android)
//  ------------------------------
//  The Android side uses MediaPipe's 478-point canonical mesh and indexes fixed
//  landmark *rings* out of a flat array (see FaceTopology.kt). Vision exposes a
//  much coarser set of *named regions* (`VNFaceLandmarks2D`) with a
//  model-defined, variable number of points each -- there is no 478-point mesh
//  and no stable global index. So instead of index rings we consume whole
//  regions:
//     Android ring            ->  Vision region
//     FACE_OVAL               ->  faceContour  (open jaw/cheek arc, see note)
//     LEFT_EYE / RIGHT_EYE    ->  leftEye / rightEye
//     LEFT_BROW / RIGHT_BROW  ->  leftEyebrow / rightEyebrow
//     LIPS_OUTER              ->  outerLips
//     NOSE_BOTTOM (subnasale) ->  lowest point of `nose`
//     UPPER_LIP_TOP           ->  topmost point of `outerLips`
//     MOUTH_LEFT / MOUTH_RIGHT->  extreme-x points of `outerLips`
//
//  NOTE on the face oval: Vision's `faceContour` traces only the jaw/cheek arc
//  (roughly ear-to-ear along the chin) -- it does NOT close over the forehead
//  like MediapPipe's FACE_OVAL ring. To get a filled skin region we synthesize
//  a closed polygon from the contour plus the two eyebrows (top edge). See
//  `faceOvalPolygon`.
//

import Foundation
import CoreGraphics
import Vision

/// Landmark polygons and anchors in **display-normalized** coordinates:
/// x,y in 0...1, origin top-left, y growing downward -- the same space the
/// Android renderer's landmarks live in after its rotate/mirror step.
struct FaceLandmarks {
    var faceOval: [CGPoint]   // closed skin-region polygon
    var leftEye: [CGPoint]
    var rightEye: [CGPoint]
    var leftBrow: [CGPoint]
    var rightBrow: [CGPoint]
    var outerLips: [CGPoint]
    var allPoints: [CGPoint]  // everything Vision returned, for the mesh overlay

    // Mustache anchors (display-normalized).
    var noseBottom: CGPoint
    var upperLipTop: CGPoint
    var mouthLeft: CGPoint
    var mouthRight: CGPoint
}

enum FaceTopology {

    /// Builds `FaceLandmarks` from a Vision observation.
    ///
    /// The observation must have been produced from a pixel buffer that is
    /// already upright and (for the front camera) mirrored, so the resulting
    /// coordinates are directly in display space -- exactly like the Android
    /// FaceTracker rotates/mirrors the bitmap before detection.
    static func makeLandmarks(from observation: VNFaceObservation) -> FaceLandmarks? {
        guard let lm = observation.landmarks else { return nil }
        let box = observation.boundingBox // normalized, origin bottom-left

        // Convert a region's box-relative points to display-normalized (y-down).
        func region(_ r: VNFaceLandmarkRegion2D?) -> [CGPoint] {
            guard let r = r else { return [] }
            return r.normalizedPoints.map { p in
                let ix = box.minX + CGFloat(p.x) * box.width
                let iyBottomUp = box.minY + CGFloat(p.y) * box.height
                return CGPoint(x: ix, y: 1.0 - iyBottomUp) // flip to y-down
            }
        }

        let contour = region(lm.faceContour)
        let leftEye = region(lm.leftEye)
        let rightEye = region(lm.rightEye)
        let leftBrow = region(lm.leftEyebrow)
        let rightBrow = region(lm.rightEyebrow)
        let outerLips = region(lm.outerLips)
        let nose = region(lm.nose)
        let allPoints = region(lm.allPoints)

        // Need at least a contour + lips to do anything useful.
        guard contour.count >= 3, outerLips.count >= 3 else { return nil }

        let faceOval = faceOvalPolygon(contour: contour, leftBrow: leftBrow, rightBrow: rightBrow)

        // Anchors. y-down means "topmost" == min y, "lowest" == max y.
        let upperLipTop = outerLips.min(by: { $0.y < $1.y }) ?? outerLips[0]
        let mouthLeft = outerLips.min(by: { $0.x < $1.x }) ?? outerLips[0]
        let mouthRight = outerLips.max(by: { $0.x < $1.x }) ?? outerLips[0]
        // Subnasale approximation: the lowest nose point. Vision has no dedicated
        // subnasale landmark, so this is intentionally coarse.
        let noseBottom = nose.max(by: { $0.y < $1.y })
            ?? CGPoint(x: upperLipTop.x, y: upperLipTop.y - 0.03)

        return FaceLandmarks(
            faceOval: faceOval,
            leftEye: leftEye,
            rightEye: rightEye,
            leftBrow: leftBrow,
            rightBrow: rightBrow,
            outerLips: outerLips,
            allPoints: allPoints,
            noseBottom: noseBottom,
            upperLipTop: upperLipTop,
            mouthLeft: mouthLeft,
            mouthRight: mouthRight
        )
    }

    /// Synthesizes a closed skin polygon. `faceContour` covers the lower face
    /// arc (jaw + cheeks); we append the eyebrow points (highest first) to close
    /// a rough oval that reaches up to the brow line. The renderer draws this as
    /// a centroid triangle-fan, so the polygon only needs to be a sane ordered
    /// loop, not perfectly convex.
    ///
    /// TODO(ios): tune the forehead coverage -- Vision gives no forehead
    /// landmarks, so the mask currently stops at the brows. If more forehead
    /// smoothing is desired, extrapolate the contour endpoints upward.
    private static func faceOvalPolygon(contour: [CGPoint],
                                        leftBrow: [CGPoint],
                                        rightBrow: [CGPoint]) -> [CGPoint] {
        var poly = contour
        // Brows, ordered so the loop stays continuous around the top.
        // Vision returns brow points left-to-right in image space.
        let brows = (rightBrow + leftBrow).sorted { $0.x > $1.x }
        poly.append(contentsOf: brows)
        return poly
    }
}
