# BeautyFilter — iOS native module

Live GPU beauty-filter camera for the FilterCam Expo app. This is the iOS
counterpart to the Android Kotlin/OpenGL module and implements the **same JS ↔
native contract** (see `../index.ts`), so the shared JS works unchanged on both
platforms.

Stack: **AVFoundation** (camera) → **Vision** (face landmarks) → **Metal**
(GPU render pipeline).

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
| `FaceTracker.swift` | Runs `VNDetectFaceLandmarksRequest`, keep-only-latest, exponential temporal smoothing (α = 0.55). Mirrors `FaceTracker.kt`. |
| `FaceTopology.swift` | `FaceLandmarks` model + maps Vision regions → face-oval / eyes / brows / lips / mustache anchors. Mirrors `FaceTopology.kt`. |
| `MetalRenderer.swift` | The `MTKViewDelegate` render pipeline (scene → blur → mask → composite → mustache → mesh → blit) + capture readback. Mirrors `BeautyRenderer.kt`. |
| `MustacheTexture.swift` | Draws the handlebar mustache with Core Graphics → `MTLTexture`. Mirrors `MustacheTexture.kt`. |
| `Shaders.metal` | Vertex/fragment functions: fullscreen pass-through, gaussian blur, solid fill (mask), composite, textured sprite, points. |

## Render pipeline (per frame)

Conceptually identical to Android's passes:

1. **camera → scene** — sample the camera texture with a center-crop so the
   scene target holds exactly what's displayed.
2. **blur** — two-pass separable gaussian at quarter resolution
   (`scene → blurA` horizontal → `blurB` vertical).
3. **face mask** — fill the face-oval polygon white, punch out eyes/brows/lips
   black (slightly inflated), then blur for a soft edge.
4. **composite → output** — `mix(scene, smoothed, mask * smoothing)` with the
   same mild brighten/lift as Android.
5. **mustache** — anchored between nose-bottom and upper-lip, scaled to mouth
   width, rotated to head roll (computed in view pixels so aspect doesn't
   distort it).
6. **face mesh** — optional debug dots for every Vision landmark.
7. **blit → drawable**.

Metal has **no triangle-fan primitive**, so mask polygons are expanded on the
CPU into a triangle list around each ring's centroid (`fanTriangles`).

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

**Face oval caveat:** Vision's `faceContour` traces only the jaw/cheek arc — it
does **not** close over the forehead like MediaPipe's `FACE_OVAL`. We synthesize
a closed skin polygon from the contour plus the eyebrow points, so the mask
currently reaches up only to the brow line (no forehead smoothing). See
`FaceTopology.faceOvalPolygon` — extrapolate the contour endpoints upward if you
want forehead coverage.

Because region point counts can vary between frames, temporal smoothing only
lerps when every region's count matches; otherwise it takes the fresh frame
verbatim.

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
      includes the filter + mustache, correctly oriented (no flip).
- [ ] Wire the module for iOS in `expo-module.config.json` (handled separately —
      add `"ios"` to `platforms` and the module class).
