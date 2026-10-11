import { signInHref, signUpHref } from '@/lib/links';

export type SearchParams = Record<string, string | string[] | undefined>;

const first = (sp: SearchParams, key: string): string => {
  const v = sp[key];
  return ((Array.isArray(v) ? v[0] : v) ?? '').trim();
};

/**
 * Where the legacy /signin and /signup routes send the browser: the BFF's hosted sign-in start with the same
 * destination and hints. `returnTo` is passed through as given (a storefront path, or an absolute Seller Center /
 * staff console URL, which the BFF validates against its allowed origins). `error` is set when the BFF callback
 * reported a failed sign-in.
 */
export function hostedLoginTarget(sp: SearchParams, mode: 'signin' | 'signup'): { href: string; error: string | null } {
  const returnTo = first(sp, 'returnTo') || undefined;
  const loginHint = (first(sp, 'email') || first(sp, 'login_hint')).slice(0, 254) || undefined;
  const invitation = first(sp, 'invitation').slice(0, 200) || undefined;
  const hints = { loginHint, invitation };
  const href = mode === 'signup' || invitation ? signUpHref(returnTo, hints) : signInHref(returnTo, hints);
  return { href, error: first(sp, 'error').slice(0, 64) || null };
}
