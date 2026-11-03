/* A countdown in whole seconds (resend a text-message code, wait out a rate limit). start(n) begins it; it ticks once
   a second and stops at 0. */
import { useCallback, useEffect, useState } from 'react';

import { secondsLeft } from '@/domain/cooldown';

export function useCooldown(): { left: number; start: (seconds: number) => void; clear: () => void } {
  const [until, setUntil] = useState(0);
  const [left, setLeft] = useState(0);
  useEffect(() => {
    setLeft(secondsLeft(until, Date.now()));
    if (until <= Date.now()) return undefined;
    const id = setInterval(() => {
      const n = secondsLeft(until, Date.now());
      setLeft(n);
      if (n === 0) clearInterval(id);
    }, 1000);
    return () => clearInterval(id);
  }, [until]);
  const start = useCallback((seconds: number) => setUntil(Date.now() + seconds * 1000), []);
  const clear = useCallback(() => setUntil(0), []);
  return { left, start, clear };
}
