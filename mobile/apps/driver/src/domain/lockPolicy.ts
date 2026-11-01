/* When the driver app locks. The lock is a layer over the app (the UI is hidden, nothing else changes): it never
   stops the sync engine, the outbox or location pings. Coming back to the foreground after more than the grace period
   locks again; short trips (a phone call banner, the camera permission prompt) do not. */

export const LOCK_GRACE_MS = 30_000;

export function shouldLockOnResume(backgroundedAt: number | null, now: number, graceMs = LOCK_GRACE_MS): boolean {
  if (backgroundedAt === null) return false;
  return now - backgroundedAt >= graceMs;
}

export type LockPhase =
  /** Nobody is signed in on this phone: no lock. */
  | 'signed-out'
  /** Signed in, first-run setup pending (Face ID offer, then the passcode). */
  | 'setup'
  /** Signed in with a passcode; needs Face ID or the passcode. */
  | 'locked'
  | 'unlocked';

export interface DeviceSecurity {
  signedIn: boolean;
  hasPasscode: boolean;
  setupComplete: boolean;
}

/** Phase on cold start: a signed-in phone always opens on the lock. */
export function phaseOnLaunch(s: DeviceSecurity): LockPhase {
  if (!s.signedIn) return 'signed-out';
  if (!s.hasPasscode || !s.setupComplete) return 'setup';
  return 'locked';
}

/** "Welcome back, <first name>": the first word of the profile name; empty when the name is only an email address. */
export function firstNameOf(name: string): string {
  const first = name.trim().split(/\s+/)[0] ?? '';
  return first.includes('@') ? '' : first;
}

/** True while the lock layer hides the app (setup counts: nothing is usable until the passcode exists). */
export function coversApp(phase: LockPhase): boolean {
  return phase === 'setup' || phase === 'locked';
}
