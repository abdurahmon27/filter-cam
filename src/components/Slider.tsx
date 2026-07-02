import { useCallback, useRef, useState } from 'react';
import {
  GestureResponderEvent,
  PanResponder,
  StyleSheet,
  View,
} from 'react-native';
import { theme } from '../theme';

type Props = {
  value: number; // 0..1
  onChange: (value: number) => void;
  accent?: string;
};

const clamp = (v: number) => Math.max(0, Math.min(1, v));

/** A lightweight horizontal slider built on PanResponder (no native deps). */
export default function Slider({ value, onChange, accent = theme.color.accent }: Props) {
  const [width, setWidth] = useState(0);
  const widthRef = useRef(0);

  const update = useCallback(
    (e: GestureResponderEvent) => {
      const w = widthRef.current;
      if (w <= 0) return;
      onChange(clamp(e.nativeEvent.locationX / w));
    },
    [onChange]
  );

  const updateRef = useRef(update);
  updateRef.current = update;
  const responder = useRef(
    PanResponder.create({
      onStartShouldSetPanResponder: () => true,
      onMoveShouldSetPanResponder: () => true,
      onPanResponderGrant: (e) => updateRef.current(e),
      onPanResponderMove: (e) => updateRef.current(e),
    })
  ).current;

  const pct = clamp(value);

  return (
    <View
      style={styles.hitArea}
      onLayout={(e) => {
        const w = e.nativeEvent.layout.width;
        widthRef.current = w;
        setWidth(w);
      }}
      {...responder.panHandlers}
    >
      <View style={styles.track}>
        <View style={[styles.fill, { width: pct * width, backgroundColor: accent }]} />
      </View>
      <View
        style={[
          styles.thumb,
          { left: pct * width - THUMB / 2, borderColor: accent },
        ]}
      />
    </View>
  );
}

const THUMB = 22;

const styles = StyleSheet.create({
  hitArea: {
    height: 36,
    justifyContent: 'center',
  },
  track: {
    height: 6,
    borderRadius: 3,
    backgroundColor: theme.color.track,
    overflow: 'hidden',
  },
  fill: {
    height: 6,
    borderRadius: 3,
  },
  thumb: {
    position: 'absolute',
    width: THUMB,
    height: THUMB,
    borderRadius: THUMB / 2,
    backgroundColor: '#fff',
    borderWidth: 3,
    top: (36 - THUMB) / 2,
    shadowColor: '#000',
    shadowOpacity: 0.3,
    shadowRadius: 4,
    shadowOffset: { width: 0, height: 1 },
    elevation: 3,
  },
});
