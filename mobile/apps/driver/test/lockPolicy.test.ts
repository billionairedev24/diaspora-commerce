import { describe, expect, it } from 'vitest';

import { coversApp, firstNameOf, LOCK_GRACE_MS, phaseOnLaunch, shouldLockOnResume } from '../src/domain/lockPolicy';

describe('lock policy', () => {
  it('locks after the grace period in the background', () => {
    expect(shouldLockOnResume(null, 1000)).toBe(false);
    expect(shouldLockOnResume(0, LOCK_GRACE_MS - 1)).toBe(false);
    expect(shouldLockOnResume(0, LOCK_GRACE_MS)).toBe(true);
  });

  it('opens on sign-in, setup or the lock', () => {
    expect(phaseOnLaunch({ signedIn: false, hasPasscode: true, setupComplete: true })).toBe('signed-out');
    expect(phaseOnLaunch({ signedIn: true, hasPasscode: false, setupComplete: false })).toBe('setup');
    expect(phaseOnLaunch({ signedIn: true, hasPasscode: true, setupComplete: false })).toBe('setup');
    expect(phaseOnLaunch({ signedIn: true, hasPasscode: true, setupComplete: true })).toBe('locked');
  });

  it('greets by first name, and not by an email address', () => {
    expect(firstNameOf('Tunde Adeyemi')).toBe('Tunde');
    expect(firstNameOf('  Tunde ')).toBe('Tunde');
    expect(firstNameOf('tunde@afrotransact.com')).toBe('');
    expect(firstNameOf('')).toBe('');
  });

  it('only setup and locked hide the app', () => {
    expect(coversApp('setup')).toBe(true);
    expect(coversApp('locked')).toBe(true);
    expect(coversApp('unlocked')).toBe(false);
    expect(coversApp('signed-out')).toBe(false);
  });
});
