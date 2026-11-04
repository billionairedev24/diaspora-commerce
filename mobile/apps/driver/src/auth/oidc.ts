/* S081 sign-in: OIDC authorization code + PKCE with identity's public client `driver-app` (email + password + 6-digit
   code or passkey on identity's hosted pages). Redirect com.afrotransact.driver:/oauth2redirect. */
import * as AuthSession from 'expo-auth-session';
import * as WebBrowser from 'expo-web-browser';

import { DEMO_DRIVERS } from '@/api/mock/fixtures';
import { env } from '@/config/env';
import { decodeJwt } from '@/domain/jwt';

WebBrowser.maybeCompleteAuthSession();

export const SCOPES = ['openid', 'profile', 'email', 'offline_access'];

export interface TokenSet {
  accessToken: string;
  refreshToken: string | null;
  idToken: string | null;
  expiresAt: number;
}

export class SignInCancelled extends Error {
  constructor() {
    super('Sign-in was cancelled');
    this.name = 'SignInCancelled';
  }
}

export function redirectUri(): string {
  return AuthSession.makeRedirectUri({ native: `${env.redirectScheme}:/oauth2redirect`, scheme: env.redirectScheme, path: 'auth/callback' });
}

let discoveryCache: AuthSession.DiscoveryDocument | null = null;
const discovery = async () => (discoveryCache ??= await AuthSession.fetchDiscoveryAsync(env.oidcIssuer));

function toTokenSet(r: AuthSession.TokenResponse, prevRefresh: string | null = null): TokenSet {
  const exp = decodeJwt(r.accessToken)?.exp ?? Math.floor(Date.now() / 1000) + (r.expiresIn ?? 300);
  return { accessToken: r.accessToken, refreshToken: r.refreshToken ?? prevRefresh, idToken: r.idToken ?? null, expiresAt: exp };
}

export async function signInWithIdentity(loginHint?: string): Promise<TokenSet> {
  if (env.mocks) return mockTokens(loginHint);
  const doc = await discovery();
  const request = new AuthSession.AuthRequest({
    clientId: env.oidcClientId,
    redirectUri: redirectUri(),
    scopes: SCOPES,
    usePKCE: true,
    codeChallengeMethod: AuthSession.CodeChallengeMethod.S256,
    extraParams: loginHint ? { login_hint: loginHint } : {}
  });
  const result = await request.promptAsync(doc);
  if (result.type !== 'success') {
    if (result.type === 'error') throw new Error(result.error?.description ?? 'Sign-in failed');
    throw new SignInCancelled();
  }
  const tokens = await AuthSession.exchangeCodeAsync({ clientId: env.oidcClientId, code: result.params.code ?? '', redirectUri: redirectUri(), extraParams: { code_verifier: request.codeVerifier ?? '' } }, doc);
  return toTokenSet(tokens);
}

/** Trades the authorization code from the in-app sign-in (and its PKCE verifier) for this app's own tokens. */
export async function exchangeSignInCode(code: string, verifier: string): Promise<TokenSet> {
  const tokens = await AuthSession.exchangeCodeAsync({ clientId: env.oidcClientId, code, redirectUri: redirectUri(), extraParams: { code_verifier: verifier } }, await discovery());
  return toTokenSet(tokens);
}

/** The refresh token is no longer accepted: sign in again. A network or server failure is not that: the session is kept. */
export class SessionGone extends Error {
  constructor(readonly reason = 'refresh token refused') {
    super('Your session ended');
    this.name = 'SessionGone';
  }
}

export function isInvalidGrant(e: unknown): boolean {
  const code = (e as { code?: string } | null)?.code;
  return code === 'invalid_grant' || code === 'invalid_token' || code === 'invalid_client' || code === 'unauthorized_client';
}

export async function refreshTokens(current: TokenSet): Promise<TokenSet> {
  if (env.mocks) {
    const c = decodeJwt(current.accessToken);
    return mockTokens(DEMO_DRIVERS.find((d) => d.sub === c?.sub)?.email);
  }
  if (!current.refreshToken) throw new SessionGone('no refresh token was issued at sign-in');
  try {
    const r = await AuthSession.refreshAsync({ clientId: env.oidcClientId, refreshToken: current.refreshToken, scopes: SCOPES }, await discovery());
    return toTokenSet(r, current.refreshToken);
  } catch (e) {
    throw isInvalidGrant(e) ? new SessionGone(`identity refused the refresh token (${(e as { code?: string }).code ?? 'invalid_grant'})`) : e;
  }
}

export async function revokeTokens(current: TokenSet): Promise<void> {
  if (env.mocks || !current.refreshToken) return;
  try {
    await AuthSession.revokeAsync({ clientId: env.oidcClientId, token: current.refreshToken, tokenTypeHint: AuthSession.TokenTypeHint.RefreshToken }, await discovery());
  } catch {
    /* offline */
  }
}

function b64url(s: string): string {
  const A = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';
  const bytes = Array.from(s, (ch) => ch.charCodeAt(0) & 0xff);
  let out = '';
  for (let i = 0; i < bytes.length; i += 3) {
    const n = ((bytes[i] ?? 0) << 16) | ((bytes[i + 1] ?? 0) << 8) | (bytes[i + 2] ?? 0);
    out += (A[(n >> 18) & 63] ?? '') + (A[(n >> 12) & 63] ?? '') + (i + 1 < bytes.length ? (A[(n >> 6) & 63] ?? '') : '') + (i + 2 < bytes.length ? (A[n & 63] ?? '') : '');
  }
  return out;
}

/** Dev mocks: demo accounts from the design (tunde = driver, daniel = ops admin with access, kofi = no access). */
export function mockTokens(email?: string): TokenSet {
  const d = DEMO_DRIVERS.find((x) => x.email === (email ?? '').trim().toLowerCase()) ?? DEMO_DRIVERS[0];
  const exp = Math.floor(Date.now() / 1000) + 300;
  const [given, family] = (d?.name ?? 'Driver').split(' ');
  const payload = { sub: d?.sub, exp, email: d?.email, given_name: given, family_name: family, pt: 'DRIVER', perms: d?.perms };
  const t = `${b64url('{"alg":"none"}')}.${b64url(JSON.stringify(payload))}.mock`;
  return { accessToken: t, refreshToken: 'mock-refresh', idToken: t, expiresAt: exp };
}
