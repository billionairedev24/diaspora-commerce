import { type ReactNode, useState } from 'react';
import { StyleSheet, View } from 'react-native';

import { PASSCODE_LENGTH, type PasscodeSetup, passcodeSetupStep, passcodeSetupTitle } from '@/domain/passcode';
import { c, sp } from '@/theme';
import { PinDots, PinPad } from '@/ui/PinPad';
import { AppText } from '@/ui/primitives';
import { Screen } from '@/ui/Screen';

/** Create → confirm a 6-digit passcode (first-run setup and Sign-in & security → Change). */
export function PasscodeSetupView({ stepLabel, onDone, header }: { stepLabel: string; onDone: (pin: string) => Promise<void>; header?: ReactNode }) {
  const [state, setState] = useState<PasscodeSetup>({ stage: 'new' });
  const [pin, setPin] = useState('');
  const [err, setErr] = useState('');
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
      if (r.error) setErr(r.error);
      if (r.done) void onDone(r.done);
    }
  };
  return (
    <Screen body="fixed" header={header}>
      <View style={styles.pinWrap}>
        <AppText variant="label" color={c.textMuted}>{stepLabel}</AppText>
        <View style={{ alignItems: 'center', gap: sp['1'] }}>
          <AppText variant="title">{passcodeSetupTitle(state)}</AppText>
          <AppText variant="small" color={c.textMuted}>Unlocks the app when Face ID isn’t available.</AppText>
        </View>
        <PinDots filled={pin.length} />
        <AppText variant="small" color={c.danger} style={{ minHeight: 20 }} accessibilityLiveRegion="assertive">{err}</AppText>
        <PinPad onDigit={press} onDelete={() => setPin((p) => p.slice(0, -1))} />
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  pinWrap: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: sp['4'], paddingHorizontal: sp['6'] }
});
