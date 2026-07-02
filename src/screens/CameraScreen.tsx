import { useRef, useState } from 'react';
import {
  ActivityIndicator,
  Pressable,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { CameraType, CameraView, useCameraPermissions } from 'expo-camera';
import { BlurView } from 'expo-blur';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import FilterCarousel from '../components/FilterCarousel';
import BeautyOverlay from '../components/BeautyOverlay';
import MustacheOverlay from '../components/MustacheOverlay';
import {
  BeautyCameraView,
  BeautyCameraViewRef,
  isBeautyCameraAvailable,
} from '../../modules/beauty-filter';
import { DEFAULT_INTENSITIES, FilterId, theme } from '../theme';

type Props = {
  onClose: () => void;
};

const NO_INTENSITIES: Record<FilterId, number> = {
  smooth: 0,
  glow: 0,
  clarity: 0,
  warm: 0,
  eyes: 0,
};

export default function CameraScreen({ onClose }: Props) {
  const insets = useSafeAreaInsets();
  const [permission, requestPermission] = useCameraPermissions();
  const [facing, setFacing] = useState<CameraType>('front');
  const [intensities, setIntensities] =
    useState<Record<FilterId, number>>(DEFAULT_INTENSITIES);
  const [mustache, setMustache] = useState(false);
  const [faceMesh, setFaceMesh] = useState(false);
  const nativeCameraRef = useRef<BeautyCameraViewRef>(null);

  const useNativeCamera = isBeautyCameraAvailable && BeautyCameraView != null;
  const beautyActive = Object.values(intensities).some((v) => v > 0.01);

  if (!permission) {
    return (
      <View style={styles.center}>
        <ActivityIndicator color="#fff" />
      </View>
    );
  }

  if (!permission.granted) {
    return (
      <View style={styles.center}>
        <Text style={styles.permEmoji}>📷</Text>
        <Text style={styles.permText}>
          FilterCam needs camera access to show your filters.
        </Text>
        <Pressable style={styles.permButton} onPress={requestPermission}>
          <Text style={styles.permButtonText}>Grant access</Text>
        </Pressable>
        <Pressable onPress={onClose} hitSlop={12}>
          <Text style={styles.link}>Go back</Text>
        </Pressable>
      </View>
    );
  }

  return (
    <View style={styles.container}>
      {useNativeCamera && BeautyCameraView ? (
        <BeautyCameraView
          ref={nativeCameraRef}
          style={StyleSheet.absoluteFill}
          facing={facing}
          smoothing={intensities.smooth}
          glow={intensities.glow}
          clarity={intensities.clarity}
          warmth={intensities.warm}
          eyeEnlarge={intensities.eyes}
          mustache={mustache}
          faceMesh={faceMesh}
        />
      ) : (
        <>
          <CameraView style={StyleSheet.absoluteFill} facing={facing} />
          {beautyActive && <BeautyOverlay />}
          {mustache && <MustacheOverlay />}
        </>
      )}

      {/* Top glass controls */}
      <View
        style={[styles.topBar, { top: insets.top + 10 }]}
        pointerEvents="box-none"
      >
        <GlassButton icon="✕" onPress={onClose} />
        <View style={styles.brandPill}>
          <Text style={styles.brandText}>FilterCam</Text>
        </View>
        <GlassButton
          icon="⟲"
          onPress={() => setFacing((f) => (f === 'front' ? 'back' : 'front'))}
        />
      </View>

      {/* Bottom filter tray */}
      <FilterCarousel
        intensities={intensities}
        onIntensity={(id, v) =>
          setIntensities((prev) => ({ ...prev, [id]: v }))
        }
        mustache={mustache}
        onToggleMustache={() => setMustache((m) => !m)}
        faceMesh={faceMesh}
        onToggleFaceMesh={() => setFaceMesh((m) => !m)}
        onReset={() => setIntensities(NO_INTENSITIES)}
        bottomInset={insets.bottom}
      />
    </View>
  );
}

function GlassButton({ icon, onPress }: { icon: string; onPress: () => void }) {
  return (
    <BlurView intensity={30} tint="dark" style={styles.glassBtn}>
      <Pressable style={styles.glassBtnPress} onPress={onPress} hitSlop={8}>
        <Text style={styles.glassBtnIcon}>{icon}</Text>
      </Pressable>
    </BlurView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#000',
    justifyContent: 'space-between',
  },
  center: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    padding: 32,
    gap: 16,
    backgroundColor: theme.color.bg,
  },
  topBar: {
    position: 'absolute',
    top: 52,
    left: 0,
    right: 0,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
  },
  brandPill: {
    paddingHorizontal: 16,
    paddingVertical: 7,
    borderRadius: theme.radius.pill,
    backgroundColor: 'rgba(0,0,0,0.35)',
  },
  brandText: {
    color: '#fff',
    fontSize: theme.font.label,
    fontWeight: '800',
    letterSpacing: 0.5,
  },
  glassBtn: {
    width: 46,
    height: 46,
    borderRadius: 23,
    overflow: 'hidden',
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: theme.color.glassBorder,
  },
  glassBtnPress: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
  glassBtnIcon: {
    color: '#fff',
    fontSize: 19,
    fontWeight: '700',
  },
  permEmoji: {
    fontSize: 56,
    marginBottom: 4,
  },
  permText: {
    color: theme.color.text,
    fontSize: theme.font.body,
    textAlign: 'center',
    lineHeight: 22,
  },
  permButton: {
    backgroundColor: theme.color.accent,
    paddingHorizontal: 28,
    paddingVertical: 14,
    borderRadius: theme.radius.pill,
    marginTop: 4,
  },
  permButtonText: {
    color: '#fff',
    fontWeight: '700',
    fontSize: theme.font.body,
  },
  link: {
    color: theme.color.textMuted,
    fontSize: theme.font.body,
  },
});
