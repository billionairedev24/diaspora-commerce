import { queryOptions } from '@tanstack/react-query';

import { type SessionApi } from '../session-api';

/* The web session timer's queries (docs/engineering/session-timeouts.md). Both read in the background (X-Background), so
   neither counts as activity. The timer decides when to read again; there is no interval here on purpose. */

export const sessionKeys = {
  all: ['session'] as const,
  policy: () => [...sessionKeys.all, 'policy'] as const,
  signedOut: () => [...sessionKeys.all, 'signed-out'] as const
};

const MINUTE = 60_000;

export const sessionQueries = {
  /** The policy and the server's clock. Rarely stale; the timer forces a read before it warns and after "Stay signed in". */
  policy: (api: SessionApi) => queryOptions({ queryKey: sessionKeys.policy(), queryFn: ({ signal }) => api.getPolicy(signal), staleTime: 5 * MINUTE, gcTime: 10 * MINUTE, retry: false }),
  /** Why the person was signed out (/v1/me `signedOutReason`, kept by the BFF for ten minutes). */
  signedOut: (api: SessionApi) => queryOptions({ queryKey: sessionKeys.signedOut(), queryFn: ({ signal }) => api.getSignedOutState(signal), staleTime: 0, retry: false })
};
