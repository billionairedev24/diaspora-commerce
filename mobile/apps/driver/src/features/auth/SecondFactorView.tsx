/* Second step of the in-app sign-in: the 6-digit code from the authenticator app or a text message, or a recovery
   code. "Don't ask again on this phone" keeps a trusted-device token (30 days). */
import { ArrowLeftIcon as ArrowLeft, WarningCircleIcon as WarningCircle } from 'phosphor-react-native';
import { useState } from 'react';
import { Pressable, StyleSheet, Switch, View } from 'react-native';

import { type Factors, isChallengeExpired, type SecondFactor, signInMessage } from '@/domain/nativeSignIn';
import { useT } from '@/i18n';
import { useDriver } from '@/session/DriverProvider';
import { c, sp, t } from '@/theme';
import { AppText, Banner, Button, Field } from '@/ui/primitives';

function firstFactor(f: Factors): SecondFactor {
  return f.totp ? 'TOTP' : f.sms ? 'SMS' : 'RECOVERY_CODE';
}

export function SecondFactorView({ factors, onBack }: { factors: Factors; onBack: (message?: string) => void }) {
  const tr = useT();
  const { verifySecondFactor, sendSignInCode } = useDriver();
  const [factor, setFactor] = useState<SecondFactor>(firstFactor(factors));
  const [code, setCode] = useState('');
  const [trust, setTrust] = useState(true);
  const [busy, setBusy] = useState(false);
  const [sending, setSending] = useState(false);
  const [sentTo, setSentTo] = useState('');
  const [err, setErr] = useState('');

  const fail = (e: unknown) => {
    if (isChallengeExpired(e)) return onBack(signInMessage(e, tr('dr.signIn.failed')));
    setErr(signInMessage(e, tr('dr.signIn.failed')));
  };

  const text = async () => {
    setErr('');
    setSending(true);
    try {
      setFactor('SMS');
      setSentTo(await sendSignInCode());
    } catch (e) {
      fail(e);
    } finally {
      setSending(false);
    }
  };

  const submit = async () => {
    if (!code.trim()) return setErr(tr('dr.mfa.enterCode'));
    setErr('');
    setBusy(true);
    try {
      await verifySecondFactor(factor, code, trust);
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  };

  const title = factor === 'RECOVERY_CODE' ? tr('dr.mfa.recoveryTitle') : factor === 'SMS' ? tr('dr.mfa.smsTitle', { hint: sentTo || factors.smsHint || '' }) : tr('dr.mfa.totpTitle');

  return (
    <View style={styles.root}>
      <Pressable accessibilityRole="button" accessibilityLabel={tr('dr.mfa.back')} onPress={() => onBack()} style={styles.back} hitSlop={12}>
        <ArrowLeft size={20} color={c.text} />
        <AppText variant="small">{tr('dr.mfa.back')}</AppText>
      </Pressable>
      <AppText variant="display" accessibilityRole="header">{tr('dr.mfa.title')}</AppText>
      <AppText variant="small" color={c.textMuted}>{title}</AppText>
      <Field
        label={factor === 'RECOVERY_CODE' ? tr('dr.mfa.recoveryCode') : tr('dr.mfa.code')}
        value={code}
        onChangeText={(v) => { setCode(v); setErr(''); }}
        keyboardType={factor === 'RECOVERY_CODE' ? 'default' : 'number-pad'}
        autoCapitalize="none"
        autoCorrect={false}
        autoComplete="one-time-code"
        textContentType="oneTimeCode"
        maxLength={factor === 'RECOVERY_CODE' ? 32 : 8}
        autoFocus
        returnKeyType="go"
        onSubmitEditing={() => void submit()}
      />
      {err ? <Banner tone="danger" icon={WarningCircle}>{err}</Banner> : null}
      <View style={styles.trust}>
        <AppText variant="small" style={{ flex: 1 }}>{tr('dr.mfa.trust')}</AppText>
        <Switch value={trust} onValueChange={setTrust} accessibilityLabel={tr('dr.mfa.trust')} />
      </View>
      <Button label={tr('dr.mfa.verify')} onPress={() => void submit()} loading={busy} />
      {factors.sms && factor !== 'SMS' ? <Button kind="outline" label={tr('dr.mfa.textMe')} onPress={() => void text()} loading={sending} /> : null}
      {factors.sms && factor === 'SMS' ? <Button kind="outline" label={tr('dr.mfa.textAgain')} onPress={() => void text()} loading={sending} /> : null}
      {factors.totp && factor !== 'TOTP' ? <Button kind="outline" label={tr('dr.mfa.useAuthenticator')} onPress={() => { setFactor('TOTP'); setCode(''); setErr(''); }} /> : null}
      {factors.recoveryCode && factor !== 'RECOVERY_CODE' ? (
        <Pressable accessibilityRole="button" onPress={() => { setFactor('RECOVERY_CODE'); setCode(''); setErr(''); }}>
          <AppText variant="small" style={{ textDecorationLine: 'underline' }}>{tr('dr.mfa.useRecovery')}</AppText>
        </Pressable>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  root: { gap: sp['4'] },
  back: { flexDirection: 'row', alignItems: 'center', gap: sp['2'], minHeight: t.size.touch },
  trust: { flexDirection: 'row', alignItems: 'center', gap: sp['3'] }
});
