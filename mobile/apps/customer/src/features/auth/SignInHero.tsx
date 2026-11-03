/* The picture above the sign-in sheet: the brand photo (or drawn art) fading to black at the bottom, the logo on top
   and the line of copy over the fade. The parent shrinks `height` while the keyboard is up. */
import { StyleSheet, View } from 'react-native';

import { CustomerHeroArt } from '@/brand/CustomerHeroArt';
import { Hero } from '@/brand/Hero';
import heroPhoto from '@/brand/heroPhoto';
import { useT } from '@/i18n';
import { c, sp, t } from '@/theme';
import { Logo } from '@/ui/Logo';
import { AppText, MAX_FONT_SCALE } from '@/ui/primitives';

export function SignInHero({ height, topInset, compact }: { height: number; topInset: number; compact: boolean }) {
  const tr = useT();
  return (
    <View style={{ height }}>
      <Hero photo={heroPhoto} art={<CustomerHeroArt />} accessibilityLabel={tr('cu.signIn.heroAlt')}>
        <View style={[styles.copy, { paddingTop: topInset + sp['3'] }]}>
          <Logo size={40} wordSize={t.fontSize.xl} tone="light" />
          {compact ? null : (
            <AppText maxFontSizeMultiplier={MAX_FONT_SCALE} style={styles.headline} color={c.textInverse} accessibilityRole="header">
              {tr('cu.signIn.everyDiasporaSMarketOne')}
            </AppText>
          )}
        </View>
      </Hero>
    </View>
  );
}

const styles = StyleSheet.create({
  copy: { flex: 1, justifyContent: 'space-between', paddingHorizontal: sp['5'], paddingBottom: sp['10'] },
  headline: { fontSize: t.fontSize['3xl'], lineHeight: t.fontSize['3xl'] * t.leading.tight, fontWeight: t.fontWeight.black, letterSpacing: -0.45 }
});
