'use client';

import { type SessionApi, sessionEndBus, type SessionEndReason, sessionQueries } from '@afrotransact/api-client';
import { SessionEndedScreen, type SessionOverReason, SessionTimeoutGuard, type SessionTimeoutLabels } from '@afrotransact/ui';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { type ReactNode, useCallback, useState } from 'react';

/* The session timer in an app shell (docs/engineering/session-timeouts.md): the same wiring for the storefront, Seller
   Center and staff console. The apps supply what differs: their strings, how they sign out, where sign-in is and which
   pages are never returned to. */

export interface SessionTimeoutProps {
  api: SessionApi;
  /** False for a guest (no session, nothing runs) and while the session is still loading. */
  signedIn: boolean;
  labels: SessionTimeoutLabels;
  locale: string;
  /**
   * Finishes the sign-out on the server (the session is already over there; this revokes what is left and ends the
   * single-sign-on session) and answers where to go next. `returnTo` is the page to come back to, if any.
   */
  endSession: (returnTo: string | undefined) => Promise<string>;
  /** Where to go without asking the server: the app's sign-in (or, for the storefront, the page signed out). */
  signInHref: (returnTo: string | undefined) => string;
  /** The page to come back to after signing in again; undefined for sensitive pages and the landing page. */
  returnTo: () => string | undefined;
  /** "Sign out now": the app's own sign-out (it also calls announceSignOut()). */
  signOut: () => void;
  /**
   * screen: show the signed-out screen and wait for "Sign in again" (back offices).
   * navigate: go to `endSession`'s answer at once (the storefront, which has no sign-in wall).
   */
  mode: 'screen' | 'navigate';
  children: ReactNode;
}

/** Hands the browser over (a full page load: nothing of the signed-in page survives in memory). */
function go(href: string): void {
  window.location.assign(href);
}

/**
 * Mounts the session timer for signed-in people. When the session ends (timer, 401 or another tab) it cancels every
 * request, clears all cached queries, drops the signed-in page for a signed-out screen and finishes the sign-out.
 */
export function SessionTimeout({ api, signedIn, labels, locale, endSession, signInHref, returnTo, signOut, mode, children }: SessionTimeoutProps) {
  const queryClient = useQueryClient();
  const [href, setHref] = useState<string | null>(null);

  const loadPolicy = useCallback(
    (fresh: boolean) => queryClient.fetchQuery({ ...sessionQueries.policy(api), staleTime: fresh ? 0 : undefined }),
    [api, queryClient]
  );

  const onEnded = useCallback(
    (_reason: SessionOverReason, source: 'timer' | 'server' | 'tab') => {
      const back = returnTo();
      void queryClient.cancelQueries();
      queryClient.clear();
      void (async () => {
        let next: string;
        if (source === 'tab') {
          // Another tab is already finishing the sign-out on the server; asking again would only repeat it.
          next = signInHref(back);
        } else {
          next = await endSession(back).catch(() => signInHref(back));
        }
        if (mode === 'navigate') go(next);
        else setHref(next);
      })();
    },
    [endSession, mode, queryClient, returnTo, signInHref]
  );

  return (
    <SessionTimeoutGuard
      enabled={signedIn}
      labels={labels}
      locale={locale}
      loadPolicy={loadPolicy}
      reportActivity={() => api.reportActivity()}
      subscribeEnd={sessionEndBus.subscribe}
      onEnded={onEnded}
      onSignOut={signOut}
      renderEnded={(reason) => <SessionEndedScreen reason={reason} labels={labels} busy={mode === 'navigate' || href === null} onSignIn={() => href && go(href)} />}
    >
      {children}
    </SessionTimeoutGuard>
  );
}

export interface SessionSignedOutLandingProps {
  api: SessionApi;
  labels: SessionTimeoutLabels;
  /** The reason a request already told us (401 with a SESSION_* code); skips asking /v1/me. */
  reason?: SessionEndReason;
  /** Go to the app's sign-in. */
  onSignIn: () => void;
  /** Shown when nothing says the session ended by the policy (the ordinary "please sign in" path). */
  fallback: ReactNode;
}

/**
 * Where a back office lands when it finds no session. If the policy ended the session a moment ago (/v1/me says so for
 * ten minutes, so a parallel request that never saw the 401 still shows why) the person gets the same signed-out
 * message as in the tab that timed out; otherwise `fallback` (the app's usual straight-to-sign-in).
 */
export function SessionSignedOutLanding({ api, labels, reason, onSignIn, fallback }: SessionSignedOutLandingProps) {
  const known = useQuery({ ...sessionQueries.signedOut(api), enabled: reason === undefined });
  const why = reason ?? known.data?.reason ?? undefined;
  if (why) return <SessionEndedScreen reason={why} labels={labels} onSignIn={onSignIn} />;
  if (known.isPending && reason === undefined) return null;
  return <>{fallback}</>;
}
