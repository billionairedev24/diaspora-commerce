/* When the app returns to the foreground it re-locks (Face ID / passcode) once it has been in the background longer
   than the grace period — short trips (the OIDC browser, a Stripe 3-D Secure page, a phone call banner) do not lock. */

export const LOCK_GRACE_MS = 30_000;

export function shouldLockOnResume(backgroundedAt: number | null, now: number, graceMs = LOCK_GRACE_MS): boolean {
  if (backgroundedAt === null) return false;
  return now - backgroundedAt >= graceMs;
}

export type SessionPhase =
  /** Not signed in: the app opens on sign-in (no guest mode). */
  | 'signed-out'
  /** Signed in, first-run setup pending (Face ID → passcode → notifications). */
  | 'setup'
  /** Signed in with a passcode; needs Face ID or passcode. */
  | 'locked'
  | 'unlocked';

export interface DeviceSecurity {
  signedIn: boolean;
  hasPasscode: boolean;
  setupComplete: boolean;
}

/** Phase on cold start: a signed-in device always opens on the unlock screen. */
export function phaseOnLaunch(s: DeviceSecurity): SessionPhase {
  if (!s.signedIn) return 'signed-out';
  if (!s.hasPasscode || !s.setupComplete) return 'setup';
  return 'locked';
}
