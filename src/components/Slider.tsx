import { useRef, useState } from 'react';
import { PanResponder, StyleSheet, View } from 'react-native';
import { theme } from '../theme';

type Props = {
  value: number; // 0..1
  onChange: (value: number) => void;
  accent?: string;
};

const clamp = (v: number) => Math.max(0, Math.min(1, v));

/**
 * Lightweight horizontal slider (no native deps). Uses absolute `pageX` measured
 * against the track's window position, so touches on the thumb/fill don't cause
 * the value to jump (which happens with the touch-relative `locationX`).
 */
export default function Slider({ value, onChange, accent = theme.color.accent }: Props) {
  const [width, setWidth] = useState(0);
  const geom = useRef({ left: 0, width: 0 });
  const viewRef = useRef<View>(null);
  const onChangeRef = useRef(onChange);
  onChangeRef.current = onChange;

  const measure = () => {
    viewRef.current?.measureInWindow((x, _y, w) => {
      geom.current = { left: x, width: w };
      if (w !== width) setWidth(w);
    });
  };

  const setFromPageX = (pageX: number) => {
    const { left, width: w } = geom.current;
    if (w <= 0) return;
    onChangeRef.current(clamp((pageX - left) / w));
  };
  const setFromPageXRef = useRef(setFromPageX);
  setFromPageXRef.current = setFromPageX;

  const responder = useRef(
    PanResponder.create({
      onStartShouldSetPanResponder: () => true,
      onMoveShouldSetPanResponder: () => true,
      onPanResponderTerminationRequest: () => false,
      onPanResponderGrant: (e) => setFromPageXRef.current(e.nativeEvent.pageX),
      onPanResponderMove: (e) => setFromPageXRef.current(e.nativeEvent.pageX),
    })
  ).current;

  const pct = clamp(value);

  return (
    <View
      ref={viewRef}
      style={styles.hitArea}
      onLayout={measure}
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
        pointerEvents="none"
      />
    </View>
  );
}

const THUMB = 22;

const styles = StyleSheet.create({
  hitArea: {
    height: 40,
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
    top: (40 - THUMB) / 2,
    shadowColor: '#000',
    shadowOpacity: 0.3,
    shadowRadius: 4,
    shadowOffset: { width: 0, height: 1 },
    elevation: 3,
  },
});
