import { useState } from 'react';
import { StatusBar } from 'expo-status-bar';
import { StyleSheet, View } from 'react-native';
import HomeScreen from './src/screens/HomeScreen';
import CameraScreen from './src/screens/CameraScreen';

type Screen = 'home' | 'camera';

export default function App() {
  const [screen, setScreen] = useState<Screen>('home');

  // Full-bleed root (no SafeAreaView) so the camera runs edge-to-edge; screens
  // handle their own safe spacing.
  return (
    <View style={styles.root}>
      <StatusBar style="light" />
      {screen === 'home' ? (
        <HomeScreen onOpenCamera={() => setScreen('camera')} />
      ) : (
        <CameraScreen onClose={() => setScreen('home')} />
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: '#08080C',
  },
});
