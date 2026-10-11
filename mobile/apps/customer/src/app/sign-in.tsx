/* S163 launch screen: the app opens on sign-in (no guest mode). Credentials, MFA, passkeys and social sign-in run on
   identity's hosted pages via OIDC authorization code + PKCE; the email typed here is passed as login_hint.
   Welcome: full-bleed brand imagery (photo from assets/hero or the drawn Sunday-table art) fading to black, with the
   sign-in form on a white sheet below it. */
import { StatusBar } from 'expo-status-bar';
import * as WebBrowser from 'expo-web-browser';
import { AppleLogoIcon as AppleLogo, FacebookLogoIcon as FacebookLogo, FingerprintIcon as Fingerprint, GoogleLogoIcon as GoogleLogo, type Icon, InfoIcon as Info, WarningCircleIcon as WarningCircle } from 'phosphor-react-native';
import { useState } from 'react';
import { Pressable, ScrollView, StyleSheet, useWindowDimensions, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { useAuth } from '@/auth/AuthProvider';
import { SignInCancelled, type SignInIntent } from '@/auth/oidc';
import { CustomerHeroArt } from '@/brand/CustomerHeroArt';
import { Hero } from '@/brand/Hero';
import heroPhoto from '@/brand/heroPhoto';
import { env } from '@/config/env';
import { useT } from '@/i18n';
import { c, sp, t } from '@/theme';
import { Logo } from '@/ui/Logo';
import { AppText, Banner, Button, Field, MAX_FONT_SCALE } from '@/ui/primitives';

const RE_EMAIL = /^[^\s@]+@[^\s@]+\.[a-z]{2,}$/i;

const SOCIALS: Array<{ provider: 'google' | 'apple' | 'facebook'; label: string; icon: Icon; color: string }> = [
  { provider: 'google', label: 'Google', icon: GoogleLogo, color: t.brand.googleBlue },
  { provider: 'apple', label: 'Apple', icon: AppleLogo, color: t.brand.apple },
  { provider: 'facebook', label: 'Facebook', icon: FacebookLogo, color: t.brand.facebook }
];

export default function SignIn() {
  const tr = useT();
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
      if (!(e instanceof SignInCancelled)) setErr(e instanceof Error ? e.message : tr('cu.signIn.failed'));
    } finally {
      setBusy(null);
    }
  };

  const submit = () => {
    const e = email.trim().toLowerCase();
    if (e && !RE_EMAIL.test(e)) return setErr(tr('cu.signIn.invalidEmail'));
    void run('email', { kind: 'sign-in', ...(e ? { loginHint: e } : {}) });
  };

  const open = (path: string) => void WebBrowser.openBrowserAsync(`${env.webUrl}${path}`);
  const insets = useSafeAreaInsets();
  const { height } = useWindowDimensions();
  const heroHeight = Math.max(280, Math.round(height * 0.46));

  return (
    <View style={styles.root}>
      <StatusBar style="light" />
      <View style={{ height: heroHeight }}>
        <Hero photo={heroPhoto} art={<CustomerHeroArt />} accessibilityLabel="A table set with groceries from the AfroTransact bag and a pot of jollof">
          <View style={[styles.heroCopy, { paddingTop: insets.top + sp['3'] }]}>
            <Logo size={40} wordSize={t.fontSize.xl} tone="light" />
            <View style={styles.heroText}>
              <AppText maxFontSizeMultiplier={MAX_FONT_SCALE} style={styles.headline} color={c.textInverse} accessibilityRole="header">{tr('cu.signIn.everyDiasporaSMarketOne')}</AppText>
            </View>
          </View>
        </Hero>
      </View>
      <ScrollView style={styles.sheet} contentContainerStyle={[styles.sheetBody, { paddingBottom: insets.bottom + sp['6'] }]} showsVerticalScrollIndicator={false} keyboardShouldPersistTaps="handled" keyboardDismissMode="on-drag">
      <AppText variant="display">{tr('cu.signIn.signIn')}</AppText>
      {flash ? <Banner tone="info" icon={Info}>{flash}</Banner> : null}
      <Field label={tr('cu.signIn.email')} value={email} onChangeText={(v) => { setEmail(v); setErr(''); }} placeholder={tr('cu.signIn.youExampleCom')} keyboardType="email-address" autoCapitalize="none" autoComplete="email" textContentType="emailAddress" returnKeyType="go" onSubmitEditing={submit} />
      <AppText variant="small" color={c.textMuted}>{tr('cu.signIn.youLlEnterYourPassword')}</AppText>
      <Pressable accessibilityRole="link" onPress={() => open('/signin?reset=1')}>
        <AppText variant="small" style={{ textDecorationLine: 'underline' }}>{tr('cu.signIn.forgotPassword')}</AppText>
      </Pressable>
      {err ? <Banner tone="danger" icon={WarningCircle}>{err}</Banner> : null}
      <Button label={tr('cu.signIn.signIn')} onPress={submit} loading={busy === 'email'} disabled={!!busy && busy !== 'email'} />
      <Button kind="outline" icon={Fingerprint} label={tr('cu.signIn.signInWithAPasskey')} onPress={() => void run('passkey', { kind: 'sign-in' })} loading={busy === 'passkey'} disabled={!!busy && busy !== 'passkey'} />
      <View style={styles.divider}>
        <View style={styles.rule} />
        <AppText variant="small" color={c.textMuted}>{tr('cu.signIn.orContinueWith')}</AppText>
        <View style={styles.rule} />
      </View>
      <View style={styles.socials}>
        {SOCIALS.map((s) => (
          <Pressable key={s.provider} accessibilityRole="button" accessibilityLabel={tr('cu.signIn.continueWith', { provider: s.label })} disabled={!!busy} onPress={() => void run(s.provider, { kind: 'provider', provider: s.provider })} style={styles.social}>
            <s.icon size={20} weight="fill" color={s.color} />
            <AppText variant="small" style={{ fontWeight: t.fontWeight.semibold }}>{s.label}</AppText>
          </Pressable>
        ))}
      </View>
      <View style={styles.newRow}>
        <AppText variant="small" color={c.textMuted}>{tr('cu.signIn.newToAfrotransact')}</AppText>
        <Button kind="outline" label={tr('cu.signIn.createYourAccount')} onPress={() => void run('signup', { kind: 'sign-up' })} loading={busy === 'signup'} />
      </View>
      <AppText variant="caption" color={c.textMuted}>
        {tr('cu.signIn.agreePrefix')}{' '}
        <AppText variant="caption" style={{ textDecorationLine: 'underline' }} onPress={() => open('/legal/conditions-of-use')}>{tr('cu.signIn.conditionsOfUse')}</AppText> {tr('cu.signIn.and')}{' '}
        <AppText variant="caption" style={{ textDecorationLine: 'underline' }} onPress={() => open('/legal/privacy')}>{tr('cu.signIn.privacyNotice')}</AppText>.
      </AppText>
      {env.mocks ? <AppText variant="caption" color={c.textSubtle}>{tr('cu.signIn.devMocksAreOnSign')}</AppText> : null}
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: t.ink['1000'] },
  heroCopy: { flex: 1, justifyContent: 'space-between', paddingHorizontal: sp['5'], paddingBottom: sp['10'] },
  heroText: { gap: sp['2'] },
  headline: { fontSize: t.fontSize['3xl'], lineHeight: t.fontSize['3xl'] * t.leading.tight, fontWeight: t.fontWeight.black, letterSpacing: -0.45 },
  sheet: { flex: 1, marginTop: -sp['6'], backgroundColor: c.canvas, borderTopLeftRadius: t.radius.lg * 2, borderTopRightRadius: t.radius.lg * 2 },
  sheetBody: { paddingHorizontal: sp['4'], paddingTop: sp['6'], gap: sp['4'] },
  divider: { flexDirection: 'row', alignItems: 'center', gap: sp['3'] },
  rule: { flex: 1, height: 1, backgroundColor: c.border },
  socials: { flexDirection: 'row', gap: sp['2'] },
  social: { flex: 1, flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: sp['2'], minHeight: t.size.controlLg, borderWidth: 1, borderColor: c.borderStrong, borderRadius: t.radius.pill },
  newRow: { gap: sp['2'], paddingTop: sp['2'] }
});
