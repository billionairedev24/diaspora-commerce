/* S081 "Sign in to drive": the work email is passed as login_hint; password, 6-digit code and passkeys run on
   identity's hosted pages (OIDC + PKCE, client driver-app). */
import { FingerprintIcon as Fingerprint, WarningCircleIcon as WarningCircle } from 'phosphor-react-native';
import { useState } from 'react';
import { StyleSheet, View } from 'react-native';

import { SignInCancelled } from '@/auth/oidc';
import { env } from '@/config/env';
import { useDriver } from '@/session/DriverProvider';
import { c, sp } from '@/theme';
import { DriverScreen } from '@/ui/DriverScreen';
import { LogoMark } from '@/ui/Logo';
import { AppText, Banner, Button, Field } from '@/ui/primitives';

export default function SignIn() {
  const { signIn } = useDriver();
  const [email, setEmail] = useState('');
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);
  const go = async () => {
    const e = email.trim().toLowerCase();
    if (e && !/^[^\s@]+@[^\s@]+\.[a-z]{2,}$/i.test(e)) return setErr('Enter your work email.');
    setErr('');
    setBusy(true);
    try {
      await signIn(e || undefined);
    } catch (x) {
      if (!(x instanceof SignInCancelled)) setErr(x instanceof Error ? x.message : 'Sign-in failed.');
    } finally {
      setBusy(false);
    }
  };
  return (
    <DriverScreen title="Driver sign in">
      <View style={styles.head}>
        <LogoMark size={56} />
        <AppText variant="display" accessibilityRole="header">Sign in to drive</AppText>
        <AppText color={c.textMuted} style={{ textAlign: 'center' }}>Use your AfroTransact staff account. You need the “Use the driver app” permission.</AppText>
      </View>
      <Field label="Work email" value={email} onChangeText={(v) => { setEmail(v); setErr(''); }} placeholder="you@afrotransact.com" keyboardType="email-address" autoCapitalize="none" autoComplete="username" onSubmitEditing={() => void go()} />
      <AppText variant="small" color={c.textMuted}>Your password and 6-digit code are entered on AfroTransact’s secure sign-in page.</AppText>
      {err ? <Banner tone="danger" icon={WarningCircle}>{err}</Banner> : null}
      <Button label={busy ? 'Signing in…' : 'Sign in'} loading={busy} onPress={() => void go()} />
      <Button kind="outline" icon={Fingerprint} label="Sign in with passkey" disabled={busy} onPress={() => void go()} />
      {env.mocks ? (
        <View style={styles.demo}>
          <AppText variant="small" style={{ fontWeight: '700' }}>Demo accounts (dev mocks)</AppText>
          <AppText variant="small">Driver: tunde@afrotransact.com{'\n'}Ops admin with driver access: daniel@afrotransact.com{'\n'}No driver access: kofi@afrotransact.com</AppText>
        </View>
      ) : null}
    </DriverScreen>
  );
}

const styles = StyleSheet.create({
  head: { alignItems: 'center', gap: sp['3'], paddingVertical: sp['4'] },
  demo: { padding: sp['3'], borderRadius: 8, backgroundColor: c.surfaceMuted, gap: 4 }
});
