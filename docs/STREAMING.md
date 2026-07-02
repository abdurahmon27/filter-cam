# Streaming the filtered camera (LiveKit)

This app is **streaming-ready**: the filtered camera frames can be pushed to a
video track without leaving the GPU. This doc explains what is already in place
and the exact remaining steps to go live with LiveKit. No LiveKit server is
wired yet — everything below activates once you have one.

## What's already done — the GPU output seam

The blocker for streaming used to be that filtered pixels only reached the
on-screen `GLSurfaceView`; the only way out was a blocking `glReadPixels` in
`takePicture()`. That's fixed.

The renderer now builds the final filtered image into an offscreen FBO
(`finalFbo`) and presents it two ways every frame:

1. **To the screen** (the preview), via `BlitPass`.
2. **To an external `Surface`** (the stream), via `StreamOutput` — an EGL window
   surface that **shares the renderer's GL context**, so frames stay on the GPU
   end-to-end (no read-back).

The public seam:

```kotlin
// modules/beauty-filter/.../view/BeautyCameraView.kt
fun setStreamSurface(surface: android.view.Surface?)   // attach / detach
```

Give it any `Surface` and every rendered frame is drawn into it. A WebRTC
`SurfaceTextureHelper` provides exactly such a surface — that's the integration
point.

```
CameraX ─► GL pipeline ─► finalFbo ─┬─► BlitPass ─► GLSurfaceView (preview)
                                     └─► StreamOutput ─► Surface ─► WebRTC/LiveKit
```

## Remaining steps to go live (Android)

### 1. Add the SDK

```bash
npx expo install @livekit/react-native @livekit/react-native-webrtc
```

Add the config plugin (`app.json` → `plugins`) per the LiveKit RN docs
(microphone/camera permissions, background modes). Rebuild the dev/EAS client —
these ship native code, so Expo Go won't work (already true for this app).

> Version note: this project is on **Expo 57 / RN 0.86 / New Architecture**.
> Pin LiveKit RN to a release that supports the New Architecture, or the app
> will fail to build. Verify before committing.

### 2. Bridge the filtered surface into a WebRTC track (native)

The filtered frames must become a `VideoTrack`. Create a custom capturer backed
by a `SurfaceTextureHelper`, hand its surface to `setStreamSurface`, and forward
its frames to the track's observer. Sketch (Kotlin, uses `org.webrtc.*` from
`@livekit/react-native-webrtc`):

```kotlin
class FilteredVideoCapturer(
    private val view: BeautyCameraView,
    private val eglContext: EglBase.Context,
) : VideoCapturer {
    private lateinit var helper: SurfaceTextureHelper
    private lateinit var observer: CapturerObserver

    override fun initialize(h: SurfaceTextureHelper, ctx: Context, obs: CapturerObserver) {
        helper = h; observer = obs
    }
    override fun startCapture(w: Int, h: Int, fps: Int) {
        helper.setTextureSize(w, h)
        helper.startListening { frame -> observer.onFrameCaptured(frame) }
        // Route the renderer's frames into the helper's surface:
        view.setStreamSurface(Surface(helper.surfaceTexture))
    }
    override fun stopCapture() { view.setStreamSurface(null); helper.stopListening() }
    override fun isScreencast() = false
    override fun dispose() {}
}
```

Register it with the *same* `PeerConnectionFactory` that
`@livekit/react-native-webrtc` uses, create a `VideoTrack`, and expose the
track id to JS. (Alternatively, run the LiveKit **Android** SDK natively and
publish the track from native — cleaner isolation for a custom-filtered track,
at the cost of not reusing the RN room state.)

### 3. Connect + publish (JS)

```ts
import { Room } from '@livekit/react-native';
const room = new Room();
await room.connect(process.env.EXPO_PUBLIC_LIVEKIT_URL!, token);
// publish the custom filtered track created in step 2
```

Read the URL from `EXPO_PUBLIC_LIVEKIT_URL` and the token from a small backend
endpoint (LiveKit Cloud dashboard or a `livekit-server-sdk` function). **Never
ship the API secret in the app** — mint tokens server-side.

## iOS (Swift / Metal)

The iOS renderer must expose the same seam. The Metal path is analogous:

1. In `MetalRenderer`, after producing the final texture, also render it into a
   `CVPixelBuffer` from a `CVPixelBufferPool` (or draw to a `CAMetalLayer` /
   `MTKView` and read the drawable's texture into a pixel buffer).
2. Wrap the pixel buffer in an `RTCVideoFrame` and feed a custom
   `RTCVideoCapturer`'s delegate, then publish via LiveKit's iOS SDK (the
   `@livekit/react-native-webrtc` pod).
3. Expose a `setStreamEnabled` view method mirroring Android's
   `setStreamSurface`.

This is not yet implemented on iOS (the iOS module itself is still unverified —
see `modules/beauty-filter/ios/README-ios.md`).

## Performance notes (profile on-device before trusting it live)

- Filtering **and** H.264 encoding together is real load. On mid/low-end
  devices, MediaPipe (GPU) + GL passes + encoder + network can thermally
  throttle. Budget for dropping resolution/FPS under load.
- Keep `glReadPixels` **off** the stream path — it's only used for the one-shot
  photo capture. The stream path is texture-only by design.
- Each recent effect (composite → beauty FBO → eye-warp → finalFbo, plus
  mustache/mesh) is a fullscreen pass. Fine for preview; re-profile once the
  encoder is also running.
- Consider streaming at a fixed target size (e.g. 720p) by sizing the capturer's
  `SurfaceTextureHelper` texture, independent of the preview resolution.

## Status

| Piece | State |
|-------|-------|
| GPU frame-output path (Android) | ✅ implemented (`StreamOutput`, `setStreamSurface`) |
| Preview unaffected when not streaming | ✅ (one extra blit per frame) |
| LiveKit SDK + custom capturer | ⛔ documented above; needs deps + a server |
| iOS output seam | ⛔ documented above; iOS module still unverified |
| On-device load testing | ⛔ needs a device + encoder + network |
