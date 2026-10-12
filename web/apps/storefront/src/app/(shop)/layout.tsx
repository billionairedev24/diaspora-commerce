import { preorderQueries } from '@afrotransact/api-client';
import { dehydrate, HydrationBoundary } from '@tanstack/react-query';
import { type ReactNode, Suspense } from 'react';

import { CookieBanner } from '@/components/legal/CookieBanner';
import { Footer } from '@/components/shell/Footer';
import { Header } from '@/components/shell/Header';
import { SignedOutNotice } from '@/components/shell/SignedOutNotice';
import { accountQueries, cartQueries, siteQueries } from '@/lib/api/queries';
import { serverApi } from '@/lib/api/server';
import { getQueryClient } from '@/lib/query/getQueryClient';

/** Storefront chrome: announcement bar, header, department bar, footer and the first-visit cookie banner (not used by /signin and /signup). */
export default async function ShopLayout({ children }: { children: ReactNode }) {
  const api = await serverApi();
  const qc = getQueryClient();
  await Promise.allSettled([
    qc.prefetchQuery(siteQueries.config(api)),
    qc.prefetchQuery(siteQueries.home(api)),
    qc.prefetchQuery(siteQueries.deliveryLocation(api)),
    qc.prefetchQuery(accountQueries.me(api)),
    qc.prefetchQuery(cartQueries.current(api)),
    qc.prefetchQuery(preorderQueries.resolution(api)),
    qc.prefetchQuery(preorderQueries.open(api))
  ]);
  return (
    <HydrationBoundary state={dehydrate(qc)}>
      {/* No Suspense boundary around the header: every shop route is dynamic (cookies), so useSearchParams needs none,
          and a boundary this large is streamed out of line (React outlines big completed boundaries) — the page then
          paints without its fixed header and the header pops in later, shifting the layout. */}
      <Header />
      <Suspense>
        <SignedOutNotice />
      </Suspense>
      <main id="main" tabIndex={-1} className="page">
        {children}
      </main>
      <Footer />
      <CookieBanner />
    </HydrationBoundary>
  );
}
