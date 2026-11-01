import { ApiError } from '@afrotransact/api-client';
import { describe, expect, it } from 'vitest';

import { createJsonRequester } from '../src/api/http';
import { isOtpComplete, normalizeOtp, otpActiveBox, otpBoxes, shouldAutoSubmit } from '../src/domain/otp';
import { cooldownSecondsLeft, formatCooldown, RESEND_COOLDOWN_SECONDS } from '../src/domain/resendCooldown';
import { setupProgress } from '../src/domain/setupProgress';
import { describeSignInError, isValidEmail, parseRetryAfter, retryWait } from '../src/domain/signInErrors';

const problem = (status: number, code: string, extra: Record<string, unknown> = {}) =>
  new ApiError({ type: 'about:blank', title: 'T', status, code, detail: 'Server words.', ...extra });

describe('one-time code input', () => {
  it('keeps digits only and at most six (paste "123 456", "12-34-56", autofill with a prefix)', () => {
    expect(normalizeOtp('123 456')).toBe('123456');
    expect(normalizeOtp('12-34-56')).toBe('123456');
    expect(normalizeOtp('Code: 987654.')).toBe('987654');
    expect(normalizeOtp('1234567890')).toBe('123456');
    expect(normalizeOtp('abc')).toBe('');
  });
  it('lays the value over six boxes and points at the next empty one', () => {
    expect(otpBoxes('12')).toEqual(['1', '2', '', '', '', '']);
    expect(otpActiveBox('')).toBe(0);
    expect(otpActiveBox('12')).toBe(2);
    expect(otpActiveBox('123456')).toBe(5);
  });
  it('backspace is a shorter value', () => {
    expect(otpBoxes(normalizeOtp('12345'.slice(0, -1)))).toEqual(['1', '2', '3', '4', '', '']);
  });
  it('submits once, on the change that completes the code', () => {
    expect(isOtpComplete('123456')).toBe(true);
    expect(shouldAutoSubmit('12345', '123456')).toBe(true);
    expect(shouldAutoSubmit('', '123456')).toBe(true); // a paste or an SMS autofill
    expect(shouldAutoSubmit('123456', '123456')).toBe(false);
    expect(shouldAutoSubmit('123456', '12345')).toBe(false);
    expect(shouldAutoSubmit('1234', '12345')).toBe(false);
  });
});

describe('resend cooldown', () => {
  it('is 30 seconds and formats as m:ss', () => {
    expect(RESEND_COOLDOWN_SECONDS).toBe(30);
    expect(formatCooldown(24)).toBe('0:24');
    expect(formatCooldown(30)).toBe('0:30');
    expect(formatCooldown(5)).toBe('0:05');
    expect(formatCooldown(90)).toBe('1:30');
    expect(formatCooldown(-3)).toBe('0:00');
  });
  it('counts whole seconds left and stops at zero', () => {
    expect(cooldownSecondsLeft(30_000, 0)).toBe(30);
    expect(cooldownSecondsLeft(30_000, 5_500)).toBe(25);
    expect(cooldownSecondsLeft(30_000, 30_000)).toBe(0);
    expect(cooldownSecondsLeft(30_000, 99_000)).toBe(0);
  });
});

describe('setup step indicator', () => {
  it('is 1 of 3 for Face ID, 2 of 3 for the passcode and 3 of 3 for notifications', () => {
    expect(setupProgress('face-id')).toEqual({ step: 1, total: 3 });
    expect(setupProgress('passcode')).toEqual({ step: 2, total: 3 });
    expect(setupProgress('notifications')).toEqual({ step: 3, total: 3 });
  });
});

describe('sign-in error to field mapping', () => {
  it('a wrong password marks both fields and carries no server text', () => {
    expect(describeSignInError(problem(401, 'INVALID_CREDENTIALS'))).toEqual({ kind: 'credentials', fields: ['email', 'password'] });
  });
  it('a wrong code marks the code', () => {
    expect(describeSignInError(problem(422, 'INVALID_CODE', { errors: { code: 'That code is incorrect.' } }))).toEqual({ kind: 'invalid-code', fields: ['code'] });
  });
  it('validation errors mark the field the server names, with its wording', () => {
    expect(describeSignInError(problem(422, 'VALIDATION_FAILED', { errors: { password: 'Enter your password.' } }))).toEqual({ kind: 'server', fields: ['password'], message: 'Enter your password.' });
    expect(describeSignInError(problem(422, 'VALIDATION_FAILED', { errors: { email: 'Enter your email address.' } })).fields).toEqual(['email']);
  });
  it('a rate limit keeps the server message and the wait the server sent', () => {
    expect(describeSignInError(problem(429, 'RATE_LIMITED', { retryAfterSeconds: 840 }))).toEqual({ kind: 'rate-limited', fields: [], message: 'Server words.', retryAfterSeconds: 840 });
    const none = describeSignInError(problem(429, 'RATE_LIMITED'));
    expect(none.kind).toBe('rate-limited');
    expect('retryAfterSeconds' in none).toBe(false);
  });
  it('an expired attempt sends the person back to the password step', () => {
    expect(describeSignInError(problem(410, 'CHALLENGE_EXPIRED')).kind).toBe('expired');
  });
  it('403, transport and unknown failures are not attributed to a field', () => {
    expect(describeSignInError(problem(403, 'FORBIDDEN'))).toEqual({ kind: 'server', fields: [], message: 'Server words.' });
    expect(describeSignInError(new TypeError('Network request failed'))).toEqual({ kind: 'network', fields: [] });
    expect(describeSignInError('boom')).toEqual({ kind: 'unknown', fields: [] });
  });
  it('words a wait as seconds, minutes or hours', () => {
    expect(retryWait(45)).toEqual({ unit: 'seconds', n: 45 });
    expect(retryWait(900)).toEqual({ unit: 'minutes', n: 15 });
    expect(retryWait(61)).toEqual({ unit: 'minutes', n: 2 });
    expect(retryWait(7200)).toEqual({ unit: 'hours', n: 2 });
  });
  it('validates the email before any call', () => {
    expect(isValidEmail(' ayo@example.com ')).toBe(true);
    expect(isValidEmail('ayo@example')).toBe(false);
    expect(isValidEmail('')).toBe(false);
  });
});

describe('Retry-After', () => {
  it('reads delta-seconds only', () => {
    expect(parseRetryAfter('900')).toBe(900);
    expect(parseRetryAfter('1.2')).toBe(2);
    expect(parseRetryAfter('0')).toBeUndefined();
    expect(parseRetryAfter('Wed, 21 Oct 2026 07:28:00 GMT')).toBeUndefined();
    expect(parseRetryAfter(null)).toBeUndefined();
  });
  it('is kept on the ApiError the requester throws, so the form can show the wait', async () => {
    const request = createJsonRequester('https://bff.test', async () =>
      new Response(JSON.stringify({ type: 'about:blank', title: 'Too many attempts', status: 429, code: 'RATE_LIMITED', detail: 'Too many sign-in attempts.' }), { status: 429, headers: { 'Retry-After': '900', 'Content-Type': 'application/problem+json' } }));
    const e = await request('/v1/auth/native/password', { method: 'POST', body: {} }).catch((x: unknown) => x);
    expect(e).toBeInstanceOf(ApiError);
    expect(describeSignInError(e)).toMatchObject({ kind: 'rate-limited', retryAfterSeconds: 900 });
  });
});
