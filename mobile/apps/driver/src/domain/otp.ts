/* One-time code entry (pure): the 6 boxes are drawn from one hidden text input, so SMS autofill, paste and the
   keyboard all end up in the same string. */

export const OTP_LENGTH = 6;

/** Digits only, at most `length` (a pasted "123 456" or "123-456" becomes "123456"). */
export function sanitizeOtp(raw: string, length = OTP_LENGTH): string {
  return raw.replace(/\D+/g, '').slice(0, length);
}

/** One entry per box: the digit, or '' for an empty box. */
export function otpCells(value: string, length = OTP_LENGTH): string[] {
  return Array.from({ length }, (_, i) => value[i] ?? '');
}

/** The box that has the focus ring: the next empty one, or the last when full. */
export function activeCell(value: string, length = OTP_LENGTH): number {
  return Math.min(value.length, length - 1);
}

/** Fires once when the last digit arrives (not on every re-render, not when deleting). */
export function justCompleted(prev: string, next: string, length = OTP_LENGTH): boolean {
  return next.length === length && prev !== next;
}
