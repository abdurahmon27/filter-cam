import { requireOptionalNativeModule } from 'expo';

/**
 * Bridge to the native Kotlin beauty filter (modules/beauty-filter).
 *
 * `requireOptionalNativeModule` returns null when the native module is not
 * present in the running binary — which is the case in Expo Go. In that case
 * the app falls back to the JS overlays for the live look.
 *
 * On Android the live BeautyCameraView applies the beauty filter and the
 * mustache on the GPU, and `takePicture` captures the filtered frame, so
 * `applyBeauty` is a no-op kept for API compatibility.
 */
type BeautyFilterModule = {
  isAvailable(): boolean;
  applyBeauty(uri: string, intensity: number): Promise<string>;
};

const nativeModule =
  requireOptionalNativeModule<BeautyFilterModule>('BeautyFilter');

export function isNativeBeautyAvailable(): boolean {
  return !!nativeModule?.isAvailable?.();
}

export async function applyNativeBeauty(
  uri: string,
  intensity = 0.6
): Promise<string> {
  if (!nativeModule) {
    // No native binary (e.g. Expo Go) — return the original untouched.
    return uri;
  }
  return nativeModule.applyBeauty(uri, intensity);
}
