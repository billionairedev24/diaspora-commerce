import { queryOptions } from '@tanstack/react-query';

import { type ApiClient } from '../client';
import { accountKeys } from './keys';

/* Key factories and queryOptions for Refer a friend (S322). Under accountKeys.all so signing out drops it with the rest
   of the personal queries. */

export const referralKeys = {
  all: [...accountKeys.all, 'referral'] as const,
  account: () => [...referralKeys.all, 'account'] as const
};

export const referralQueries = {
  account: (api: ApiClient) => queryOptions({ queryKey: referralKeys.account(), queryFn: ({ signal }) => api.getAccountReferral(signal), staleTime: 30_000 })
};
