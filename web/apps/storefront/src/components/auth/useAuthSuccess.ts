'use client';

import { accountKeys, cartKeys } from '@afrotransact/api-client';
import { useQueryClient } from '@tanstack/react-query';
import { useRouter } from 'next/navigation';
import { useCallback } from 'react';

import { safeReturnTo } from '@/lib/links';

/** After authentication: refresh session-bound queries (me, merged cart) and go back to returnTo. */
export function useAuthSuccess() {
  const qc = useQueryClient();
  const router = useRouter();
  return useCallback(
    async (redirectTo: string) => {
      await Promise.all([qc.invalidateQueries({ queryKey: accountKeys.all }), qc.invalidateQueries({ queryKey: cartKeys.all })]);
      router.replace(safeReturnTo(redirectTo));
      router.refresh();
    },
    [qc, router]
  );
}
