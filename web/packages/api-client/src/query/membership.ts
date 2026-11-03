import { queryOptions } from '@tanstack/react-query';

import { type ApiClient } from '../client';
import { accountKeys } from './keys';

/* Key factories and queryOptions for AfroTransact membership (S323). Everything personal hangs under accountKeys.all so
   signing out drops it with the rest; the public offer has its own root. */

const MINUTE = 60_000;

export const membershipKeys = {
  offer: () => ['membership', 'offer'] as const,
  all: [...accountKeys.all, 'membership'] as const,
  account: () => [...membershipKeys.all, 'account'] as const,
  benefits: () => [...membershipKeys.all, 'benefits'] as const,
  /** What membership does for the cart; refetch after the cart or the membership changes. */
  cart: () => [...membershipKeys.all, 'cart'] as const
};

export const membershipQueries = {
  offer: (api: ApiClient) => queryOptions({ queryKey: membershipKeys.offer(), queryFn: ({ signal }) => api.getMembershipOffer(signal), staleTime: 5 * MINUTE }),
  account: (api: ApiClient) => queryOptions({ queryKey: membershipKeys.account(), queryFn: ({ signal }) => api.getAccountMembership(signal), staleTime: MINUTE / 2 }),
  benefits: (api: ApiClient) => queryOptions({ queryKey: membershipKeys.benefits(), queryFn: ({ signal }) => api.listAccountMembershipBenefits(20, signal), staleTime: MINUTE / 2 }),
  cart: (api: ApiClient) => queryOptions({ queryKey: membershipKeys.cart(), queryFn: ({ signal }) => api.getCartMembershipSavings(signal), staleTime: MINUTE / 2 })
};
