/* Passcode confirmation sheet used by confirmWithDevice() when Face ID is off or declined (payments over $100). */
import { useState } from 'react';
import { useWindowDimensions } from 'react-native';

import { useAuth } from '@/auth/AuthProvider';
import { PASSCODE_LENGTH } from '@/domain/passcode';
import { useT } from '@/i18n';
import { c, sp } from '@/theme';
import { PinDots, PinPad } from '@/ui/PinPad';
import { AppText, Button } from '@/ui/primitives';
import { Sheet } from '@/ui/Sheet';

export function PasscodePrompt() {
  const tr = useT();
  const { passcodePrompt, verifyPasscode } = useAuth();
  const compact = useWindowDimensions().height < 700;
  const [pin, setPin] = useState('');
  const [err, setErr] = useState(false);
  const [shake, setShake] = useState(0);
  if (!passcodePrompt) return null;
  const close = (ok: boolean) => {
    setPin('');
    setErr(false);
    passcodePrompt.resolve(ok);
  };
  const press = (d: string) => {
    if (pin.length >= PASSCODE_LENGTH) return;
    const next = pin + d;
    setPin(next);
    setErr(false);
    if (next.length === PASSCODE_LENGTH) {
      void verifyPasscode(next).then((ok) => {
        if (ok) close(true);
        else { setPin(''); setErr(true); setShake((n) => n + 1); }
      });
    }
  };
  return (
    <Sheet visible onClose={() => close(false)}>
      <AppText variant="heading" accessibilityRole="header" style={{ textAlign: 'center' }}>{tr('cu.confirm.title')}</AppText>
      <AppText variant="small" color={c.textMuted} style={{ textAlign: 'center' }}>{passcodePrompt.reason}</AppText>
      <PinDots filled={pin.length} error={err} shake={shake} />
      <AppText variant="small" color={c.danger} style={{ textAlign: 'center', minHeight: 18 }} accessibilityLiveRegion="assertive" accessibilityRole="alert">{err ? tr('cu.confirm.wrong') : ''}</AppText>
      <PinPad onDigit={press} onDelete={() => setPin((p) => p.slice(0, -1))} compact={compact} />
      <Button kind="ghost" label={tr('cu.confirm.cancel')} onPress={() => close(false)} style={{ marginTop: sp['2'] }} />
    </Sheet>
  );
}
