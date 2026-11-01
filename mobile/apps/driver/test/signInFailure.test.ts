import { describe, expect, it } from 'vitest';

import { ApiProblemError, NetworkError } from '../src/api/http';
import { classifySignInFailure, failureField } from '../src/domain/signInFailure';

const problem = (status: number, code: string, extra: Record<string, unknown> = {}, wait?: number) => new ApiProblemError(status, { code, detail: 'server words', ...extra }, wait);

describe('sign-in failures (BFF native sign-in problems)', () => {
  it('a wrong password is a credentials failure that marks the password field', () => {
    const f = classifySignInFailure(problem(401, 'INVALID_CREDENTIALS'), 'password');
    expect(f).toEqual({ kind: 'credentials' });
    expect(failureField(f)).toBe('password');
  });

  it('a wrong or expired code marks the code boxes', () => {
    const f = classifySignInFailure(problem(422, 'INVALID_CODE', { errors: { code: 'x' } }), 'code');
    expect(f).toEqual({ kind: 'invalid-code' });
    expect(failureField(f)).toBe('code');
  });

  it('a rate limit carries only the Retry-After the server sent', () => {
    expect(classifySignInFailure(problem(429, 'RATE_LIMITED', {}, 90), 'password')).toEqual({ kind: 'rate-limited', retryAfterSeconds: 90 });
    expect(classifySignInFailure(problem(429, 'RATE_LIMITED'), 'code')).toEqual({ kind: 'rate-limited', retryAfterSeconds: null });
  });

  it('an expired challenge (410) means start again, wherever it comes from', () => {
    expect(classifySignInFailure(problem(410, 'CHALLENGE_EXPIRED'), 'code')).toEqual({ kind: 'expired' });
  });

  it('a missing field marks that field', () => {
    const f = classifySignInFailure(problem(422, 'VALIDATION_FAILED', { errors: { email: 'Enter your email address.' } }), 'password');
    expect(f).toEqual({ kind: 'field-required', field: 'email' });
    expect(failureField(f)).toBe('email');
  });

  it('403 refusals keep the server wording; outages and offline are told apart', () => {
    expect(classifySignInFailure(problem(403, 'FORBIDDEN'), 'password')).toEqual({ kind: 'refused', detail: 'server words' });
    expect(classifySignInFailure(problem(503, 'UPSTREAM_UNAVAILABLE'), 'password')).toEqual({ kind: 'unavailable' });
    expect(classifySignInFailure(new SyntaxError('Unexpected token <'), 'password')).toEqual({ kind: 'unavailable' });
    expect(classifySignInFailure(new NetworkError(new Error('x'), 'bff'), 'password')).toEqual({ kind: 'offline' });
    expect(classifySignInFailure(new TypeError('Network request failed'), 'password')).toEqual({ kind: 'offline' });
    expect(classifySignInFailure(new Error('boom'), 'password')).toEqual({ kind: 'unknown' });
  });
});
