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
}

/** Hosted sign-in start; `returnTo` is an absolute URL on this app (where the browser lands signed in). */
export function loginHref(returnTo: string, opts: LoginOptions = {}): string {
  const q = new URLSearchParams({ returnTo });
  if (opts.prompt) q.set('prompt', opts.prompt);
  if (opts.loginHint) q.set('login_hint', opts.loginHint);
  if (opts.invitation) q.set('invitation', opts.invitation);
  return `${BFF_BASE_PATH}/v1/auth/login?${q.toString()}`;
}

/** This app's key in the BFF's signed-out landing (/v1/auth/signed-out/{app}, registered at identity). */
const APP = 'staff-console';

/** Where this app lands after signing out when the BFF could not say: its own sign-in, coming back to `path`. */
export function signedOutHref(path: string): string {
  const q = path && path !== '/' ? `?${new URLSearchParams({ state: path }).toString()}` : '';
  return `${BFF_BASE_PATH}/v1/auth/signed-out/${APP}${q}`;
}

/**
 * Ends this app's session and the identity session (single sign-out): the BFF answers identity's end-session URL,
 * and identity then returns the browser to this app's own branded sign-in page, which brings the person back to the
 * page they signed out on. Without an answer the browser goes to that sign-in directly.
 */
export async function signOutEverywhere(): Promise<void> {
  const path = `${window.location.pathname}${window.location.search}`;
  let redirectTo: string | null = null;
  try {
    const res = await fetch(`${BFF_BASE_PATH}/v1/auth/sign-out`, {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ returnTo: path })
    });
    if (res.ok) {
      const body = (await res.json()) as { redirectTo?: string | null };
      redirectTo = typeof body.redirectTo === 'string' ? body.redirectTo : null;
    }
  } catch {
    /* the session is gone either way */
  }
  window.location.assign(redirectTo ?? signedOutHref(path));
}
