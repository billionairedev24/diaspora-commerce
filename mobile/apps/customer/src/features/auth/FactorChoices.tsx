/* "Other ways to verify" as plain list rows (label, optional detail, chevron), flat with hairline dividers. */
import { CaretLeftIcon as CaretLeft, CaretRightIcon as CaretRight } from 'phosphor-react-native';
import { ActivityIndicator, I18nManager, Pressable, StyleSheet, View } from 'react-native';

import { c, sp, t } from '@/theme';
import { AppText } from '@/ui/primitives';

export interface FactorChoice {
  key: string;
  label: string;
  detail?: string;
  loading?: boolean;
  onPress: () => void;
}

export function FactorChoices({ title, choices, disabled }: { title: string; choices: FactorChoice[]; disabled: boolean }) {
  if (choices.length === 0) return null;
  const Chevron = I18nManager.isRTL ? CaretLeft : CaretRight;
  return (
    <View accessibilityRole="menu" accessibilityLabel={title}>
      {choices.map((ch, i) => (
        <Pressable
          key={ch.key}
          accessibilityRole="button"
          accessibilityLabel={ch.detail ? `${ch.label}, ${ch.detail}` : ch.label}
          accessibilityState={{ disabled, busy: !!ch.loading }}
          onPress={disabled ? undefined : ch.onPress}
          style={({ pressed }) => [styles.row, i === 0 ? styles.first : null, { opacity: disabled && !ch.loading ? 0.5 : pressed ? 0.7 : 1 }]}
        >
          <AppText variant="bodyStrong" style={{ flex: 1 }}>{ch.label}</AppText>
          {ch.detail ? <AppText variant="small" color={c.textMuted}>{ch.detail}</AppText> : null}
          {ch.loading ? <ActivityIndicator color={c.text} /> : <Chevron size={16} color={c.textMuted} />}
        </Pressable>
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  row: { minHeight: t.size.touch + sp['2'], flexDirection: 'row', alignItems: 'center', gap: sp['2'], borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: c.borderStrong },
  first: { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: c.borderStrong }
});
