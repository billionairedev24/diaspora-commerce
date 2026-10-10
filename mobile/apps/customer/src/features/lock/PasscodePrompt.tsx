/* Passcode confirmation sheet used by confirmWithDevice() when Face ID is off or declined (payments over $100). */
import { useState } from 'react';

import { useAuth } from '@/auth/AuthProvider';
import { PASSCODE_LENGTH } from '@/domain/passcode';
import { c, sp } from '@/theme';
import { PinDots, PinPad } from '@/ui/PinPad';
import { AppText, Button } from '@/ui/primitives';
import { Sheet } from '@/ui/Sheet';

export function PasscodePrompt() {
  const { passcodePrompt, verifyPasscode } = useAuth();
  const [pin, setPin] = useState('');
  const [err, setErr] = useState('');
  if (!passcodePrompt) return null;
  const close = (ok: boolean) => {
    setPin('');
    setErr('');
    passcodePrompt.resolve(ok);
  };
  const press = (d: string) => {
    const next = (pin + d).slice(0, PASSCODE_LENGTH);
    setPin(next);
    if (next.length === PASSCODE_LENGTH) {
      void verifyPasscode(next).then((ok) => {
        if (ok) close(true);
        else { setPin(''); setErr('Wrong passcode. Try again.'); }
      });
    }
  };
  return (
    <Sheet visible onClose={() => close(false)}>
      <AppText variant="heading" style={{ textAlign: 'center' }}>Confirm with your passcode</AppText>
      <AppText variant="small" color={c.textMuted} style={{ textAlign: 'center' }}>{passcodePrompt.reason}</AppText>
      <PinDots filled={pin.length} />
      <AppText variant="small" color={c.danger} style={{ textAlign: 'center', minHeight: 18 }}>{err}</AppText>
      <PinPad onDigit={press} onDelete={() => setPin((p) => p.slice(0, -1))} />
      <Button kind="ghost" label="Cancel" onPress={() => close(false)} style={{ marginTop: sp['2'] }} />
    </Sheet>
  );
}
