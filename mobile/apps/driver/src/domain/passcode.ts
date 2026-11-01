/* App passcode rules (pure; unit-tested). Same rules as the customer app: 6 digits, not one repeated digit, entered
   twice; five wrong entries end the session. The passcode never leaves the phone: only a salted SHA-256 hash is kept
   in the secure store. Results carry codes, not text: the screens translate them (dr.lock.*). */

export const PASSCODE_LENGTH = 6;
/** Five wrong passcodes force a full sign-in. */
export const MAX_PASSCODE_ATTEMPTS = 5;

export type PasscodeIssue = 'length' | 'repeating' | 'mismatch';

/** Returns the problem with a new passcode, or null when it is acceptable. */
export function validateNewPasscode(pin: string): PasscodeIssue | null {
  if (!new RegExp(`^\\d{${PASSCODE_LENGTH}}$`).test(pin)) return 'length';
  if (/^(\d)\1+$/.test(pin)) return 'repeating';
  return null;
}

export type PasscodeSetup = { stage: 'new' } | { stage: 'confirm'; first: string };

export interface SetupStep {
  next: PasscodeSetup;
  /** Set when both entries matched: the passcode to store. */
  done?: string;
  error?: PasscodeIssue;
}

/** Create, then confirm. A mismatch starts again from "create". */
export function passcodeSetupStep(state: PasscodeSetup, pin: string): SetupStep {
  if (state.stage === 'new') {
    const error = validateNewPasscode(pin);
    if (error) return { next: state, error };
    return { next: { stage: 'confirm', first: pin } };
  }
  if (pin !== state.first) return { next: { stage: 'new' }, error: 'mismatch' };
  return { next: { stage: 'new' }, done: pin };
}

export type UnlockResult =
  | { kind: 'unlocked'; attempts: 0 }
  | { kind: 'retry'; attempts: number; left: number }
  | { kind: 'locked-out'; attempts: number };

/** Records one unlock attempt. `attempts` is the number of wrong entries so far (persisted across restarts). */
export function recordUnlockAttempt(attempts: number, correct: boolean): UnlockResult {
  if (correct) return { kind: 'unlocked', attempts: 0 };
  const used = attempts + 1;
  const left = MAX_PASSCODE_ATTEMPTS - used;
  if (left <= 0) return { kind: 'locked-out', attempts: used };
  return { kind: 'retry', attempts: used, left };
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
