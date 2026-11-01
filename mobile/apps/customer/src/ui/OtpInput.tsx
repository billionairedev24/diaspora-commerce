/* Six-box one-time-code input. One hidden TextInput owns the value (so SMS autofill, paste and backspace behave like a
   normal field: iOS offers the code above the keyboard via textContentType=oneTimeCode, Android via autoComplete), and
   six boxes only draw it. Screen readers get one field ("Verification code, 6 digits", "3 of 6 digits entered"), not six.
   onComplete fires once, on the change that fills the sixth box. The digits are never logged or stored here. */
import { useState } from 'react';
import { StyleSheet, TextInput, View } from 'react-native';

import { isOtpComplete, normalizeOtp, otpActiveBox, otpBoxes, OTP_LENGTH, shouldAutoSubmit } from '@/domain/otp';
import { useT } from '@/i18n';
import { c, sp, t } from '@/theme';

import { AppText } from './primitives';

export interface OtpInputProps {
  value: string;
  onChange: (value: string) => void;
  /** Called once when the sixth digit is entered (typed, pasted or autofilled). */
  onComplete: (value: string) => void;
  error?: boolean;
  disabled?: boolean;
  autoFocus?: boolean;
}

export function OtpInput({ value, onChange, onComplete, error = false, disabled = false, autoFocus = false }: OtpInputProps) {
  const tr = useT();
  const [focused, setFocused] = useState(false);
  const boxes = otpBoxes(value);
  const active = otpActiveBox(value);
  const complete = isOtpComplete(value);

  const change = (text: string) => {
    // While a check is running the field keeps focus (and the keyboard) but takes no input.
    if (disabled) return;
    const next = normalizeOtp(text);
    onChange(next);
    if (shouldAutoSubmit(value, next)) onComplete(next);
  };

  return (
    <View style={styles.row}>
      {boxes.map((digit, i) => {
        const isActive = focused && !disabled && (complete ? i === OTP_LENGTH - 1 : i === active);
        return (
          <View
            key={i}
            style={[styles.box, { borderColor: error ? c.danger : isActive ? c.text : c.borderStrong, borderWidth: isActive || error ? 2 : 1, opacity: disabled ? 0.5 : 1 }]}
            accessibilityElementsHidden
            importantForAccessibility="no-hide-descendants"
          >
            <AppText variant="title" style={styles.digit}>{digit}</AppText>
          </View>
        );
      })}
      <TextInput
        value={value}
        onChangeText={change}
        onFocus={() => setFocused(true)}
        onBlur={() => setFocused(false)}
        autoFocus={autoFocus}
        keyboardType="number-pad"
        inputMode="numeric"
        textContentType="oneTimeCode"
        autoComplete="one-time-code"
        autoCorrect={false}
        spellCheck={false}
        caretHidden
        selectionColor="transparent"
        accessibilityLabel={tr('cu.mfa.otpLabel')}
        accessibilityValue={{ text: tr('cu.mfa.otpProgress', { n: normalizeOtp(value).length }) }}
        accessibilityState={{ disabled }}
        style={styles.hidden}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  // Digits always run left to right, in Arabic too.
  row: { flexDirection: 'row', justifyContent: 'space-between', gap: sp['2'], direction: 'ltr' },
  box: { flex: 1, maxWidth: 56, minHeight: t.size.controlLg + sp['3'], alignItems: 'center', justifyContent: 'center', borderRadius: t.radius.md, backgroundColor: c.surface },
  digit: { textAlign: 'center' },
  // Transparent, on top of the boxes: a tap anywhere focuses it, a long press offers Paste.
  hidden: { position: 'absolute', top: 0, bottom: 0, start: 0, end: 0, opacity: 0.02, color: 'transparent', fontSize: t.fontSize.base }
});
