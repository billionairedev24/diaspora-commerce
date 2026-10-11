/* Sign-in and sign-out for the back office. Credentials are only ever entered on identity's hosted pages (single
   sign-on with the storefront and the other AfroTransact apps): the BFF starts the authorization request on this
   app's own origin (/api/bff), so the session cookie is set here, and returns to `returnTo` afterwards. */

import { BFF_BASE_PATH } from './config';

export interface LoginOptions {
  /** OIDC prompt: 'create' opens identity's sign-up page. */
  prompt?: 'create';
  /** Email to prefill (OIDC login_hint). */
  loginHint?: string;
  /** Organisation invitation token: the account created on identity's sign-up page joins the organisation. */
  invitation?: string;
  /** "Start selling" / "Offer your services": identity's pages use the seller or provider copy. */
  intent?: 'sell' | 'provide';
}

/** Hosted sign-in start; `returnTo` is an absolute URL on this app (where the browser lands signed in). */
export function loginHref(returnTo: string, opts: LoginOptions = {}): string {
  const q = new URLSearchParams({ returnTo });
  if (opts.prompt) q.set('prompt', opts.prompt);
  if (opts.loginHint) q.set('login_hint', opts.loginHint);
  if (opts.invitation) q.set('invitation', opts.invitation);
  if (opts.intent) q.set('intent', opts.intent);
  return `${BFF_BASE_PATH}/v1/auth/login?${q.toString()}`;
}

/**
 * Ends this app's session and the identity session (RP-initiated logout: identity returns the browser to this app's
 * home page). Falls back to `fallbackUrl` when there is no identity session to end.
 */
export async function signOutEverywhere(fallbackUrl: string): Promise<void> {
  let redirectTo: string | null = null;
  try {
    const res = await fetch(`${BFF_BASE_PATH}/v1/auth/sign-out`, { method: 'POST', credentials: 'include' });
    if (res.ok) {
      const body = (await res.json()) as { redirectTo?: string | null };
      redirectTo = typeof body.redirectTo === 'string' ? body.redirectTo : null;
    }
  } catch {
    /* the session is gone either way */
  }
  window.location.assign(redirectTo ?? fallbackUrl);
}
