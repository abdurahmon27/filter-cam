# FilterCam 🥸

A React Native (Expo) camera app with **real face-tracked filters on Android**,
implemented in **Kotlin** as a local Expo native module.

- **Home screen** → **Open Camera** button
- **Camera screen** with a bottom filter carousel of independent sliders:
  - **Beauty ✨** — GPU skin smoothing applied **only to the face**: MediaPipe
    Face Mesh (478 landmarks) builds a mask from the face oval with the eyes,
    brows and lips punched out, and an OpenGL blur/composite pipeline smooths
    just that region. Alongside smoothing are **glow**, **clarity** (even
    tone / redness reduction) and **warmth** sliders that light the whole face.
  - **Reshape** — **eye-enlarge**, **nose-slim** and **face-slim** liquify
    warps, each scaled to the tracked face.
  - **Mustache 🥸** — a mustache sprite anchored to the nose/upper-lip
    landmarks, following position, scale and head roll in real time.
- Flip camera, framing presets, and a shutter button. Captures save the
  **filtered** frame (WYSIWYG).

Runs on **Android** (Kotlin + OpenGL ES) and **iOS** (Swift + Metal + Vision),
both behind the same JS component. Up to **5 faces** are tracked at once.

## Tech stack — SDKs & packages

The interesting dependencies all live on the native (Kotlin) side. The JS shell
is deliberately thin.

### Face tracking & beauty (native — `modules/beauty-filter/android/build.gradle`)

| Package | Version | Role |
|---------|---------|------|
| **`com.google.mediapipe:tasks-vision`** | `0.10.26.1` | **Face tracking.** MediaPipe Tasks *Face Landmarker* — a **478-point face mesh** run in `VIDEO` mode on a dedicated detection thread, with a **GPU delegate (CPU fallback)**. This is the SDK that finds the face and its landmarks. |
| Model asset: `face_landmarker.task` | ~3.7 MB | The MediaPipe face-mesh model, bundled in `src/main/assets` and kept uncompressed (`noCompress 'task'`) so it can be memory-mapped. |
| **`androidx.camera:camera-core` / `camera-camera2` / `camera-lifecycle`** | `1.6.0` | **CameraX.** Drives the camera with a **single `ImageAnalysis` use case** (`STRATEGY_KEEP_ONLY_LATEST`, RGBA output) — there is no separate `Preview` use case; the filtered analysis frames *are* what the user sees. Each frame is displayed immediately while a downscaled copy feeds MediaPipe on its own thread. |
| **OpenGL ES 2.0** (platform) | — | **Beauty rendering.** Not a package — the Android platform GL API. `BeautyRenderer` implements the whole per-frame pipeline (scene FBO → two-pass gaussian blur → two face masks → composite → reshape liquify → mustache → sharpen blit) in hand-written GLSL shaders. |
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
- **Display and detection are decoupled.** Every analysis frame is rotated
  upright and shown immediately (full camera frame rate); a downscaled copy is
  detected on a dedicated thread and its landmarks are published back
  asynchronously, so the preview never stalls on MediaPipe.
- Each preview frame is delivered as an RGBA `Bitmap` and uploaded to a GL
  texture; all filtering (blur, masks, composite, reshape, sharpen) then runs on
  the GPU through FBO passes. The **stream output** (`StreamOutput`, see
  `docs/STREAMING.md`) is fully GPU-resident — no `glReadPixels`.
- Face detection uses a 4:3 analysis stream (`KEEP_ONLY_LATEST`); MediaPipe runs
  in `VIDEO` mode with a GPU delegate when available (CPU fallback). Up to 5
  faces are tracked.
- The blur and mask passes run at quarter resolution; landmarks are adaptively
  smoothed **per face** to keep the masks, reshape and mustache stable.
- Beauty adds a **glow** (brightness + highlight bloom), **clarity** (even tone /
  redness reduction) and **warmth**, plus optional eye/nose/face **reshape**
  liquify — all confined to the tracked face.

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

- **The right architecture for real-time filters.** All filtering happens on the
  GPU through FBO passes, and the stream-output path (`StreamOutput`) is fully
  GPU-resident — no `glReadPixels`. The live preview uploads each analysis frame
  as a 2D texture (a per-frame CPU→GPU upload; MediaPipe needs the bitmap anyway),
  which keeps the display and detection paths sharing one frame source.
- **Face tracking and rendering are decoupled and run off the UI thread.**
  Detection runs in `VIDEO` mode on its own thread off a single-slot buffer, and
  the display path shows every frame at full rate without waiting for it; the
  renderer just reads the latest smoothed landmarks via `@Volatile` fields.
  Producer/consumer with no locks on the hot path.
- **Beauty is applied *only to the face*, and cheaply.** A landmark-built mask
  (face oval, with eyes/brows/lips punched out) means smoothing doesn't wipe out
  facial detail, and the blur/mask passes run at **quarter resolution**
  (1/16 the pixels) — the standard, correct optimization.
- **WYSIWYG capture.** Photos are read back from the same rendered frame
  (`glReadPixels`), so the saved image is exactly what the user saw — no
  separate "apply filter to a still" code path that could drift from the preview.
- **Stability details are handled.** Landmarks are adaptively smoothed per face
  (the smoothing eases off during real motion so they don't drag), and the
  smoothing state resets on a face-count change. When no face is detected the
  masks clear to black, so the composite is a clean no-op. Front-camera mirroring
  is applied in the same rotate step that uprights the frame, so coordinates line
  up with the mirrored preview.
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
- **Portrait-only.** The app is locked to portrait and frames are uprighted from
  the device-reported `imageInfo.rotationDegrees`; there's no landscape handling.
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

- The app is portrait-only; frames are uprighted from the device-reported
  `imageInfo.rotationDegrees` (in `tracking/FaceTracker.kt`), with no landscape
  handling.
- iOS (`modules/beauty-filter/ios`) is written but not yet compiled — build it on
  a Mac and work through `ios/README-ios.md` before shipping.
- Captures come from the rendered preview (`glReadPixels` at view resolution),
  not the full-resolution sensor still.
- No photo gallery/preview screen yet — capture saves a JPEG into the app
  cache and resolves its `file://` URI.
