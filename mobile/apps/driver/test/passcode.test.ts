import { describe, expect, it } from 'vitest';

import { digestsEqual, MAX_PASSCODE_ATTEMPTS, passcodeDigestInput, passcodeSetupStep, recordUnlockAttempt, validateNewPasscode } from '../src/domain/passcode';

describe('passcode setup', () => {
  it('rejects short and repeated-digit passcodes', () => {
    expect(validateNewPasscode('123')).toBe('length');
    expect(validateNewPasscode('12345a')).toBe('length');
    expect(validateNewPasscode('111111')).toBe('repeating');
    expect(validateNewPasscode('204816')).toBeNull();
  });

  it('asks to confirm and stores on a match', () => {
    const a = passcodeSetupStep({ stage: 'new' }, '204816');
    expect(a.next).toEqual({ stage: 'confirm', first: '204816' });
    expect(passcodeSetupStep(a.next, '204816').done).toBe('204816');
  });

  it('starts again when the confirmation differs', () => {
    const b = passcodeSetupStep({ stage: 'confirm', first: '204816' }, '204817');
    expect(b.next).toEqual({ stage: 'new' });
    expect(b.error).toBe('mismatch');
    expect(b.done).toBeUndefined();
  });

  it('stays on "create" after a weak passcode', () => {
    const r = passcodeSetupStep({ stage: 'new' }, '000000');
    expect(r.next).toEqual({ stage: 'new' });
    expect(r.error).toBe('repeating');
  });
});

describe('unlock attempts', () => {
  it('counts down and forces a full sign-in after five wrong passcodes', () => {
    let attempts = 0;
    const left: number[] = [];
    for (let i = 0; i < MAX_PASSCODE_ATTEMPTS - 1; i += 1) {
      const r = recordUnlockAttempt(attempts, false);
      expect(r.kind).toBe('retry');
      attempts = r.attempts;
      if (r.kind === 'retry') left.push(r.left);
    }
    expect(left).toEqual([4, 3, 2, 1]);
    expect(recordUnlockAttempt(attempts, false)).toEqual({ kind: 'locked-out', attempts: 5 });
  });

  it('resets the counter on success', () => {
    expect(recordUnlockAttempt(3, true)).toEqual({ kind: 'unlocked', attempts: 0 });
  });

  it('a counter persisted at 4 locks out on the very next wrong entry (restart does not reset it)', () => {
    expect(recordUnlockAttempt(4, false).kind).toBe('locked-out');
  });

  it('salts the digest input and compares digests', () => {
    expect(passcodeDigestInput('s', '204816')).toBe('s:204816');
    expect(digestsEqual('abcd', 'abcd')).toBe(true);
    expect(digestsEqual('abcd', 'abce')).toBe(false);
    expect(digestsEqual('abc', 'abcd')).toBe(false);
  });
});
