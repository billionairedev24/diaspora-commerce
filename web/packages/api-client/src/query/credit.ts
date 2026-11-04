import { infiniteQueryOptions, queryOptions } from '@tanstack/react-query';

import { type ApiClient } from '../client';
import { accountKeys } from './keys';

/* Key factories and queryOptions for AfroTransact credit and gift cards (S320, S321). Everything hangs under
   accountKeys.all so signing out drops it with the rest of the personal queries. */

const MINUTE = 60_000;

export const creditKeys = {
  all: [...accountKeys.all, 'credit'] as const,
  account: () => [...creditKeys.all, 'account'] as const,
  giftCardConfig: () => [...creditKeys.all, 'gift-card-config'] as const,
  giftCards: () => [...creditKeys.all, 'gift-cards'] as const
};

export const creditQueries = {
  /** Balance and history, newest first; each further page is the entries before the previous page's `nextBefore`. */
  account: (api: ApiClient) =>
    infiniteQueryOptions({
      queryKey: creditKeys.account(),
      queryFn: ({ pageParam, signal }) => api.getAccountCredit({ before: pageParam || undefined, limit: 20 }, signal),
      initialPageParam: '',
      getNextPageParam: (last) => last.nextBefore ?? undefined,
      staleTime: MINUTE / 2
    }),
  giftCardConfig: (api: ApiClient) =>
    queryOptions({ queryKey: creditKeys.giftCardConfig(), queryFn: ({ signal }) => api.getGiftCardConfig(signal), staleTime: 10 * MINUTE }),
  giftCards: (api: ApiClient) => queryOptions({ queryKey: creditKeys.giftCards(), queryFn: ({ signal }) => api.listMyGiftCards(signal), staleTime: MINUTE / 2 })
};
