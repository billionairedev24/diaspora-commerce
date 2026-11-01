/* Password input with Show/Hide inside the field row. The password lives only in the caller's state: no autocorrect,
   no suggestions, and the platform's password manager can fill it (textContentType/autoComplete). */
import { type Ref, useState } from 'react';
import { Pressable, StyleSheet, TextInput, type TextInputProps, View } from 'react-native';

import { useT } from '@/i18n';
import { c, sp, t } from '@/theme';

import { AppText, MAX_FONT_SCALE } from './primitives';

export function PasswordField({ label, error, invalid, inputRef, ...input }: Omit<TextInputProps, 'secureTextEntry' | 'style'> & { label: string; error?: string; invalid?: boolean; inputRef?: Ref<TextInput> }) {
  const tr = useT();
  const [shown, setShown] = useState(false);
  const bad = !!error || !!invalid;
  return (
    <View style={{ gap: sp['1'] }}>
      <AppText variant="small" color={c.textMuted} style={{ fontWeight: t.fontWeight.semibold }}>{label}</AppText>
      <View style={[styles.row, bad ? { borderColor: c.danger, borderWidth: 2 } : null]}>
        <TextInput
          ref={inputRef}
          placeholderTextColor={c.textSubtle}
          accessibilityLabel={label}
          accessibilityHint={error}
          maxFontSizeMultiplier={MAX_FONT_SCALE}
          autoCapitalize="none"
          autoCorrect={false}
          spellCheck={false}
          autoComplete="current-password"
          textContentType="password"
          {...input}
          secureTextEntry={!shown}
          style={styles.input}
        />
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={shown ? tr('cu.signIn.hidePassword') : tr('cu.signIn.showPassword')}
          accessibilityState={{ checked: shown }}
          onPress={() => setShown((v) => !v)}
          hitSlop={8}
          style={styles.toggle}
        >
          <AppText variant="small" style={{ fontWeight: t.fontWeight.semibold, textDecorationLine: 'underline' }}>{shown ? tr('cu.signIn.hideShort') : tr('cu.signIn.showShort')}</AppText>
        </Pressable>
      </View>
      {error ? <AppText variant="caption" color={c.danger} accessibilityRole="alert" accessibilityLiveRegion="polite">{error}</AppText> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  row: { minHeight: t.size.controlLg, flexDirection: 'row', alignItems: 'center', borderWidth: 1, borderColor: c.borderStrong, borderRadius: t.radius.md, backgroundColor: c.surface, paddingStart: sp['3'] },
  input: { flex: 1, alignSelf: 'stretch', fontSize: t.fontSize.base, color: c.text },
  toggle: { minHeight: t.size.touch, minWidth: t.size.touch, paddingHorizontal: sp['3'], alignItems: 'center', justifyContent: 'center' }
});
