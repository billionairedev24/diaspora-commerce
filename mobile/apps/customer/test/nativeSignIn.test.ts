import { ApiError } from '@afrotransact/api-client';
import { describe, expect, it, vi } from 'vitest';

import { type JsonRequester } from '../src/api/http';
import { base64Url, bytesToBase64, isChallengeExpired, makePkce, NativeSignIn, signInMessage } from '../src/domain/nativeSignIn';

const pkce = { verifier: 'v'.repeat(43), challenge: 'c'.repeat(43) };

function flow(responses: Array<unknown | Error>, trusted: string | null = null) {
  const calls: Array<{ path: string; body: Record<string, unknown> }> = [];
  const queue = [...responses];
  const request = (async (path: string, opts?: { body?: Record<string, unknown> }) => {
    calls.push({ path, body: opts?.body ?? {} });
    const next = queue.shift();
    if (next instanceof Error) throw next;
    return next;
  }) as unknown as JsonRequester;
  const store = new Map<string, string>();
  const exchange = vi.fn(async (code: string, verifier: string) => ({ code, verifier }));
  const f = new NativeSignIn({
    request, app: 'customer', redirectUri: 'com.afrotransact.customer:/oauth2redirect', pkce, exchange,
    trustedDevice: { get: async () => trusted, set: async (e, t) => void store.set(e, t) }
  });
  return { f, calls, exchange, store };
}

describe('PKCE', () => {
  it('makes a 43-character S256 pair from 32 random bytes', async () => {
    const p = await makePkce((n) => new Uint8Array(n).fill(251), async () => '+/+/==');
    expect(p.verifier).toHaveLength(43);
    expect(p.verifier).not.toMatch(/[+/=]/);
    expect(p.challenge).toBe('-_-_');
  });
  it('encodes base64 and base64url', () => {
    expect(bytesToBase64(new Uint8Array([104, 105]))).toBe('aGk=');
    expect(base64Url('a+b/c==')).toBe('a-b_c');
  });
});

describe('NativeSignIn', () => {
  it('trades the code for tokens when the password is enough', async () => {
    const { f, calls, exchange } = flow([{ status: 'AUTHENTICATED', code: 'code-1' }]);
    const step = await f.password(' Ada@Example.com ', 'Passw0rd!');
    expect(step).toEqual({ kind: 'done', trustedDeviceToken: null, recoveryCodes: [], mfaSetupSuggested: false });
    expect(calls[0]).toEqual({ path: '/v1/auth/native/password', body: { app: 'customer', codeChallenge: pkce.challenge, redirectUri: 'com.afrotransact.customer:/oauth2redirect', email: 'ada@example.com', password: 'Passw0rd!' } });
    expect(exchange).toHaveBeenCalledWith('code-1', pkce.verifier);
    expect(f.tokens).toEqual({ code: 'code-1', verifier: pkce.verifier });
  });

  it('asks for the second factor, then signs in and remembers the phone', async () => {
    const { f, calls, store } = flow([
      { status: 'MFA_REQUIRED', challengeId: 'chal-1', factors: { totp: true, sms: true, smsHint: '•••• 0142', recoveryCode: true } },
      { sentTo: '•••• 0142' },
      { status: 'AUTHENTICATED', code: 'code-2', trustedDeviceToken: 'trusted-1' }
    ]);
    const first = await f.password('ada@example.com', 'pw');
    expect(first).toMatchObject({ kind: 'second-factor', challengeId: 'chal-1' });
    expect(f.tokens).toBeNull();
    expect(await f.sendSmsCode()).toBe('•••• 0142');
    const done = await f.verify('SMS', '123 456', true);
    expect(done).toEqual({ kind: 'done', trustedDeviceToken: 'trusted-1', recoveryCodes: [], mfaSetupSuggested: false });
    expect(calls[2]?.body).toMatchObject({ challengeId: 'chal-1', factor: 'SMS', code: '123456', trustDevice: true, codeChallenge: pkce.challenge });
    expect(store.get('ada@example.com')).toBe('trusted-1');
  });

  it('emails a code for the email factor and verifies it', async () => {
    const { f, calls } = flow([
      { status: 'MFA_REQUIRED', challengeId: 'chal-3', factors: { totp: true, emailOtp: true, emailHint: 'a••@example.com', sms: false, recoveryCode: true } },
      { sentTo: 'a••@example.com' },
      { status: 'AUTHENTICATED', code: 'code-3' }
    ]);
    const first = await f.password('ada@example.com', 'pw');
    expect(first).toMatchObject({ kind: 'second-factor', factors: { emailOtp: true, sms: false } });
    expect(await f.sendEmailCode()).toBe('a••@example.com');
    expect(calls[1]).toMatchObject({ path: '/v1/auth/native/mfa/email', body: { challengeId: 'chal-3' } });
    expect(await f.verify('EMAIL_OTP', '123456', false)).toMatchObject({ kind: 'done' });
    expect(calls[2]?.body).toMatchObject({ factor: 'EMAIL_OTP', code: '123456' });
  });

  it('forces enrolment when two-step verification is mandatory and nothing is set up: no code, enrol, then sign in with recovery codes', async () => {
    const { f, calls, exchange } = flow([
      { status: 'MFA_ENROLMENT_REQUIRED', challengeId: 'chal-e', factors: { totp: false, sms: false, recoveryCode: false, enrolMethods: ['TOTP', 'EMAIL_OTP'] } },
      { secret: 'JBSWY3DPEHPK3PXP', otpauthUri: 'otpauth://totp/AfroTransact:a@b.co?secret=JBSWY3DPEHPK3PXP' },
      { recoveryCodes: ['abcde-fghjk'], status: 'AUTHENTICATED', code: 'code-e' }
    ]);
    const first = await f.password('ada@example.com', 'pw');
    expect(first).toEqual({ kind: 'enrol', challengeId: 'chal-e', methods: ['TOTP', 'EMAIL_OTP'] });
    expect(f.tokens).toBeNull();
    expect((await f.enrolTotp()).secret).toBe('JBSWY3DPEHPK3PXP');
    expect(calls[1]?.path).toBe('/v1/auth/native/mfa/enrol/totp');
    const done = await f.confirmEnrol('TOTP', '123 456');
    expect(done).toMatchObject({ kind: 'done', recoveryCodes: ['abcde-fghjk'] });
    expect(calls[2]).toMatchObject({ path: '/v1/auth/native/mfa/enrol/totp/confirm', body: { challengeId: 'chal-e', code: '123456' } });
    expect(exchange).toHaveBeenCalledWith('code-e', pkce.verifier);
  });

  it('enrols email codes by emailing a first code to the verified address', async () => {
    const { f, calls } = flow([
      { status: 'MFA_ENROLMENT_REQUIRED', challengeId: 'chal-m', factors: { totp: false, sms: false, recoveryCode: false } },
      { sentTo: 's••@example.com' },
      { recoveryCodes: [], status: 'AUTHENTICATED', code: 'code-m' }
    ]);
    const first = await f.password('staff@afrotransact.com', 'pw');
    expect(first).toMatchObject({ kind: 'enrol', methods: ['TOTP', 'EMAIL_OTP'] });
    expect(await f.enrolEmail()).toBe('s••@example.com');
    expect(calls[1]?.path).toBe('/v1/auth/native/mfa/enrol/email');
    await f.confirmEnrol('EMAIL_OTP', '654321');
    expect(calls[2]?.path).toBe('/v1/auth/native/mfa/enrol/email/confirm');
  });

  it('passes the "secure your account" suggestion on a password-only sign-in', async () => {
    const { f } = flow([{ status: 'AUTHENTICATED', code: 'c', mfaSetupSuggested: true }]);
    expect(await f.password('ada@example.com', 'pw')).toMatchObject({ kind: 'done', mfaSetupSuggested: true });
  });

  it('sends the trusted-device token it kept so the code is skipped', async () => {
    const { f, calls } = flow([{ status: 'AUTHENTICATED', code: 'c' }], 'trusted-1');
    await f.password('ada@example.com', 'pw');
    expect(calls[0]?.body.trustedDeviceToken).toBe('trusted-1');
  });

  it('fails when the server answers without a code', async () => {
    const { f } = flow([{ status: 'AUTHENTICATED' }]);
    await expect(f.password('a@b.co', 'pw')).rejects.toThrow('did not complete');
  });
});

describe('messages', () => {
  const problem = (status: number, detail: string, code?: string, errors?: Record<string, string>) => new ApiError({ type: 'about:blank', title: 'x', status, detail, code, errors });
  it('shows the server wording, a field error first, and a connection message for transport failures', () => {
    expect(signInMessage(problem(401, 'The email or password is incorrect.', 'INVALID_CREDENTIALS'), 'fallback')).toBe('The email or password is incorrect.');
    expect(signInMessage(problem(422, 'Invalid', 'INVALID_CODE', { code: 'That code is incorrect or expired.' }), 'fallback')).toBe('That code is incorrect or expired.');
    expect(signInMessage(new TypeError('Network request failed'), 'fallback')).toMatch(/connection/);
    expect(signInMessage('boom', 'fallback')).toBe('fallback');
  });
  it('knows when the attempt expired', () => {
    expect(isChallengeExpired(problem(410, 'expired', 'CHALLENGE_EXPIRED'))).toBe(true);
    expect(isChallengeExpired(problem(422, 'wrong code', 'INVALID_CODE'))).toBe(false);
  });
});
