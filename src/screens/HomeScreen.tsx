import { Pressable, StyleSheet, Text, View } from 'react-native';

type Props = {
  onOpenCamera: () => void;
};

export default function HomeScreen({ onOpenCamera }: Props) {
  return (
    <View style={styles.container}>
      <View style={styles.hero}>
        <Text style={styles.emoji}>🥸</Text>
        <Text style={styles.title}>FilterCam</Text>
        <Text style={styles.subtitle}>Beauty & mustache filters, live.</Text>
      </View>

      <Pressable
        style={({ pressed }) => [styles.button, pressed && styles.buttonPressed]}
        onPress={onOpenCamera}
      >
        <Text style={styles.buttonText}>Open Camera</Text>
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingVertical: 72,
  },
  hero: {
    alignItems: 'center',
    marginTop: 80,
  },
  emoji: {
    fontSize: 88,
    marginBottom: 16,
  },
  title: {
    color: '#ffffff',
    fontSize: 40,
    fontWeight: '800',
    letterSpacing: 0.5,
  },
  subtitle: {
    color: '#9aa0aa',
    fontSize: 16,
    marginTop: 8,
  },
  button: {
    backgroundColor: '#ff375f',
    paddingHorizontal: 48,
    paddingVertical: 18,
    borderRadius: 999,
    marginBottom: 24,
  },
  buttonPressed: {
    opacity: 0.85,
    transform: [{ scale: 0.98 }],
  },
  buttonText: {
    color: '#ffffff',
    fontSize: 18,
    fontWeight: '700',
  },
});
