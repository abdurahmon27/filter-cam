import { useRef, useState } from 'react';
import {
  ActivityIndicator,
  Pressable,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import {
  CameraType,
  CameraView,
  useCameraPermissions,
} from 'expo-camera';
import { BlurView } from 'expo-blur';
import FilterBar, { FilterId } from '../components/FilterBar';
import BeautyOverlay from '../components/BeautyOverlay';
import MustacheOverlay from '../components/MustacheOverlay';
import {
  BeautyCameraView,
  BeautyCameraViewRef,
  isBeautyCameraAvailable,
} from '../../modules/beauty-filter';

type Props = {
  onClose: () => void;
};

const BEAUTY_STRENGTH = 0.85;

export default function CameraScreen({ onClose }: Props) {
  const [permission, requestPermission] = useCameraPermissions();
  const [facing, setFacing] = useState<CameraType>('front');
  const [filters, setFilters] = useState<Record<FilterId, boolean>>({
    beauty: true,
    mustache: false,
  });
  const [faceMesh, setFaceMesh] = useState(false);
  const cameraRef = useRef<CameraView>(null);
  const nativeCameraRef = useRef<BeautyCameraViewRef>(null);

  // In a dev/EAS build the Kotlin module renders the camera itself:
  // MediaPipe face mesh + GL beauty filter + tracked mustache.
  const useNativeCamera = isBeautyCameraAvailable && BeautyCameraView != null;

  const toggle = (id: FilterId) =>
    setFilters((prev) => ({ ...prev, [id]: !prev[id] }));

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
        <Text style={styles.permText}>
          FilterCam needs camera access to show your filters.
        </Text>
        <Pressable style={styles.permButton} onPress={requestPermission}>
          <Text style={styles.permButtonText}>Grant access</Text>
        </Pressable>
        <Pressable onPress={onClose}>
          <Text style={styles.link}>Go back</Text>
        </Pressable>
      </View>
    );
  }

  return (
    <View style={styles.container}>
      {/* Camera fills the entire screen. */}
      {useNativeCamera && BeautyCameraView ? (
        <BeautyCameraView
          ref={nativeCameraRef}
          style={StyleSheet.absoluteFill}
          facing={facing}
          smoothing={filters.beauty ? BEAUTY_STRENGTH : 0}
          mustache={filters.mustache}
          faceMesh={faceMesh}
        />
      ) : (
        <>
          <CameraView
            ref={cameraRef}
            style={StyleSheet.absoluteFill}
            facing={facing}
          />
          {filters.beauty && <BeautyOverlay />}
          {filters.mustache && <MustacheOverlay />}
        </>
      )}

      {/* Top glass buttons floating over the camera. */}
      <View style={styles.topBar} pointerEvents="box-none">
        <BlurView intensity={30} tint="dark" style={styles.glassCircle}>
          <Pressable style={styles.circlePress} onPress={onClose}>
            <Text style={styles.iconText}>✕</Text>
          </Pressable>
        </BlurView>
        <BlurView intensity={30} tint="dark" style={styles.glassCircle}>
          <Pressable
            style={styles.circlePress}
            onPress={() =>
              setFacing((f) => (f === 'front' ? 'back' : 'front'))
            }
          >
            <Text style={styles.iconText}>⟲</Text>
          </Pressable>
        </BlurView>
      </View>

      {/* Bottom glass control panel floating over the camera. */}
      <BlurView intensity={45} tint="dark" style={styles.bottomPanel}>
        <FilterBar active={filters} onToggle={toggle} />
        <View style={styles.shutterRow}>
          <Pressable
            style={({ pressed }) => [
              styles.meshToggle,
              faceMesh && styles.meshToggleActive,
              pressed && styles.shutterPressed,
            ]}
            onPress={() => setFaceMesh((on) => !on)}
          >
            <Text style={styles.meshEmoji}>{faceMesh ? '🟢' : '⚪️'}</Text>
            <Text style={[styles.meshLabel, faceMesh && styles.meshLabelActive]}>
              {faceMesh ? 'Face mesh on' : 'Face mesh off'}
            </Text>
          </Pressable>
        </View>
      </BlurView>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#0b0b0f',
  },
  center: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    padding: 32,
    gap: 16,
  },
  topBar: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    paddingTop: 52,
    paddingHorizontal: 16,
    flexDirection: 'row',
    justifyContent: 'space-between',
  },
  glassCircle: {
    width: 46,
    height: 46,
    borderRadius: 23,
    overflow: 'hidden',
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: 'rgba(255,255,255,0.25)',
  },
  circlePress: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
  iconText: {
    color: '#fff',
    fontSize: 20,
    fontWeight: '700',
  },
  bottomPanel: {
    position: 'absolute',
    left: 0,
    right: 0,
    bottom: 0,
    paddingTop: 14,
    paddingBottom: 34,
    borderTopLeftRadius: 28,
    borderTopRightRadius: 28,
    overflow: 'hidden',
    borderTopWidth: StyleSheet.hairlineWidth,
    borderColor: 'rgba(255,255,255,0.18)',
  },
  shutterRow: {
    alignItems: 'center',
    paddingTop: 4,
  },
  shutterPressed: {
    opacity: 0.8,
  },
  meshToggle: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    paddingHorizontal: 26,
    paddingVertical: 16,
    borderRadius: 999,
    backgroundColor: 'rgba(255,255,255,0.12)',
    borderWidth: 2,
    borderColor: 'transparent',
  },
  meshToggleActive: {
    backgroundColor: 'rgba(21,255,140,0.18)',
    borderColor: '#15ff8c',
  },
  meshEmoji: {
    fontSize: 20,
  },
  meshLabel: {
    color: '#c7ccd4',
    fontSize: 16,
    fontWeight: '700',
  },
  meshLabelActive: {
    color: '#eafff4',
  },
  permText: {
    color: '#e6e6ea',
    fontSize: 16,
    textAlign: 'center',
  },
  permButton: {
    backgroundColor: '#ff375f',
    paddingHorizontal: 28,
    paddingVertical: 14,
    borderRadius: 999,
  },
  permButtonText: {
    color: '#fff',
    fontWeight: '700',
    fontSize: 16,
  },
  link: {
    color: '#9aa0aa',
    fontSize: 15,
  },
});
