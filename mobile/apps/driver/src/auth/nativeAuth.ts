/* Glue for the in-app sign-in (domain/nativeSignIn.ts): Expo crypto for PKCE, the BFF as the requester, the trusted
   device tokens in the secure store. Mocks (EXPO_PUBLIC_MOCKS) sign in as the demo driver of that email; an email starting with
   "mfa" asks for the code 123456 so the second-factor screen can be tried. */
import * as Crypto from 'expo-crypto';

import { ApiProblemError, NetworkError } from '@/api/http';
import { env } from '@/config/env';
import { type JsonRequester, makePkce, NativeSignIn, type Pkce, type SignInStep } from '@/domain/nativeSignIn';

import { exchangeSignInCode, mockTokens, redirectUri, type TokenSet } from './oidc';
import { secureStorage } from './secureStorage';

const trustedKey = (email: string) => `at.driver.trusted.${email.replace(/[^a-z0-9._-]/g, '_')}`;

const trustedDevice = {
  get: (email: string) => secureStorage.get(trustedKey(email)).catch(() => null),
  set: (email: string, token: string) => secureStorage.set(trustedKey(email), token).catch(() => undefined)
};

/** Plain fetch: sign-in is the one call made without a token (a 401 here is a wrong password, not a lost session). */
const request: JsonRequester = async <T,>(path: string, opts: { method: 'POST'; body: Record<string, unknown> }): Promise<T> => {
  let res: Response;
  try {
    res = await fetch(`${env.bffUrl}${path}`, { method: opts.method, headers: { Accept: 'application/json', 'Content-Type': 'application/json', 'X-Client': 'driver-app' }, body: JSON.stringify(opts.body) });
  } catch (e) {
    throw new NetworkError(e, new URL(env.bffUrl).host);
  }
  const text = await res.text();
  const body: unknown = text ? JSON.parse(text) : undefined;
  if (!res.ok) throw new ApiProblemError(res.status, body && typeof body === 'object' ? (body as Record<string, unknown>) : {});
  return body as T;
};

export async function newPkce(): Promise<Pkce> {
  return makePkce((n) => Crypto.getRandomBytes(n), (input) => Crypto.digestStringAsync(Crypto.CryptoDigestAlgorithm.SHA256, input, { encoding: Crypto.CryptoEncoding.BASE64 }));
}

export type { SignInStep };

export interface NativeAttempt {
  password(email: string, password: string): Promise<SignInStep>;
  sendSmsCode(): Promise<string>;
  verify(factor: 'TOTP' | 'SMS' | 'RECOVERY_CODE', code: string, trustDevice: boolean): Promise<SignInStep>;
  tokens(): TokenSet | null;
}

export async function beginNativeSignIn(): Promise<NativeAttempt> {
  if (env.mocks) return mockAttempt();
  const flow = new NativeSignIn<TokenSet>({ request, app: 'driver', redirectUri: redirectUri(), pkce: await newPkce(), exchange: exchangeSignInCode, trustedDevice });
  return {
    password: (e, p) => flow.password(e, p),
    sendSmsCode: () => flow.sendSmsCode(),
    verify: (f, c, t) => flow.verify(f, c, t),
    tokens: () => flow.tokens
  };
}

function mockAttempt(): NativeAttempt {
  let tokens: TokenSet | null = null;
  let needsCode = false;
  let who = '';
  const finish = (): SignInStep => {
    tokens = mockTokens(who);
    return { kind: 'done', trustedDeviceToken: null };
  };
  const wrong = (title: string, status: number, code: string) => new ApiProblemError(status, { title, code, detail: title });
  return {
    async password(email, password) {
      who = email.trim().toLowerCase();
      if (!password) throw wrong('Enter your password.', 422, 'VALIDATION_FAILED');
      if (password === 'wrong') throw wrong('The email or password is incorrect.', 401, 'INVALID_CREDENTIALS');
      if (who.startsWith('mfa')) {
        needsCode = true;
        return { kind: 'second-factor', challengeId: 'mock-challenge', factors: { totp: true, sms: true, smsHint: '•••• 0142', recoveryCode: true } };
      }
      return finish();
    },
    async sendSmsCode() {
      return '•••• 0142';
    },
    async verify(_factor, code) {
      if (!needsCode || code.replace(/\s+/g, '') !== '123456') throw wrong('That code is incorrect or expired.', 422, 'INVALID_CODE');
      return finish();
    },
    tokens: () => tokens
  };
}
