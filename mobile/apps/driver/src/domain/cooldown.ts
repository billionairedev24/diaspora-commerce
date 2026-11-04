/* Resend cooldown (pure): a text-message code can be asked for again 30 seconds after the last one. */

export const SMS_RESEND_SECONDS = 30;

/** Whole seconds left until `until` (epoch ms); 0 once it has passed. */
export function secondsLeft(until: number, now: number): number {
  return Math.max(0, Math.ceil((until - now) / 1000));
}

/** m:ss for a wait ("0:07", "2:05"). */
export function formatWait(seconds: number): string {
  const s = Math.max(0, Math.round(seconds));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}
