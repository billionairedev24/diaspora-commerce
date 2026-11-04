/* One alternative on the second step ("Text me a code", "Use a recovery code"): a full-width list row with a caret. */
import { CaretLeftIcon as CaretLeft, CaretRightIcon as CaretRight } from 'phosphor-react-native';
import { I18nManager, Pressable, StyleSheet, View } from 'react-native';

import { c, sp, t } from '@/theme';
import { AppText } from '@/ui/primitives';

export function FactorRow({ label, hint, onPress, disabled }: { label: string; hint?: string; onPress: () => void; disabled?: boolean }) {
  const Caret = I18nManager.isRTL ? CaretLeft : CaretRight;
  return (
    <Pressable accessibilityRole="button" accessibilityLabel={hint ? `${label}. ${hint}` : label} accessibilityState={{ disabled: !!disabled }} onPress={disabled ? undefined : onPress} style={({ pressed }) => [styles.row, { opacity: disabled ? 0.5 : pressed ? 0.7 : 1 }]}>
      <View style={{ flex: 1 }}>
        <AppText variant="bodyStrong">{label}</AppText>
        {hint ? <AppText variant="caption" color={c.textMuted}>{hint}</AppText> : null}
      </View>
      <Caret size={18} color={c.textMuted} />
    </Pressable>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center', gap: sp['3'], minHeight: t.size.touch + 8, paddingVertical: sp['2'], borderTopWidth: 1, borderTopColor: c.border }
});
