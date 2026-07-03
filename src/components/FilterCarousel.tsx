import { useEffect, useRef, useState } from 'react';
import {
  Animated,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
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
  /** Whether the carousel body is expanded (visible) or collapsed to the handle. */
  expanded: boolean;
  /** Toggle expanded/collapsed (the parent runs the LayoutAnimation). */
  onToggle: () => void;
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
  expanded,
  onToggle,
}: Props) {
  const [selected, setSelected] = useState<FilterId>('smooth');
  const fade = useRef(new Animated.Value(1)).current;
  const [bodyHeight, setBodyHeight] = useState(0);

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

  // Collapse by fixing the body's height to 0 / measured. The slide itself is a
  // native LayoutAnimation triggered by the parent, so it's smooth (no per-frame
  // JS), and the camera area above grows/shrinks with it.
  const bodyCollapsedStyle =
    bodyHeight > 0 ? { height: expanded ? bodyHeight : 0 } : undefined;

  return (
    <View style={[styles.panel, { paddingBottom: bottomInset }]}>
      {/* Semicircle "dome" handle — protrudes above the panel as a pull tab. */}
      <View style={styles.handleWrap} pointerEvents="box-none">
        <Pressable onPress={onToggle} hitSlop={18}>
          <View style={styles.dome}>
            <View
              style={[
                styles.chevron,
                expanded ? styles.chevronDown : styles.chevronUp,
              ]}
            />
          </View>
        </Pressable>
      </View>

      <View style={[styles.body, bodyCollapsedStyle]}>
        <View
          style={styles.bodyInner}
          onLayout={(e) => {
            const h = e.nativeEvent.layout.height;
            if (h > 0 && Math.abs(h - bodyHeight) > 1) setBodyHeight(h);
          }}
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
        </View>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  panel: {
    // In-flow (not absolute): occupies real layout space below the camera area,
    // so collapsing hands that space back to the preview. overflow visible so the
    // dome handle can protrude above the top edge.
    borderTopLeftRadius: theme.radius.lg,
    borderTopRightRadius: theme.radius.lg,
    backgroundColor: '#000',
  },
  handleWrap: {
    position: 'absolute',
    top: -24,
    left: 0,
    right: 0,
    alignItems: 'center',
  },
  dome: {
    width: 54,
    height: 27,
    borderTopLeftRadius: 27,
    borderTopRightRadius: 27,
    backgroundColor: '#000',
    alignItems: 'center',
    justifyContent: 'center',
    // Lift it off the camera so the curved tab reads as a handle.
    elevation: 5,
    shadowColor: '#000',
    shadowOpacity: 0.4,
    shadowRadius: 4,
    shadowOffset: { width: 0, height: -2 },
  },
  chevron: {
    width: 10,
    height: 10,
    borderColor: '#fff',
    borderRightWidth: 2.4,
    borderBottomWidth: 2.4,
  },
  chevronDown: {
    // Points down (collapse). Nudged up so it sits centered in the dome.
    marginTop: 2,
    transform: [{ rotate: '45deg' }],
  },
  chevronUp: {
    marginTop: 5,
    transform: [{ rotate: '-135deg' }],
  },
  body: {
    overflow: 'hidden',
  },
  bodyInner: {
    paddingTop: 10,
    paddingBottom: 12,
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
