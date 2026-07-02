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

iOS support was removed for now — this is Android-only.

## How the native side works (`modules/beauty-filter/android`)

```
BeautyFilterModule.kt   # Expo module definition: props, takePicture()
BeautyCameraView.kt     # ExpoView hosting a GLSurfaceView + CameraX binding
FaceTracker.kt          # MediaPipe FaceLandmarker (live stream) + smoothing
BeautyRenderer.kt       # GL pipeline: camera OES -> scene FBO -> blur ->
                        # face-mask FBO -> composite -> mustache sprite
FaceTopology.kt         # landmark index rings (face oval, eyes, brows, lips)
MustacheTexture.kt      # mustache drawn with Canvas, uploaded as GL texture
src/main/assets/face_landmarker.task  # MediaPipe face mesh model (~3.7 MB)
```

Performance notes:
- Camera frames never leave the GPU for rendering (OES texture -> shaders).
- Face detection runs on a 4:3 analysis stream (`KEEP_ONLY_LATEST`) on its own
  thread; MediaPipe runs in LIVE_STREAM mode with a GPU delegate when
  available (CPU fallback).
- The blur and mask passes run at quarter resolution; landmarks are
  exponentially smoothed to keep the mask and mustache stable.

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

## Known limitations / next steps

- Front/back camera orientation and mirroring math is written for a
  portrait-locked app and needs a quick on-device sanity check (the usual
  GL/camera gotcha); tune `BeautyRenderer.drawCameraToScene` if the preview is
  rotated or flipped.
- Captures come from the rendered preview (`glReadPixels` at view resolution),
  not the full-resolution sensor still.
- No photo gallery/preview screen yet — capture saves a JPEG into the app
  cache and resolves its `file://` URI.
