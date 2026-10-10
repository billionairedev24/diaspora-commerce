/** Canonical storefront URLs. */
export const productHref = (atin: string): string => `/p/${encodeURIComponent(atin)}`;
export const signInHref = (returnTo?: string): string => (returnTo && returnTo !== '/' ? `/signin?returnTo=${encodeURIComponent(returnTo)}` : '/signin');
export const signUpHref = (returnTo?: string): string => (returnTo && returnTo !== '/' ? `/signup?returnTo=${encodeURIComponent(returnTo)}` : '/signup');

/** Only same-site relative paths are allowed as post-login destinations. */
export function safeReturnTo(value: string | null | undefined): string {
  return value && value.startsWith('/') && !value.startsWith('//') && !value.startsWith('/signin') && !value.startsWith('/signup') ? value : '/';
}
