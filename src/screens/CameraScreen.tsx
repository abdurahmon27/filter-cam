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
import FilterBar, { FilterId } from '../components/FilterBar';
import BeautyOverlay from '../components/BeautyOverlay';
import MustacheOverlay from '../components/MustacheOverlay';
import { applyNativeBeauty, isNativeBeautyAvailable } from '../native/beautyFilter';

type Props = {
  onClose: () => void;
};

export default function CameraScreen({ onClose }: Props) {
  const [permission, requestPermission] = useCameraPermissions();
  const [facing, setFacing] = useState<CameraType>('front');
  const [filters, setFilters] = useState<Record<FilterId, boolean>>({
    beauty: true,
    mustache: false,
  });
  const [busy, setBusy] = useState(false);
  const cameraRef = useRef<CameraView>(null);

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

  const capture = async () => {
    if (!cameraRef.current || busy) return;
    try {
      setBusy(true);
      const photo = await cameraRef.current.takePictureAsync();
      if (photo?.uri && filters.beauty && isNativeBeautyAvailable()) {
        await applyNativeBeauty(photo.uri, 0.6);
      }
      // A gallery/preview screen can be added here later.
    } catch (e) {
      console.warn('capture failed', e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <View style={styles.container}>
      <View style={styles.cameraWrap}>
        <CameraView ref={cameraRef} style={StyleSheet.absoluteFill} facing={facing} />
        {filters.beauty && <BeautyOverlay />}
        {filters.mustache && <MustacheOverlay />}

        <Pressable style={styles.closeBtn} onPress={onClose}>
          <Text style={styles.closeText}>✕</Text>
        </Pressable>
        <Pressable
          style={styles.flipBtn}
          onPress={() =>
            setFacing((f) => (f === 'front' ? 'back' : 'front'))
          }
        >
          <Text style={styles.flipText}>⟲</Text>
        </Pressable>
      </View>

      <View style={styles.controls}>
        <FilterBar active={filters} onToggle={toggle} />
        <View style={styles.shutterRow}>
          <Pressable
            style={({ pressed }) => [styles.shutter, pressed && styles.shutterPressed]}
            onPress={capture}
          >
            {busy ? (
              <ActivityIndicator color="#0b0b0f" />
            ) : (
              <View style={styles.shutterInner} />
            )}
          </Pressable>
        </View>
      </View>
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
  cameraWrap: {
    flex: 1,
    overflow: 'hidden',
    borderBottomLeftRadius: 24,
    borderBottomRightRadius: 24,
  },
  controls: {
    backgroundColor: '#0b0b0f',
    paddingBottom: 12,
  },
  closeBtn: {
    position: 'absolute',
    top: 16,
    left: 16,
    width: 40,
    height: 40,
    borderRadius: 20,
    backgroundColor: 'rgba(0,0,0,0.4)',
    alignItems: 'center',
    justifyContent: 'center',
  },
  closeText: {
    color: '#fff',
    fontSize: 18,
    fontWeight: '700',
  },
  flipBtn: {
    position: 'absolute',
    top: 16,
    right: 16,
    width: 40,
    height: 40,
    borderRadius: 20,
    backgroundColor: 'rgba(0,0,0,0.4)',
    alignItems: 'center',
    justifyContent: 'center',
  },
  flipText: {
    color: '#fff',
    fontSize: 22,
    fontWeight: '700',
  },
  shutterRow: {
    alignItems: 'center',
    paddingTop: 4,
  },
  shutter: {
    width: 74,
    height: 74,
    borderRadius: 37,
    backgroundColor: '#fff',
    alignItems: 'center',
    justifyContent: 'center',
    borderWidth: 4,
    borderColor: 'rgba(255,255,255,0.35)',
  },
  shutterPressed: {
    opacity: 0.8,
  },
  shutterInner: {
    width: 58,
    height: 58,
    borderRadius: 29,
    backgroundColor: '#fff',
    borderWidth: 2,
    borderColor: '#0b0b0f',
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
