/* In-app sign-in (no hosted page, no system browser): the form posts email + password to the BFF, which checks them
   with identity (second factor when the account has one) and returns an authorization code for this app's own PKCE
   request; the app exchanges the code at identity's token endpoint and holds its own tokens. This module is pure
   (crypto, HTTP and the code exchange are injected) so the whole state machine is unit-tested. */
import { ApiProblemError, NetworkError } from '@/api/http';

/** Plain JSON POST against the BFF (no token: sign-in is the one call made without one). */
export type JsonRequester = <T>(path: string, opts: { method: 'POST'; body: Record<string, unknown> }) => Promise<T>;

export type SecondFactor = 'TOTP' | 'SMS' | 'RECOVERY_CODE';

export interface Factors {
  totp: boolean;
  sms: boolean;
  smsHint?: string;
  recoveryCode: boolean;
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
  | { kind: 'done'; trustedDeviceToken: string | null }
  | { kind: 'second-factor'; challengeId: string; factors: Factors };

interface ServerResult {
  status: 'MFA_REQUIRED' | 'AUTHENTICATED';
  challengeId?: string;
  factors?: Factors;
  code?: string;
  trustedDeviceToken?: string;
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

  async verify(factor: SecondFactor, code: string, trustDevice: boolean): Promise<SignInStep> {
    const r = await this.d.request<ServerResult>('/v1/auth/native/mfa/verify', {
      method: 'POST',
      body: { ...this.base(), challengeId: this.challengeId, factor, code: code.replace(/\s+/g, ''), trustDevice }
    });
    return this.step(r);
  }

  private async step(r: ServerResult): Promise<SignInStep> {
    if (r.status === 'MFA_REQUIRED' && r.challengeId) {
      this.challengeId = r.challengeId;
      return { kind: 'second-factor', challengeId: r.challengeId, factors: r.factors ?? { totp: true, sms: false, recoveryCode: true } };
    }
    if (r.status !== 'AUTHENTICATED' || !r.code) throw new Error('Sign-in did not complete. Try again.');
    this.tokens = await this.d.exchange(r.code, this.d.pkce.verifier);
    if (r.trustedDeviceToken) await this.d.trustedDevice.set(this.email, r.trustedDeviceToken);
    return { kind: 'done', trustedDeviceToken: r.trustedDeviceToken ?? null };
  }
}

function fieldError(e: ApiProblemError): string | undefined {
  const errors = e.body.errors;
  if (!errors || typeof errors !== 'object') return undefined;
  const f = errors as Record<string, unknown>;
  const v = f.code ?? f.password ?? f.email;
  return typeof v === 'string' ? v : undefined;
}

/** What the form shows for a failed step. The BFF's wording is already plain; only the transport needs words. */
export function signInMessage(e: unknown, fallback: string): string {
  if (e instanceof ApiProblemError) {
    return fieldError(e) ?? e.message;
  }
  if (e instanceof TypeError || e instanceof NetworkError) return "Can't reach AfroTransact. Check your connection and try again.";
  return e instanceof Error && e.message ? e.message : fallback;
}

/** True when the failure means "start over" (the challenge expired), not "try the code again". */
export function isChallengeExpired(e: unknown): boolean {
  return e instanceof ApiProblemError && (e.status === 410 || e.code === 'CHALLENGE_EXPIRED');
}
