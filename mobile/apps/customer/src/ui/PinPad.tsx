import { BackspaceIcon as Backspace, ScanSmileyIcon as ScanSmiley } from 'phosphor-react-native';
import { Pressable, StyleSheet, View } from 'react-native';

import { PASSCODE_LENGTH } from '@/domain/passcode';
import { c, sp, t } from '@/theme';

import { AppText } from './primitives';

export function PinDots({ filled }: { filled: number }) {
  return (
    <View style={styles.dots} accessibilityLabel={`${filled} of ${PASSCODE_LENGTH} digits entered`}>
      {Array.from({ length: PASSCODE_LENGTH }, (_, i) => (
        <View key={i} style={[styles.dot, i < filled ? { backgroundColor: t.ink['1000'] } : null]} />
      ))}
    </View>
  );
}

/** 3×4 keypad (1–9, Face ID, 0, delete). The Face ID key is only shown on the lock screen with Face ID on. */
export function PinPad({ onDigit, onDelete, onBiometric }: { onDigit: (d: string) => void; onDelete: () => void; onBiometric?: () => void }) {
  const keys = ['1', '2', '3', '4', '5', '6', '7', '8', '9', 'bio', '0', 'del'];
  return (
    <View style={styles.grid}>
      {keys.map((k) => {
        if (k === 'bio') {
          return onBiometric ? (
            <Pressable key={k} accessibilityRole="button" accessibilityLabel="Unlock with Face ID" onPress={onBiometric} style={[styles.key, styles.clear]}>
              <ScanSmiley size={30} color={c.text} />
            </Pressable>
          ) : (
            <View key={k} style={[styles.key, styles.clear]} />
          );
        }
        if (k === 'del') {
          return (
            <Pressable key={k} accessibilityRole="button" accessibilityLabel="Delete" onPress={onDelete} style={[styles.key, styles.clear]}>
              <Backspace size={26} color={c.text} />
            </Pressable>
          );
        }
        return (
          <Pressable key={k} accessibilityRole="button" accessibilityLabel={k} onPress={() => onDigit(k)} style={({ pressed }) => [styles.key, pressed ? { backgroundColor: t.ink['100'] } : null]}>
            <AppText style={{ fontSize: 26, fontWeight: t.fontWeight.medium }}>{k}</AppText>
          </Pressable>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  dots: { flexDirection: 'row', gap: sp['3'], justifyContent: 'center' },
  dot: { width: 14, height: 14, borderRadius: 7, borderWidth: 2, borderColor: t.ink['1000'] },
  grid: { flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'center', gap: sp['3'], maxWidth: 3 * 76 + 2 * sp['3'], alignSelf: 'center' },
  key: { width: 76, height: 76, borderRadius: 38, alignItems: 'center', justifyContent: 'center', backgroundColor: c.surfaceMuted },
  clear: { backgroundColor: 'transparent' }
});
