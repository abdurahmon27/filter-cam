import { useEffect, useRef, useState } from 'react';
import {
  Animated,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { BlurView } from 'expo-blur';
import Slider from './Slider';
import { BEAUTY_FILTERS, FilterId, theme } from '../theme';

type Props = {
  intensities: Record<FilterId, number>;
  onIntensity: (id: FilterId, value: number) => void;
  mustache: boolean;
  onToggleMustache: () => void;
  faceMesh: boolean;
  onToggleFaceMesh: () => void;
  onReset: () => void;
  /** Safe-area bottom inset (Android nav bar / iOS home indicator). */
  bottomInset?: number;
};

/** A single round selector button (icon + label) with active/selected states. */
function Chip({
  icon,
  label,
  active,
  selected,
  accent = theme.color.accent,
  onPress,
}: {
  icon: string;
  label: string;
  active: boolean;
  selected?: boolean;
  accent?: string;
  onPress: () => void;
}) {
  return (
    <Pressable onPress={onPress} style={styles.chip}>
      {({ pressed }) => (
        <>
          <View
            style={[
              styles.chipCircle,
              active && { backgroundColor: theme.color.accentSoft },
              active && { borderColor: accent },
              selected && { borderColor: accent, borderWidth: 2.5 },
              pressed && { transform: [{ scale: 0.92 }] },
            ]}
          >
            <Text style={styles.chipIcon}>{icon}</Text>
            {active && <View style={[styles.activeDot, { backgroundColor: accent }]} />}
          </View>
          <Text
            style={[styles.chipLabel, (active || selected) && { color: '#fff' }]}
            numberOfLines={1}
          >
            {label}
          </Text>
        </>
      )}
    </Pressable>
  );
}

export default function FilterCarousel({
  intensities,
  onIntensity,
  mustache,
  onToggleMustache,
  faceMesh,
  onToggleFaceMesh,
  onReset,
  bottomInset = 0,
}: Props) {
  const [selected, setSelected] = useState<FilterId>('smooth');
  const fade = useRef(new Animated.Value(1)).current;

  // Gentle fade when switching which filter's slider is shown.
  useEffect(() => {
    fade.setValue(0);
    Animated.timing(fade, {
      toValue: 1,
      duration: 180,
      useNativeDriver: true,
    }).start();
  }, [selected, fade]);

  const current = BEAUTY_FILTERS.find((f) => f.id === selected)!;
  const value = intensities[selected];

  return (
    <BlurView
      intensity={40}
      tint="dark"
      style={[styles.panel, { paddingBottom: 18 + bottomInset }]}
    >
      {/* Intensity row for the selected beauty filter */}
      <Animated.View style={[styles.sliderRow, { opacity: fade }]}>
        <View style={styles.sliderHeader}>
          <Text style={styles.sliderTitle}>
            {current.icon}  {current.label}
          </Text>
          <Text style={styles.sliderValue}>{Math.round(value * 100)}</Text>
        </View>
        <Slider value={value} onChange={(v) => onIntensity(selected, v)} />
      </Animated.View>

      {/* Scrollable selector */}
      <ScrollView
        horizontal
        showsHorizontalScrollIndicator={false}
        contentContainerStyle={styles.scroll}
      >
        {BEAUTY_FILTERS.map((f) => (
          <Chip
            key={f.id}
            icon={f.icon}
            label={f.label}
            active={intensities[f.id] > 0.01}
            selected={selected === f.id}
            onPress={() => setSelected(f.id)}
          />
        ))}

        <View style={styles.divider} />

        <Chip
          icon="🥸"
          label="Mustache"
          active={mustache}
          onPress={onToggleMustache}
        />
        <Chip
          icon="🕸️"
          label="Mesh"
          active={faceMesh}
          accent={theme.color.mint}
          onPress={onToggleFaceMesh}
        />

        <View style={styles.divider} />

        <Chip icon="↺" label="Reset" active={false} onPress={onReset} />
      </ScrollView>
    </BlurView>
  );
}

const styles = StyleSheet.create({
  panel: {
    position: 'absolute',
    left: 0,
    right: 0,
    bottom: 0,
    borderTopLeftRadius: theme.radius.lg,
    borderTopRightRadius: theme.radius.lg,
    overflow: 'hidden',
    borderTopWidth: StyleSheet.hairlineWidth,
    borderColor: theme.color.glassBorder,
    paddingTop: 14,
    paddingBottom: 30,
  },
  sliderRow: {
    paddingHorizontal: 22,
    marginBottom: 14,
  },
  sliderHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'flex-end',
    marginBottom: 8,
  },
  sliderTitle: {
    color: '#fff',
    fontSize: theme.font.body,
    fontWeight: '700',
  },
  sliderValue: {
    color: theme.color.accent,
    fontSize: theme.font.body,
    fontWeight: '800',
    fontVariant: ['tabular-nums'],
  },
  scroll: {
    paddingHorizontal: 16,
    gap: 14,
    alignItems: 'center',
  },
  chip: {
    alignItems: 'center',
    width: 62,
  },
  chipCircle: {
    width: 54,
    height: 54,
    borderRadius: 27,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: 'rgba(255,255,255,0.08)',
    borderWidth: 1.5,
    borderColor: 'transparent',
  },
  chipIcon: {
    fontSize: 24,
  },
  activeDot: {
    position: 'absolute',
    top: 3,
    right: 3,
    width: 9,
    height: 9,
    borderRadius: 5,
  },
  chipLabel: {
    color: theme.color.textMuted,
    fontSize: theme.font.tiny,
    fontWeight: '600',
    marginTop: 6,
  },
  divider: {
    width: StyleSheet.hairlineWidth,
    height: 44,
    backgroundColor: theme.color.hairline,
    marginHorizontal: 4,
  },
});
