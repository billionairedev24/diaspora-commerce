import { queryOptions } from '@tanstack/react-query';

import { type ApiClient } from '../client';
import { accountKeys } from './keys';

/* Key factories and queryOptions for delivery quote requests. They hang under accountKeys.all so signing out drops them
   with the rest of the personal queries. The shopper is emailed when a quote is ready, so nothing polls. */

const MINUTE = 60_000;

export const deliveryQuoteKeys = {
  all: [...accountKeys.all, 'delivery-quotes'] as const,
  list: () => [...deliveryQuoteKeys.all, 'list'] as const,
  one: (id: string) => [...deliveryQuoteKeys.all, 'one', id] as const
};

export const deliveryQuoteQueries = {
  mine: (api: ApiClient) =>
    queryOptions({
      queryKey: deliveryQuoteKeys.list(),
      queryFn: ({ signal }) => api.listMyDeliveryQuotes(signal),
      staleTime: MINUTE / 2
    }),
  one: (api: ApiClient, id: string) =>
    queryOptions({ queryKey: deliveryQuoteKeys.one(id), queryFn: ({ signal }) => api.getMyDeliveryQuote(id, signal), staleTime: MINUTE / 2, retry: false })
};
