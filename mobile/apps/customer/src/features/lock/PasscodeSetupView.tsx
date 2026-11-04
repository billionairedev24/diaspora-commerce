import { type ReactNode, useState } from 'react';
import { StyleSheet, useWindowDimensions, View } from 'react-native';

import { useAuth } from '@/auth/AuthProvider';
import { PASSCODE_LENGTH, type PasscodeSetup, passcodeSetupStep } from '@/domain/passcode';
import { type MessageKey, useT } from '@/i18n';
import { c, sp } from '@/theme';
import { PinDots, PinPad } from '@/ui/PinPad';
import { AppText } from '@/ui/primitives';
import { Screen } from '@/ui/Screen';

/** Create → confirm a 6-digit passcode (first-run setup and Sign-in & security → Change). The passcode rules live in
    domain/passcode.ts; this only shows them. `embedded` = inside the first-run setup screen (no Screen of its own). */
export function PasscodeSetupView({ onDone, header, embedded = false }: { onDone: (pin: string) => Promise<void>; header?: ReactNode; embedded?: boolean }) {
  const tr = useT();
  const { biometryLabel } = useAuth();
  const compact = useWindowDimensions().height < 700;
  const [state, setState] = useState<PasscodeSetup>({ stage: 'new' });
  const [pin, setPin] = useState('');
  const [err, setErr] = useState<MessageKey | ''>('');
  const [shake, setShake] = useState(0);
  const press = (d: string) => {
    if (pin.length >= PASSCODE_LENGTH) return;
    const next = pin + d;
    setPin(next);
    setErr('');
    if (next.length === PASSCODE_LENGTH) {
      // Evaluate immediately so fast typists don't lose the first digit of the confirmation.
      const r = passcodeSetupStep(state, next);
      setPin('');
      setState(r.next);
      if (r.error) {
        setErr(state.stage === 'confirm' ? 'cu.setup.errMismatch' : /^\d+$/.test(next) ? 'cu.setup.errRepeated' : 'cu.setup.errFormat');
        setShake((n) => n + 1);
      }
      if (r.done) void onDone(r.done);
    }
  };
  const confirming = state.stage === 'confirm';
  const body = (
    <View style={styles.wrap}>
      <View style={{ alignItems: 'center', gap: sp['1'] }}>
        <AppText variant="display" accessibilityRole="header" style={styles.center}>{confirming ? tr('cu.setup.passcodeConfirm') : tr('cu.setup.passcodeCreate')}</AppText>
        <AppText variant="small" color={c.textMuted} style={styles.center}>{confirming ? tr('cu.setup.passcodeConfirmHint') : tr('cu.setup.passcodeCreateHint', { biometry: biometryLabel })}</AppText>
      </View>
      <PinDots filled={pin.length} error={!!err} shake={shake} />
      <AppText variant="small" color={c.danger} style={[styles.center, { minHeight: 20 }]} accessibilityLiveRegion="assertive" accessibilityRole="alert">{err ? tr(err) : ''}</AppText>
      <PinPad onDigit={press} onDelete={() => setPin((p) => p.slice(0, -1))} compact={compact} />
    </View>
  );
  if (embedded) return body;
  return (
    <Screen body="fixed" header={header}>
      {body}
    </Screen>
  );
}

const styles = StyleSheet.create({
  wrap: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: sp['4'], paddingHorizontal: sp['6'] },
  center: { textAlign: 'center' }
});
