import { dehydrate, HydrationBoundary } from '@tanstack/react-query';
import { cookies } from 'next/headers';
import { type ReactNode, Suspense } from 'react';

import { Footer } from '@/components/shell/Footer';
import { Header } from '@/components/shell/Header';
import { accountQueries, cartQueries, siteQueries } from '@/lib/api/queries';
import { serverApi } from '@/lib/api/server';
import { getQueryClient } from '@/lib/query/getQueryClient';

/** Storefront chrome: announcement bar, header, department bar and footer (not used by /signin and /signup). */
export default async function ShopLayout({ children }: { children: ReactNode }) {
  const api = await serverApi();
  const qc = getQueryClient();
  await Promise.allSettled([
    qc.prefetchQuery(siteQueries.config(api)),
    qc.prefetchQuery(siteQueries.home(api)),
    qc.prefetchQuery(siteQueries.deliveryLocation(api)),
    qc.prefetchQuery(accountQueries.me(api)),
    qc.prefetchQuery(cartQueries.current(api))
  ]);
  const locale = (await cookies()).get('at_locale')?.value ?? 'en';
  return (
    <HydrationBoundary state={dehydrate(qc)}>
      <Suspense>
        <Header />
      </Suspense>
      <main id="main" tabIndex={-1} className="page">
        {children}
      </main>
      <Footer locale={locale} />
    </HydrationBoundary>
  );
}
