/* Top of the sign-in screen: full-bleed brand picture (clip, photo or the drawn hand-over art) fading to black, the
   "afrotransact delivery" logo and the headline. It gives way to the form when the keyboard is up. */
import { StyleSheet, useWindowDimensions, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { DriverHeroArt } from '@/brand/DriverHeroArt';
import { Hero } from '@/brand/Hero';
import heroPhoto from '@/brand/heroPhoto';
import heroVideo from '@/brand/heroVideo';
import { useT } from '@/i18n';
import { c, sp, t } from '@/theme';
import { Logo } from '@/ui/Logo';
import { AppText } from '@/ui/primitives';

/** Full height, or just the logo strip when `compact` (keyboard up, or a long second step). */
export function useHeroHeight(compact: boolean): number {
  const insets = useSafeAreaInsets();
  const { height } = useWindowDimensions();
  return compact ? insets.top + 96 : Math.max(280, Math.round(height * 0.46));
}

export function SignInHero({ height, title }: { height: number; title: string }) {
  const tr = useT();
  const insets = useSafeAreaInsets();
  return (
    <View style={{ height }}>
      <Hero photo={heroPhoto} video={heroVideo} art={<DriverHeroArt />} accessibilityLabel={tr('dr.signIn.heroAlt')}>
        <View style={[styles.copy, { paddingTop: insets.top + sp['3'] }]}>
          <Logo size={40} wordSize={t.fontSize.xl} tone="light" suffix="delivery" />
          <AppText style={styles.headline} color={c.textInverse} accessibilityRole="header">{title}</AppText>
        </View>
      </Hero>
    </View>
  );
}

const styles = StyleSheet.create({
  copy: { flex: 1, justifyContent: 'space-between', paddingHorizontal: sp['5'], paddingBottom: sp['10'] },
  headline: { fontSize: t.fontSize['4xl'], lineHeight: t.fontSize['4xl'] * t.leading.tight, fontWeight: t.fontWeight.black, letterSpacing: -0.55 }
});
