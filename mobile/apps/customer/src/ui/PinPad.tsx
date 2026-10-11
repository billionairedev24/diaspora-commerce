import { BackspaceIcon as Backspace, ScanSmileyIcon as ScanSmiley } from 'phosphor-react-native';
import { Pressable, StyleSheet, View } from 'react-native';

import { PASSCODE_LENGTH } from '@/domain/passcode';
import { c, sp, t } from '@/theme';

import { AppText } from './primitives';

export type PinTone = 'light' | 'dark';

/** Colours per canvas: light (white screens, lock overlay) or dark (the black onboarding screens). */
const TONE = {
  light: { ink: t.ink['1000'], fill: t.ink['1000'], key: c.surfaceMuted, pressed: t.ink['100'], text: c.text },
  dark: { ink: c.textInverse, fill: t.gold['500'], key: t.ink['800'], pressed: t.ink['700'], text: c.textInverse }
} as const;

export function PinDots({ filled, tone = 'light' }: { filled: number; tone?: PinTone }) {
  const k = TONE[tone];
  return (
    <View style={styles.dots} accessibilityLabel={`${filled} of ${PASSCODE_LENGTH} digits entered`}>
      {Array.from({ length: PASSCODE_LENGTH }, (_, i) => (
        <View key={i} style={[styles.dot, { borderColor: i < filled ? k.fill : k.ink }, i < filled ? { backgroundColor: k.fill } : null]} />
      ))}
    </View>
  );
}

/** 3×4 keypad (1–9, Face ID, 0, delete). The Face ID key is only shown on the lock screen with Face ID on. */
export function PinPad({ onDigit, onDelete, onBiometric, tone = 'light' }: { onDigit: (d: string) => void; onDelete: () => void; onBiometric?: () => void; tone?: PinTone }) {
  const tk = TONE[tone];
  const keys = ['1', '2', '3', '4', '5', '6', '7', '8', '9', 'bio', '0', 'del'];
  return (
    <View style={styles.grid}>
      {keys.map((k) => {
        if (k === 'bio') {
          return onBiometric ? (
            <Pressable key={k} accessibilityRole="button" accessibilityLabel="Unlock with Face ID" onPress={onBiometric} style={[styles.key, styles.clear]}>
              <ScanSmiley size={30} color={tk.text} />
            </Pressable>
          ) : (
            <View key={k} style={[styles.key, styles.clear]} />
          );
        }
        if (k === 'del') {
          return (
            <Pressable key={k} accessibilityRole="button" accessibilityLabel="Delete" onPress={onDelete} style={[styles.key, styles.clear]}>
              <Backspace size={26} color={tk.text} />
            </Pressable>
          );
        }
        return (
          <Pressable key={k} accessibilityRole="button" accessibilityLabel={k} onPress={() => onDigit(k)} style={({ pressed }) => [styles.key, { backgroundColor: pressed ? tk.pressed : tk.key }]}>
            <AppText color={tk.text} style={{ fontSize: 26, fontWeight: t.fontWeight.medium }}>{k}</AppText>
          </Pressable>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  dots: { flexDirection: 'row', gap: sp['3'], justifyContent: 'center' },
  dot: { width: 14, height: 14, borderRadius: 7, borderWidth: 2 },
  grid: { flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'center', gap: sp['3'], maxWidth: 3 * 76 + 2 * sp['3'], alignSelf: 'center' },
  key: { width: 76, height: 76, borderRadius: 38, alignItems: 'center', justifyContent: 'center', backgroundColor: c.surfaceMuted },
  clear: { backgroundColor: 'transparent' }
});
