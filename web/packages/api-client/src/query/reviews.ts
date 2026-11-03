import { queryOptions } from '@tanstack/react-query';

import { type ApiClient } from '../client';
import { type ReviewListQuery } from '../reviews-client';
import { accountKeys, productKeys } from './keys';

/* Key factories and queryOptions for product reviews (S324). The public list sits under productKeys.all; `mine` is
   personal, so it sits under accountKeys.all and signing out drops it with the rest of the personal queries. */

export const reviewKeys = {
  all: [...productKeys.all, 'reviews'] as const,
  lists: (atin: string) => [...reviewKeys.all, atin, 'list'] as const,
  list: (atin: string, query: ReviewListQuery) => [...reviewKeys.lists(atin), query] as const,
  mine: (atin: string) => [...accountKeys.all, 'reviews', atin, 'mine'] as const
};

export const reviewQueries = {
  list: (api: ApiClient, atin: string, query: ReviewListQuery = {}) =>
    queryOptions({ queryKey: reviewKeys.list(atin, query), queryFn: ({ signal }) => api.listProductReviews(atin, query, signal), staleTime: 30_000 }),
  mine: (api: ApiClient, atin: string) => queryOptions({ queryKey: reviewKeys.mine(atin), queryFn: ({ signal }) => api.getMyProductReview(atin, signal), staleTime: 15_000 })
};
