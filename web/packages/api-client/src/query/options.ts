import { infiniteQueryOptions, keepPreviousData, queryOptions } from '@tanstack/react-query';

import { type ApiClient } from '../client';
import { type LegalDocumentKey } from '../schemas/legal';
import { type SearchQuery } from '../schemas/search';
import { accountKeys, careersKeys, cartKeys, legalKeys, productKeys, searchKeys, siteKeys } from './keys';

/* queryOptions shared by server prefetch (RSC + HydrationBoundary) and client hooks, so both sides
   always use identical keys, fetchers and stale times. */

const MINUTE = 60_000;

export const siteQueries = {
  config: (api: ApiClient) => queryOptions({ queryKey: siteKeys.config(), queryFn: ({ signal }) => api.getSiteConfig(signal), staleTime: 10 * MINUTE }),
  home: (api: ApiClient) => queryOptions({ queryKey: siteKeys.home(), queryFn: ({ signal }) => api.getHome(signal), staleTime: MINUTE }),
  deliveryLocation: (api: ApiClient) =>
    queryOptions({ queryKey: siteKeys.deliveryLocation(), queryFn: ({ signal }) => api.getDeliveryLocation(signal), staleTime: 10 * MINUTE })
};

export const legalQueries = {
  document: (api: ApiClient, key: LegalDocumentKey, locale: string, version?: string) =>
    queryOptions({
      queryKey: legalKeys.document(key, locale, version),
      queryFn: ({ signal }) => api.getLegalDocument(key, { locale, version }, signal),
      staleTime: 5 * MINUTE
    }),
  cookiePolicy: (api: ApiClient) => queryOptions({ queryKey: legalKeys.cookiePolicy(), queryFn: ({ signal }) => api.getCookiePolicy(signal), staleTime: 10 * MINUTE })
};

export const productQueries = {
  detail: (api: ApiClient, atin: string) => queryOptions({ queryKey: productKeys.detail(atin), queryFn: ({ signal }) => api.getProduct(atin, signal), staleTime: 30_000 })
};

export const searchQueries = {
  results: (api: ApiClient, query: SearchQuery) =>
    queryOptions({ queryKey: searchKeys.result(query), queryFn: ({ signal }) => api.search(query, signal), placeholderData: keepPreviousData, staleTime: 30_000 }),
  /** The storefront's results list: page 0 first, the next page whenever the shopper nears the end (no page numbers). */
  infiniteResults: (api: ApiClient, query: SearchQuery) =>
    infiniteQueryOptions({
      queryKey: searchKeys.infinite(query),
      queryFn: ({ pageParam, signal }) => api.search({ ...query, page: pageParam }, signal),
      initialPageParam: 0,
      getNextPageParam: (last) => (last.page + 1 < last.pageCount ? last.page + 1 : undefined),
      staleTime: 30_000
    }),
  suggestions: (api: ApiClient, q: string) =>
    queryOptions({ queryKey: searchKeys.suggestions(q), queryFn: ({ signal }) => api.suggest(q, signal), placeholderData: keepPreviousData, staleTime: MINUTE, enabled: q.trim().length > 0 }),
  addresses: (api: ApiClient, q: string) =>
    queryOptions({ queryKey: searchKeys.addresses(q), queryFn: ({ signal }) => api.searchAddresses(q, signal), staleTime: 5 * MINUTE, enabled: q.trim().length > 0 })
};

export const cartQueries = {
  current: (api: ApiClient) => queryOptions({ queryKey: cartKeys.current(), queryFn: ({ signal }) => api.getCart(signal), staleTime: 0 })
};

export const accountQueries = {
  me: (api: ApiClient) => queryOptions({ queryKey: accountKeys.me(), queryFn: ({ signal }) => api.getMe(signal), staleTime: MINUTE })
};

export const careersQueries = {
  jobs: (api: ApiClient) => queryOptions({ queryKey: careersKeys.jobs(), queryFn: ({ signal }) => api.getJobs(signal), staleTime: 10 * MINUTE })
};
