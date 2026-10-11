/* First-run setup steps (Face ID → passcode → notifications) as one family of full-bleed brand screens: black canvas,
   a gold medallion with the step's symbol, gold step label, white copy, gold call to action. */
import { StatusBar } from 'expo-status-bar';
import { type Icon as PhosphorIcon } from 'phosphor-react-native';
import { type ReactNode } from 'react';
import { ScrollView, StyleSheet, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import Svg, { Circle, Defs, RadialGradient, Stop } from 'react-native-svg';

import { c, sp, t } from '@/theme';
import { AppText } from '@/ui/primitives';

/** The step's symbol on a gold disc inside soft rings. */
export function Medallion({ icon: Icon, size = 220 }: { icon: PhosphorIcon; size?: number }) {
  const disc = size * 0.46;
  return (
    <View style={{ width: size, height: size, alignSelf: 'center', alignItems: 'center', justifyContent: 'center' }} accessibilityElementsHidden importantForAccessibility="no-hide-descendants">
      <Svg width={size} height={size} viewBox="0 0 100 100" style={StyleSheet.absoluteFill}>
        <Defs>
          <RadialGradient id="halo" cx="0.5" cy="0.5" r="0.5">
            <Stop offset="0.3" stopColor={t.gold['500']} stopOpacity={0.35} />
            <Stop offset="1" stopColor={t.gold['500']} stopOpacity={0} />
          </RadialGradient>
        </Defs>
        <Circle cx="50" cy="50" r="50" fill="url(#halo)" />
        <Circle cx="50" cy="50" r="38" fill="none" stroke={t.gold['700']} strokeOpacity={0.5} strokeWidth={0.6} />
        <Circle cx="50" cy="50" r="31" fill="none" stroke={t.gold['600']} strokeOpacity={0.6} strokeWidth={0.6} />
      </Svg>
      <View style={{ width: disc, height: disc, borderRadius: disc / 2, backgroundColor: t.gold['500'], alignItems: 'center', justifyContent: 'center' }}>
        <Icon size={disc * 0.5} color={t.ink['1000']} weight="bold" />
      </View>
    </View>
  );
}

export interface OnboardingScreenProps {
  step?: string;
  art?: ReactNode;
  title?: string;
  body?: string;
  footer?: ReactNode;
  children?: ReactNode;
}

export function OnboardingScreen({ step, art, title, body, footer, children }: OnboardingScreenProps) {
  const insets = useSafeAreaInsets();
  return (
    <View style={styles.root}>
      <StatusBar style="light" />
      <ScrollView style={{ flex: 1 }} contentContainerStyle={[styles.body, { paddingTop: insets.top + sp['6'] }]} showsVerticalScrollIndicator={false}>
        {step ? <AppText variant="label" color={t.gold['500']} style={styles.center}>{step}</AppText> : null}
        {art}
        {title ? <AppText color={c.textInverse} style={[styles.title, styles.center]} accessibilityRole="header">{title}</AppText> : null}
        {body ? <AppText color={c.headerMuted} style={styles.center}>{body}</AppText> : null}
        {children}
      </ScrollView>
      {footer ? <View style={[styles.footer, { paddingBottom: insets.bottom + sp['3'] }]}>{footer}</View> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: t.ink['1000'] },
  body: { flexGrow: 1, paddingHorizontal: sp['6'], gap: sp['4'], justifyContent: 'center' },
  title: { fontSize: t.fontSize['2xl'], lineHeight: t.fontSize['2xl'] * t.leading.tight, fontWeight: t.fontWeight.black },
  center: { textAlign: 'center' },
  footer: { paddingHorizontal: sp['4'], paddingTop: sp['3'], gap: sp['2'] }
});
