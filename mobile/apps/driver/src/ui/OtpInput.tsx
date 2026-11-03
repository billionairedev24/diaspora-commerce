/* Six-box one-time code input. One hidden TextInput holds the value (so the keyboard, paste and SMS / authenticator
   autofill via textContentType="oneTimeCode" / autoComplete="one-time-code" all work), the boxes only draw it. The
   last digit submits (onComplete). Screen readers get one field ("Verification code, 3 of 6 digits"), not six boxes.
   Boxes keep left-to-right order in Arabic: a code is read in the order it was typed. */
import { useEffect, useRef, useState } from 'react';
import { StyleSheet, TextInput, View } from 'react-native';

import { activeCell, justCompleted, OTP_LENGTH, otpCells, sanitizeOtp } from '@/domain/otp';
import { c, sp, t } from '@/theme';

import { AppText, MAX_FONT_SCALE } from './primitives';

export interface OtpInputProps {
  value: string;
  onChange: (value: string) => void;
  onComplete: (value: string) => void;
  label: string;
  /** Read by screen readers after the digit count, e.g. the error. */
  hint?: string;
  error?: boolean;
  disabled?: boolean;
  autoFocus?: boolean;
  /** Changing this refocuses the input (after a wrong code the boxes are cleared and ready again). */
  focusKey?: number;
}

export function OtpInput({ value, onChange, onComplete, label, hint, error, disabled, autoFocus = true, focusKey = 0 }: OtpInputProps) {
  const ref = useRef<TextInput>(null);
  const [focused, setFocused] = useState(false);
  const prev = useRef('');
  useEffect(() => { if (!disabled) ref.current?.focus(); }, [focusKey, disabled]);
  // The parent can clear the boxes (after a wrong code): keep the last value in step so the same code can be re-entered.
  useEffect(() => { prev.current = value; }, [value]);
  const change = (raw: string) => {
    const next = sanitizeOtp(raw);
    const before = prev.current;
    prev.current = next;
    onChange(next);
    if (justCompleted(before, next)) onComplete(next);
  };
  const cells = otpCells(value);
  const active = activeCell(value);
  return (
    <View style={styles.wrap}>
      <View style={styles.boxes} pointerEvents="none" accessibilityElementsHidden importantForAccessibility="no-hide-descendants">
        {cells.map((d, i) => (
          <View key={i} style={[styles.box, error ? styles.boxError : focused && i === active ? styles.boxActive : null]}>
            <AppText style={styles.digit}>{d}</AppText>
          </View>
        ))}
      </View>
      <TextInput
        ref={ref}
        value={value}
        onChangeText={change}
        onFocus={() => setFocused(true)}
        onBlur={() => setFocused(false)}
        editable={!disabled}
        autoFocus={autoFocus}
        keyboardType="number-pad"
        textContentType="oneTimeCode"
        autoComplete="one-time-code"
        autoCorrect={false}
        caretHidden
        selectionColor="transparent"
        maxFontSizeMultiplier={MAX_FONT_SCALE}
        accessibilityLabel={label}
        accessibilityHint={hint}
        accessibilityValue={{ text: `${value.length} / ${OTP_LENGTH}` }}
        style={styles.input}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { minHeight: 60, direction: 'ltr' },
  boxes: { flexDirection: 'row', gap: sp['2'], direction: 'ltr' },
  box: { flex: 1, minHeight: 60, borderRadius: t.radius.md, borderWidth: 1, borderColor: c.borderStrong, backgroundColor: c.surface, alignItems: 'center', justifyContent: 'center' },
  boxActive: { borderWidth: 2, borderColor: t.ink['1000'] },
  boxError: { borderWidth: 2, borderColor: c.danger, backgroundColor: c.dangerBg },
  digit: { fontSize: t.fontSize['2xl'], fontWeight: t.fontWeight.bold },
  // Invisible but real: it receives the keyboard, the paste menu and the autofill, and covers the boxes so a tap focuses it.
  input: { ...StyleSheet.absoluteFill, opacity: 0.01, color: 'transparent', fontSize: t.fontSize.base }
});
