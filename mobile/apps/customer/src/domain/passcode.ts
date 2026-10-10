/* S163 app passcode rules (pure; unit-tested). The passcode itself never leaves the device: only a salted
   SHA-256 hash is kept in the secure store. */

export const PASSCODE_LENGTH = 6;
/** Five wrong passcodes force a full sign-in. */
export const MAX_PASSCODE_ATTEMPTS = 5;

export const LOCKED_OUT_MESSAGE = 'Too many wrong passcodes. Sign in with your password.';

/** Returns an error message, or null when the passcode is acceptable. */
export function validateNewPasscode(pin: string): string | null {
  if (!new RegExp(`^\\d{${PASSCODE_LENGTH}}$`).test(pin)) return `Enter ${PASSCODE_LENGTH} digits.`;
  if (/^(\d)\1+$/.test(pin)) return 'Too easy to guess. Avoid repeating one digit.';
  return null;
}

export type PasscodeSetup = { stage: 'new' } | { stage: 'confirm'; first: string };

export interface SetupStep {
  next: PasscodeSetup;
  /** Set when both entries matched: the passcode to store. */
  done?: string;
  error?: string;
}

/** Create → confirm. A mismatch starts again from "Create". */
export function passcodeSetupStep(state: PasscodeSetup, pin: string): SetupStep {
  if (state.stage === 'new') {
    const error = validateNewPasscode(pin);
    if (error) return { next: state, error };
    return { next: { stage: 'confirm', first: pin } };
  }
  if (pin !== state.first) return { next: { stage: 'new' }, error: 'Passcodes didn’t match. Start again.' };
  return { next: { stage: 'new' }, done: pin };
}

export function passcodeSetupTitle(state: PasscodeSetup): string {
  return state.stage === 'new' ? 'Create a 6-digit passcode' : 'Confirm your passcode';
}

export type UnlockResult =
  | { kind: 'unlocked'; attempts: 0 }
  | { kind: 'retry'; attempts: number; message: string }
  | { kind: 'locked-out'; attempts: number; message: string };

/** Records one unlock attempt. `attempts` is the number of wrong entries so far (persisted across restarts). */
export function recordUnlockAttempt(attempts: number, correct: boolean): UnlockResult {
  if (correct) return { kind: 'unlocked', attempts: 0 };
  const used = attempts + 1;
  const left = MAX_PASSCODE_ATTEMPTS - used;
  if (left <= 0) return { kind: 'locked-out', attempts: used, message: LOCKED_OUT_MESSAGE };
  return { kind: 'retry', attempts: used, message: `Wrong passcode · ${left} attempt${left === 1 ? '' : 's'} left` };
}

/** Hash input for the stored passcode: `salt:pin`, hashed with SHA-256 by the caller (expo-crypto). */
export function passcodeDigestInput(salt: string, pin: string): string {
  return `${salt}:${pin}`;
}

/** Constant-time comparison of two hex digests. */
export function digestsEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i += 1) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}
