/* Password input with Show / Hide inside the field row (a 44 px target at the trailing edge). */
import { forwardRef, useState } from 'react';
import { Pressable, StyleSheet, TextInput, type TextInputProps, View } from 'react-native';

import { useT } from '@/i18n';
import { c, sp, t } from '@/theme';
import { AppText, MAX_FONT_SCALE } from '@/ui/primitives';

export const PasswordField = forwardRef<TextInput, Omit<TextInputProps, 'secureTextEntry' | 'style'> & { label: string; error?: string; invalid?: boolean }>(function PasswordField({ label, error, invalid, ...input }, ref) {
  const tr = useT();
  const [shown, setShown] = useState(false);
  const bad = !!error || !!invalid;
  return (
    <View style={{ gap: sp['1'] }}>
      <AppText variant="small" color={c.textMuted} style={{ fontWeight: t.fontWeight.semibold }}>{label}</AppText>
      <View style={[styles.row, bad ? { borderColor: c.danger, borderWidth: 2 } : null]}>
        <TextInput
          ref={ref}
          placeholderTextColor={c.textSubtle}
          accessibilityLabel={label}
          accessibilityHint={error}
          maxFontSizeMultiplier={MAX_FONT_SCALE}
          autoCapitalize="none"
          autoCorrect={false}
          autoComplete="current-password"
          textContentType="password"
          {...input}
          secureTextEntry={!shown}
          style={styles.input}
        />
        <Pressable accessibilityRole="button" accessibilityLabel={shown ? tr('dr.signIn.hidePassword') : tr('dr.signIn.showPassword')} accessibilityState={{ selected: shown }} onPress={() => setShown((v) => !v)} hitSlop={6} style={styles.toggle}>
          <AppText variant="small" style={{ fontWeight: t.fontWeight.bold, textDecorationLine: 'underline' }}>{shown ? tr('dr.signIn.hide') : tr('dr.signIn.show')}</AppText>
        </Pressable>
      </View>
      {error ? <AppText variant="caption" color={c.danger} accessibilityRole="alert" accessibilityLiveRegion="polite">{error}</AppText> : null}
    </View>
  );
});

const styles = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center', minHeight: t.size.controlLg, borderWidth: 1, borderColor: c.borderStrong, borderRadius: t.radius.md, backgroundColor: c.surface },
  input: { flex: 1, minHeight: t.size.controlLg, paddingHorizontal: sp['3'], fontSize: t.fontSize.base, color: c.text },
  toggle: { minWidth: t.size.touch, minHeight: t.size.touch, paddingHorizontal: sp['3'], alignItems: 'center', justifyContent: 'center' }
});
