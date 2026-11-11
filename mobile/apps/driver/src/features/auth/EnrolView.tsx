/* Forced two-step setup after the password: drivers are staff, so two-step verification is mandatory and, with nothing set
   up yet, no code is sent. The driver sets up an authenticator app (setup key, or "Open in authenticator app") or email
   codes (a first code is emailed to the verified address), confirms a 6-digit code, sees the recovery codes once, and
   only then gets a session. There is no skip. */
import { ArrowLeftIcon as ArrowLeft, WarningCircleIcon as WarningCircle } from 'phosphor-react-native';
import { useState } from 'react';
import { Linking, Pressable, StyleSheet, View } from 'react-native';

import { type EnrolMethod, type TotpSetup } from '@/domain/nativeSignIn';
import { OTP_LENGTH } from '@/domain/otp';
import { classifySignInFailure, type SignInFailure } from '@/domain/signInFailure';
import { useT } from '@/i18n';
import { useDriver } from '@/session/DriverProvider';
import { c, sp, t } from '@/theme';
import { OtpInput } from '@/ui/OtpInput';
import { AppText, Banner, Button } from '@/ui/primitives';

import { FactorRow } from './FactorRow';
import { failureMessage } from './failureText';

type Stage = { kind: 'choose' } | { kind: 'totp'; setup: TotpSetup } | { kind: 'email'; hint: string } | { kind: 'codes'; codes: string[] };

export function EnrolView({ methods, onBack }: { methods: EnrolMethod[]; onBack: (message?: string) => void }) {
  const tr = useT();
  const { enrolTotp, enrolEmail, confirmEnrol, finishEnrolment } = useDriver();
  const [stage, setStage] = useState<Stage>({ kind: 'choose' });
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<SignInFailure | null>(null);
  const [focusKey, setFocusKey] = useState(0);

  const fail = (e: unknown) => {
    const f = classifySignInFailure(e, 'code');
    if (f.kind === 'expired') return onBack(failureMessage(tr, f));
    setCode('');
    setFocusKey((k) => k + 1);
    setFailure(f);
  };
  const guarded = async (fn: () => Promise<void>) => {
    setFailure(null);
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
    if (value.length !== OTP_LENGTH) return setFailure({ kind: 'field-required', field: 'code' });
    return guarded(async () => {
      const codes = await confirmEnrol(method, value);
      if (codes.length > 0) setStage({ kind: 'codes', codes });
    });
  };
  const text = failure ? failureMessage(tr, failure, 0) : '';

  if (stage.kind === 'codes') {
    return (
      <View style={styles.root} testID="enrol-codes">
        <AppText variant="display" accessibilityRole="header">{tr('dr.enrol.codesTitle')}</AppText>
        <AppText variant="small" color={c.textMuted}>{tr('dr.enrol.codesLead')}</AppText>
        <View style={styles.codes}>
          {stage.codes.map((k) => <AppText key={k} variant="bodyStrong" style={styles.code}>{k}</AppText>)}
        </View>
        <Button label={tr('dr.enrol.codesDone')} onPress={() => void finishEnrolment()} />
      </View>
    );
  }
  const method: EnrolMethod | null = stage.kind === 'totp' ? 'TOTP' : stage.kind === 'email' ? 'EMAIL_OTP' : null;
  return (
    <View style={styles.root} testID="enrol">
      <Pressable accessibilityRole="button" accessibilityLabel={tr('dr.mfa.back')} onPress={() => (stage.kind === 'choose' ? onBack() : (setStage({ kind: 'choose' }), setCode(''), setFailure(null)))} style={styles.back} hitSlop={12}>
        <ArrowLeft size={20} color={c.text} />
        <AppText variant="small">{stage.kind === 'choose' ? tr('dr.mfa.back') : tr('dr.enrol.otherMethod')}</AppText>
      </Pressable>
      <View style={{ gap: sp['1'] }}>
        <AppText variant="display" accessibilityRole="header">{tr('dr.enrol.title')}</AppText>
        <AppText variant="small" color={c.textMuted}>
          {stage.kind === 'choose' ? tr('dr.enrol.lead') : stage.kind === 'totp' ? tr('dr.enrol.scan') : tr('dr.enrol.emailSent', { hint: stage.hint })}
        </AppText>
      </View>
      {stage.kind === 'totp' ? (
        <View style={{ gap: sp['2'] }}>
          <AppText variant="small" color={c.textMuted}>{tr('dr.enrol.keyLabel')}</AppText>
          <AppText variant="bodyStrong" selectable style={styles.code} testID="enrol-secret">{stage.setup.secret.replace(/(.{4})/g, '$1 ').trim()}</AppText>
          <Button kind="outline" label={tr('dr.enrol.openApp')} onPress={() => void Linking.openURL(stage.setup.otpauthUri).catch(() => undefined)} />
        </View>
      ) : null}
      {method ? (
        <View style={{ gap: sp['2'] }}>
          <OtpInput value={code} onChange={(v) => { setCode(v); if (failure) setFailure(null); }} onComplete={(v) => void confirm(method, v)} label={tr('dr.enrol.codeLabel')} error={!!failure} disabled={busy} focusKey={focusKey} />
          {failure ? <Banner tone="danger" icon={WarningCircle}>{text}</Banner> : null}
          <Button label={tr('dr.enrol.confirm')} onPress={() => void confirm(method, code)} loading={busy} disabled={code.length !== OTP_LENGTH} />
          {stage.kind === 'email' ? <Button kind="ghost" label={tr('dr.enrol.resend')} onPress={() => void startEmail()} disabled={busy} /> : null}
        </View>
      ) : (
        <View>
          {failure ? <Banner tone="danger" icon={WarningCircle}>{text}</Banner> : null}
          {methods.includes('TOTP') ? <FactorRow label={tr('dr.enrol.totp')} hint={tr('dr.enrol.totpHint')} onPress={() => void startTotp()} disabled={busy} /> : null}
          {methods.includes('EMAIL_OTP') ? <FactorRow label={tr('dr.enrol.email')} onPress={() => void startEmail()} disabled={busy} /> : null}
        </View>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  root: { gap: sp['4'] },
  back: { flexDirection: 'row', alignItems: 'center', gap: sp['2'], minHeight: t.size.touch, alignSelf: 'flex-start' },
  codes: { flexDirection: 'row', flexWrap: 'wrap', gap: sp['2'] },
  code: { letterSpacing: 1 }
});
