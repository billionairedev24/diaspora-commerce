'use client';

import { SessionTimeout } from '@afrotransact/account';
import { accountQueries } from '@afrotransact/api-client';
import { useI18n } from '@afrotransact/i18n/react';
import { announceSignOut } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import { type ReactNode, useCallback, useMemo } from 'react';

import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { sessionApi } from '@/lib/session-api';
import { sessionLabels } from '@/lib/session-labels';

import { signOutReturnTo } from './accountMenuItems';
import { SIGNED_OUT_PARAM } from './SignedOutNotice';

/** The page the shopper was on, signed out: the same address with the flag SignedOutNotice reads. */
export function signedOutLanding(returnTo: string | undefined): string {
  const url = new URL(returnTo ?? '/', 'http://storefront.local');
  url.searchParams.set(SIGNED_OUT_PARAM, '1');
  return `${url.pathname}${url.search}`;
}

/** Ends the BFF session and identity's (single sign-out); answers the page to land on. Never throws. */
async function endSession(returnTo: string | undefined): Promise<string> {
  try {
    const { redirectTo } = await browserApi().signOut({ returnTo });
    if (redirectTo) return redirectTo;
  } catch {
    /* the session is over on the server anyway */
  }
  return signedOutLanding(returnTo);
}

/**
 * The shopper's sign-out timer. Shoppers stay signed in for 30 days without activity (90 days in all) and get no
 * warning, so this mostly lets the page follow the server: a 401 with SESSION_IDLE_TIMEOUT / SESSION_ABSOLUTE_TIMEOUT, or
 * a sign-out in another tab, clears the account from the page and lands on the same page as a guest with the reason.
 * A guest has no session: nothing runs.
 */
export function StorefrontSessionTimeout({ children }: { children: ReactNode }) {
  const t = useT();
  const { locale } = useI18n();
  const me = useQuery(accountQueries.me(browserApi()));
  const labels = useMemo(() => sessionLabels(t), [t]);
  const returnTo = useCallback(() => signOutReturnTo(window.location.pathname, window.location.search), []);
  // "Sign out now" in the warning (shown only when staff give shoppers a warning time): a real sign-out, like the menu's.
  const signOut = useCallback(() => {
    void endSession(returnTo()).then((href) => {
      announceSignOut();
      window.location.assign(href);
    });
  }, [returnTo]);
  return (
    <SessionTimeout
      api={sessionApi()}
      signedIn={me.data?.authenticated === true}
      labels={labels}
      locale={locale}
      endSession={endSession}
      signInHref={signedOutLanding}
      returnTo={returnTo}
      signOut={signOut}
      mode="navigate"
    >
      {children}
    </SessionTimeout>
  );
}
