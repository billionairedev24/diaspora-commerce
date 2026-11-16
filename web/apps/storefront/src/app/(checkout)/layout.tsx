import { dehydrate, HydrationBoundary } from '@tanstack/react-query';
import { type ReactNode } from 'react';

import { CheckoutChrome } from '@/components/checkout/CheckoutChrome';
import { CookieBanner } from '@/components/legal/CookieBanner';
import { accountQueries, cartQueries, siteQueries } from '@/lib/api/queries';
import { serverApi } from '@/lib/api/server';
import { getQueryClient } from '@/lib/query/getQueryClient';

/**
 * The checkout is a focused flow, like Amazon's: a minimal header (logo, "Checkout (N items)", secure label) and a slim footer
 * with the legal links. No mega navbar, department bar, announcement bar, big footer, chat launcher or support button.
 * Only what the law and the cart need: the cookie choice.
 */
export default async function CheckoutLayout({ children }: { children: ReactNode }) {
  const api = await serverApi();
  const qc = getQueryClient();
  await Promise.allSettled([qc.prefetchQuery(siteQueries.config(api)), qc.prefetchQuery(accountQueries.me(api)), qc.prefetchQuery(cartQueries.current(api))]);
  return (
    <HydrationBoundary state={dehydrate(qc)}>
      <CheckoutChrome>{children}</CheckoutChrome>
      <CookieBanner />
    </HydrationBoundary>
  );
}
