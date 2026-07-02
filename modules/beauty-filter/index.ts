// Local Expo native module: beauty-filter
//
// Android (Kotlin + OpenGL ES) and iOS (Swift + Metal + Vision) both render the
// camera through a GPU pipeline: a face tracker finds landmarks, a gaussian-blur
// pass smooths skin only inside the face oval (eyes, brows and lips are punched
// out of the mask), a glow lifts the skin, and a mustache sprite is anchored to
// the nose/upper-lip landmarks. Up to 5 faces are handled at once.
// See docs/ARCHITECTURE.md and modules/beauty-filter/ios/README-ios.md.
import { requireNativeView, requireOptionalNativeModule } from 'expo';
import type * as React from 'react';
import type { ViewProps } from 'react-native';

export type BeautyCameraViewProps = ViewProps & {
  /** Which camera to use. Defaults to 'front'. */
  facing?: 'front' | 'back';
  /** Skin-smoothing strength, 0..1. 0 disables the beauty filter. */
  smoothing?: number;
  /** Show the face-tracked mustache. */
  mustache?: boolean;
  /** Overlay every tracked landmark as a dot (debug / face-mesh view). */
  faceMesh?: boolean;
};

export type BeautyCameraViewRef = {
  /** Captures the current filtered frame; resolves to a file:// URI. */
  takePicture(): Promise<string>;
};

const nativeModule = requireOptionalNativeModule('BeautyFilter');

/** True when running in a build that contains the native module (not Expo Go). */
export const isBeautyCameraAvailable = nativeModule != null;

export const BeautyCameraView = (
  isBeautyCameraAvailable ? requireNativeView('BeautyFilter') : null
) as React.ComponentType<
  BeautyCameraViewProps & { ref?: React.Ref<BeautyCameraViewRef> }
> | null;
