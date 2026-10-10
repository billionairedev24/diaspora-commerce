import { describe, expect, it } from 'vitest';

import { LOCK_GRACE_MS, phaseOnLaunch, shouldLockOnResume } from '../src/domain/lockPolicy';

describe('lock policy', () => {
  it('locks after the grace period in the background', () => {
    expect(shouldLockOnResume(null, 1000)).toBe(false);
    expect(shouldLockOnResume(0, LOCK_GRACE_MS - 1)).toBe(false);
    expect(shouldLockOnResume(0, LOCK_GRACE_MS)).toBe(true);
  });

  it('opens on sign-in, setup or unlock', () => {
    expect(phaseOnLaunch({ signedIn: false, hasPasscode: true, setupComplete: true })).toBe('signed-out');
    expect(phaseOnLaunch({ signedIn: true, hasPasscode: false, setupComplete: false })).toBe('setup');
    expect(phaseOnLaunch({ signedIn: true, hasPasscode: true, setupComplete: true })).toBe('locked');
  });
});
