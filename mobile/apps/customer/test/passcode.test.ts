import { describe, expect, it } from 'vitest';

import { digestsEqual, LOCKED_OUT_MESSAGE, MAX_PASSCODE_ATTEMPTS, passcodeSetupStep, recordUnlockAttempt, validateNewPasscode } from '../src/domain/passcode';

describe('passcode setup', () => {
  it('rejects short and repeated-digit passcodes', () => {
    expect(validateNewPasscode('123')).toMatch(/6 digits/);
    expect(validateNewPasscode('111111')).toBe('Too easy to guess. Avoid repeating one digit.');
    expect(validateNewPasscode('204816')).toBeNull();
  });

  it('asks to confirm and stores on a match', () => {
    const a = passcodeSetupStep({ stage: 'new' }, '204816');
    expect(a.next).toEqual({ stage: 'confirm', first: '204816' });
    const b = passcodeSetupStep(a.next, '204816');
    expect(b.done).toBe('204816');
  });

  it('starts again when the confirmation differs', () => {
    const b = passcodeSetupStep({ stage: 'confirm', first: '204816' }, '204817');
    expect(b.next).toEqual({ stage: 'new' });
    expect(b.error).toBe('Passcodes didn’t match. Start again.');
    expect(b.done).toBeUndefined();
  });
});

describe('unlock attempts', () => {
  it('counts down and forces full sign-in after five wrong passcodes', () => {
    let attempts = 0;
    const messages: string[] = [];
    for (let i = 0; i < MAX_PASSCODE_ATTEMPTS - 1; i += 1) {
      const r = recordUnlockAttempt(attempts, false);
      expect(r.kind).toBe('retry');
      attempts = r.attempts;
      if (r.kind === 'retry') messages.push(r.message);
    }
    expect(messages).toEqual(['Wrong passcode · 4 attempts left', 'Wrong passcode · 3 attempts left', 'Wrong passcode · 2 attempts left', 'Wrong passcode · 1 attempt left']);
    const last = recordUnlockAttempt(attempts, false);
    expect(last).toEqual({ kind: 'locked-out', attempts: 5, message: LOCKED_OUT_MESSAGE });
  });

  it('resets the counter on success', () => {
    expect(recordUnlockAttempt(3, true)).toEqual({ kind: 'unlocked', attempts: 0 });
  });

  it('compares digests', () => {
    expect(digestsEqual('abcd', 'abcd')).toBe(true);
    expect(digestsEqual('abcd', 'abce')).toBe(false);
    expect(digestsEqual('abc', 'abcd')).toBe(false);
  });
});
