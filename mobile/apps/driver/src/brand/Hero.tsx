/* Full-bleed welcome imagery: an optional short muted loop (assets/hero/hero.mp4, see heroVideo.ts), else an optional
   photo (heroPhoto.ts) or the drawn brand art with a slow Ken Burns pan, and a gradient to black so white copy and the
   gold call to action read on top. Reduce motion: no video and no pan, just the still. */
import { useVideoPlayer, VideoView } from 'expo-video';
import { type ReactNode, useEffect, useRef } from 'react';
import { Animated, Easing, Image, type ImageSourcePropType, StyleSheet, View } from 'react-native';
import Svg, { Defs, LinearGradient, Rect, Stop } from 'react-native-svg';

import { t } from '@/theme';

import { useReducedMotion } from './useReducedMotion';

const PAN_MS = 18_000;

export function KenBurns({ children }: { children: ReactNode }) {
  const reduced = useReducedMotion();
  const p = useRef(new Animated.Value(0)).current;
  useEffect(() => {
    if (reduced) {
      p.setValue(0);
      return;
    }
    const loop = Animated.loop(
      Animated.sequence([
        Animated.timing(p, { toValue: 1, duration: PAN_MS, easing: Easing.inOut(Easing.quad), useNativeDriver: true }),
        Animated.timing(p, { toValue: 0, duration: PAN_MS, easing: Easing.inOut(Easing.quad), useNativeDriver: true })
      ])
    );
    loop.start();
    return () => loop.stop();
  }, [p, reduced]);
  const scale = p.interpolate({ inputRange: [0, 1], outputRange: [1.04, 1.12] });
  const translateX = p.interpolate({ inputRange: [0, 1], outputRange: [-6, 10] });
  const translateY = p.interpolate({ inputRange: [0, 1], outputRange: [4, -6] });
  return <Animated.View style={[StyleSheet.absoluteFill, { transform: [{ scale }, { translateX }, { translateY }] }]}>{children}</Animated.View>;
}

/** Transparent at the top to solid black (--at-ink-1000) at the bottom. */
export function FadeToBlack({ from = 0.35 }: { from?: number }) {
  return (
    <Svg style={StyleSheet.absoluteFill} width="100%" height="100%" preserveAspectRatio="none" viewBox="0 0 1 1" pointerEvents="none">
      <Defs>
        <LinearGradient id="heroShade" x1="0" y1="0" x2="0" y2="1">
          <Stop offset="0" stopColor={t.ink['1000']} stopOpacity={0.25} />
          <Stop offset={String(from)} stopColor={t.ink['1000']} stopOpacity={0} />
          <Stop offset="0.72" stopColor={t.ink['1000']} stopOpacity={0.7} />
          <Stop offset="1" stopColor={t.ink['1000']} stopOpacity={1} />
        </LinearGradient>
      </Defs>
      <Rect x="0" y="0" width="1" height="1" fill="url(#heroShade)" />
    </Svg>
  );
}

function HeroVideo({ source }: { source: number }) {
  const player = useVideoPlayer(source, (p) => {
    p.loop = true;
    p.muted = true;
    p.play();
  });
  return <VideoView player={player} style={StyleSheet.absoluteFill} contentFit="cover" nativeControls={false} />;
}

export interface HeroProps {
  /** A photo when one was added under assets/hero; otherwise the art is shown. */
  photo: ImageSourcePropType | null;
  /** A short clip under assets/hero (muted, looping); wins over the photo unless Reduce motion is on. */
  video?: number | null;
  art: ReactNode;
  /** Copy and logo laid over the bottom of the image. */
  children?: ReactNode;
  accessibilityLabel: string;
}

export function Hero({ photo, video = null, art, children, accessibilityLabel }: HeroProps) {
  const reduced = useReducedMotion();
  const still = photo ? <Image source={photo} style={StyleSheet.absoluteFill} resizeMode="cover" /> : art;
  return (
    <View style={styles.root}>
      <View style={StyleSheet.absoluteFill} accessible accessibilityRole="image" accessibilityLabel={accessibilityLabel}>
        {video !== null && !reduced ? <HeroVideo source={video} /> : <KenBurns>{still}</KenBurns>}
      </View>
      <FadeToBlack />
      <View style={styles.overlay} pointerEvents="box-none">{children}</View>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, overflow: 'hidden', backgroundColor: t.ink['1000'] },
  overlay: { flex: 1, justifyContent: 'flex-end' }
});
