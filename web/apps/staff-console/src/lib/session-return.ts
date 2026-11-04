/* Where the staff console comes back to after the sign-out policy ended the session and the person signs in again. The
   page they were on is kept, except pages that must not be reopened by a sign-in alone: security and identity settings,
   the person's own account (sign-in methods, sessions, activity). Those, and any address that is not a path of this app,
   go to the landing page. */

const SENSITIVE_PREFIXES = ['/settings', '/account'];

/** The page to come back to, or undefined for the landing page. */
export function sessionReturnTo(pathname: string, search = ''): string | undefined {
  if (!pathname.startsWith('/') || pathname.startsWith('//') || pathname === '/') return undefined;
  if (SENSITIVE_PREFIXES.some((p) => pathname === p || pathname.startsWith(`${p}/`))) return undefined;
  return `${pathname}${search}`;
}
