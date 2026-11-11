/* Second step of the in-app sign-in (mandatory two-step verification): the 6-digit code from the authenticator app or a
   text message (six boxes, submits at the sixth digit), or a recovery code. "Trust this phone" is OFF by default for
   drivers; the server decides how long a trusted phone skips this step (identity: the trusted-device token's lifetime). */
import { ArrowLeftIcon as ArrowLeft, WarningCircleIcon as WarningCircle } from 'phosphor-react-native';
import { useEffect, useRef, useState } from 'react';
import { Pressable, StyleSheet, Switch, View } from 'react-native';

import { formatWait, SMS_RESEND_SECONDS } from '@/domain/cooldown';
import { type Factors, type SecondFactor } from '@/domain/nativeSignIn';
import { OTP_LENGTH } from '@/domain/otp';
import { classifySignInFailure, failureField, type SignInFailure } from '@/domain/signInFailure';
import { useT } from '@/i18n';
import { useDriver } from '@/session/DriverProvider';
import { c, sp, t } from '@/theme';
import { OtpInput } from '@/ui/OtpInput';
import { AppText, Banner, Button, Field } from '@/ui/primitives';
import { useCooldown } from '@/ui/useCooldown';

import { FactorRow } from './FactorRow';
import { failureMessage } from './failureText';

function firstFactor(f: Factors): SecondFactor {
  return f.totp ? 'TOTP' : f.emailOtp ? 'EMAIL_OTP' : f.sms ? 'SMS' : 'RECOVERY_CODE';
}

export function SecondFactorView({ factors, onBack }: { factors: Factors; onBack: (message?: string) => void }) {
  const tr = useT();
  const { verifySecondFactor, sendSignInCode, sendEmailCode } = useDriver();
  const [factor, setFactor] = useState<SecondFactor>(firstFactor(factors));
  const [code, setCode] = useState('');
  const [trust, setTrust] = useState(false);
  const [busy, setBusy] = useState(false);
  const [sending, setSending] = useState(false);
  const [sentTo, setSentTo] = useState('');
  const [sentEmailTo, setSentEmailTo] = useState('');
  const [failure, setFailure] = useState<SignInFailure | null>(null);
  const [focusKey, setFocusKey] = useState(0);
  const resend = useCooldown();
  const wait = useCooldown();
  const autoSent = useRef(false);

  const fail = (e: unknown, clear: boolean) => {
    const f = classifySignInFailure(e, 'code');
    if (f.kind === 'expired') return onBack(failureMessage(tr, f));
    if (f.kind === 'rate-limited' && f.retryAfterSeconds) wait.start(f.retryAfterSeconds);
    if (clear) { setCode(''); setFocusKey((k) => k + 1); }
    setFailure(f);
  };

  const sendText = async () => {
    setFailure(null);
    setSending(true);
    try {
      setSentTo(await sendSignInCode());
      resend.start(SMS_RESEND_SECONDS);
      setFactor('SMS');
      setCode('');
      setFocusKey((k) => k + 1);
    } catch (e) {
      fail(e, false);
    } finally {
      setSending(false);
    }
  };

  const sendEmail = async () => {
    setFailure(null);
    setSending(true);
    try {
      setSentEmailTo(await sendEmailCode());
      resend.start(SMS_RESEND_SECONDS);
      setFactor('EMAIL_OTP');
      setCode('');
      setFocusKey((k) => k + 1);
    } catch (e) {
      fail(e, false);
    } finally {
      setSending(false);
    }
  };

  // A driver whose only app-less factor is a text message gets the code without having to ask for it.
  useEffect(() => {
    if (factor === 'SMS' && !autoSent.current) {
      autoSent.current = true;
      void sendText();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const submit = async (value: string) => {
    const entered = value.trim();
    if (!entered) return setFailure({ kind: 'field-required', field: 'code' });
    setFailure(null);
    setBusy(true);
    try {
      await verifySecondFactor(factor, entered, trust);
    } catch (e) {
      fail(e, factor !== 'RECOVERY_CODE');
    } finally {
      setBusy(false);
    }
  };

  const choose = (next: SecondFactor) => {
    setFactor(next);
    setCode('');
    setFailure(null);
    setFocusKey((k) => k + 1);
  };

  const recovery = factor === 'RECOVERY_CODE';
  const limited = failure?.kind === 'rate-limited' && wait.left > 0;
  const inCode = failure ? failureField(failure) === 'code' : false;
  const text = failure ? failureMessage(tr, failure, wait.left) : '';
  const emailHint = sentEmailTo || factors.emailHint || '';
  const subtitle = recovery ? tr('dr.mfa.recoveryTitle') : factor === 'EMAIL_OTP' ? (emailHint ? tr('dr.mfa.emailTitle', { hint: emailHint }) : tr('dr.mfa.emailTitleNoHint')) : factor === 'SMS' ? tr('dr.mfa.smsTitle', { hint: sentTo || factors.smsHint || '' }) : tr('dr.mfa.totpTitle');
  const ready = recovery ? code.trim().length > 0 : code.length === OTP_LENGTH;

  return (
    <View style={styles.root}>
      <Pressable accessibilityRole="button" accessibilityLabel={tr('dr.mfa.back')} onPress={() => onBack()} style={styles.back} hitSlop={12}>
        <ArrowLeft size={20} color={c.text} />
        <AppText variant="small">{tr('dr.mfa.back')}</AppText>
      </Pressable>
      <View style={{ gap: sp['1'] }}>
        <AppText variant="display" accessibilityRole="header">{tr('dr.mfa.title')}</AppText>
        <AppText variant="small" color={c.textMuted}>{subtitle}</AppText>
      </View>
      {recovery ? (
        <Field
          label={tr('dr.mfa.recoveryCode')}
          value={code}
          onChangeText={(v) => { setCode(v); setFailure(null); }}
          error={inCode ? text : undefined}
          autoCapitalize="none"
          autoCorrect={false}
          maxLength={32}
          autoFocus
          returnKeyType="go"
          onSubmitEditing={() => void submit(code)}
        />
      ) : (
        <View style={{ gap: sp['2'] }}>
          <OtpInput value={code} onChange={(v) => { setCode(v); if (failure) setFailure(null); }} onComplete={(v) => void submit(v)} label={tr('dr.mfa.codeLabel')} hint={inCode ? text : undefined} error={inCode} disabled={busy} focusKey={focusKey} />
          {inCode ? <AppText variant="caption" color={c.danger} accessibilityRole="alert" accessibilityLiveRegion="polite">{text}</AppText> : null}
        </View>
      )}
      {failure && !inCode ? <Banner tone="danger" icon={WarningCircle}>{text}</Banner> : null}
      <View style={styles.trust}>
        <View style={{ flex: 1 }}>
          <AppText variant="bodyStrong">{tr('dr.mfa.trustTitle')}</AppText>
          <AppText variant="caption" color={c.textMuted}>{tr('dr.mfa.trustHint')}</AppText>
        </View>
        <Switch value={trust} onValueChange={setTrust} accessibilityLabel={tr('dr.mfa.trustTitle')} trackColor={{ true: c.success, false: t.ink['200'] }} thumbColor={c.surface} />
      </View>
      <Button label={limited ? tr('dr.signIn.tryAgainIn', { time: formatWait(wait.left) }) : tr('dr.mfa.verify')} onPress={() => void submit(code)} loading={busy} disabled={!ready || limited} />
      <View>
        {factors.totp && factor !== 'TOTP' ? <FactorRow label={tr('dr.mfa.useAuthenticator')} onPress={() => choose('TOTP')} /> : null}
        {factors.emailOtp && factor !== 'EMAIL_OTP' ? <FactorRow label={factors.totp ? tr('dr.mfa.useEmail') : tr('dr.mfa.emailMe')} hint={factors.emailHint} onPress={() => void sendEmail()} disabled={sending} /> : null}
        {factors.emailOtp && factor === 'EMAIL_OTP' ? <FactorRow label={resend.left > 0 ? tr('dr.mfa.sendAgainIn', { time: formatWait(resend.left) }) : tr('dr.mfa.emailAgain')} onPress={() => void sendEmail()} disabled={sending || resend.left > 0} /> : null}
        {factors.sms && factor !== 'SMS' ? <FactorRow label={tr('dr.mfa.textMe')} hint={factors.smsHint ? tr('dr.mfa.textMeHint', { hint: factors.smsHint }) : undefined} onPress={() => void sendText()} disabled={sending} /> : null}
        {factors.sms && factor === 'SMS' ? <FactorRow label={resend.left > 0 ? tr('dr.mfa.sendAgainIn', { time: formatWait(resend.left) }) : tr('dr.mfa.textAgain')} onPress={() => void sendText()} disabled={sending || resend.left > 0} /> : null}
        {factors.recoveryCode && !recovery ? <FactorRow label={tr('dr.mfa.useRecovery')} onPress={() => choose('RECOVERY_CODE')} /> : null}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { gap: sp['4'] },
  back: { flexDirection: 'row', alignItems: 'center', gap: sp['2'], minHeight: t.size.touch, alignSelf: 'flex-start' },
  trust: { flexDirection: 'row', alignItems: 'center', gap: sp['3'], paddingVertical: sp['1'] }
});
