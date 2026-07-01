import { BlurView } from 'expo-blur';
import { StyleSheet, View } from 'react-native';

/**
 * A lightweight "beauty" look for the live preview: a soft blur to smooth
 * skin, plus a warm/bright wash. This runs everywhere (including Expo Go).
 *
 * For a true GPU skin-smoothing filter on captured photos, the app calls the
 * native Swift module (see modules/beauty-filter). That only activates in a
 * dev build / EAS build, so this overlay is the always-available fallback.
 */
export default function BeautyOverlay() {
  return (
    <View style={StyleSheet.absoluteFill} pointerEvents="none">
      <BlurView
        intensity={18}
        tint="light"
        style={StyleSheet.absoluteFill}
      />
      <View style={styles.warmWash} />
    </View>
  );
}

const styles = StyleSheet.create({
  warmWash: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    backgroundColor: 'rgba(255, 226, 210, 0.16)',
  },
});
