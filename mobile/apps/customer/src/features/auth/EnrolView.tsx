/* Forced two-step setup after the password: mandatory two-step verification (staff, or a portal setting) and nothing is
   set up yet. No code was sent. The person picks an authenticator app (setup key, or "Open in authenticator app" which
   hands the otpauth link to the installed app) or email codes (a first code is emailed to the verified address), confirms
   a 6-digit code, sees the recovery codes once, and only then gets a session. There is no skip. */
import { ArrowLeftIcon as ArrowLeft, WarningCircleIcon as WarningCircle } from 'phosphor-react-native';
import { useState } from 'react';
import { Linking, Pressable, StyleSheet, View } from 'react-native';

import { useAuth } from '@/auth/AuthProvider';
import { type EnrolMethod, type TotpSetup } from '@/domain/nativeSignIn';
import { isOtpComplete } from '@/domain/otp';
import { describeSignInError } from '@/domain/signInErrors';
import { useT } from '@/i18n';
import { c, sp, t } from '@/theme';
import { OtpInput } from '@/ui/OtpInput';
import { AppText, Button } from '@/ui/primitives';

import { type FactorChoice, FactorChoices } from './FactorChoices';
import { failureText } from './signInText';

type Stage = { kind: 'choose' } | { kind: 'totp'; setup: TotpSetup } | { kind: 'email'; hint: string } | { kind: 'codes'; codes: string[] };

export function EnrolView({ methods, onBack }: { methods: EnrolMethod[]; onBack: (message?: string) => void }) {
  const tr = useT();
  const { enrolTotp, enrolEmail, confirmEnrol, finishEnrolment } = useAuth();
  const [stage, setStage] = useState<Stage>({ kind: 'choose' });
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');

  const fail = (e: unknown) => {
    const failure = describeSignInError(e);
    const text = failureText(tr, failure);
    if (failure.kind === 'expired') return onBack(text);
    setErr(text);
    if (failure.kind === 'invalid-code' || failure.kind === 'server') setCode('');
  };

  const guarded = async (fn: () => Promise<void>) => {
    setErr('');
    setBusy(true);
    try {
      await fn();
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  };

  const startTotp = () => guarded(async () => setStage({ kind: 'totp', setup: await enrolTotp() }));
  const startEmail = () => guarded(async () => setStage({ kind: 'email', hint: await enrolEmail() }));
  const confirm = (method: EnrolMethod, value: string) => {
    if (!isOtpComplete(value)) return setErr(tr('cu.mfa.enterCode'));
    return guarded(async () => {
      const codes = await confirmEnrol(method, value);
      if (codes.length > 0) setStage({ kind: 'codes', codes });
    });
  };

  const choices: FactorChoice[] = [];
  if (methods.includes('TOTP')) choices.push({ key: 'totp', label: tr('cu.enrol.totp'), detail: tr('cu.enrol.totpHint'), onPress: () => void startTotp() });
  if (methods.includes('EMAIL_OTP')) choices.push({ key: 'email', label: tr('cu.enrol.email'), onPress: () => void startEmail() });

  if (stage.kind === 'codes') {
    return (
      <View style={styles.root} testID="enrol-codes">
        <AppText variant="display" accessibilityRole="header">{tr('cu.enrol.codesTitle')}</AppText>
        <AppText variant="small" color={c.textMuted}>{tr('cu.enrol.codesLead')}</AppText>
        <View style={styles.codes}>
          {stage.codes.map((k) => <AppText key={k} variant="bodyStrong" style={styles.code}>{k}</AppText>)}
        </View>
        <Button label={tr('cu.enrol.codesDone')} onPress={() => void finishEnrolment()} />
      </View>
    );
  }

  const method: EnrolMethod | null = stage.kind === 'totp' ? 'TOTP' : stage.kind === 'email' ? 'EMAIL_OTP' : null;
  return (
    <View style={styles.root} testID="enrol">
      <Pressable accessibilityRole="button" accessibilityLabel={tr('cu.mfa.back')} onPress={() => (stage.kind === 'choose' ? onBack() : (setStage({ kind: 'choose' }), setCode(''), setErr('')))} style={styles.back} hitSlop={12}>
        <ArrowLeft size={20} color={c.text} />
        <AppText variant="small" style={{ fontWeight: t.fontWeight.semibold }}>{stage.kind === 'choose' ? tr('cu.mfa.back') : tr('cu.enrol.otherMethod')}</AppText>
      </Pressable>
      <View style={{ gap: sp['1'] }}>
        <AppText variant="display" accessibilityRole="header">{tr('cu.enrol.title')}</AppText>
        <AppText variant="small" color={c.textMuted}>
          {stage.kind === 'choose' ? tr('cu.enrol.lead') : stage.kind === 'totp' ? tr('cu.enrol.scan') : tr('cu.enrol.emailSent', { hint: stage.hint })}
        </AppText>
      </View>
      {stage.kind === 'totp' ? (
        <View style={{ gap: sp['2'] }}>
          <AppText variant="small" color={c.textMuted}>{tr('cu.enrol.keyLabel')}</AppText>
          <AppText variant="bodyStrong" selectable style={styles.code} testID="enrol-secret">{stage.setup.secret.replace(/(.{4})/g, '$1 ').trim()}</AppText>
          <Button kind="outline" label={tr('cu.enrol.openApp')} onPress={() => void Linking.openURL(stage.setup.otpauthUri).catch(() => undefined)} />
        </View>
      ) : null}
      {method ? (
        <View style={{ gap: sp['2'] }}>
          <AppText variant="small" color={c.textMuted}>{tr('cu.enrol.codeLabel')}</AppText>
          <OtpInput key={stage.kind} value={code} onChange={(v) => { setCode(v); setErr(''); }} onComplete={(v) => void confirm(method, v)} error={!!err} disabled={busy} autoFocus />
          {err ? (
            <View style={styles.error} accessibilityRole="alert" accessibilityLiveRegion="assertive">
              <WarningCircle size={16} color={c.danger} />
              <AppText variant="small" color={c.danger} style={{ flex: 1 }}>{err}</AppText>
            </View>
          ) : null}
          <Button label={tr('cu.enrol.confirm')} onPress={() => void confirm(method, code)} loading={busy} />
          {stage.kind === 'email' ? <Button kind="ghost" label={tr('cu.enrol.resend')} onPress={() => void startEmail()} disabled={busy} /> : null}
        </View>
      ) : (
        <>
          {err ? <AppText variant="small" color={c.danger} accessibilityRole="alert">{err}</AppText> : null}
          <FactorChoices title={tr('cu.enrol.title')} choices={choices} disabled={busy} />
        </>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  root: { gap: sp['4'] },
  back: { flexDirection: 'row', alignItems: 'center', gap: sp['2'], minHeight: t.size.touch, alignSelf: 'flex-start' },
  error: { flexDirection: 'row', alignItems: 'center', gap: sp['2'] },
  codes: { flexDirection: 'row', flexWrap: 'wrap', gap: sp['2'] },
  code: { letterSpacing: 1 }
});
