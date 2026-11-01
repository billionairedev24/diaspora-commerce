/* Passcode entry pieces: the six dots (with a shake and a red state for a wrong entry) and the 3×4 keypad. The keypad
   and dots always run left to right, in Arabic too, like a phone's own keypad. */
import { BackspaceIcon as Backspace, ScanSmileyIcon as ScanSmiley } from 'phosphor-react-native';
import { useEffect, useRef } from 'react';
import { Animated, Pressable, StyleSheet, View } from 'react-native';

import { useReducedMotion } from '@/brand/useReducedMotion';
import { PASSCODE_LENGTH } from '@/domain/passcode';
import { useT } from '@/i18n';
import { c, sp, t } from '@/theme';

import { AppText } from './primitives';

const SHAKE = [-8, 8, -6, 6, -3, 3, 0];

/** `shake` changes each time a wrong entry should shake the dots (it is a counter, 0 = never). */
export function PinDots({ filled, error = false, shake = 0 }: { filled: number; error?: boolean; shake?: number }) {
  const tr = useT();
  const reduced = useReducedMotion();
  const x = useRef(new Animated.Value(0)).current;
  useEffect(() => {
    if (!shake || reduced) return;
    Animated.sequence(SHAKE.map((to) => Animated.timing(x, { toValue: to, duration: 45, useNativeDriver: true }))).start();
  }, [shake, reduced, x]);
  const ink = error ? c.danger : t.ink['1000'];
  return (
    <Animated.View style={[styles.dots, { transform: [{ translateX: x }] }]} accessible accessibilityLabel={tr('cu.pin.dots', { n: filled, total: PASSCODE_LENGTH })}>
      {Array.from({ length: PASSCODE_LENGTH }, (_, i) => (
        <View key={i} style={[styles.dot, { borderColor: ink }, i < filled ? { backgroundColor: ink } : null]} />
      ))}
    </Animated.View>
  );
}

/** 1–9, Face ID, 0, delete. The Face ID key is only passed on the lock screen with Face ID on. `compact` on short phones. */
export function PinPad({ onDigit, onDelete, onBiometric, biometryLabel = 'Face ID', compact = false }: { onDigit: (d: string) => void; onDelete: () => void; onBiometric?: () => void; biometryLabel?: string; compact?: boolean }) {
  const tr = useT();
  const size = compact ? 64 : 76;
  const key = { width: size, height: size, borderRadius: size / 2 };
  const keys = ['1', '2', '3', '4', '5', '6', '7', '8', '9', 'bio', '0', 'del'];
  return (
    <View style={[styles.grid, { maxWidth: 3 * size + 2 * sp['3'] }]}>
      {keys.map((k) => {
        if (k === 'bio') {
          return onBiometric ? (
            <Pressable key={k} accessibilityRole="button" accessibilityLabel={tr('cu.pin.biometric', { biometry: biometryLabel })} onPress={onBiometric} style={[styles.key, key, styles.clear]}>
              <ScanSmiley size={30} color={c.text} />
            </Pressable>
          ) : (
            <View key={k} style={[styles.key, key, styles.clear]} />
          );
        }
        if (k === 'del') {
          return (
            <Pressable key={k} accessibilityRole="button" accessibilityLabel={tr('cu.pin.delete')} onPress={onDelete} style={[styles.key, key, styles.clear]}>
              <Backspace size={26} color={c.text} />
            </Pressable>
          );
        }
        return (
          <Pressable key={k} accessibilityRole="button" accessibilityLabel={k} onPress={() => onDigit(k)} style={({ pressed }) => [styles.key, key, { backgroundColor: pressed ? t.ink['100'] : c.surfaceMuted }]}>
            <AppText style={{ fontSize: compact ? 24 : 26, fontWeight: t.fontWeight.medium }}>{k}</AppText>
          </Pressable>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  dots: { flexDirection: 'row', gap: sp['3'], justifyContent: 'center', direction: 'ltr' },
  dot: { width: 14, height: 14, borderRadius: 7, borderWidth: 2 },
  grid: { flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'center', gap: sp['3'], alignSelf: 'center', direction: 'ltr' },
  key: { alignItems: 'center', justifyContent: 'center' },
  clear: { backgroundColor: 'transparent' }
});
