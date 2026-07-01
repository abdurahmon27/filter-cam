import { useRef } from 'react';
import {
  Animated,
  Image,
  ImageSourcePropType,
  PanResponder,
  StyleSheet,
  Text,
  View,
} from 'react-native';

type Props = {
  /**
   * Optional mustache PNG (transparent background works best).
   * Drop a file at assets/mustache.png and pass require('../../assets/mustache.png').
   * When omitted, a drawn mustache shape is used.
   */
  source?: ImageSourcePropType;
};

/**
 * A draggable mustache you position over your face. Real automatic face
 * tracking needs a native frame processor (a dev build), so for now you drag
 * it into place — which also works fully in Expo Go.
 */
export default function MustacheOverlay({ source }: Props) {
  const pan = useRef(new Animated.ValueXY({ x: 0, y: 0 })).current;

  const responder = useRef(
    PanResponder.create({
      onStartShouldSetPanResponder: () => true,
      onMoveShouldSetPanResponder: () => true,
      onPanResponderGrant: () => {
        pan.extractOffset();
      },
      onPanResponderMove: Animated.event([null, { dx: pan.x, dy: pan.y }], {
        useNativeDriver: false,
      }),
      onPanResponderRelease: () => {
        pan.flattenOffset();
      },
    })
  ).current;

  return (
    <View style={styles.container} pointerEvents="box-none">
      <Animated.View
        {...responder.panHandlers}
        style={[styles.draggable, { transform: pan.getTranslateTransform() }]}
      >
        {source ? (
          <Image source={source} style={styles.image} resizeMode="contain" />
        ) : (
          <DrawnMustache />
        )}
        <Text style={styles.hint}>drag me</Text>
      </Animated.View>
    </View>
  );
}

function DrawnMustache() {
  return (
    <View style={styles.mustacheRow}>
      <View style={[styles.half, styles.halfLeft]} />
      <View style={[styles.half, styles.halfRight]} />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    alignItems: 'center',
    justifyContent: 'center',
  },
  draggable: {
    alignItems: 'center',
  },
  image: {
    width: 160,
    height: 80,
  },
  mustacheRow: {
    flexDirection: 'row',
  },
  half: {
    width: 60,
    height: 46,
    backgroundColor: '#141414',
  },
  halfLeft: {
    borderTopLeftRadius: 60,
    borderBottomLeftRadius: 60,
    borderBottomRightRadius: 40,
    transform: [{ rotate: '8deg' }],
  },
  halfRight: {
    borderTopRightRadius: 60,
    borderBottomRightRadius: 60,
    borderBottomLeftRadius: 40,
    transform: [{ rotate: '-8deg' }],
  },
  hint: {
    color: 'rgba(255,255,255,0.75)',
    fontSize: 12,
    marginTop: 6,
    fontWeight: '600',
  },
});
