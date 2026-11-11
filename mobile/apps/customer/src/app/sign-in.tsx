/* S163 launch screen: the app opens on sign-in (no guest mode). Email, password and the 2-step code are entered right
   here (BFF → identity first-party sign-in, then this app's own authorization-code + PKCE tokens); only social
   providers, passkeys, creating an account and "Forgot password?" open identity's secure page. A first sign-in on the
   phone continues into setup (Face ID, passcode, notifications).
   Layout: the brand picture fading to black on top (it gives way while the keyboard is up), the form on a white sheet
   with a rounded top. The pieces live in features/auth: SignInHero, SignInForm, ProviderButtons, SignInFooter and
   SecondFactorView. */
import { StatusBar } from 'expo-status-bar';
import { useState } from 'react';
import { ScrollView, StyleSheet, useWindowDimensions, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { useAuth } from '@/auth/AuthProvider';
import { type EnrolMethod, type Factors } from '@/domain/nativeSignIn';
import { EnrolView } from '@/features/auth/EnrolView';
import { SecondFactorView } from '@/features/auth/SecondFactorView';
import { SignInForm } from '@/features/auth/SignInForm';
import { SignInHero } from '@/features/auth/SignInHero';
import { c, sp, t } from '@/theme';
import { useKeyboardHeight } from '@/ui/useKeyboardHeight';

export default function SignIn() {
  const { cancelSignIn } = useAuth();
  const [factors, setFactors] = useState<Factors | null>(null);
  const [enrol, setEnrol] = useState<EnrolMethod[] | null>(null);
  const [notice, setNotice] = useState('');
  const insets = useSafeAreaInsets();
  const { height } = useWindowDimensions();
  const keyboard = useKeyboardHeight();

  const backToPassword = (message?: string) => {
    cancelSignIn();
    setFactors(null);
    setEnrol(null);
    setNotice(message ?? '');
  };

  // With the keyboard up the picture gives way to the form, and the form lifts above the keyboard.
  const compact = keyboard > 0;
  const heroHeight = compact ? insets.top + 96 : Math.max(280, Math.round(height * 0.46));

  return (
    <View style={styles.root}>
      <StatusBar style="light" />
      <SignInHero height={heroHeight} topInset={insets.top} compact={compact} />
      <ScrollView
        style={styles.sheet}
        contentContainerStyle={[styles.sheetBody, { paddingBottom: Math.max(insets.bottom, keyboard) + sp['6'] }]}
        showsVerticalScrollIndicator={false}
        keyboardShouldPersistTaps="handled"
        keyboardDismissMode="on-drag"
      >
        {enrol ? <EnrolView methods={enrol} onBack={backToPassword} /> : factors ? <SecondFactorView factors={factors} onBack={backToPassword} /> : <SignInForm notice={notice} onSecondFactor={setFactors} onEnrol={setEnrol} />}
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: t.ink['1000'] },
  sheet: { flex: 1, marginTop: -sp['6'], backgroundColor: c.canvas, borderTopLeftRadius: t.radius.lg * 2, borderTopRightRadius: t.radius.lg * 2 },
  sheetBody: { paddingHorizontal: sp['4'], paddingTop: sp['6'] }
});
