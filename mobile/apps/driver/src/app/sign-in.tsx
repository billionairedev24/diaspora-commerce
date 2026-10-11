/* S081 "Sign in to drive": the work email is passed as login_hint; password, 6-digit code and passkeys run on
   identity's hosted pages (OIDC + PKCE, client driver-app).
   Welcome: full-bleed imagery (a clip or photo from assets/hero, else the drawn hand-over-at-the-door art) fading to
   black, with the sign-in form on a white sheet below it. */
import { StatusBar } from 'expo-status-bar';
import { FingerprintIcon as Fingerprint, WarningCircleIcon as WarningCircle } from 'phosphor-react-native';
import { useState } from 'react';
import { ScrollView, StyleSheet, useWindowDimensions, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { SignInCancelled } from '@/auth/oidc';
import { DriverHeroArt } from '@/brand/DriverHeroArt';
import { Hero } from '@/brand/Hero';
import heroPhoto from '@/brand/heroPhoto';
import heroVideo from '@/brand/heroVideo';
import { env } from '@/config/env';
import { useT } from '@/i18n';
import { useDriver } from '@/session/DriverProvider';
import { c, sp, t } from '@/theme';
import { Logo } from '@/ui/Logo';
import { AppText, Banner, Button, Field } from '@/ui/primitives';

export default function SignIn() {
  const tr = useT();
  const { signIn } = useDriver();
  const insets = useSafeAreaInsets();
  const { height } = useWindowDimensions();
  const [email, setEmail] = useState('');
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);
  const go = async () => {
    const e = email.trim().toLowerCase();
    if (e && !/^[^\s@]+@[^\s@]+\.[a-z]{2,}$/i.test(e)) return setErr(tr('dr.signIn.enterWorkEmail'));
    setErr('');
    setBusy(true);
    try {
      await signIn(e || undefined);
    } catch (x) {
      if (!(x instanceof SignInCancelled)) setErr(x instanceof Error ? x.message : tr('dr.signIn.failed'));
    } finally {
      setBusy(false);
    }
  };
  const heroHeight = Math.max(280, Math.round(height * 0.46));
  return (
    <View style={styles.root}>
      <StatusBar style="light" />
      <View style={{ height: heroHeight }}>
        <Hero photo={heroPhoto} video={heroVideo} art={<DriverHeroArt />} accessibilityLabel="A courier in a gold vest hands a parcel to a customer at their door">
          <View style={[styles.heroCopy, { paddingTop: insets.top + sp['3'] }]}>
            <Logo size={40} wordSize={t.fontSize.xl} tone="light" suffix="delivery" />
            <AppText style={styles.headline} color={c.textInverse} accessibilityRole="header">{tr('dr.signIn.signInToDrive')}</AppText>
          </View>
        </Hero>
      </View>
      <ScrollView style={styles.sheet} contentContainerStyle={[styles.sheetBody, { paddingBottom: insets.bottom + sp['6'] }]} showsVerticalScrollIndicator={false} keyboardShouldPersistTaps="handled" keyboardDismissMode="on-drag">
        <AppText color={c.textMuted}>{tr('dr.signIn.useYourAfrotransactStaffAccount')}</AppText>
        <Field label={tr('dr.signIn.workEmail')} value={email} onChangeText={(v) => { setEmail(v); setErr(''); }} placeholder={tr('dr.signIn.youAfrotransactCom')} keyboardType="email-address" autoCapitalize="none" autoComplete="username" onSubmitEditing={() => void go()} />
        <AppText variant="small" color={c.textMuted}>{tr('dr.signIn.yourPasswordAnd6Digit')}</AppText>
        {err ? <Banner tone="danger" icon={WarningCircle}>{err}</Banner> : null}
        <Button label={busy ? tr('dr.signIn.signingIn') : tr('dr.signIn.signIn')} loading={busy} onPress={() => void go()} />
        <Button kind="outline" icon={Fingerprint} label={tr('dr.signIn.signInWithPasskey')} disabled={busy} onPress={() => void go()} />
        {env.mocks ? (
          <View style={styles.demo}>
            <AppText variant="small" style={{ fontWeight: t.fontWeight.bold }}>{tr('dr.signIn.demoAccountsDevMocks')}</AppText>
            <AppText variant="small">Driver: tunde@afrotransact.com{'\n'}Ops admin with driver access: daniel@afrotransact.com{'\n'}No driver access: kofi@afrotransact.com</AppText>
          </View>
        ) : null}
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: t.ink['1000'] },
  heroCopy: { flex: 1, justifyContent: 'space-between', paddingHorizontal: sp['5'], paddingBottom: sp['10'] },
  headline: { fontSize: t.fontSize['4xl'], lineHeight: t.fontSize['4xl'] * t.leading.tight, fontWeight: t.fontWeight.black, letterSpacing: -0.55 },
  sheet: { flex: 1, marginTop: -sp['6'], backgroundColor: c.canvas, borderTopLeftRadius: t.radius.lg * 2, borderTopRightRadius: t.radius.lg * 2 },
  sheetBody: { paddingHorizontal: sp['4'], paddingTop: sp['6'], gap: sp['4'] },
  demo: { padding: sp['3'], borderRadius: t.radius.md, backgroundColor: c.surfaceMuted, gap: sp['1'] }
});
