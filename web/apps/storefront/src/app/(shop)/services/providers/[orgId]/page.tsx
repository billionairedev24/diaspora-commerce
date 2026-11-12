import { isApiError, type ProviderPage } from '@afrotransact/api-client';
import { dehydrate, HydrationBoundary, type QueryClient } from '@tanstack/react-query';
import { type Metadata } from 'next';
import { notFound } from 'next/navigation';
import { cache } from 'react';

import { JsonLd } from '@/components/seo/JsonLd';
import { ProviderPageView } from '@/components/services/ProviderPageView';
import { getT } from '@/i18n/server';
import { shopperQueries } from '@/lib/api/queries';
import { serverApi } from '@/lib/api/server';
import { getQueryClient } from '@/lib/query/getQueryClient';
import { providerSeo } from '@/lib/seo/pages';
import { getIndexingPolicy } from '@/lib/seo/server';

type Props = { params: Promise<{ orgId: string }> };

const load = cache(async (orgId: string): Promise<{ qc: QueryClient; page: ProviderPage } | null> => {
  const qc = getQueryClient();
  try {
    return { qc, page: await qc.fetchQuery(shopperQueries.provider(await serverApi(), orgId)) };
  } catch (e) {
    if (isApiError(e) && (e.status === 404 || e.status === 410)) return null;
    throw e;
  }
});

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const r = await load(decodeURIComponent((await params).orgId));
  const t = await getT();
  if (!r) return { title: t('seo.notFound.title'), robots: { index: false, follow: false } };
  return providerSeo(r.page, await getIndexingPolicy(), t).metadata;
}

export default async function Page({ params }: Props) {
  const orgId = decodeURIComponent((await params).orgId);
  const r = await load(orgId);
  if (!r) notFound();
  const seo = providerSeo(r.page, await getIndexingPolicy(), await getT());
  return (
    <HydrationBoundary state={dehydrate(r.qc)}>
      <JsonLd data={seo.jsonLd} />
      <ProviderPageView orgId={orgId} />
    </HydrationBoundary>
  );
}
