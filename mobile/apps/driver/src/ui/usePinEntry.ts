/* Digits typed on the PinPad: collects up to PASSCODE_LENGTH and hands the full passcode to `onComplete` once. The
   six dots stay filled (and further keys are ignored) until the caller has its answer and calls reset(), so a fast
   typist cannot submit twice. */
import { useCallback, useState } from 'react';

import { PASSCODE_LENGTH } from '@/domain/passcode';

export function usePinEntry(onComplete: (pin: string) => void) {
  const [pin, setPin] = useState('');
  const press = useCallback((d: string) => {
    if (pin.length >= PASSCODE_LENGTH) return;
    const next = pin + d;
    setPin(next);
    if (next.length === PASSCODE_LENGTH) onComplete(next);
  }, [pin, onComplete]);
  const remove = useCallback(() => setPin((p) => (p.length >= PASSCODE_LENGTH ? p : p.slice(0, -1))), []);
  const reset = useCallback(() => setPin(''), []);
  return { pin, press, remove, reset };
}
