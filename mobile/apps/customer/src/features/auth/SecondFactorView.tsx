/* Second step of the in-app sign-in: the 6-digit code from the authenticator app or a text message (six boxes, submitted
   as soon as the sixth digit is in), or a recovery code (a normal text field). "Trust this device for 30 days" asks the
   BFF for a trusted-device token (contract: trustDevice → "skips the second factor for 30 days"). Text messages are only
   sent on request, with a 30 second wait before the next one. A challenge that expired sends the person back to the
   password step with the server's message. */
import { ArrowLeftIcon as ArrowLeft, ArrowRightIcon as ArrowRight, WarningCircleIcon as WarningCircle } from 'phosphor-react-native';
import { useEffect, useRef, useState } from 'react';
import { I18nManager, Pressable, StyleSheet, Switch, View } from 'react-native';

import { useAuth } from '@/auth/AuthProvider';
import { type Factors, type SecondFactor } from '@/domain/nativeSignIn';
import { isOtpComplete } from '@/domain/otp';
import { formatCooldown, RESEND_COOLDOWN_SECONDS } from '@/domain/resendCooldown';
import { describeSignInError } from '@/domain/signInErrors';
import { useT } from '@/i18n';
import { c, sp, t } from '@/theme';
import { OtpInput } from '@/ui/OtpInput';
import { AppText, Button, Field } from '@/ui/primitives';

import { type FactorChoice, FactorChoices } from './FactorChoices';
import { failureText } from './signInText';
import { useCooldown } from './useCooldown';

function firstFactor(f: Factors): SecondFactor {
  return f.totp ? 'TOTP' : f.sms ? 'SMS' : 'RECOVERY_CODE';
}

export function SecondFactorView({ factors, onBack }: { factors: Factors; onBack: (message?: string) => void }) {
  const tr = useT();
  const { verifySecondFactor, sendSignInCode } = useAuth();
  const [factor, setFactor] = useState<SecondFactor>(firstFactor(factors));
  const [code, setCode] = useState('');
  const [trust, setTrust] = useState(false);
  const [busy, setBusy] = useState(false);
  const [sending, setSending] = useState(false);
  const [sentTo, setSentTo] = useState('');
  const [readyAt, setReadyAt] = useState<number | null>(null);
  const [err, setErr] = useState('');
  const left = useCooldown(readyAt);
  const autoSent = useRef(false);
  const isOtp = factor !== 'RECOVERY_CODE';

  const fail = (e: unknown) => {
    const failure = describeSignInError(e);
    const text = failureText(tr, failure);
    if (failure.kind === 'expired') return onBack(text);
    setErr(text);
    if (failure.kind === 'invalid-code' || failure.kind === 'server') setCode('');
  };

  /** Texts a code. Inside the wait after the last one it only switches to the code that was already sent. */
  const text = async () => {
    setErr('');
    setCode('');
    if (left > 0 && readyAt !== null) {
      setFactor('SMS');
      return;
    }
    setSending(true);
    try {
      setSentTo(await sendSignInCode());
      setReadyAt(Date.now() + RESEND_COOLDOWN_SECONDS * 1000);
      setFactor('SMS');
    } catch (e) {
      fail(e);
    } finally {
      setSending(false);
    }
  };

  // Text-message-only accounts have no other code to type, so the first text goes out when the screen opens.
  useEffect(() => {
    if (autoSent.current || factors.totp || !factors.sms) return;
    autoSent.current = true;
    void text();
    // eslint-disable-next-line react-hooks/exhaustive-deps -- once, on open
  }, []);

  const submit = async (value: string) => {
    if (busy) return;
    if (isOtp ? !isOtpComplete(value) : !value.trim()) return setErr(tr('cu.mfa.enterCode'));
    setErr('');
    setBusy(true);
    try {
      await verifySecondFactor(factor, value, trust);
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  };

  const choose = (next: SecondFactor) => {
    setFactor(next);
    setCode('');
    setErr('');
  };

  const hint = sentTo || factors.smsHint || '';
  const heading = factor === 'RECOVERY_CODE' ? tr('cu.mfa.headingRecovery') : factor === 'SMS' ? tr('cu.mfa.headingSms') : tr('cu.mfa.headingTotp');
  const explanation = factor === 'RECOVERY_CODE' ? tr('cu.mfa.recoveryTitle') : factor === 'SMS' ? (hint ? tr('cu.mfa.smsTitle', { hint }) : tr('cu.mfa.smsTitleNoHint')) : tr('cu.mfa.totpTitle');

  const choices: FactorChoice[] = [];
  if (factors.sms && factor !== 'SMS') choices.push({ key: 'sms', label: tr('cu.mfa.textMe'), ...(factors.smsHint ? { detail: factors.smsHint } : {}), loading: sending, onPress: () => void text() });
  if (factors.totp && factor !== 'TOTP') choices.push({ key: 'totp', label: tr('cu.mfa.useAuthenticator'), onPress: () => choose('TOTP') });
  if (factors.recoveryCode && factor !== 'RECOVERY_CODE') choices.push({ key: 'recovery', label: tr('cu.mfa.useRecovery'), onPress: () => choose('RECOVERY_CODE') });

  const Back = I18nManager.isRTL ? ArrowRight : ArrowLeft;
  return (
    <View style={styles.root}>
      <Pressable accessibilityRole="button" accessibilityLabel={tr('cu.mfa.back')} onPress={() => onBack()} style={styles.back} hitSlop={12}>
        <Back size={20} color={c.text} />
        <AppText variant="small" style={{ fontWeight: t.fontWeight.semibold }}>{tr('cu.mfa.back')}</AppText>
      </Pressable>
      <View style={{ gap: sp['1'] }}>
        <AppText variant="small" color={c.textMuted} style={{ fontWeight: t.fontWeight.semibold }}>{tr('cu.mfa.title')}</AppText>
        <AppText variant="display" accessibilityRole="header">{heading}</AppText>
        <AppText variant="small" color={c.textMuted}>{explanation}</AppText>
      </View>
      <View style={{ gap: sp['2'] }}>
        {isOtp ? (
          // Remounted per factor so the new field takes focus and starts empty.
          <OtpInput key={factor} value={code} onChange={(v) => { setCode(v); setErr(''); }} onComplete={(v) => void submit(v)} error={!!err} disabled={busy} autoFocus />
        ) : (
          <Field
            label={tr('cu.mfa.recoveryCode')}
            value={code}
            onChangeText={(v) => { setCode(v); setErr(''); }}
            invalid={!!err}
            autoCapitalize="none"
            autoCorrect={false}
            spellCheck={false}
            maxLength={32}
            autoFocus
            editable={!busy}
            returnKeyType="go"
            onSubmitEditing={() => void submit(code)}
          />
        )}
        {err ? (
          <View style={styles.error} accessibilityRole="alert" accessibilityLiveRegion="assertive">
            <WarningCircle size={16} color={c.danger} />
            <AppText variant="small" color={c.danger} style={{ flex: 1 }}>{err}</AppText>
          </View>
        ) : null}
        {factor === 'SMS' ? (
          left > 0 ? (
            <AppText variant="small" color={c.textMuted}>{tr('cu.mfa.textAgainIn', { time: formatCooldown(left) })}</AppText>
          ) : (
            <Pressable accessibilityRole="button" accessibilityState={{ disabled: sending, busy: sending }} onPress={sending ? undefined : () => void text()} hitSlop={8} style={styles.again}>
              <AppText variant="small" style={styles.againText}>{tr('cu.mfa.textAgain')}</AppText>
            </Pressable>
          )
        ) : null}
      </View>
      <View style={styles.trust}>
        <AppText variant="small" style={{ flex: 1 }}>{tr('cu.mfa.trust')}</AppText>
        <Switch value={trust} onValueChange={setTrust} disabled={busy} accessibilityLabel={tr('cu.mfa.trust')} trackColor={{ false: c.borderStrong, true: c.success }} />
      </View>
      <Button label={tr('cu.mfa.verify')} onPress={() => void submit(code)} loading={busy} />
      <FactorChoices title={tr('cu.mfa.otherWays')} choices={choices} disabled={busy || sending} />
    </View>
  );
}

const styles = StyleSheet.create({
  root: { gap: sp['4'] },
  back: { flexDirection: 'row', alignItems: 'center', gap: sp['2'], minHeight: t.size.touch, alignSelf: 'flex-start' },
  error: { flexDirection: 'row', alignItems: 'center', gap: sp['2'] },
  again: { minHeight: t.size.touch, justifyContent: 'center', alignSelf: 'flex-start' },
  againText: { fontWeight: t.fontWeight.semibold, textDecorationLine: 'underline' },
  trust: { flexDirection: 'row', alignItems: 'center', gap: sp['3'], minHeight: t.size.touch, borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: c.borderStrong }
});
