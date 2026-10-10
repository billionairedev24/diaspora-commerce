/* S163: OIDC authorization code + PKCE (S256) against identity (Spring Authorization Server) with expo-auth-session.
   Public client `customer-app`; redirect com.afrotransact.customer:/oauth2redirect (native) or
   http://localhost:8081/auth/callback (web dev). Refresh tokens rotate (single use). */
import * as AuthSession from 'expo-auth-session';
import * as WebBrowser from 'expo-web-browser';

import { env } from '@/config/env';
import { decodeJwt } from '@/domain/jwt';

WebBrowser.maybeCompleteAuthSession();

export const SCOPES = ['openid', 'profile', 'email', 'phone', 'offline_access'];

export interface TokenSet {
  accessToken: string;
  refreshToken: string | null;
  idToken: string | null;
  /** Epoch seconds. */
  expiresAt: number;
}

export type SignInIntent = { kind: 'sign-in'; loginHint?: string } | { kind: 'sign-up' } | { kind: 'provider'; provider: 'google' | 'apple' | 'facebook' };

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
async function discovery(): Promise<AuthSession.DiscoveryDocument> {
  discoveryCache ??= await AuthSession.fetchDiscoveryAsync(env.oidcIssuer);
  return discoveryCache;
}

function toTokenSet(r: AuthSession.TokenResponse, previousRefresh: string | null = null): TokenSet {
  const claims = decodeJwt(r.accessToken);
  const expiresAt = claims?.exp ?? Math.floor(Date.now() / 1000) + (r.expiresIn ?? 300);
  return { accessToken: r.accessToken, refreshToken: r.refreshToken ?? previousRefresh, idToken: r.idToken ?? null, expiresAt };
}

/** Extra authorize parameters: hints the hosted login page can use (ignored when unsupported). */
export function authorizeParams(intent: SignInIntent): Record<string, string> {
  if (intent.kind === 'sign-in' && intent.loginHint) return { login_hint: intent.loginHint };
  if (intent.kind === 'sign-up') return { prompt: 'create' };
  if (intent.kind === 'provider') return { idp_hint: intent.provider };
  return {};
}

export async function signInWithIdentity(intent: SignInIntent): Promise<TokenSet> {
  if (env.mocks) return mockTokens();
  const doc = await discovery();
  const request = new AuthSession.AuthRequest({
    clientId: env.oidcClientId,
    redirectUri: redirectUri(),
    scopes: SCOPES,
    usePKCE: true,
    codeChallengeMethod: AuthSession.CodeChallengeMethod.S256,
    extraParams: authorizeParams(intent)
  });
  const result = await request.promptAsync(doc);
  if (result.type !== 'success') {
    if (result.type === 'error') throw new Error(result.error?.description ?? result.params.error_description ?? 'Sign-in failed');
    throw new SignInCancelled();
  }
  const tokens = await AuthSession.exchangeCodeAsync(
    { clientId: env.oidcClientId, code: result.params.code ?? '', redirectUri: redirectUri(), extraParams: { code_verifier: request.codeVerifier ?? '' } },
    doc
  );
  return toTokenSet(tokens);
}

export async function refreshTokens(current: TokenSet): Promise<TokenSet> {
  if (env.mocks) return mockTokens();
  if (!current.refreshToken) throw new Error('No refresh token');
  const doc = await discovery();
  const r = await AuthSession.refreshAsync({ clientId: env.oidcClientId, refreshToken: current.refreshToken, scopes: SCOPES }, doc);
  return toTokenSet(r, current.refreshToken);
}

/** Revokes the refresh token (best effort) so sign-out ends the session server-side too. */
export async function revokeTokens(current: TokenSet): Promise<void> {
  if (env.mocks || !current.refreshToken) return;
  try {
    const doc = await discovery();
    await AuthSession.revokeAsync({ clientId: env.oidcClientId, token: current.refreshToken, tokenTypeHint: AuthSession.TokenTypeHint.RefreshToken }, doc);
  } catch {
    /* offline: the refresh token simply expires */
  }
}

/* Dev mocks: an unsigned token carrying the demo shopper's claims (the mock backend accepts any bearer). */
function b64url(s: string): string {
  const B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';
  const bytes = Array.from(s, (ch) => ch.charCodeAt(0) & 0xff);
  let out = '';
  for (let i = 0; i < bytes.length; i += 3) {
    const n = ((bytes[i] ?? 0) << 16) | ((bytes[i + 1] ?? 0) << 8) | (bytes[i + 2] ?? 0);
    out += (B64[(n >> 18) & 63] ?? '') + (B64[(n >> 12) & 63] ?? '') + (i + 1 < bytes.length ? (B64[(n >> 6) & 63] ?? '') : '') + (i + 2 < bytes.length ? (B64[n & 63] ?? '') : '');
  }
  return out;
}

function mockTokens(): TokenSet {
  const exp = Math.floor(Date.now() / 1000) + 300;
  const payload = { sub: 'usr_adaeze', exp, email: 'adaeze@example.com', given_name: 'Adaeze', family_name: 'Okafor', pt: 'CUSTOMER', roles: ['account.customer'] };
  const token = `${b64url('{"alg":"none"}')}.${b64url(JSON.stringify(payload))}.mock`;
  return { accessToken: token, refreshToken: 'mock-refresh', idToken: token, expiresAt: exp };
}
