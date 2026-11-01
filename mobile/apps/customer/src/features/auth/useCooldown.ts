import { useEffect, useState } from 'react';

import { cooldownSecondsLeft } from '@/domain/resendCooldown';

/** Whole seconds left until `readyAt` (epoch ms, or null for "not waiting"), ticking once a second while it counts down. */
export function useCooldown(readyAt: number | null): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (readyAt === null) return undefined;
    setNow(Date.now());
    const id = setInterval(() => {
      const n = Date.now();
      setNow(n);
      if (cooldownSecondsLeft(readyAt, n) === 0) clearInterval(id);
    }, 1000);
    return () => clearInterval(id);
  }, [readyAt]);
  return readyAt === null ? 0 : cooldownSecondsLeft(readyAt, now);
}
