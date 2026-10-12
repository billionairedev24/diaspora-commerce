'use client';

import { createNavGuard } from '@afrotransact/ui';
import { useRouter } from 'next/navigation';
import { useCallback } from 'react';

/** Seller Center's redirect-loop guard (see @afrotransact/ui createNavGuard): one per browser tab. */
export const navGuard = createNavGuard({ key: 'at.sc.nav' });

/**
 * router.replace for redirects the shell and pages make on their own (onboarding ↔ home, a wizard step): counted per
 * destination, and refused once they go round in a loop (the shell then shows what it tried, with a way out).
 */
export function useShellRedirect(): (to: string) => void {
  const router = useRouter();
  return useCallback(
    (to: string) => {
      if (navGuard.allow(to)) router.replace(to);
    },
    [router]
  );
}
