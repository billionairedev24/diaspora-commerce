/* S081 "Sign in to drive": work email, password and the two-step code are entered right here (BFF → identity first-party
   sign-in, then this app's own authorization-code + PKCE tokens, client driver-app); no hosted page or browser.
   Welcome: full-bleed imagery (a clip or photo from assets/hero, else the drawn hand-over-at-the-door art) fading to
   black, with the form on a white sheet below it. Drivers are staff: no social sign-in, no create account, and no
   in-app password reset (help goes to dispatch). After the first sign-in on a phone the app lock setup follows. */
import { StatusBar } from 'expo-status-bar';
import { useState } from 'react';
import { ScrollView, StyleSheet, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { env } from '@/config/env';
import { type Factors } from '@/domain/nativeSignIn';
import { PasswordForm } from '@/features/auth/PasswordForm';
import { SecondFactorView } from '@/features/auth/SecondFactorView';
import { SignInHero, useHeroHeight } from '@/features/auth/SignInHero';
import { useT } from '@/i18n';
import { useDriver } from '@/session/DriverProvider';
import { c, sp, t } from '@/theme';
import { AppText } from '@/ui/primitives';
import { useKeyboardHeight } from '@/ui/useKeyboardHeight';

export default function SignIn() {
  const tr = useT();
  const { cancelSignIn } = useDriver();
  const insets = useSafeAreaInsets();
  const keyboard = useKeyboardHeight();
  const [factors, setFactors] = useState<Factors | null>(null);
  const [message, setMessage] = useState('');
  const backToPassword = (m?: string) => {
    cancelSignIn();
    setFactors(null);
    setMessage(m ?? '');
  };
  // With the keyboard up the picture gives way to the form, and the form lifts above the keyboard.
  const heroHeight = useHeroHeight(keyboard > 0 || factors !== null);
  return (
    <View style={styles.root}>
      <StatusBar style="light" />
      <SignInHero height={heroHeight} title={tr('dr.signIn.signInToDrive')} />
      <ScrollView style={styles.sheet} contentContainerStyle={[styles.sheetBody, { paddingBottom: Math.max(insets.bottom, keyboard) + sp['6'] }]} showsVerticalScrollIndicator={false} keyboardShouldPersistTaps="handled" keyboardDismissMode="on-drag">
        {factors ? <SecondFactorView factors={factors} onBack={backToPassword} /> : <PasswordForm onSecondFactor={setFactors} message={message} />}
        {env.mocks ? (
          <View style={styles.demo}>
            <AppText variant="small" style={{ fontWeight: t.fontWeight.bold }}>{tr('dr.signIn.demoAccountsDevMocks')}</AppText>
            <AppText variant="small">{tr('dr.signIn.demoAccounts')}</AppText>
          </View>
        ) : null}
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: t.ink['1000'] },
  sheet: { flex: 1, marginTop: -sp['6'], backgroundColor: c.canvas, borderTopLeftRadius: t.radius.lg * 2, borderTopRightRadius: t.radius.lg * 2 },
  sheetBody: { paddingHorizontal: sp['4'], paddingTop: sp['6'], gap: sp['4'] },
  demo: { padding: sp['3'], borderRadius: t.radius.md, backgroundColor: c.surfaceMuted, gap: sp['1'] }
});
