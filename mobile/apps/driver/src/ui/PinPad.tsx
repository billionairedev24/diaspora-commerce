/* Passcode dots and the 3x4 keypad. The lock and setup screens are black, so the pad is drawn for a dark canvas:
   big round keys (76 px, well over the touch minimum) with the biometric key on the left and delete on the right. */
import { BackspaceIcon as Backspace, ScanSmileyIcon as ScanSmiley } from 'phosphor-react-native';
import { Pressable, StyleSheet, View } from 'react-native';

import { PASSCODE_LENGTH } from '@/domain/passcode';
import { useT } from '@/i18n';
import { c, sp, t } from '@/theme';

import { AppText } from './primitives';

export function PinDots({ filled, error }: { filled: number; error?: boolean }) {
  const tr = useT();
  return (
    <View style={styles.dots} accessibilityRole="progressbar" accessibilityLabel={tr('dr.lock.digitsEntered', { filled, total: PASSCODE_LENGTH })}>
      {Array.from({ length: PASSCODE_LENGTH }, (_, i) => (
        <View key={i} style={[styles.dot, { borderColor: error ? t.red['100'] : i < filled ? t.gold['500'] : c.textInverse }, i < filled ? { backgroundColor: error ? t.red['100'] : t.gold['500'] } : null]} />
      ))}
    </View>
  );
}

const DIGITS = ['1', '2', '3', '4', '5', '6', '7', '8', '9'] as const;

export function PinPad({ onDigit, onDelete, onBiometric, biometricLabel }: { onDigit: (d: string) => void; onDelete: () => void; onBiometric?: () => void; biometricLabel?: string }) {
  const tr = useT();
  const key = (d: string) => (
    <Pressable key={d} accessibilityRole="button" accessibilityLabel={d} onPress={() => onDigit(d)} style={({ pressed }) => [styles.key, { backgroundColor: pressed ? t.ink['700'] : t.ink['800'] }]}>
      <AppText color={c.textInverse} style={styles.digit}>{d}</AppText>
    </Pressable>
  );
  return (
    // The pad keeps its left-to-right layout in Arabic: digits are not mirrored.
    <View style={[styles.grid, { direction: 'ltr' }]}>
      {DIGITS.map(key)}
      {onBiometric ? (
        <Pressable accessibilityRole="button" accessibilityLabel={tr('dr.lock.unlockWith', { method: biometricLabel ?? '' })} onPress={onBiometric} style={[styles.key, styles.clear]}>
          <ScanSmiley size={30} color={t.gold['500']} />
        </Pressable>
      ) : <View style={[styles.key, styles.clear]} />}
      {key('0')}
      <Pressable accessibilityRole="button" accessibilityLabel={tr('dr.lock.delete')} onPress={onDelete} style={[styles.key, styles.clear]}>
        <Backspace size={26} color={c.textInverse} />
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  dots: { flexDirection: 'row', gap: sp['3'], justifyContent: 'center' },
  dot: { width: 14, height: 14, borderRadius: 7, borderWidth: 2 },
  grid: { flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'center', gap: sp['3'], maxWidth: 3 * 76 + 2 * sp['3'], alignSelf: 'center' },
  key: { width: 76, height: 76, borderRadius: 38, alignItems: 'center', justifyContent: 'center' },
  digit: { fontSize: 26, fontWeight: t.fontWeight.medium },
  clear: { backgroundColor: 'transparent' }
});
