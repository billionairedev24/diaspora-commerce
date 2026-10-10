/* Unlock screen (design: PIN "lock"): logo, "Welcome back", passcode keypad with the Face ID key, "Forgot passcode?"
   and "Not {name}? Sign out". Rendered over the app so unlocking returns you to where you left off. */
import { useEffect, useRef, useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { useAuth } from '@/auth/AuthProvider';
import { PASSCODE_LENGTH } from '@/domain/passcode';
import { c, sp, t } from '@/theme';
import { LogoMark } from '@/ui/Logo';
import { PinDots, PinPad } from '@/ui/PinPad';
import { AppText, Button } from '@/ui/primitives';

export function LockOverlay() {
  const { user, faceId, biometryLabel, unlockWithPasscode, unlockWithBiometrics, signOut } = useAuth();
  const insets = useSafeAreaInsets();
  const [pin, setPin] = useState('');
  const [err, setErr] = useState('');
  const asked = useRef(false);

  useEffect(() => {
    if (faceId && !asked.current) {
      asked.current = true;
      void unlockWithBiometrics();
    }
  }, [faceId, unlockWithBiometrics]);

  const press = (d: string) => {
    if (pin.length >= PASSCODE_LENGTH) return;
    const next = pin + d;
    setPin(next);
    setErr('');
    if (next.length === PASSCODE_LENGTH) {
      void unlockWithPasscode(next).then((r) => {
        setPin('');
        if (r.kind === 'retry') setErr(r.message);
      });
    }
  };

  return (
    <View style={[StyleSheet.absoluteFill, styles.root, { paddingTop: insets.top + sp['6'], paddingBottom: insets.bottom + sp['4'] }]} accessibilityViewIsModal>
      <View style={styles.head}>
        <View style={styles.brand}>
          <LogoMark size={28} />
          <AppText variant="bodyStrong" style={{ fontWeight: t.fontWeight.black }}>afrotransact</AppText>
        </View>
        <View style={styles.avatar}>
          <AppText variant="title" color={c.textInverse}>{user?.initials ?? 'AT'}</AppText>
        </View>
        <AppText variant="heading">Welcome back, {user?.first ?? ''}</AppText>
      </View>
      <View style={{ alignItems: 'center', gap: sp['1'] }}>
        <AppText variant="bodyStrong">Enter your passcode</AppText>
        <AppText variant="small" color={c.textMuted}>{faceId ? `Or tap the ${biometryLabel} key.` : `${biometryLabel} is off for this app.`}</AppText>
      </View>
      <PinDots filled={pin.length} />
      <AppText variant="small" color={c.danger} style={{ minHeight: 20, textAlign: 'center' }} accessibilityLiveRegion="assertive">{err}</AppText>
      <PinPad onDigit={press} onDelete={() => setPin((p) => p.slice(0, -1))} onBiometric={faceId ? () => void unlockWithBiometrics() : undefined} />
      <View style={{ gap: sp['1'], alignItems: 'center' }}>
        <Button kind="ghost" size="md" label="Forgot passcode? Sign in with password" onPress={() => void signOut({ flash: 'Sign in with your password to reset your passcode.' })} />
        <Button kind="ghost" size="md" label={`Not ${user?.first ?? 'you'}? Sign out`} onPress={() => void signOut()} />
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { backgroundColor: c.canvas, alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: sp['6'], zIndex: 100 },
  head: { alignItems: 'center', gap: sp['3'] },
  brand: { flexDirection: 'row', alignItems: 'center', gap: sp['2'] },
  avatar: { width: 64, height: 64, borderRadius: 32, backgroundColor: t.ink['1000'], alignItems: 'center', justifyContent: 'center' }
});
