/* S081 "Sign in to drive": work email, password and the 6-digit code are entered right here (BFF → identity first-party
   sign-in, then this app's own authorization-code + PKCE tokens, client driver-app); no hosted page or browser.
   Welcome: full-bleed imagery (a clip or photo from assets/hero, else the drawn hand-over-at-the-door art) fading to
   black, with the sign-in form on a white sheet below it. */
import { StatusBar } from 'expo-status-bar';
import { InfoIcon as Info, WarningCircleIcon as WarningCircle } from 'phosphor-react-native';
import { useState } from 'react';
import { Pressable, ScrollView, StyleSheet, useWindowDimensions, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { DriverHeroArt } from '@/brand/DriverHeroArt';
import { Hero } from '@/brand/Hero';
import heroPhoto from '@/brand/heroPhoto';
import heroVideo from '@/brand/heroVideo';
import { env } from '@/config/env';
import { type Factors, signInMessage } from '@/domain/nativeSignIn';
import { SecondFactorView } from '@/features/auth/SecondFactorView';
import { useT } from '@/i18n';
import { useDriver } from '@/session/DriverProvider';
import { c, sp, t } from '@/theme';
import { Logo } from '@/ui/Logo';
import { AppText, Banner, Button, Field } from '@/ui/primitives';
import { useKeyboardHeight } from '@/ui/useKeyboardHeight';

export default function SignIn() {
  const tr = useT();
  const { signInWithPassword, cancelSignIn, notice } = useDriver();
  const insets = useSafeAreaInsets();
  const { height } = useWindowDimensions();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [factors, setFactors] = useState<Factors | null>(null);
  const [message, setMessage] = useState('');
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);
  const go = async () => {
    const e = email.trim().toLowerCase();
    if (!/^[^\s@]+@[^\s@]+\.[a-z]{2,}$/i.test(e)) return setErr(tr('dr.signIn.enterWorkEmail'));
    if (!password) return setErr(tr('dr.signIn.enterPassword'));
    setErr('');
    setMessage('');
    setBusy(true);
    try {
      const step = await signInWithPassword(e, password);
      if (step.kind === 'second-factor') setFactors(step.factors);
    } catch (x) {
      setErr(signInMessage(x, tr('dr.signIn.failed')));
    } finally {
      setBusy(false);
    }
  };
  const backToPassword = (m?: string) => {
    cancelSignIn();
    setFactors(null);
    setPassword('');
    setMessage(m ?? '');
  };
  const keyboard = useKeyboardHeight();
  // With the keyboard up the picture gives way to the form, and the form lifts above the keyboard.
  const heroHeight = keyboard > 0 ? insets.top + 96 : Math.max(280, Math.round(height * 0.46));
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
      <ScrollView style={styles.sheet} contentContainerStyle={[styles.sheetBody, { paddingBottom: Math.max(insets.bottom, keyboard) + sp['6'] }]} showsVerticalScrollIndicator={false} keyboardShouldPersistTaps="handled" keyboardDismissMode="on-drag">
        {factors ? <SecondFactorView factors={factors} onBack={backToPassword} /> : (<>
        <AppText color={c.textMuted}>{tr('dr.signIn.useYourAfrotransactStaffAccount')}</AppText>
        {notice ? <Banner tone="info" icon={Info}>{notice}</Banner> : null}
        {message ? <Banner tone="info" icon={Info}>{message}</Banner> : null}
        <Field label={tr('dr.signIn.workEmail')} value={email} onChangeText={(v) => { setEmail(v); setErr(''); }} placeholder={tr('dr.signIn.youAfrotransactCom')} keyboardType="email-address" autoCapitalize="none" autoCorrect={false} autoComplete="username" textContentType="username" returnKeyType="next" />
        <Field label={tr('dr.signIn.password')} value={password} onChangeText={(v) => { setPassword(v); setErr(''); }} secureTextEntry={!showPassword} autoCapitalize="none" autoCorrect={false} autoComplete="current-password" textContentType="password" returnKeyType="go" onSubmitEditing={() => void go()} />
        <Pressable accessibilityRole="button" onPress={() => setShowPassword((v) => !v)} hitSlop={10}>
          <AppText variant="small" style={{ textDecorationLine: 'underline' }}>{showPassword ? tr('dr.signIn.hidePassword') : tr('dr.signIn.showPassword')}</AppText>
        </Pressable>
        {err ? <Banner tone="danger" icon={WarningCircle}>{err}</Banner> : null}
        <Button label={busy ? tr('dr.signIn.signingIn') : tr('dr.signIn.signIn')} loading={busy} onPress={() => void go()} />
        </>)}
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
