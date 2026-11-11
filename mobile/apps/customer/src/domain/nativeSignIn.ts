/* In-app sign-in (no hosted page, no system browser): the form posts email + password to the BFF, which checks them
   with identity (second factor when the account has one) and returns an authorization code for this app's own PKCE
   request; the app exchanges the code at identity's token endpoint and holds its own tokens. This module is pure
   (crypto, HTTP and the code exchange are injected) so the whole state machine is unit-tested. */
import { ApiError } from '@afrotransact/api-client';

import type { JsonRequester } from '@/api/http';

/** SMS only appears while identity's SMS feature flag is on (off by default). */
export type SecondFactor = 'TOTP' | 'EMAIL_OTP' | 'SMS' | 'RECOVERY_CODE';

/** What a person with mandatory two-step verification and nothing set up may enrol (no code is sent before they do). */
export type EnrolMethod = 'TOTP' | 'EMAIL_OTP';

export interface Factors {
  totp: boolean;
  /** Email codes are on; the code goes to emailHint (sent with the password step when it is the only factor). */
  emailOtp?: boolean;
  emailHint?: string;
  sms: boolean;
  smsHint?: string;
  recoveryCode: boolean;
  enrolMethods?: EnrolMethod[];
}

export interface TotpSetup {
  secret: string;
  otpauthUri: string;
}

export interface Pkce {
  verifier: string;
  challenge: string;
}

export function base64Url(b64: string): string {
  return b64.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

const B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

export function bytesToBase64(bytes: Uint8Array): string {
  let out = '';
  for (let i = 0; i < bytes.length; i += 3) {
    const n = ((bytes[i] ?? 0) << 16) | ((bytes[i + 1] ?? 0) << 8) | (bytes[i + 2] ?? 0);
    out += (B64[(n >> 18) & 63] ?? '') + (B64[(n >> 12) & 63] ?? '') + (i + 1 < bytes.length ? (B64[(n >> 6) & 63] ?? '') : '=') + (i + 2 < bytes.length ? (B64[n & 63] ?? '') : '=');
  }
  return out;
}

/** PKCE S256: a 43-character verifier from 32 random bytes and its SHA-256 challenge. */
export async function makePkce(randomBytes: (n: number) => Uint8Array, sha256Base64: (input: string) => Promise<string>): Promise<Pkce> {
  const verifier = base64Url(bytesToBase64(randomBytes(32)));
  return { verifier, challenge: base64Url(await sha256Base64(verifier)) };
}

export type SignInStep =
  | { kind: 'done'; trustedDeviceToken: string | null; recoveryCodes: string[]; mfaSetupSuggested: boolean }
  | { kind: 'second-factor'; challengeId: string; factors: Factors }
  | { kind: 'enrol'; challengeId: string; methods: EnrolMethod[] };

interface ServerResult {
  status: 'MFA_REQUIRED' | 'MFA_ENROLMENT_REQUIRED' | 'AUTHENTICATED';
  challengeId?: string;
  factors?: Factors;
  code?: string;
  trustedDeviceToken?: string;
  recoveryCodes?: string[];
  mfaSetupSuggested?: boolean;
}

export interface NativeSignInDeps<T> {
  request: JsonRequester;
  /** The BFF's app key ("customer", "driver"). */
  app: string;
  redirectUri: string;
  pkce: Pkce;
  /** Exchanges the authorization code (with this attempt's PKCE verifier) for the app's own tokens. */
  exchange(code: string, verifier: string): Promise<T>;
  /** A trusted-device token kept from an earlier sign-in of this email on this phone (skips the second factor). */
  trustedDevice: { get(email: string): Promise<string | null>; set(email: string, token: string): Promise<void> };
}

/** One sign-in attempt: password, then (if needed) a second factor. The PKCE pair lives for the attempt. */
export class NativeSignIn<T> {
  private email = '';
  private challengeId = '';
  tokens: T | null = null;

  constructor(private readonly d: NativeSignInDeps<T>) {}

  private base() {
    return { app: this.d.app, codeChallenge: this.d.pkce.challenge, redirectUri: this.d.redirectUri };
  }

  async password(email: string, password: string): Promise<SignInStep> {
    this.email = email.trim().toLowerCase();
    const trustedDeviceToken = (await this.d.trustedDevice.get(this.email)) ?? undefined;
    const r = await this.d.request<ServerResult>('/v1/auth/native/password', {
      method: 'POST',
      body: { ...this.base(), email: this.email, password, ...(trustedDeviceToken ? { trustedDeviceToken } : {}) }
    });
    return this.step(r);
  }

  /** Texts a sign-in code; resolves the masked number the code went to. */
  async sendSmsCode(): Promise<string> {
    const r = await this.d.request<{ sentTo?: string }>('/v1/auth/native/mfa/sms', { method: 'POST', body: { ...this.base(), challengeId: this.challengeId } });
    return r.sentTo ?? '';
  }

  /** Emails a sign-in code ("send me an email code instead"); resolves the masked address. Resends are throttled. */
  async sendEmailCode(): Promise<string> {
    const r = await this.d.request<{ sentTo?: string }>('/v1/auth/native/mfa/email', { method: 'POST', body: { ...this.base(), challengeId: this.challengeId } });
    return r.sentTo ?? '';
  }

  /** Forced enrolment: the authenticator's secret and otpauth URI (QR or manual key). */
  async enrolTotp(): Promise<TotpSetup> {
    return this.d.request<TotpSetup>('/v1/auth/native/mfa/enrol/totp', { method: 'POST', body: { ...this.base(), challengeId: this.challengeId } });
  }

  /** Forced enrolment: emails a first code to the verified email; resolves the masked address. */
  async enrolEmail(): Promise<string> {
    const r = await this.d.request<{ sentTo?: string }>('/v1/auth/native/mfa/enrol/email', { method: 'POST', body: { ...this.base(), challengeId: this.challengeId } });
    return r.sentTo ?? '';
  }

  /** Forced enrolment: confirms the first code; the sign-in completes (recovery codes come back once). */
  async confirmEnrol(method: EnrolMethod, code: string): Promise<SignInStep> {
    const path = method === 'TOTP' ? '/v1/auth/native/mfa/enrol/totp/confirm' : '/v1/auth/native/mfa/enrol/email/confirm';
    const r = await this.d.request<ServerResult>(path, { method: 'POST', body: { ...this.base(), challengeId: this.challengeId, code: code.replace(/\s+/g, '') } });
    return this.step(r);
  }

  async verify(factor: SecondFactor, code: string, trustDevice: boolean): Promise<SignInStep> {
    const r = await this.d.request<ServerResult>('/v1/auth/native/mfa/verify', {
      method: 'POST',
      body: { ...this.base(), challengeId: this.challengeId, factor, code: code.replace(/\s+/g, ''), trustDevice }
    });
    return this.step(r);
  }

  private async step(r: ServerResult): Promise<SignInStep> {
    if (r.status === 'MFA_ENROLMENT_REQUIRED' && r.challengeId) {
      this.challengeId = r.challengeId;
      return { kind: 'enrol', challengeId: r.challengeId, methods: r.factors?.enrolMethods ?? ['TOTP', 'EMAIL_OTP'] };
    }
    if (r.status === 'MFA_REQUIRED' && r.challengeId) {
      this.challengeId = r.challengeId;
      return { kind: 'second-factor', challengeId: r.challengeId, factors: r.factors ?? { totp: true, emailOtp: false, sms: false, recoveryCode: true } };
    }
    if (r.status !== 'AUTHENTICATED' || !r.code) throw new Error('Sign-in did not complete. Try again.');
    this.tokens = await this.d.exchange(r.code, this.d.pkce.verifier);
    if (r.trustedDeviceToken) await this.d.trustedDevice.set(this.email, r.trustedDeviceToken);
    return { kind: 'done', trustedDeviceToken: r.trustedDeviceToken ?? null, recoveryCodes: r.recoveryCodes ?? [], mfaSetupSuggested: r.mfaSetupSuggested ?? false };
  }
}

/** What the form shows for a failed step. The BFF's wording is already plain; only the transport needs words. */
export function signInMessage(e: unknown, fallback: string): string {
  if (e instanceof ApiError) {
    return e.fieldErrors.code ?? e.fieldErrors.password ?? e.fieldErrors.email ?? e.message;
  }
  if (e instanceof TypeError) return "Can't reach AfroTransact. Check your connection and try again.";
  return e instanceof Error && e.message ? e.message : fallback;
}

/** True when the failure means "start over" (the challenge expired), not "try the code again". */
export function isChallengeExpired(e: unknown): boolean {
  return e instanceof ApiError && (e.status === 410 || e.code === 'CHALLENGE_EXPIRED');
}
