/* First-run setup after the first sign-in on a phone: offer Face ID (only when this phone has it), then create and
   confirm a 6-digit passcode, which is always the fallback. There is no way to skip the passcode. */
import { ScanSmileyIcon as ScanSmiley,WarningCircleIcon as WarningCircle } from 'phosphor-react-native';
import { useState } from 'react';
import { StyleSheet, View } from 'react-native';

import { type PasscodeIssue, type PasscodeSetup, passcodeSetupStep } from '@/domain/passcode';
import { type MessageKey, useT } from '@/i18n';
import { useLock } from '@/session/LockProvider';
import { c, sp, t } from '@/theme';
import { PinDots, PinPad } from '@/ui/PinPad';
import { AppText, Banner, Button } from '@/ui/primitives';
import { usePinEntry } from '@/ui/usePinEntry';

import { LockFrame } from './LockFrame';

const ISSUE: Record<PasscodeIssue, MessageKey> = {
  length: 'dr.lock.issueLength',
  repeating: 'dr.lock.issueRepeating',
  mismatch: 'dr.lock.issueMismatch'
};

function FaceIdStep({ steps }: { steps: number }) {
  const tr = useT();
  const { setFaceId, biometryLabel } = useLock();
  const [failed, setFailed] = useState(false);
  const [busy, setBusy] = useState(false);
  const turnOn = async () => {
    setBusy(true);
    setFailed(!(await setFaceId(true)));
    setBusy(false);
  };
  return (
    <LockFrame footer={
      <>
        <Button label={tr('dr.lock.turnOn', { method: biometryLabel })} loading={busy} onPress={() => void turnOn()} />
        <Button kind="onDark" label={tr('dr.lock.passcodeOnly')} disabled={busy} onPress={() => void setFaceId(false)} />
      </>
    }>
      <AppText variant="label" color={t.gold['500']}>{tr('dr.lock.step', { n: 1, total: steps })}</AppText>
      <View style={styles.medal}><ScanSmiley size={56} color={t.ink['1000']} weight="bold" /></View>
      <AppText variant="display" color={c.textInverse} style={styles.centre} accessibilityRole="header">{tr('dr.lock.unlockWithTitle', { method: biometryLabel })}</AppText>
      <AppText color={t.ink['300']} style={styles.centre}>{tr('dr.lock.faceIdBody', { method: biometryLabel })}</AppText>
      {failed ? <Banner tone="warning" icon={WarningCircle}>{tr('dr.lock.faceIdFailed', { method: biometryLabel })}</Banner> : null}
    </LockFrame>
  );
}

function PasscodeStep({ steps }: { steps: number }) {
  const tr = useT();
  const { savePasscode } = useLock();
  const [state, setState] = useState<PasscodeSetup>({ stage: 'new' });
  const [issue, setIssue] = useState<PasscodeIssue | null>(null);
  const entry = usePinEntry((pin) => {
    const r = passcodeSetupStep(state, pin);
    setState(r.next);
    setIssue(r.error ?? null);
    entry.reset();
    if (r.done) void savePasscode(r.done);
  });
  return (
    <LockFrame>
      <AppText variant="label" color={t.gold['500']}>{tr('dr.lock.step', { n: steps, total: steps })}</AppText>
      <AppText variant="display" color={c.textInverse} style={styles.centre} accessibilityRole="header">{state.stage === 'new' ? tr('dr.lock.createPasscode') : tr('dr.lock.confirmPasscode')}</AppText>
      <AppText color={t.ink['300']} style={styles.centre}>{tr('dr.lock.passcodeBody')}</AppText>
      <PinDots filled={entry.pin.length} error={!!issue} />
      <AppText variant="small" color={t.red['100']} style={[styles.centre, { minHeight: 20 }]} accessibilityRole="alert" accessibilityLiveRegion="assertive">{issue ? tr(ISSUE[issue]) : ''}</AppText>
      <PinPad onDigit={(d) => { setIssue(null); entry.press(d); }} onDelete={entry.remove} />
    </LockFrame>
  );
}

export function SetupFlow() {
  const { setupStep, biometricsAvailable } = useLock();
  const steps = biometricsAvailable ? 2 : 1;
  return setupStep === 'face-id' ? <FaceIdStep steps={steps} /> : <PasscodeStep steps={steps} />;
}

const styles = StyleSheet.create({
  centre: { textAlign: 'center' },
  medal: { width: 112, height: 112, borderRadius: 56, backgroundColor: t.gold['500'], alignItems: 'center', justifyContent: 'center', marginBottom: sp['2'] }
});
