# FilterCam 🥸

A React Native (Expo) camera app with **real face-tracked filters on Android**,
implemented in **Kotlin** as a local Expo native module.

- **Home screen** → **Open Camera** button
- **Camera screen** with a bottom filter bar:
  - **Beauty ✨** — GPU skin smoothing applied **only to the face**: MediaPipe
    Face Mesh (478 landmarks) builds a mask from the face oval with the eyes,
    brows and lips punched out, and an OpenGL blur/composite pipeline smooths
    just that region.
  - **Mustache 🥸** — a mustache sprite anchored to the nose/upper-lip
    landmarks, following position, scale and head roll in real time.
- Flip camera + shutter button. Captures save the **filtered** frame (WYSIWYG).

Runs on **Android** (Kotlin + OpenGL ES) and **iOS** (Swift + Metal + Vision),
both behind the same JS component. Up to **5 faces** are tracked at once.

## Tech stack — SDKs & packages

The interesting dependencies all live on the native (Kotlin) side. The JS shell
is deliberately thin.

### Face tracking & beauty (native — `modules/beauty-filter/android/build.gradle`)

| Package | Version | Role |
|---------|---------|------|
| **`com.google.mediapipe:tasks-vision`** | `0.10.26.1` | **Face tracking.** MediaPipe Tasks *Face Landmarker* — a **478-point face mesh** run in `LIVE_STREAM` mode with a **GPU delegate (CPU fallback)**. This is the SDK that finds the face and its landmarks every frame. |
| Model asset: `face_landmarker.task` | ~3.7 MB | The MediaPipe face-mesh model, bundled in `src/main/assets` and kept uncompressed (`noCompress 'task'`) so it can be memory-mapped. |
| **`androidx.camera:camera-core` / `camera-camera2` / `camera-lifecycle`** | `1.6.0` | **CameraX.** Drives the camera: a `Preview` use case feeds the OpenGL surface, and an `ImageAnalysis` use case (`STRATEGY_KEEP_ONLY_LATEST`) feeds frames to MediaPipe on a background executor. |
| **OpenGL ES 2.0** (platform) | — | **Beauty rendering.** Not a package — the Android platform GL API. `BeautyRenderer` implements the whole per-frame pipeline (scene FBO → two-pass gaussian blur → face-mask FBO → composite → mustache sprite) in hand-written GLSL shaders. |
| `android.graphics.Canvas` (platform) | — | Draws the mustache sprite once, uploaded as a GL texture (`MustacheTexture.kt`). |
| `androidx.core:core-ktx` | `1.18.0` | Kotlin extensions. |

**In short:** *MediaPipe Face Landmarker* does the face tracking, *CameraX*
supplies the frames, and *OpenGL ES 2.0* does the beauty smoothing + mustache
compositing. There is **no third-party "beauty SDK"** — the skin-smoothing is a
custom mask-driven blur written directly in GLSL.

### App shell (JS — `package.json`)

| Package | Version | Role |
|---------|---------|------|
| `expo` | `~57.0.1` | Expo SDK 57; the local native module is wired through the **Expo Modules API**. |
| `react-native` | `0.86.0` | UI runtime. |
| `react` | `19.2.3` | — |
| `expo-camera` | `~57.0.0` | Fallback camera used **only in Expo Go** (no native module there). |
| `expo-dev-client` | `~57.0.4` | Custom dev client so the native module can be loaded in development. |
| `expo-blur` / `expo-status-bar` | `~57.0.0` | Decorative JS-overlay fallback + status bar. |

> Pin note: everything is on the **Expo SDK 57** line (RN 0.86 / React 19). See
> the versioned docs at <https://docs.expo.dev/versions/v57.0.0/> before
> touching the JS/config side.

## How the native side works (`modules/beauty-filter`)

The Android module is organized by responsibility (see
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the full map):

```
android/.../beautyfilter/
  BeautyFilterModule.kt     # Expo wiring: props, takePicture()
  view/    BeautyCameraView.kt · CameraController.kt   # RN view + CameraX
  tracking/ FaceTracker.kt · FaceTopology.kt           # MediaPipe, multi-face
  render/  BeautyRenderer.kt (orchestrator) + one *Pass.kt per step
           Viewport.kt (crop + coords) · MustacheTexture.kt
  gl/      GlUtils · Framebuffer · ScreenQuad · Shaders
  src/main/assets/face_landmarker.task   # MediaPipe face mesh model (~3.7 MB)

ios/       BeautyFilterModule.swift · BeautyCameraView.swift
           CameraController.swift (AVFoundation) · FaceTracker.swift (Vision)
           MetalRenderer.swift · Shaders.metal · MustacheTexture.swift
           README-ios.md   # Vision-vs-MediaPipe notes + first-build checklist
```

Performance notes:
- Camera frames never leave the GPU for rendering (OES/CVPixelBuffer -> shaders).
- Face detection runs on a 4:3 analysis stream (`KEEP_ONLY_LATEST`) on its own
  thread; MediaPipe runs in LIVE_STREAM mode with a GPU delegate when
  available (CPU fallback). Up to 5 faces are tracked.
- The blur and mask passes run at quarter resolution; landmarks are
  exponentially smoothed **per face** to keep the mask and mustache stable.
- Beauty adds a subtle "glow" (brightness/warmth lift + highlight bloom) inside
  the face mask for a younger, shinier look.

## JS layout

```
App.tsx                       # root, switches Home <-> Camera
src/screens/HomeScreen.tsx    # landing screen + Open Camera button
src/screens/CameraScreen.tsx  # renders the native BeautyCameraView when the
                              # binary contains it; JS-overlay fallback in Expo Go
src/components/*              # filter bar + Expo Go fallback overlays
src/native/beautyFilter.ts    # optional-module bridge helpers
modules/beauty-filter/        # the local Expo module (Kotlin)
  index.ts                    #   exports BeautyCameraView + types
```

## Architecture

For a deeper walk-through of how the pieces fit together — the JS/native
boundary, the per-frame OpenGL pipeline, face-tracking threads, and the capture
flow, with **mermaid diagrams** — see [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## Run it on Android

Needs a device/emulator with a camera, JDK 17+, and the Android SDK.

```sh
npm install
npx expo run:android          # prebuilds android/ and installs the debug app
```

`android/` is generated (it's gitignored); the source of truth for native code
is `modules/beauty-filter/android`.

Expo Go still works for UI iteration, but falls back to the fake JS overlays
(full-screen blur + draggable mustache) since it can't contain native code.

## Deep review — what's good, what's not

A candid assessment of the design, not a sales pitch.

### What's good

- **The right architecture for real-time filters.** Camera pixels stay on the
  GPU as an OES external texture from capture to display — nothing is copied to
  the CPU for the live view. That's the single most important decision for a
  smooth preview, and it's done correctly.
- **Face tracking and rendering are decoupled and run off the UI thread.**
  MediaPipe runs in `LIVE_STREAM` mode on CameraX's analysis executor with
  `KEEP_ONLY_LATEST`, so detection can lag without ever stalling the preview;
  the renderer just reads the latest smoothed landmarks via `@Volatile` fields.
  Producer/consumer with no locks on the hot path.
- **Beauty is applied *only to the face*, and cheaply.** A landmark-built mask
  (face oval, with eyes/brows/lips punched out) means smoothing doesn't wipe out
  facial detail, and the blur/mask passes run at **quarter resolution**
  (1/16 the pixels) — the standard, correct optimization.
- **WYSIWYG capture.** Photos are read back from the same rendered frame
  (`glReadPixels`), so the saved image is exactly what the user saw — no
  separate "apply filter to a still" code path that could drift from the preview.
- **Stability details are handled.** Landmarks are exponentially smoothed and
  time-stamped; stale results (>400 ms) are dropped so the filter fades out
  cleanly when the face leaves the frame. Front-camera mirroring is matched
  between the analysis bitmap and the preview so coordinates line up.
- **Graceful degradation.** GPU delegate → CPU fallback for MediaPipe; native
  module → JS-overlay fallback in Expo Go. The app doesn't hard-crash when its
  best path isn't available.
- **Thin, honest JS/native boundary.** One `<BeautyCameraView>` component plus a
  `takePicture()` promise. Easy to reason about, and well documented (see
  [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)).

### What's weak / where it falls short

- **Capture is preview-resolution, not sensor-resolution.** `glReadPixels` reads
  the on-screen framebuffer, so photos are limited to the view size rather than
  the full camera still — fine for a demo, not for a real camera app.
- **The preview rotation offset is empirical.** `PREVIEW_ROTATION_OFFSET` in
  `CameraPass` was tuned on a device to align the preview with the upright
  landmark space; other sensors/orientations may need a different value.
- **iOS is written but unverified.** The Swift/Metal/Vision module mirrors the
  Android contract but was authored without a Mac to compile against — expect to
  fix the metallib bundling, capture orientation, and the Vision-synthesized face
  mask on first build (see `ios/README-ios.md`).
- **Hand-rolled GL pipeline = maintenance surface.** Custom GLSL, FBOs and
  coordinate remapping are powerful but fragile; there are no automated tests
  around the render/coordinate math, so regressions are hard to catch without a
  device. (The pipeline is now split into one class per pass, which helps.)
- **No test coverage and no CI visible.** Correctness rests on manual on-device
  checking. The mask/landmark/crop remapping in particular benefits from tests.
- **Beauty is blur-based.** A mask-driven gaussian blur plus a brightness/warmth
  glow and highlight bloom — no frequency-separation smoothing or blemish removal
  that dedicated beauty SDKs offer. A reasonable scope choice, but not a full
  cosmetic pipeline.
- **No gallery/preview screen.** Captures are written to the app cache and only a
  `file://` URI is returned; there's no review, retake, or save-to-gallery flow.

**Bottom line:** the *engineering* is good — the real-time GPU pipeline, the
threading model, and the WYSIWYG capture are done the way a native filter camera
should be done. The *product* is a focused, cross-platform demo (Android verified,
iOS unverified): multi-face, preview-resolution capture, and a blur+glow beauty
effect. It's a strong foundation, not a finished app.

## Known limitations / next steps

- The preview rotation is portrait-tuned via `PREVIEW_ROTATION_OFFSET` in
  `render/CameraPass.kt`; other sensors/orientations may need a different value.
- iOS (`modules/beauty-filter/ios`) is written but not yet compiled — build it on
  a Mac and work through `ios/README-ios.md` before shipping.
- Captures come from the rendered preview (`glReadPixels` at view resolution),
  not the full-resolution sensor still.
- No photo gallery/preview screen yet — capture saves a JPEG into the app
  cache and resolves its `file://` URI.
