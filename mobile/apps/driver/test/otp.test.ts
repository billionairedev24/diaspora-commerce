import { describe, expect, it } from 'vitest';

import { formatWait, secondsLeft, SMS_RESEND_SECONDS } from '../src/domain/cooldown';
import { activeCell, justCompleted, otpCells, sanitizeOtp } from '../src/domain/otp';

describe('one-time code input', () => {
  it('keeps digits only and at most six (paste, autofill)', () => {
    expect(sanitizeOtp('123 456')).toBe('123456');
    expect(sanitizeOtp('123-456789')).toBe('123456');
    expect(sanitizeOtp('ab1c2')).toBe('12');
    expect(sanitizeOtp('')).toBe('');
  });

  it('draws one box per digit and focuses the next empty one', () => {
    expect(otpCells('12')).toEqual(['1', '2', '', '', '', '']);
    expect(activeCell('')).toBe(0);
    expect(activeCell('123')).toBe(3);
    expect(activeCell('123456')).toBe(5);
  });

  it('submits once when the sixth digit arrives, not when deleting', () => {
    expect(justCompleted('12345', '123456')).toBe(true);
    expect(justCompleted('123456', '123456')).toBe(false);
    expect(justCompleted('123456', '12345')).toBe(false);
    expect(justCompleted('', '123456')).toBe(true);
  });
});

describe('resend cooldown', () => {
  it('is 30 seconds and counts down to zero', () => {
    expect(SMS_RESEND_SECONDS).toBe(30);
    const until = 1_000_000 + SMS_RESEND_SECONDS * 1000;
    expect(secondsLeft(until, 1_000_000)).toBe(30);
    expect(secondsLeft(until, 1_000_000 + 29_001)).toBe(1);
    expect(secondsLeft(until, until)).toBe(0);
    expect(secondsLeft(until, until + 5000)).toBe(0);
  });

  it('formats waits as m:ss', () => {
    expect(formatWait(7)).toBe('0:07');
    expect(formatWait(125)).toBe('2:05');
    expect(formatWait(-3)).toBe('0:00');
  });
});
