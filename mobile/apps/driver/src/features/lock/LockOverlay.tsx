/* The lock screen (design 2.5): "Welcome back, <first name>", Face ID tried automatically, then the passcode keypad.
   Drawn over the running app, so unlocking returns to the same stop, scan or signature. Nothing here touches sync. */
import { ScanSmileyIcon as ScanSmiley } from 'phosphor-react-native';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';

import { firstNameOf } from '@/domain/lockPolicy';
import { type UnlockResult } from '@/domain/passcode';
import { type MessageKey, useT } from '@/i18n';
import { useDriver } from '@/session/DriverProvider';
import { useLock } from '@/session/LockProvider';
import { c, sp, t } from '@/theme';
import { PinDots, PinPad } from '@/ui/PinPad';
import { AppText, Button } from '@/ui/primitives';
import { usePinEntry } from '@/ui/usePinEntry';

import { LockFrame } from './LockFrame';

function retryKey(r: UnlockResult): MessageKey {
  return r.kind === 'retry' && r.left === 1 ? 'dr.lock.wrongOne' : 'dr.lock.wrongMany';
}

export function LockOverlay() {
  const tr = useT();
  const { user, sync, signOut } = useDriver();
  const { faceId, biometryLabel, unlockWithPasscode, unlockWithBiometrics } = useLock();
  const [mode, setMode] = useState<'biometric' | 'passcode'>(faceId ? 'biometric' : 'passcode');
  const [err, setErr] = useState('');
  const asked = useRef(false);
  const name = firstNameOf(user?.name ?? '');
  const unsent = sync.outbox.events.length;

  const tryBiometric = useCallback(() => {
    void unlockWithBiometrics();
  }, [unlockWithBiometrics]);

  // Face ID first, automatically; a cancel or a miss leaves the buttons and the passcode keypad.
  useEffect(() => {
    if (faceId && !asked.current) {
      asked.current = true;
      tryBiometric();
    }
  }, [faceId, tryBiometric]);

  const entry = usePinEntry((pin) => {
    void unlockWithPasscode(pin).then((r) => {
      entry.reset();
      if (r.kind === 'retry') setErr(tr(retryKey(r), { left: r.left }));
    });
  });

  const footer = (
    <>
      {unsent ? <AppText variant="caption" color={t.ink['300']} style={styles.centre}>{tr('dr.lock.unsentStay')}</AppText> : null}
      <Button kind="ghostOnDark" size="md" label={mode === 'passcode' ? tr('dr.lock.forgotSignOut') : name ? tr('dr.lock.notYouSignOut', { name }) : tr('dr.profile.signOut')} onPress={() => void signOut()} style={styles.signOut} />
    </>
  );

  return (
    <LockFrame footer={footer}>
      <AppText variant="display" color={c.textInverse} style={styles.centre} accessibilityRole="header">
        {name ? tr('dr.lock.welcomeBack', { name }) : tr('dr.lock.welcomeBackNoName')}
      </AppText>
      {mode === 'biometric' ? (
        <>
          <AppText color={t.ink['300']} style={styles.centre}>{tr('dr.lock.useToUnlock', { method: biometryLabel })}</AppText>
          <Pressable accessibilityRole="button" accessibilityLabel={tr('dr.lock.unlockWith', { method: biometryLabel })} onPress={tryBiometric} style={({ pressed }) => [styles.face, pressed ? { opacity: 0.8 } : null]}>
            <ScanSmiley size={64} color={t.gold['500']} />
          </Pressable>
          <Button kind="onDark" label={tr('dr.lock.usePasscode')} onPress={() => setMode('passcode')} style={styles.wide} />
        </>
      ) : (
        <View style={styles.pin}>
          <AppText color={t.ink['300']} style={styles.centre}>{tr('dr.lock.enterPasscode')}</AppText>
          <PinDots filled={entry.pin.length} error={!!err} />
          <AppText variant="small" color={t.red['100']} style={[styles.centre, { minHeight: 20 }]} accessibilityRole="alert" accessibilityLiveRegion="assertive">{err}</AppText>
          <PinPad onDigit={(d) => { setErr(''); entry.press(d); }} onDelete={entry.remove} onBiometric={faceId ? () => { setMode('biometric'); tryBiometric(); } : undefined} biometricLabel={biometryLabel} />
        </View>
      )}
    </LockFrame>
  );
}

const styles = StyleSheet.create({
  centre: { textAlign: 'center' },
  pin: { alignItems: 'center', gap: sp['4'] },
  face: { width: 120, height: 120, borderRadius: t.radius.lg * 2, borderWidth: 2, borderColor: t.gold['500'], alignItems: 'center', justifyContent: 'center' },
  wide: { alignSelf: 'stretch' },
  signOut: { alignSelf: 'center' }
});
