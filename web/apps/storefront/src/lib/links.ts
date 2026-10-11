/** Canonical storefront URLs. */
export const productHref = (atin: string): string => `/p/${encodeURIComponent(atin)}`;

/** The BFF's hosted sign-in start (same-origin proxy): it redirects to identity's sign-in page (single sign-on). */
export const LOGIN_START_PATH = '/api/bff/v1/auth/login';

export interface LoginHints {
  /** Prefilled email on identity's pages (OIDC login_hint). */
  loginHint?: string;
  /** Organisation invitation token: the new account joins the organisation without an email code. */
  invitation?: string;
}

function loginHref(returnTo: string | undefined, prompt: 'create' | undefined, hints: LoginHints = {}): string {
  const q = new URLSearchParams();
  if (returnTo && returnTo !== '/') q.set('returnTo', returnTo);
  if (prompt) q.set('prompt', prompt);
  if (hints.loginHint) q.set('login_hint', hints.loginHint);
  if (hints.invitation) q.set('invitation', hints.invitation);
  const qs = q.toString();
  return qs ? `${LOGIN_START_PATH}?${qs}` : LOGIN_START_PATH;
}

/**
 * "Sign in": identity's hosted sign-in page via the BFF, then back to `returnTo`. A full-page navigation — render it
 * with a plain `<a>` (or `window.location`), not Next's client-side `<Link>`.
 */
export const signInHref = (returnTo?: string, hints?: LoginHints): string => loginHref(returnTo, undefined, hints);

/** "Create account": identity's hosted sign-up page (OIDC prompt=create), then back to `returnTo` signed in. */
export const signUpHref = (returnTo?: string, hints?: LoginHints): string => loginHref(returnTo, 'create', hints);

/** Only same-site relative paths are allowed as post-login destinations. */
export function safeReturnTo(value: string | null | undefined): string {
  return value && value.startsWith('/') && !value.startsWith('//') && !value.startsWith('/signin') && !value.startsWith('/signup') ? value : '/';
}
