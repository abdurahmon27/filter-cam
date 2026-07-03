# BeautyFilter — iOS native module

Live GPU beauty-filter camera for the FilterCam Expo app. This is the iOS
counterpart to the Android Kotlin/OpenGL module and implements the **same JS ↔
native contract** (see `../index.ts`), so the shared JS works unchanged on both
platforms.

Stack: **AVFoundation** (camera) → **Vision** (face landmarks) → **Metal**
(GPU render pipeline).

It implements the **full** Android feature set: the seven beauty sliders
(`smoothing`, `glow`, `clarity`, `warmth`, `eyeEnlarge`, `noseSlim`, `faceSlim`),
the mustache and face-mesh toggles, **up to 5 faces** at once, the two-mask
smoothing/tone split, the face-reshape (liquify) warp, and the sharpen blit — the
Metal shaders are ports of the Android GLSL in `gl/Shaders.kt`. The one seam not
ported is the GPU stream output (Android's `StreamOutput` → a WebRTC `Surface`);
see **Streaming** below.

> Written on Linux without Xcode — it has **not been compiled**. Treat it as a
> careful first draft to build on a Mac. Search for `TODO(ios):` for the spots
> most likely to need tuning.

## File layout

| File | Responsibility |
| --- | --- |
| `BeautyFilter.podspec` | CocoaPods spec; compiles Swift + `Shaders.metal` (→ `default.metallib`), links the system frameworks. |
| `BeautyFilterModule.swift` | Expo `Module` definition: `isAvailable`, `applyBeauty` (no-op), the view, its props and `takePicture`. Mirrors `BeautyFilterModule.kt`. |
| `BeautyCameraView.swift` | `ExpoView`: owns the session + `MTKView` + face tracker and wires them together. Mirrors `BeautyCameraView.kt`. |
| `CameraController.swift` | `AVCaptureSession` setup, front/back switching, delivers upright/mirrored BGRA `CVPixelBuffer`s. |
| `FaceTracker.swift` | Runs `VNDetectFaceLandmarksRequest`, keep-only-latest, **up to 5 faces**, adaptive per-point temporal smoothing (resets on a face-count change). Mirrors `FaceTracker.kt`. |
| `FaceTopology.swift` | `FaceLandmarks` model + maps Vision regions → face-oval / eyes / brows / lips + mustache & reshape anchors. Mirrors `FaceTopology.kt`. |
| `MetalRenderer.swift` | The `MTKViewDelegate` render pipeline (scene → blur → two masks → composite → reshape → mustache → mesh → sharpen) + capture readback. Mirrors `BeautyRenderer.kt` + the `*Pass.kt` classes. |
| `MustacheTexture.swift` | Draws the handlebar mustache with Core Graphics → `MTLTexture`. Mirrors `MustacheTexture.kt`. |
| `Shaders.metal` | Vertex/fragment functions: fullscreen pass-through, gaussian blur, solid fill (mask), composite (two masks + glow/clarity/warmth + life grade), reshape (liquify), sharpen, textured sprite, points. Ports of `gl/Shaders.kt`. |

## Render pipeline (per frame)

A one-to-one port of Android's passes:

1. **camera → scene** — sample the camera texture with a center-crop so the
   scene target holds exactly what's displayed.
2. **blur** — two-pass separable gaussian at quarter resolution
   (`scene → blurA` horizontal → `blurB` vertical), radius `2.6/side`.
3. **two face masks** (quarter res, for every tracked face) — a **skin mask**
   (oval white, eyes/brows/lips punched black) that gates smoothing, and a
   **face mask** (full oval) that gates the tone/light. Each is feathered with a
   `4/side` edge blur through a shared scratch target.
4. **composite → output** — edge-aware frequency-separation smoothing (skin
   mask) + `glow`/`clarity`/`warmth` (face mask) + a global micro-contrast/
   vibrance "life" grade. Same `composite_fragment` math as Android's
   `COMPOSITE_FS`.
5. **reshape → final** — a liquify warp that enlarges eyes, narrows the nose and
   slims the jaw by warping sampled UVs; a plain blit when all three strengths
   are 0. Region centres/radii are derived per face (inter-eye scale) exactly as
   Android's `FaceReshapePass`.
6. **mustache** — anchored between nose-bottom and upper-lip, scaled to mouth
   width, rotated to head roll (per face; computed in view pixels so aspect
   doesn't distort it).
7. **face mesh** — optional debug dots for every Vision landmark (per face).
8. **sharpen → drawable** — an unsharp-mask blit (`sharpen_fragment`, amount
   0.85) that also feeds the WYSIWYG capture readback.

Metal has **no triangle-fan primitive**, so mask polygons are expanded on the
CPU into a triangle list around each ring's centroid (`fanTriangles`). The
reshape arrays are passed as fixed-length (5 faces / 10 eyes) constant buffers so
they're always bound; the shader loops break at the live counts.

## Vision vs. MediaPipe (the important difference)

Android uses MediaPipe's **478-point** canonical mesh and indexes fixed landmark
*rings* out of a flat array. Apple **Vision** exposes a much coarser set of
**named regions** (`VNFaceLandmarks2D`) with a model-defined, variable number of
points each — there is no 478-point mesh and no stable global index. So instead
of index rings we consume whole regions:

| Android ring | Vision region |
| --- | --- |
| `FACE_OVAL` | `faceContour` **+ eyebrows** (synthesized closed loop — see below) |
| `LEFT_EYE` / `RIGHT_EYE` | `leftEye` / `rightEye` |
| `LEFT_BROW` / `RIGHT_BROW` | `leftEyebrow` / `rightEyebrow` |
| `LIPS_OUTER` | `outerLips` |
| `NOSE_BOTTOM` (subnasale) | lowest point of `nose` (approximation — Vision has no subnasale) |
| `UPPER_LIP_TOP` | topmost `outerLips` point |
| `MOUTH_LEFT` / `MOUTH_RIGHT` | extreme-x `outerLips` points |
| `NOSE_TIP` (reshape) | centroid of the `nose` region |
| `CHEEK_LEFT` / `CHEEK_RIGHT` / `CHIN` (reshape jaw) | x-extremes / lowest point of the synthesized face-oval polygon |

The eye centres and radii the reshape needs are computed in `MetalRenderer`
(`ringCenterVis`) from the `leftEye`/`rightEye` regions — the same way Android's
`FaceReshapePass` reads its eye rings — so no extra Vision regions are required.

**Face oval caveat:** Vision's `faceContour` traces only the jaw/cheek arc — it
does **not** close over the forehead like MediaPipe's `FACE_OVAL`. We synthesize
a closed skin polygon from the contour plus the eyebrow points, so the mask
currently reaches up only to the brow line (no forehead smoothing). See
`FaceTopology.faceOvalPolygon` — extrapolate the contour endpoints upward if you
want forehead coverage.

Up to **5 faces** are tracked (largest first). Smoothing is adaptive per point
(low base, rising with motion) and per face index, and — like Android — resets
whenever the face count changes, since ordering isn't stable across that
boundary. Because region point counts can also vary between frames, a given
face only lerps when every region's count matches; otherwise it takes the fresh
frame verbatim.

## Streaming (the one seam not yet ported)

Android exposes `BeautyCameraView.setStreamSurface(Surface?)` and a
`StreamOutput` that pushes every filtered frame GPU-side into an external
`Surface` (the LiveKit/WebRTC capturer seam — see `docs/STREAMING.md`). That has
**no iOS equivalent yet**, and it isn't part of the shared JS contract
(`index.ts` has no streaming prop/method), so the app doesn't need it to run.

The iOS analogue would publish the filtered frame as a `CVPixelBuffer` /
`CMSampleBuffer` to a consumer such as a LiveKit `RTCVideoSource`. The natural
hook is right after the reshape/overlay passes in `MetalRenderer.draw`: render
`finalTex` into a shared, CPU/GPU-visible target (the capture path already does
this into `stagingTex`) and hand its `CVPixelBuffer` to the sink. `TODO(ios):`
add a `frameOutput` callback + a `CVPixelBufferPool` when wiring a real
publisher.

## Orientation & mirroring

Android receives sensor-oriented frames and rotates the texture + detection
bitmap by hand (the fiddly `PREVIEW_ROTATION_OFFSET`). On iOS we push that work
onto the **capture connection** instead:

- `CameraController` sets `connection.videoOrientation = .portrait` and, for the
  **front** camera, `connection.isVideoMirrored = true`.
- So the delivered `CVPixelBuffer` is **already upright and selfie-mirrored** —
  both the Metal preview and Vision see the same display space. Vision runs with
  `.up` orientation and the renderer only has to center-crop (no rotation math).

Coordinate spaces:
- Vision landmarks are normalized to the face **bounding box**, origin
  bottom-left, y-up. `FaceTopology` converts them to display-normalized
  (0..1, origin top-left, y-**down**) — the same space the Android renderer used.
- Metal clip space is y-**up** (+1 top) and texture rows are top-down, so
  `MetalRenderer.toNdc` flips y, and capture readback needs **no** vertical flip
  (Android's `glReadPixels` path did).

`videoOrientation` is deprecated in iOS 17 (`videoRotationAngle` replaces it);
kept for the iOS 15.1 deployment target — see the `TODO(ios)` in
`CameraController`.

## First-build checklist (on a Mac)

- [ ] **`NSCameraUsageDescription`** — add a usage string to the app's
      `Info.plist` (e.g. via `app.json` → `ios.infoPlist`). Without it the app
      crashes the moment the session starts.
- [ ] **Test on a real device** — the **Simulator has no camera** and no
      Metal-capable GPU for this path; the preview stays black there. Verify on
      hardware.
- [ ] **Metal device availability** — `MTLCreateSystemDefaultDevice()` returns
      nil on the Simulator; the view degrades to black instead of crashing, but
      confirm the device path works.
- [ ] **`default.metallib` builds & loads** — confirm `Shaders.metal` is
      compiled into the pod and `makeDefaultLibrary(bundle:)` finds it. If the
      metallib ends up in the app bundle instead of the framework bundle, adjust
      the `Bundle(for:)` lookup in `MetalRenderer.buildPipelines`.
- [ ] **Connection orientation actually rotates the buffer** — verify the front
      preview is upright + mirrored and landmarks line up (toggle `faceMesh`).
      If not, revisit `CameraController.applyOrientation`.
- [ ] **Capture WYSIWYG** — `takePicture` should resolve a `file://` JPEG that
      includes the filter + mustache + reshape, correctly oriented (no flip). The
      capture path re-runs the sharpen into `stagingTex`, so it matches the
      preview.
- [ ] **All seven sliders drive the composite/reshape** — sweep `smoothing`,
      `glow`, `clarity`, `warmth`, then `eyeEnlarge`/`noseSlim`/`faceSlim`. The
      reshape pass is a plain blit at 0, so a mis-tuned anchor shows up only when
      a warp slider is raised. Nose/jaw anchors come from the Vision-synthesized
      oval, so expect to tune `noseCenter` / the cheek-chin picks in
      `MetalRenderer.renderReshape` on a real face.
- [ ] **Multi-face** — with two people in frame, confirm masks/mustache/reshape
      track both, and that adding/removing a face doesn't glitch (count-change
      smoothing reset).
- [ ] Module wiring — `expo-module.config.json` already lists `"ios"` and
      `BeautyFilterModule`; `app.json` already has the `ios` block. Just confirm
      the pod's `default.metallib` builds.
