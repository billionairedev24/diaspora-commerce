/* Step one of sign-in: work email + password. Errors are inline and specific (field highlight + a sentence, never a
   dialog). A rate limit disables the button with the wait the server sent, when it sent one. */
import { InfoIcon as Info, WarningCircleIcon as WarningCircle } from 'phosphor-react-native';
import { useRef, useState } from 'react';
import { type TextInput, View } from 'react-native';

import { formatWait } from '@/domain/cooldown';
import { type EnrolMethod, type Factors } from '@/domain/nativeSignIn';
import { classifySignInFailure, failureField, type SignInFailure } from '@/domain/signInFailure';
import { useT } from '@/i18n';
import { useDriver } from '@/session/DriverProvider';
import { c, sp } from '@/theme';
import { AppText, Banner, Button, Field } from '@/ui/primitives';
import { useCooldown } from '@/ui/useCooldown';

import { DispatchHelp } from './DispatchHelp';
import { failureMessage } from './failureText';
import { PasswordField } from './PasswordField';

const EMAIL = /^[^\s@]+@[^\s@]+\.[a-z]{2,}$/i;

export function PasswordForm({ onSecondFactor, onEnrol, message }: { onSecondFactor: (factors: Factors) => void; onEnrol: (methods: EnrolMethod[]) => void; message?: string }) {
  const tr = useT();
  const { signInWithPassword, notice } = useDriver();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [failure, setFailure] = useState<SignInFailure | null>(null);
  const [busy, setBusy] = useState(false);
  const wait = useCooldown();
  const passwordRef = useRef<TextInput>(null);

  const go = async () => {
    const e = email.trim().toLowerCase();
    if (!EMAIL.test(e)) return setFailure({ kind: 'field-required', field: 'email' });
    if (!password) return setFailure({ kind: 'field-required', field: 'password' });
    setFailure(null);
    setBusy(true);
    try {
      const step = await signInWithPassword(e, password);
      if (step.kind === 'second-factor') onSecondFactor(step.factors);
      else if (step.kind === 'enrol') onEnrol(step.methods);
    } catch (x) {
      const f = classifySignInFailure(x, 'password');
      if (f.kind === 'rate-limited' && f.retryAfterSeconds) wait.start(f.retryAfterSeconds);
      if (f.kind === 'credentials') setPassword('');
      setFailure(f);
    } finally {
      setBusy(false);
    }
  };

  const field = failure ? failureField(failure) : null;
  const text = failure ? failureMessage(tr, failure, wait.left) : '';
  const limited = failure?.kind === 'rate-limited' && wait.left > 0;
  // Field-level problems sit under their field; everything else is one banner above the button.
  const emailError = field === 'email' ? text : undefined;
  const passwordError = field === 'password' ? text : undefined;
  const banner = failure && !field ? text : '';
  const info = notice || message;

  return (
    <View style={{ gap: sp['4'] }}>
      <AppText color={c.textMuted}>{tr('dr.signIn.staffAccount')}</AppText>
      {info ? <Banner tone="info" icon={Info}>{info}</Banner> : null}
      <Field
        label={tr('dr.signIn.workEmail')}
        value={email}
        onChangeText={(v) => { setEmail(v); if (failure) setFailure(null); }}
        placeholder={tr('dr.signIn.youAfrotransactCom')}
        error={emailError}
        // A wrong email or password: the server does not say which, so both fields are marked.
        style={failure?.kind === 'credentials' ? { borderColor: c.danger, borderWidth: 2 } : undefined}
        keyboardType="email-address"
        autoCapitalize="none"
        autoCorrect={false}
        autoComplete="username"
        textContentType="username"
        returnKeyType="next"
        onSubmitEditing={() => passwordRef.current?.focus()}
      />
      <PasswordField
        ref={passwordRef}
        label={tr('dr.signIn.password')}
        value={password}
        onChangeText={(v) => { setPassword(v); if (failure) setFailure(null); }}
        error={passwordError}
        returnKeyType="go"
        onSubmitEditing={() => void go()}
      />
      {banner ? <Banner tone="danger" icon={WarningCircle}>{banner}</Banner> : null}
      <Button label={limited ? tr('dr.signIn.tryAgainIn', { time: formatWait(wait.left) }) : busy ? tr('dr.signIn.signingIn') : tr('dr.signIn.signIn')} loading={busy} disabled={limited} onPress={() => void go()} />
      <DispatchHelp />
    </View>
  );
}
