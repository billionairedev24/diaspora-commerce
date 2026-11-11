/* Glue for the in-app sign-in (domain/nativeSignIn.ts): Expo crypto for PKCE, the BFF as the requester, the trusted
   device tokens in the secure store. Mocks (EXPO_PUBLIC_MOCKS) sign in as the demo driver of that email; an email starting with
   "mfa" asks for the code 123456 so the second-factor screen can be tried. */
import * as Crypto from 'expo-crypto';

import { ApiProblemError, NetworkError } from '@/api/http';
import { env } from '@/config/env';
import { type EnrolMethod, type JsonRequester, makePkce, NativeSignIn, type Pkce, type SignInStep, type TotpSetup } from '@/domain/nativeSignIn';

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
  let body: unknown;
  try {
    body = text ? JSON.parse(text) : undefined;
  } catch {
    body = undefined; // a proxy's HTML error page: the status alone decides the message
  }
  if (!res.ok) {
    const wait = Number.parseInt(res.headers.get('Retry-After') ?? '', 10);
    throw new ApiProblemError(res.status, body && typeof body === 'object' ? (body as Record<string, unknown>) : {}, Number.isFinite(wait) && wait > 0 ? wait : undefined);
  }
  return body as T;
};

export async function newPkce(): Promise<Pkce> {
  return makePkce((n) => Crypto.getRandomBytes(n), (input) => Crypto.digestStringAsync(Crypto.CryptoDigestAlgorithm.SHA256, input, { encoding: Crypto.CryptoEncoding.BASE64 }));
}

export type { SignInStep };

export interface NativeAttempt {
  password(email: string, password: string): Promise<SignInStep>;
  sendSmsCode(): Promise<string>;
  sendEmailCode(): Promise<string>;
  verify(factor: 'TOTP' | 'EMAIL_OTP' | 'SMS' | 'RECOVERY_CODE', code: string, trustDevice: boolean): Promise<SignInStep>;
  /** Forced enrolment (mandatory two-step verification, nothing set up yet): no code was sent before this. */
  enrolTotp(): Promise<TotpSetup>;
  enrolEmail(): Promise<string>;
  confirmEnrol(method: EnrolMethod, code: string): Promise<SignInStep>;
  tokens(): TokenSet | null;
}

export async function beginNativeSignIn(): Promise<NativeAttempt> {
  if (env.mocks) return mockAttempt();
  const flow = new NativeSignIn<TokenSet>({ request, app: 'driver', redirectUri: redirectUri(), pkce: await newPkce(), exchange: exchangeSignInCode, trustedDevice });
  return {
    password: (e, p) => flow.password(e, p),
    sendSmsCode: () => flow.sendSmsCode(),
    sendEmailCode: () => flow.sendEmailCode(),
    verify: (f, c, t) => flow.verify(f, c, t),
    enrolTotp: () => flow.enrolTotp(),
    enrolEmail: () => flow.enrolEmail(),
    confirmEnrol: (m, c) => flow.confirmEnrol(m, c),
    tokens: () => flow.tokens
  };
}

function mockAttempt(): NativeAttempt {
  let tokens: TokenSet | null = null;
  let needsCode = false;
  let enrolling = false;
  let who = '';
  const finish = (recoveryCodes: string[] = []): SignInStep => {
    tokens = mockTokens(who);
    return { kind: 'done', trustedDeviceToken: null, recoveryCodes, mfaSetupSuggested: false };
  };
  const wrong = (title: string, status: number, code: string) => new ApiProblemError(status, { title, code, detail: title });
  return {
    async password(email, password) {
      who = email.trim().toLowerCase();
      if (!password) throw wrong('Enter your password.', 422, 'VALIDATION_FAILED');
      if (password === 'limit') throw new ApiProblemError(429, { title: 'Too many attempts', code: 'RATE_LIMITED' }, 45);
      if (password === 'wrong') throw wrong('The email or password is incorrect.', 401, 'INVALID_CREDENTIALS');
      if (who.startsWith('mfa')) {
        needsCode = true;
        return { kind: 'second-factor', challengeId: 'mock-challenge', factors: { totp: true, emailOtp: true, emailHint: 'm••@example.com', sms: false, recoveryCode: true } };
      }
      if (who.startsWith('staff')) {
        enrolling = true;
        return { kind: 'enrol', challengeId: 'mock-enrol', methods: ['TOTP', 'EMAIL_OTP'] };
      }
      return finish();
    },
    async sendSmsCode() {
      return '•••• 0142';
    },
    async sendEmailCode() {
      return 'm••@example.com';
    },
    async enrolTotp() {
      return { secret: 'JBSWY3DPEHPK3PXP', otpauthUri: 'otpauth://totp/AfroTransact:staff@example.com?secret=JBSWY3DPEHPK3PXP&issuer=AfroTransact' };
    },
    async enrolEmail() {
      return 's••@example.com';
    },
    async confirmEnrol(_method, code) {
      if (!enrolling || code.replace(/\s+/g, '') !== '123456') throw wrong('That code is incorrect or expired.', 422, 'INVALID_CODE');
      return finish(['abcde-fghjk', 'mnpqr-stuvw', 'xyzab-cdefg']);
    },
    async verify(_factor, code) {
      if (!needsCode || code.replace(/\s+/g, '') !== '123456') throw wrong('That code is incorrect or expired.', 422, 'INVALID_CODE');
      return finish();
    },
    tokens: () => tokens
  };
}
