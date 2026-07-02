#!/usr/bin/env bash
#
# Launch the Android emulator with its front camera fed from the host webcam
# (webcam0 = /dev/video0), then install + start the FilterCam APK.
#
# Prereq for a real face in the filters (see docs/ARCHITECTURE.md §6):
#   1. v4l2loopback added to configuration.nix + `sudo nixos-rebuild switch`
#      so /dev/video0 exists.
#   2. OBS running with your vdo.ninja link as a Browser source, then
#      "Start Virtual Camera" (writes to /dev/video0).
#
# Without those, the emulator still boots and the app runs, but the front
# camera will be blank/unavailable and no face will be detected.
#
# Usage:
#   scripts/run-emulator.sh              # launch emulator + install + start app
#   scripts/run-emulator.sh --no-install # just launch the emulator
set -euo pipefail

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$SDK" ]]; then
  echo "ANDROID_HOME / ANDROID_SDK_ROOT not set." >&2
  exit 1
fi
EMULATOR="$SDK/emulator/emulator"
ADB="$SDK/platform-tools/adb"
AVD="Pixel_7_API_35"
PKG="com.haywan.filtercam"
APK="$(dirname "$0")/../android/app/build/outputs/apk/release/app-release.apk"

# GPU: 'host' uses your real GPU (fast). If the emulator fails to start or the
# screen is black, switch to 'swiftshader_indirect' (software, slower but safe).
GPU="${EMU_GPU:-host}"

echo "==> Host camera check"
# NixOS programs.obs-studio.enableVirtualCamera creates the loopback at
# /dev/video1 (video_nr=1, "OBS Cam"). The emulator maps the first host camera
# it finds to webcam0 regardless of the /dev/videoN number.
if compgen -G "/dev/video*" >/dev/null; then
  echo "    Found: $(ls /dev/video* | tr '\n' ' ')— front camera will use webcam0."
else
  echo "    WARNING: no /dev/video* device. Rebuild+reboot with"
  echo "    programs.obs-studio.enableVirtualCamera, then start OBS Virtual"
  echo "    Camera, or the front camera will be blank."
fi

echo "==> Launching emulator ($AVD, -gpu $GPU, front camera = webcam0)"
"$EMULATOR" -avd "$AVD" -gpu "$GPU" -camera-front webcam0 >/tmp/filtercam-emulator.log 2>&1 &

echo "==> Waiting for device to come online"
"$ADB" wait-for-device
# Wait for full boot before installing.
until [[ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
  sleep 2
done
echo "    Boot completed."

if [[ "${1:-}" != "--no-install" ]]; then
  if [[ -f "$APK" ]]; then
    echo "==> Installing APK"
    "$ADB" install -r "$APK"
    echo "==> Launching app"
    "$ADB" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null
    echo "    Started $PKG"
  else
    echo "APK not found at $APK — build it with:"
    echo "  (cd android && ./gradlew assembleRelease)"
  fi
fi

echo "Done. Emulator log: /tmp/filtercam-emulator.log"
