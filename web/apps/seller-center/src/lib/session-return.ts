/* Where Seller Center comes back to after the sign-out policy ended the session and the person signs in again. The page
   they were on is kept, except the person's own account pages (sign-in methods, sessions, activity), which a sign-in
   alone must not reopen, and any address that is not a path of this app. */

const SENSITIVE_PREFIXES = ['/account'];

/** The page to come back to, or undefined for the landing page. */
export function sessionReturnTo(pathname: string, search = ''): string | undefined {
  if (!pathname.startsWith('/') || pathname.startsWith('//') || pathname === '/') return undefined;
  if (SENSITIVE_PREFIXES.some((p) => pathname === p || pathname.startsWith(`${p}/`))) return undefined;
  return `${pathname}${search}`;
}
