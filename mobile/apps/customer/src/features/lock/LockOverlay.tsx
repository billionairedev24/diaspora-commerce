/* Unlock screen: brand bar, avatar and "Welcome back, {name}", the passcode dots and keypad with the Face ID key, and the
   two ways out ("Forgot passcode? Sign in with password", "Not {name}? Sign out"). Face ID is tried once automatically
   when the screen appears. Rendered over the app so unlocking returns you to where you left off. Five wrong passcodes
   sign out (AuthProvider); this screen only shows the count that is left. */
import { StatusBar } from 'expo-status-bar';
import { useEffect, useRef, useState } from 'react';
import { ScrollView, StyleSheet, useWindowDimensions, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { useAuth } from '@/auth/AuthProvider';
import { MAX_PASSCODE_ATTEMPTS, PASSCODE_LENGTH } from '@/domain/passcode';
import { useT } from '@/i18n';
import { c, sp, t } from '@/theme';
import { LogoMark } from '@/ui/Logo';
import { PinDots, PinPad } from '@/ui/PinPad';
import { AppText, Button } from '@/ui/primitives';

export function LockOverlay() {
  const tr = useT();
  const { user, faceId, biometryLabel, unlockWithPasscode, unlockWithBiometrics, signOut } = useAuth();
  const insets = useSafeAreaInsets();
  const compact = useWindowDimensions().height < 700;
  const [pin, setPin] = useState('');
  const [err, setErr] = useState('');
  const [shake, setShake] = useState(0);
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
        if (r.kind === 'retry') {
          setErr(tr('cu.lock.wrong', { n: Math.max(0, MAX_PASSCODE_ATTEMPTS - r.attempts) }));
          setShake((n) => n + 1);
        }
      });
    }
  };

  const first = user?.first ?? '';
  return (
    <View style={[StyleSheet.absoluteFill, styles.root]} accessibilityViewIsModal>
      <StatusBar style="light" />
      <View style={[styles.bar, { paddingTop: insets.top + sp['3'] }]}>
        <LogoMark size={28} />
        <AppText variant="bodyStrong" color={c.textInverse} style={{ fontWeight: t.fontWeight.black }}>afrotransact</AppText>
      </View>
      <ScrollView contentContainerStyle={[styles.body, { paddingBottom: insets.bottom + sp['4'] }]} showsVerticalScrollIndicator={false} bounces={false}>
        <View style={styles.head}>
          <View style={styles.avatar} accessibilityElementsHidden importantForAccessibility="no-hide-descendants">
            <AppText variant="title" color={t.gold['500']}>{user?.initials ?? 'AT'}</AppText>
          </View>
          <AppText variant="display" accessibilityRole="header" style={styles.center}>{tr('cu.lock.welcome', { name: first })}</AppText>
          <AppText variant="small" color={c.textMuted} style={styles.center}>
            {tr('cu.lock.enter')} {faceId ? tr('cu.lock.hintBio', { biometry: biometryLabel }) : tr('cu.lock.hintOff')}
          </AppText>
        </View>
        <PinDots filled={pin.length} error={!!err} shake={shake} />
        <AppText variant="small" color={c.danger} style={[styles.center, { minHeight: 20 }]} accessibilityLiveRegion="assertive" accessibilityRole="alert">{err}</AppText>
        <PinPad onDigit={press} onDelete={() => setPin((p) => p.slice(0, -1))} onBiometric={faceId ? () => void unlockWithBiometrics() : undefined} biometryLabel={biometryLabel} compact={compact} />
        <View style={{ gap: sp['1'], alignItems: 'center' }}>
          <Button kind="ghost" size="md" label={tr('cu.lock.forgot')} onPress={() => void signOut({ flash: { code: 'passcode-reset' } })} />
          <Button kind="ghost" size="md" label={first ? tr('cu.lock.notYou', { name: first }) : tr('cu.lock.notYouGeneric')} onPress={() => void signOut()} />
        </View>
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { backgroundColor: c.canvas, zIndex: 100 },
  bar: { flexDirection: 'row', alignItems: 'center', gap: sp['2'], paddingHorizontal: sp['4'], paddingBottom: sp['3'], backgroundColor: t.ink['1000'] },
  body: { flexGrow: 1, alignItems: 'center', justifyContent: 'space-between', gap: sp['3'], paddingHorizontal: sp['6'], paddingTop: sp['5'] },
  head: { alignItems: 'center', gap: sp['2'] },
  avatar: { width: 64, height: 64, borderRadius: 32, backgroundColor: t.ink['1000'], alignItems: 'center', justifyContent: 'center' },
  center: { textAlign: 'center' }
});
