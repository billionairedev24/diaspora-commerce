/* S163 launch screen: the app opens on sign-in (no guest mode). Credentials, MFA, passkeys and social sign-in run on
   identity's hosted pages via OIDC authorization code + PKCE; the email typed here is passed as login_hint. */
import * as WebBrowser from 'expo-web-browser';
import { AppleLogoIcon as AppleLogo, FacebookLogoIcon as FacebookLogo, FingerprintIcon as Fingerprint, GoogleLogoIcon as GoogleLogo, type Icon, InfoIcon as Info, WarningCircleIcon as WarningCircle } from 'phosphor-react-native';
import { useState } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';

import { useAuth } from '@/auth/AuthProvider';
import { SignInCancelled, type SignInIntent } from '@/auth/oidc';
import { env } from '@/config/env';
import { c, sp, t } from '@/theme';
import { Logo } from '@/ui/Logo';
import { AppText, Banner, Button, Field } from '@/ui/primitives';
import { Screen } from '@/ui/Screen';

const RE_EMAIL = /^[^\s@]+@[^\s@]+\.[a-z]{2,}$/i;

const SOCIALS: Array<{ provider: 'google' | 'apple' | 'facebook'; label: string; icon: Icon; color: string }> = [
  { provider: 'google', label: 'Google', icon: GoogleLogo, color: t.brand.googleBlue },
  { provider: 'apple', label: 'Apple', icon: AppleLogo, color: t.brand.apple },
  { provider: 'facebook', label: 'Facebook', icon: FacebookLogo, color: t.brand.facebook }
];

export default function SignIn() {
  const { signIn, flash } = useAuth();
  const [email, setEmail] = useState('');
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState<string | null>(null);

  const run = async (key: string, intent: SignInIntent) => {
    setErr('');
    setBusy(key);
    try {
      await signIn(intent);
    } catch (e) {
      if (!(e instanceof SignInCancelled)) setErr(e instanceof Error ? e.message : 'Sign-in failed. Try again.');
    } finally {
      setBusy(null);
    }
  };

  const submit = () => {
    const e = email.trim().toLowerCase();
    if (e && !RE_EMAIL.test(e)) return setErr('Enter a valid email address.');
    void run('email', { kind: 'sign-in', ...(e ? { loginHint: e } : {}) });
  };

  const open = (path: string) => void WebBrowser.openBrowserAsync(`${env.webUrl}${path}`);

  return (
    <Screen>
      <View style={styles.brand}>
        <Logo size={44} wordSize={t.fontSize['2xl']} />
        <AppText variant="small" color={c.textMuted}>Every diaspora’s market, one cart.</AppText>
      </View>
      <AppText variant="display" accessibilityRole="header">Sign in</AppText>
      {flash ? <Banner tone="info" icon={Info}>{flash}</Banner> : null}
      <Field label="Email" value={email} onChangeText={(v) => { setEmail(v); setErr(''); }} placeholder="you@example.com" keyboardType="email-address" autoCapitalize="none" autoComplete="email" textContentType="emailAddress" returnKeyType="go" onSubmitEditing={submit} />
      <AppText variant="small" color={c.textMuted}>You’ll enter your password and 2-step code on AfroTransact’s secure sign-in page.</AppText>
      <Pressable accessibilityRole="link" onPress={() => open('/signin?reset=1')}>
        <AppText variant="small" style={{ textDecorationLine: 'underline' }}>Forgot password?</AppText>
      </Pressable>
      {err ? <Banner tone="danger" icon={WarningCircle}>{err}</Banner> : null}
      <Button label="Sign in" onPress={submit} loading={busy === 'email'} disabled={!!busy && busy !== 'email'} />
      <Button kind="outline" icon={Fingerprint} label="Sign in with a passkey" onPress={() => void run('passkey', { kind: 'sign-in' })} loading={busy === 'passkey'} disabled={!!busy && busy !== 'passkey'} />
      <View style={styles.divider}>
        <View style={styles.rule} />
        <AppText variant="small" color={c.textMuted}>or continue with</AppText>
        <View style={styles.rule} />
      </View>
      <View style={styles.socials}>
        {SOCIALS.map((s) => (
          <Pressable key={s.provider} accessibilityRole="button" accessibilityLabel={`Continue with ${s.label}`} disabled={!!busy} onPress={() => void run(s.provider, { kind: 'provider', provider: s.provider })} style={styles.social}>
            <s.icon size={20} weight="fill" color={s.color} />
            <AppText variant="small" style={{ fontWeight: t.fontWeight.semibold }}>{s.label}</AppText>
          </Pressable>
        ))}
      </View>
      <View style={styles.newRow}>
        <AppText variant="small" color={c.textMuted}>New to AfroTransact?</AppText>
        <Button kind="outline" label="Create your account" onPress={() => void run('signup', { kind: 'sign-up' })} loading={busy === 'signup'} />
      </View>
      <AppText variant="caption" color={c.textMuted}>
        By continuing you agree to AfroTransact’s{' '}
        <AppText variant="caption" style={{ textDecorationLine: 'underline' }} onPress={() => open('/legal/terms')}>Conditions of Use</AppText> and{' '}
        <AppText variant="caption" style={{ textDecorationLine: 'underline' }} onPress={() => open('/legal/privacy')}>Privacy Notice</AppText>.
      </AppText>
      {env.mocks ? <AppText variant="caption" color={c.textSubtle}>Dev mocks are on: sign-in completes as adaeze@example.com.</AppText> : null}
    </Screen>
  );
}

const styles = StyleSheet.create({
  brand: { alignItems: 'center', gap: sp['2'], paddingVertical: sp['4'] },
  divider: { flexDirection: 'row', alignItems: 'center', gap: sp['3'] },
  rule: { flex: 1, height: 1, backgroundColor: c.border },
  socials: { flexDirection: 'row', gap: sp['2'] },
  social: { flex: 1, flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: sp['2'], height: t.size.controlLg, borderWidth: 1, borderColor: c.borderStrong, borderRadius: t.radius.pill },
  newRow: { gap: sp['2'], paddingTop: sp['2'] }
});
