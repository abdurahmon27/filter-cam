import { useRef, useState, type ReactNode } from 'react';
import {
  ActivityIndicator,
  LayoutAnimation,
  Platform,
  Pressable,
  StyleSheet,
  Text,
  UIManager,
  View,
} from 'react-native';
import { CameraType, CameraView, useCameraPermissions } from 'expo-camera';
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

// Enable native layout animations on Android so the tray + camera resize smoothly.
if (
  Platform.OS === 'android' &&
  UIManager.setLayoutAnimationEnabledExperimental
) {
  UIManager.setLayoutAnimationEnabledExperimental(true);
}

// Smooth, native-driven slide for the collapsible tray (no per-frame JS).
const TRAY_ANIM: Parameters<typeof LayoutAnimation.configureNext>[0] = {
  duration: 260,
  update: { type: LayoutAnimation.Types.easeInEaseOut },
};

// Preview framing. 'fill' crops the sides to fill the tall screen (most zoomed
// in); '4:5' / '1:1' show the full camera width (zoomed out), with the freed
// space below merging into the black tray — no blur, no visible border.
// Framing = the preview box shape. Wider ratio (4:5) = more of you shown (zoomed
// out) but more black; taller ratio (3:5) = fills more of the screen but crops
// the sides more (zoomed in). 'fill' fills the whole screen (most zoomed in).
type Framing = 'fill' | '3:5' | '2:3' | '4:5';
const FRAMINGS: Framing[] = ['2:3', '3:5', '4:5', 'fill'];
const FRAMING_LABEL: Record<Framing, string> = {
  fill: 'FULL',
  '3:5': '3:5',
  '2:3': '2:3',
  '4:5': '4:5',
};

type Props = {
  onClose: () => void;
};

const NO_INTENSITIES: Record<FilterId, number> = {
  smooth: 0,
  glow: 0,
  clarity: 0,
  warm: 0,
  eyes: 0,
  nose: 0,
  slim: 0,
};

export default function CameraScreen({ onClose }: Props) {
  const insets = useSafeAreaInsets();
  const [permission, requestPermission] = useCameraPermissions();
  const [facing, setFacing] = useState<CameraType>('front');
  const [intensities, setIntensities] =
    useState<Record<FilterId, number>>(DEFAULT_INTENSITIES);
  const [mustache, setMustache] = useState(false);
  const [faceMesh, setFaceMesh] = useState(false);
  const [carouselOpen, setCarouselOpen] = useState(true);
  const [framing, setFraming] = useState<Framing>('3:5');
  const nativeCameraRef = useRef<BeautyCameraViewRef>(null);

  const useNativeCamera = isBeautyCameraAvailable && BeautyCameraView != null;
  const beautyActive = Object.values(intensities).some((v) => v > 0.01);

  // The preview box shape. 'fill' overlays the whole area; the others are a
  // top-aligned box whose height comes from the aspect ratio, so the black
  // remainder sits at the bottom and blends into the tray.
  // With the tray OPEN the preview uses the chosen framing (e.g. 3:5), leaving a
  // black area below that the tray covers. When the tray COLLAPSES, the preview
  // grows to fill the freed space — so the collapse animation smoothly enlarges
  // the camera instead of just revealing black. 'fill' is always full.
  const previewStyle =
    framing === 'fill' || !carouselOpen
      ? styles.previewFill
      : ({
          width: '100%',
          aspectRatio:
            framing === '4:5' ? 4 / 5 : framing === '2:3' ? 2 / 3 : 3 / 5,
        } as const);

  const cycleFraming = () => {
    LayoutAnimation.configureNext(TRAY_ANIM);
    setFraming((f) => FRAMINGS[(FRAMINGS.indexOf(f) + 1) % FRAMINGS.length]);
  };

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
      {/* Camera area — flexes to fill whatever space the carousel leaves, so
          collapsing the tray genuinely enlarges the preview. */}
      <View style={styles.cameraArea}>
        {/* Preview box — its shape drives the framing (fill / 4:5 / 1:1). */}
        <View style={[styles.previewBox, previewStyle]}>
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
              noseSlim={intensities.nose}
              faceSlim={intensities.slim}
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
        </View>

        {/* Top glass controls */}
        <View
          style={[styles.topBar, { top: insets.top + 10 }]}
          pointerEvents="box-none"
        >
          <RoundButton onPress={onClose}>
            <View style={styles.closeIcon}>
              <View style={[styles.closeBar, styles.closeBarA]} />
              <View style={[styles.closeBar, styles.closeBarB]} />
            </View>
          </RoundButton>
          <View style={styles.brandPill}>
            <Text style={styles.brandText}>FilterCam</Text>
          </View>
          <View style={styles.topRight}>
            <Pressable
              onPress={cycleFraming}
              hitSlop={8}
              style={({ pressed }) => [styles.pillBtn, pressed && styles.btnPressed]}
            >
              <Text style={styles.pillBtnText}>{FRAMING_LABEL[framing]}</Text>
            </Pressable>
            <RoundButton
              onPress={() =>
                setFacing((f) => (f === 'front' ? 'back' : 'front'))
              }
            >
              <Text style={styles.flipGlyph}>⟲</Text>
            </RoundButton>
          </View>
        </View>
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
        expanded={carouselOpen}
        onToggle={() => {
          LayoutAnimation.configureNext(TRAY_ANIM);
          setCarouselOpen((o) => !o);
        }}
      />
    </View>
  );
}

function RoundButton({
  onPress,
  children,
}: {
  onPress: () => void;
  children: ReactNode;
}) {
  return (
    <Pressable
      onPress={onPress}
      hitSlop={8}
      style={({ pressed }) => [styles.roundBtn, pressed && styles.btnPressed]}
    >
      {children}
    </Pressable>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#000',
  },
  cameraArea: {
    flex: 1,
    overflow: 'hidden',
    backgroundColor: '#000',
  },
  previewBox: {
    overflow: 'hidden',
    backgroundColor: '#000',
  },
  previewFill: {
    flex: 1,
  },
  topRight: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
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
  roundBtn: {
    width: 44,
    height: 44,
    borderRadius: 22,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: 'rgba(0,0,0,0.40)',
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.16)',
  },
  btnPressed: {
    opacity: 0.55,
  },
  flipGlyph: {
    color: '#fff',
    fontSize: 22,
    fontWeight: '500',
    marginTop: -1,
  },
  // Framing toggle — a pill so the label ("4:5" / "FULL") sits cleanly.
  pillBtn: {
    height: 44,
    minWidth: 44,
    paddingHorizontal: 13,
    borderRadius: 22,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: 'rgba(0,0,0,0.40)',
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.16)',
  },
  pillBtnText: {
    color: '#fff',
    fontSize: 14,
    fontWeight: '800',
    letterSpacing: 0.5,
    fontVariant: ['tabular-nums'],
  },
  // Crisp drawn "×" so the close icon isn't a mismatched glyph.
  closeIcon: {
    width: 16,
    height: 16,
    alignItems: 'center',
    justifyContent: 'center',
  },
  closeBar: {
    position: 'absolute',
    top: 7,
    left: 0,
    width: 16,
    height: 2,
    borderRadius: 1,
    backgroundColor: '#fff',
  },
  closeBarA: {
    transform: [{ rotate: '45deg' }],
  },
  closeBarB: {
    transform: [{ rotate: '-45deg' }],
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
