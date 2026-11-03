/* First-run setup steps (Face ID → passcode → notifications) in one visual language, like the sign-in sheet: a black bar
   with "Set up this phone" and "1 of 3", a white body, one gold primary action and a quiet "Not now". */
import { StatusBar } from 'expo-status-bar';
import { type Icon as PhosphorIcon } from 'phosphor-react-native';
import { type ReactNode } from 'react';
import { ScrollView, StyleSheet, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { setupProgress, type SetupStepName } from '@/domain/setupProgress';
import { useT } from '@/i18n';
import { c, sp, t } from '@/theme';
import { AppText } from '@/ui/primitives';

/** "1 of 3" with a segment per step; read as "Step 1 of 3". */
export function StepIndicator({ step }: { step: SetupStepName }) {
  const tr = useT();
  const p = setupProgress(step);
  return (
    <View accessible accessibilityLabel={tr('cu.setup.progressA11y', { step: p.step, total: p.total })} style={styles.indicator}>
      <AppText variant="small" color={c.headerMuted} style={{ fontWeight: t.fontWeight.semibold }}>{tr('cu.setup.progress', { step: p.step, total: p.total })}</AppText>
      <View style={styles.segments}>
        {Array.from({ length: p.total }, (_, i) => (
          <View key={i} style={[styles.segment, { backgroundColor: i < p.step ? t.gold['500'] : t.ink['700'] }]} />
        ))}
      </View>
    </View>
  );
}

/** The step's symbol on a soft square, as in the design. */
export function IconTile({ icon: Icon }: { icon: PhosphorIcon }) {
  return (
    <View style={styles.tile} accessibilityElementsHidden importantForAccessibility="no-hide-descendants">
      <Icon size={44} color={c.success} weight="regular" />
    </View>
  );
}

export interface OnboardingScreenProps {
  step: SetupStepName;
  art?: ReactNode;
  title?: string;
  body?: string;
  footer?: ReactNode;
  children?: ReactNode;
  /** The body is its own fixed layout (the passcode keypad) instead of a scrolling column. */
  fixed?: boolean;
}

export function OnboardingScreen({ step, art, title, body, footer, children, fixed = false }: OnboardingScreenProps) {
  const tr = useT();
  const insets = useSafeAreaInsets();
  return (
    <View style={styles.root}>
      <StatusBar style="light" />
      <View style={[styles.bar, { paddingTop: insets.top + sp['3'] }]}>
        <AppText variant="heading" color={c.textInverse} accessibilityRole="header" style={{ flex: 1 }}>{tr('cu.setup.title')}</AppText>
        <StepIndicator step={step} />
      </View>
      {fixed ? (
        <View style={{ flex: 1 }}>{children}</View>
      ) : (
        <ScrollView style={{ flex: 1 }} contentContainerStyle={styles.body} showsVerticalScrollIndicator={false} keyboardShouldPersistTaps="handled">
          {art}
          {title ? <AppText variant="display" style={styles.center} accessibilityRole="header">{title}</AppText> : null}
          {body ? <AppText color={c.textMuted} style={styles.center}>{body}</AppText> : null}
          {children}
        </ScrollView>
      )}
      {footer ? <View style={[styles.footer, { paddingBottom: insets.bottom + sp['3'] }]}>{footer}</View> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: c.canvas },
  bar: { flexDirection: 'row', alignItems: 'center', gap: sp['3'], paddingHorizontal: sp['4'], paddingBottom: sp['3'], backgroundColor: t.ink['1000'] },
  indicator: { alignItems: 'flex-end', gap: sp['1'] },
  segments: { flexDirection: 'row', gap: 3 },
  segment: { width: 18, height: 3, borderRadius: 2 },
  tile: { alignSelf: 'center', width: 88, height: 88, borderRadius: t.radius.lg, backgroundColor: c.successBg, alignItems: 'center', justifyContent: 'center' },
  body: { flexGrow: 1, justifyContent: 'center', paddingHorizontal: sp['6'], paddingVertical: sp['6'], gap: sp['4'] },
  center: { textAlign: 'center' },
  footer: { paddingHorizontal: sp['4'], paddingTop: sp['3'], gap: sp['2'] }
});
