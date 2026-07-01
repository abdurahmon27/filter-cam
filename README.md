# FilterCam 🥸

A React Native (Expo) camera app with live filters, built to develop from Linux/NixOS and ship to iPhone via EAS Build.

- **Home screen** → **Open Camera** button
- **Camera screen** with a bottom filter bar:
  - **Beauty ✨** — live soft blur + warm wash (JS overlay). On captured photos it runs a real Core Image skin-smoothing pass via a native **Swift** module when available.
  - **Mustache 🥸** — a draggable mustache you position over your face.
- Flip camera + shutter button.

## Why Expo (and not plain Swift/Xcode)

This machine is Linux (NixOS). iOS apps can only be *built/signed/installed* with Xcode on macOS. Expo lets you:
- **Develop on Linux** and preview on your iPhone over Wi-Fi.
- **Build a real signed app on Expo's cloud Macs** with `eas build` — no Mac needed.

## Project layout

```
App.tsx                      # root, switches Home <-> Camera
src/screens/HomeScreen.tsx   # landing screen + Open Camera button
src/screens/CameraScreen.tsx # camera, permissions, filter toggles, shutter
src/components/FilterBar.tsx  # Beauty / Mustache toggle chips
src/components/BeautyOverlay.tsx    # live beauty look (blur + tint)
src/components/MustacheOverlay.tsx  # draggable mustache (emoji/shape or PNG)
src/native/beautyFilter.ts    # JS bridge to the Swift module (safe fallback)
modules/beauty-filter/        # local Expo native module
  ios/BeautyFilterModule.swift  #   <-- the Swift beauty filter (Core Image)
```

## Run it (two ways to get it on your iPhone)

### A) Fastest: preview in Expo Go over Wi-Fi (no Mac, no build)

> Note: Expo Go does **not** include the native Swift module, so on-capture native
> smoothing is skipped and the live JS beauty overlay is used. Great for iterating on UI.

1. Install **Expo Go** from the App Store on your iPhone.
2. Put the phone and laptop on the **same Wi-Fi**.
3. Start the dev server:
   ```sh
   cd ~/Developer/filter-cam
   npx expo start
   ```
4. Scan the QR code with the iPhone Camera app → opens in Expo Go.

### B) Real app with the Swift filter: EAS development build (cloud Mac)

This compiles the native Swift module on Expo's macOS builders and installs a real app.

1. Install and log in to EAS:
   ```sh
   npm i -g eas-cli
   eas login
   ```
2. Register your device and build a dev client:
   ```sh
   eas device:create          # register your iPhone (needs an Apple Developer account)
   eas build --profile development --platform ios
   ```
3. Open the install link from the build, install on the iPhone, then:
   ```sh
   npx expo start --dev-client
   ```

For a shippable build later: `eas build --profile production --platform ios`.

## Using a real mustache image

Drop a transparent PNG at `assets/mustache.png`, then in
`src/screens/CameraScreen.tsx` pass it to the overlay:

```tsx
<MustacheOverlay source={require('../../assets/mustache.png')} />
```

## Toolchain notes (NixOS)

Node came from your existing nvm install (`node v24`). Nothing was added to
`/etc/nixos`. If you ever want a reproducible shell, a `flake.nix`/`nix-shell`
with `nodejs` works too.

## Known limitations / next steps

- **Mustache is drag-positioned**, not auto face-tracked. Real tracking needs a
  frame processor (`react-native-vision-camera` + a face detector) and a dev build.
- **Live beauty** is an overlay approximation; the true GPU filter (Swift) runs
  on captured stills, and only in a dev/EAS build.
- No photo gallery/preview screen yet — capture just runs the pipeline.
