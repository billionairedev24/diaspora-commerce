'use client';

import { SessionTimeout } from '@afrotransact/account';
import { staffQueries } from '@afrotransact/api-client';
import { useI18n } from '@afrotransact/i18n/react';
import { useQuery } from '@tanstack/react-query';
import { type ReactNode, useCallback, useMemo } from 'react';

import { useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';
import { endSession, signedOutHref, signOutEverywhere } from '@/lib/auth';
import { sessionApi } from '@/lib/session-api';
import { sessionLabels } from '@/lib/session-labels';
import { sessionReturnTo } from '@/lib/session-return';

/**
 * The staff console's sign-out timer (15 minutes idle, 8 hours in total by default; staff can change both): warns
 * before the end, reports real input, signs out at the end and shows why. Runs only for a signed-in staff member, so the
 * session is read from the shell's own query (no extra request).
 */
export function StaffSessionTimeout({ children }: { children: ReactNode }) {
  const t = useT();
  const { locale } = useI18n();
  const signedIn = useQuery({ ...staffQueries.session(staffApi()), enabled: false }).data !== undefined;
  const labels = useMemo(() => sessionLabels(t), [t]);
  const returnTo = useCallback(() => sessionReturnTo(window.location.pathname, window.location.search), []);
  const signInHref = useCallback((back: string | undefined) => signedOutHref(back ?? '/'), []);
  const signOut = useCallback(() => void signOutEverywhere(), []);
  return (
    <SessionTimeout
      api={sessionApi()}
      signedIn={signedIn}
      labels={labels}
      locale={locale}
      endSession={endSession}
      signInHref={signInHref}
      returnTo={returnTo}
      signOut={signOut}
      mode="screen"
    >
      {children}
    </SessionTimeout>
  );
}
