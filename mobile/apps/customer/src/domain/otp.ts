/* One-time code entry (pure; unit-tested): what the six boxes show for what was typed, pasted or autofilled. */

export const OTP_LENGTH = 6;

/** Keeps digits only (spaces and dashes from a pasted "123 456" or an SMS autofill are dropped), at most OTP_LENGTH. */
export function normalizeOtp(input: string): string {
  return input.replace(/\D/g, '').slice(0, OTP_LENGTH);
}

/** One entry per box: the digit, or '' for an empty box. */
export function otpBoxes(value: string): string[] {
  const v = normalizeOtp(value);
  return Array.from({ length: OTP_LENGTH }, (_, i) => v[i] ?? '');
}

/** The box that takes the next digit (the last box once the code is complete). */
export function otpActiveBox(value: string): number {
  return Math.min(normalizeOtp(value).length, OTP_LENGTH - 1);
}

export function isOtpComplete(value: string): boolean {
  return normalizeOtp(value).length === OTP_LENGTH;
}

/** True only on the change that completes the code, so a re-render or a corrected digit never submits twice. */
export function shouldAutoSubmit(previous: string, next: string): boolean {
  return isOtpComplete(next) && !isOtpComplete(previous);
}
