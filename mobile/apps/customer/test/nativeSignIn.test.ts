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
    expect(step).toEqual({ kind: 'done', trustedDeviceToken: null });
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
    expect(done).toEqual({ kind: 'done', trustedDeviceToken: 'trusted-1' });
    expect(calls[2]?.body).toMatchObject({ challengeId: 'chal-1', factor: 'SMS', code: '123456', trustDevice: true, codeChallenge: pkce.challenge });
    expect(store.get('ada@example.com')).toBe('trusted-1');
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
