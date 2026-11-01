/* "Text again in 0:24": the wait before another text message can be requested (pure; unit-tested). */

export const RESEND_COOLDOWN_SECONDS = 30;

/** Whole seconds left until `readyAt` (epoch ms), never negative. */
export function cooldownSecondsLeft(readyAt: number, now: number): number {
  return Math.max(0, Math.ceil((readyAt - now) / 1000));
}

/** m:ss, e.g. 24 → "0:24", 90 → "1:30". */
export function formatCooldown(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}
