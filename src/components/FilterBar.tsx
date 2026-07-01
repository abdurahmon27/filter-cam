import { Pressable, StyleSheet, Text, View } from 'react-native';

export type FilterId = 'beauty' | 'mustache';

type Props = {
  active: Record<FilterId, boolean>;
  onToggle: (id: FilterId) => void;
};

const FILTERS: { id: FilterId; label: string; emoji: string }[] = [
  { id: 'beauty', label: 'Beauty', emoji: '✨' },
  { id: 'mustache', label: 'Mustache', emoji: '🥸' },
];

export default function FilterBar({ active, onToggle }: Props) {
  return (
    <View style={styles.bar}>
      {FILTERS.map((f) => {
        const isOn = active[f.id];
        return (
          <Pressable
            key={f.id}
            onPress={() => onToggle(f.id)}
            style={({ pressed }) => [
              styles.chip,
              isOn && styles.chipActive,
              pressed && styles.chipPressed,
            ]}
          >
            <Text style={styles.chipEmoji}>{f.emoji}</Text>
            <Text style={[styles.chipLabel, isOn && styles.chipLabelActive]}>
              {f.label}
            </Text>
          </Pressable>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  bar: {
    flexDirection: 'row',
    justifyContent: 'center',
    gap: 16,
    paddingVertical: 20,
    paddingHorizontal: 16,
  },
  chip: {
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 22,
    paddingVertical: 12,
    borderRadius: 18,
    backgroundColor: 'rgba(255,255,255,0.12)',
    borderWidth: 2,
    borderColor: 'transparent',
    minWidth: 92,
  },
  chipActive: {
    backgroundColor: 'rgba(255,55,95,0.22)',
    borderColor: '#ff375f',
  },
  chipPressed: {
    opacity: 0.7,
  },
  chipEmoji: {
    fontSize: 26,
    marginBottom: 4,
  },
  chipLabel: {
    color: '#c7ccd4',
    fontSize: 14,
    fontWeight: '600',
  },
  chipLabelActive: {
    color: '#ffffff',
  },
});
