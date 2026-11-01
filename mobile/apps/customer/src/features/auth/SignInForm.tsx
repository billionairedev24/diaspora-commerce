/* The sign-in sheet's content: heading, email, password (Show/Hide inside the field), "Forgot password?", the primary
   Sign in button, then providers + passkey and the create-account / legal footer. Errors are inline: the field that is
   wrong is marked and says why; form-wide problems (rate limit, no connection) sit above the button. Only the control
   that was pressed shows busy, the rest are disabled. Nothing typed here is logged or kept beyond this component. */
import { InfoIcon as Info, WarningCircleIcon as WarningCircle } from 'phosphor-react-native';
import { useRef, useState } from 'react';
import { Pressable, StyleSheet, type TextInput, View } from 'react-native';

import { type SignOutFlash, useAuth } from '@/auth/AuthProvider';
import { SignInCancelled, type SignInIntent } from '@/auth/oidc';
import { describeSignInError, isValidEmail } from '@/domain/signInErrors';
import { type Factors } from '@/domain/nativeSignIn';
import { type MessageKey, useT } from '@/i18n';
import { sp, t } from '@/theme';
import { PasswordField } from '@/ui/PasswordField';
import { AppText, Banner, Button, Field } from '@/ui/primitives';

import { openWeb } from './openWeb';
import { ProviderButtons } from './ProviderButtons';
import { SignInFooter } from './SignInFooter';
import { failureText } from './signInText';

const FLASH_KEY: Record<SignOutFlash['code'], MessageKey> = {
  'passcode-locked': 'cu.flash.passcodeLocked',
  'passcode-reset': 'cu.flash.passcodeReset',
  'session-ended': 'cu.flash.sessionEnded',
  'all-devices': 'cu.flash.allDevices'
};

interface Errors {
  email?: string;
  password?: string;
  /** Marked without a message of its own. */
  markEmail?: boolean;
  form?: string;
}

export function SignInForm({ notice, onSecondFactor }: { notice: string; onSecondFactor: (factors: Factors) => void }) {
  const tr = useT();
  const { signIn, signInWithPassword, flash } = useAuth();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [errors, setErrors] = useState<Errors>({});
  const [busy, setBusy] = useState<string | null>(null);
  const passwordRef = useRef<TextInput>(null);

  /** Hosted identity page: providers, passkey, create account. */
  const run = async (key: string, intent: SignInIntent) => {
    setErrors({});
    setBusy(key);
    try {
      await signIn(intent);
    } catch (e) {
      if (!(e instanceof SignInCancelled)) setErrors({ form: e instanceof Error && e.message ? e.message : tr('cu.signIn.failed') });
    } finally {
      setBusy(null);
    }
  };

  const submit = async () => {
    if (busy) return;
    const e = email.trim().toLowerCase();
    if (!isValidEmail(e)) return setErrors({ email: tr('cu.signIn.invalidEmail') });
    if (!password) return setErrors({ password: tr('cu.signIn.enterPassword') });
    setErrors({});
    setBusy('email');
    try {
      const step = await signInWithPassword(e, password);
      if (step.kind === 'second-factor') onSecondFactor(step.factors);
    } catch (x) {
      const failure = describeSignInError(x);
      const text = failureText(tr, failure);
      if (failure.kind === 'credentials') setErrors({ markEmail: true, password: text });
      else if (failure.fields.includes('email')) setErrors({ email: text });
      else if (failure.fields.includes('password')) setErrors({ password: text });
      else setErrors({ form: text });
    } finally {
      setBusy(null);
    }
  };

  const off = busy !== null;
  return (
    <View style={styles.root}>
      <AppText variant="display" accessibilityRole="header">{tr('cu.signIn.signIn')}</AppText>
      {flash ? <Banner tone="info" icon={Info}>{`${tr(FLASH_KEY[flash.code])}${flash.detail ? ` (${flash.detail})` : ''}`}</Banner> : null}
      {notice ? <Banner tone="info" icon={Info}>{notice}</Banner> : null}
      <View style={{ gap: sp['3'] }}>
        <Field
          label={tr('cu.signIn.email')}
          value={email}
          onChangeText={(v) => { setEmail(v); setErrors({}); }}
          error={errors.email}
          invalid={errors.markEmail}
          placeholder={tr('cu.signIn.youExampleCom')}
          keyboardType="email-address"
          autoCapitalize="none"
          autoCorrect={false}
          autoComplete="email"
          textContentType="username"
          editable={!off}
          returnKeyType="next"
          submitBehavior="submit"
          onSubmitEditing={() => passwordRef.current?.focus()}
        />
        <PasswordField
          label={tr('cu.signIn.password')}
          value={password}
          onChangeText={(v) => { setPassword(v); setErrors({}); }}
          error={errors.password}
          editable={!off}
          inputRef={passwordRef}
          returnKeyType="go"
          onSubmitEditing={() => void submit()}
        />
        <Pressable accessibilityRole="link" onPress={() => openWeb('/signin?reset=1')} hitSlop={8} style={styles.forgot}>
          <AppText variant="small" style={styles.forgotText}>{tr('cu.signIn.forgotPassword')}</AppText>
        </Pressable>
      </View>
      {errors.form ? <Banner tone="danger" icon={WarningCircle}>{errors.form}</Banner> : null}
      <Button label={tr('cu.signIn.signIn')} onPress={() => void submit()} loading={busy === 'email'} disabled={off && busy !== 'email'} />
      <ProviderButtons busy={busy} onRun={(k, i) => void run(k, i)} />
      <SignInFooter busy={busy} onRun={(k, i) => void run(k, i)} />
    </View>
  );
}

const styles = StyleSheet.create({
  root: { gap: sp['4'] },
  forgot: { alignSelf: 'flex-end', minHeight: t.size.touch, justifyContent: 'center' },
  forgotText: { fontWeight: t.fontWeight.semibold, textDecorationLine: 'underline' }
});
