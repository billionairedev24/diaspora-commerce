'use client';

import { accountQueries } from '@afrotransact/api-client';
import { useToast } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useEffect, useRef } from 'react';

import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';

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
 * does not repeat it. When the sign-out policy ended the session (idle or maximum length), /v1/me says why for ten
 * minutes (`signedOutReason`), so the toast says that instead, even when the 401 was seen by another tab.
 */
export function SignedOutNotice() {
  const params = useSearchParams();
  const pathname = usePathname();
  const router = useRouter();
  const toast = useToast();
  const t = useT();
  const shown = useRef(false);
  const signedOut = params.get(SIGNED_OUT_PARAM) === '1';
  const me = useQuery({ ...accountQueries.me(browserApi()), enabled: signedOut });
  const ready = signedOut && !me.isPending;
  const reason = me.data?.signedOutReason;
  useEffect(() => {
    if (!ready || shown.current) return;
    shown.current = true;
    toast.show(reason === 'SESSION_IDLE_TIMEOUT' ? t('session.endedIdle') : reason === 'SESSION_ABSOLUTE_TIMEOUT' ? t('session.endedAbsolute') : t('account.signedOut'));
    router.replace(withoutSignedOutFlag(pathname, params), { scroll: false });
  }, [ready, reason, pathname, params, router, toast, t]);
  return null;
}
