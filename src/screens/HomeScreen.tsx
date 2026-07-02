import { Pressable, StyleSheet, Text, View } from 'react-native';
import { theme } from '../theme';

type Props = {
  onOpenCamera: () => void;
};

const HIGHLIGHTS = [
  { icon: '💧', label: 'Smooth' },
  { icon: '✨', label: 'Glow' },
  { icon: '👁️', label: 'Big Eyes' },
  { icon: '🥸', label: 'Mustache' },
];

export default function HomeScreen({ onOpenCamera }: Props) {
  return (
    <View style={styles.container}>
      <View style={styles.hero}>
        <View style={styles.badge}>
          <Text style={styles.badgeEmoji}>🥸</Text>
        </View>
        <Text style={styles.title}>FilterCam</Text>
        <Text style={styles.subtitle}>
          Real-time beauty & AR filters, on your face.
        </Text>

        <View style={styles.chips}>
          {HIGHLIGHTS.map((h) => (
            <View key={h.label} style={styles.chip}>
              <Text style={styles.chipEmoji}>{h.icon}</Text>
              <Text style={styles.chipLabel}>{h.label}</Text>
            </View>
          ))}
        </View>
      </View>

      <View style={styles.footer}>
        <Pressable
          style={({ pressed }) => [styles.button, pressed && styles.buttonPressed]}
          onPress={onOpenCamera}
        >
          <Text style={styles.buttonText}>Open Camera</Text>
        </Pressable>
        <Text style={styles.hint}>Front camera · tap a filter to adjust</Text>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: theme.color.bg,
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingTop: 130,
    paddingBottom: 56,
  },
  hero: {
    alignItems: 'center',
  },
  badge: {
    width: 116,
    height: 116,
    borderRadius: 34,
    backgroundColor: theme.color.accentSoft,
    borderWidth: 1,
    borderColor: theme.color.accentGlow,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 26,
    shadowColor: theme.color.accent,
    shadowOpacity: 0.5,
    shadowRadius: 30,
    shadowOffset: { width: 0, height: 0 },
  },
  badgeEmoji: {
    fontSize: 64,
  },
  title: {
    color: theme.color.text,
    fontSize: theme.font.hero,
    fontWeight: '800',
    letterSpacing: 0.5,
  },
  subtitle: {
    color: theme.color.textMuted,
    fontSize: theme.font.body,
    marginTop: 10,
    textAlign: 'center',
    paddingHorizontal: 40,
    lineHeight: 22,
  },
  chips: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    justifyContent: 'center',
    gap: 10,
    marginTop: 34,
    paddingHorizontal: 24,
  },
  chip: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    paddingHorizontal: 14,
    paddingVertical: 9,
    borderRadius: theme.radius.pill,
    backgroundColor: theme.color.bgElev,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: theme.color.hairline,
  },
  chipEmoji: {
    fontSize: 16,
  },
  chipLabel: {
    color: theme.color.textMuted,
    fontSize: theme.font.label,
    fontWeight: '600',
  },
  footer: {
    alignItems: 'center',
    width: '100%',
    paddingHorizontal: 28,
  },
  button: {
    backgroundColor: theme.color.accent,
    paddingVertical: 18,
    borderRadius: theme.radius.pill,
    width: '100%',
    alignItems: 'center',
    shadowColor: theme.color.accent,
    shadowOpacity: 0.5,
    shadowRadius: 20,
    shadowOffset: { width: 0, height: 6 },
  },
  buttonPressed: {
    opacity: 0.9,
    transform: [{ scale: 0.98 }],
  },
  buttonText: {
    color: '#fff',
    fontSize: 18,
    fontWeight: '800',
  },
  hint: {
    color: theme.color.textDim,
    fontSize: theme.font.label,
    marginTop: 14,
  },
});
