import { dehydrate, HydrationBoundary } from '@tanstack/react-query';
import { type ReactNode } from 'react';

import { CookieBanner } from '@/components/legal/CookieBanner';
import { accountQueries, cartQueries } from '@/lib/api/queries';
import { serverApi } from '@/lib/api/server';
import { getQueryClient } from '@/lib/query/getQueryClient';

/**
 * A seller's own storefront (/b/{slug}) has its OWN header and footer: this layout deliberately renders none of the
 * marketplace chrome (no mega navbar, department bar, deliver-to picker, marketplace footer or support chat). It only
 * carries what the law and the cart need: the cookie choice and the shopper's cart.
 */
export default async function StoreLayout({ children }: { children: ReactNode }) {
  const api = await serverApi();
  const qc = getQueryClient();
  await Promise.allSettled([qc.prefetchQuery(accountQueries.me(api)), qc.prefetchQuery(cartQueries.current(api))]);
  return (
    <HydrationBoundary state={dehydrate(qc)}>
      {children}
      <CookieBanner />
    </HydrationBoundary>
  );
}
