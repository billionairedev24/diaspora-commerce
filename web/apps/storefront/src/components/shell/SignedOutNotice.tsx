'use client';

import { useToast } from '@afrotransact/ui';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useEffect, useRef } from 'react';

import { useT } from '@/i18n/t';

/** Query flag the BFF adds when identity returns the browser here after single sign-out. */
export const SIGNED_OUT_PARAM = 'signedOut';

/** The page's address without the signed-out flag (other query parameters kept). */
export function withoutSignedOutFlag(pathname: string, params: URLSearchParams): string {
  const rest = new URLSearchParams(params);
  rest.delete(SIGNED_OUT_PARAM);
  const q = rest.toString();
  return q ? `${pathname}?${q}` : pathname;
}

/**
 * After signing out (through identity, so every app is signed out) the storefront shows the page the person was on, or
 * the home page, with a "You're signed out" toast, and drops the flag from the address so a reload or a shared link
 * does not repeat it.
 */
export function SignedOutNotice() {
  const params = useSearchParams();
  const pathname = usePathname();
  const router = useRouter();
  const toast = useToast();
  const t = useT();
  const shown = useRef(false);
  const signedOut = params.get(SIGNED_OUT_PARAM) === '1';
  useEffect(() => {
    if (!signedOut || shown.current) return;
    shown.current = true;
    toast.show(t('account.signedOut'));
    router.replace(withoutSignedOutFlag(pathname, params), { scroll: false });
  }, [signedOut, pathname, params, router, toast, t]);
  return null;
}
