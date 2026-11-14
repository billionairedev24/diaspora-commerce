'use client';

import { accountKeys, cartKeys } from '@afrotransact/api-client';
import { announceSignOut, useToast } from '@afrotransact/ui';
import { useQueryClient } from '@tanstack/react-query';
import { useRouter } from 'next/navigation';

import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';

import { signOutReturnTo } from './accountMenuItems';

/** Signs the shopper out (account menu, All menu): ends the BFF session, tells the other tabs and identity (single sign-out), then says so. */
export function useSignOut(): () => Promise<void> {
  const t = useT();
  const router = useRouter();
  const qc = useQueryClient();
  const toast = useToast();
  return async () => {
    let redirectTo: string | null = null;
    try {
      ({ redirectTo } = await browserApi().signOut({
        returnTo: signOutReturnTo(window.location.pathname, window.location.search)
      }));
    } catch {
      /* signed out locally anyway: the session cookie is gone or was never there */
    }
    // The other tabs of this browser leave too (session timer).
    announceSignOut();
    if (redirectTo) {
      // Ends the identity session too (single sign-out); identity then returns the browser to this page (or the home
      // page) with signedOut=1, where SignedOutNotice says so. No sign-in wall.
      window.location.assign(redirectTo);
      return;
    }
    await Promise.all([qc.invalidateQueries({ queryKey: accountKeys.all }), qc.invalidateQueries({ queryKey: cartKeys.all })]);
    toast.show(t('account.signedOut'));
    router.push('/');
    router.refresh();
  };
}
