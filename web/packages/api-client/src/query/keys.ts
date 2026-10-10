import { type SearchQuery } from '../schemas/search';

/* TanStack Query key factories. Keys are hierarchical so whole families can be invalidated
   (e.g. queryClient.invalidateQueries({ queryKey: searchKeys.all }) after the deliver-to ZIP changes). */

export const siteKeys = {
  all: ['site'] as const,
  config: () => [...siteKeys.all, 'config'] as const,
  home: () => [...siteKeys.all, 'home'] as const,
  deliveryLocation: () => [...siteKeys.all, 'delivery-location'] as const
};

export const productKeys = {
  all: ['product'] as const,
  details: () => [...productKeys.all, 'detail'] as const,
  detail: (atin: string) => [...productKeys.details(), atin] as const
};

export const searchKeys = {
  all: ['search'] as const,
  results: () => [...searchKeys.all, 'results'] as const,
  result: (query: SearchQuery) => [...searchKeys.results(), query] as const,
  suggestions: (q: string) => [...searchKeys.all, 'suggest', q.trim().toLowerCase()] as const,
  addresses: (q: string) => [...searchKeys.all, 'addresses', q.trim().toLowerCase()] as const
};

export const cartKeys = {
  all: ['cart'] as const,
  current: () => [...cartKeys.all, 'current'] as const
};

export const accountKeys = {
  all: ['account'] as const,
  me: () => [...accountKeys.all, 'me'] as const,
  addresses: () => [...accountKeys.all, 'addresses'] as const,
  paymentMethods: () => [...accountKeys.all, 'payment-methods'] as const,
  orders: () => [...accountKeys.all, 'orders'] as const,
  returns: () => [...accountKeys.all, 'returns'] as const
};

export const careersKeys = {
  all: ['careers'] as const,
  jobs: () => [...careersKeys.all, 'jobs'] as const
};
