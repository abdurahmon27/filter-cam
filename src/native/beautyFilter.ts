import { requireOptionalNativeModule } from 'expo';

/**
 * Bridge to the native Swift beauty filter (modules/beauty-filter).
 *
 * `requireOptionalNativeModule` returns null when the native module is not
 * present in the running binary — which is the case in Expo Go. In that case
 * the app falls back to the JS BeautyOverlay for the live look, and skips
 * native post-processing on captured photos.
 */
type BeautyFilterModule = {
  isAvailable(): boolean;
  /** Returns a file:// URI of the smoothed image. `intensity` is 0..1. */
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
