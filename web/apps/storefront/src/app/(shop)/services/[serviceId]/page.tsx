import { isApiError, type ServiceDetail } from '@afrotransact/api-client';
import { dehydrate, HydrationBoundary, type QueryClient } from '@tanstack/react-query';
import { type Metadata } from 'next';
import { notFound } from 'next/navigation';
import { cache } from 'react';

import { JsonLd } from '@/components/seo/JsonLd';
import { ServiceDetailView } from '@/components/services/ServiceDetailView';
import { getT } from '@/i18n/server';
import { shopperQueries } from '@/lib/api/queries';
import { serverApi } from '@/lib/api/server';
import { getQueryClient } from '@/lib/query/getQueryClient';
import { serviceSeo } from '@/lib/seo/pages';
import { getIndexingPolicy } from '@/lib/seo/server';

type Props = { params: Promise<{ serviceId: string }> };

/** The service (once per request), or null when the provider no longer offers it (then the page is a 404 and leaves the sitemap). */
const load = cache(async (id: string): Promise<{ qc: QueryClient; service: ServiceDetail } | null> => {
  const qc = getQueryClient();
  try {
    return { qc, service: await qc.fetchQuery(shopperQueries.service(await serverApi(), id)) };
  } catch (e) {
    if (isApiError(e) && (e.status === 404 || e.status === 410)) return null;
    throw e;
  }
});

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const r = await load(decodeURIComponent((await params).serviceId));
  const t = await getT();
  if (!r) return { title: t('seo.notFound.title'), robots: { index: false, follow: false } };
  return serviceSeo(r.service, await getIndexingPolicy(), t).metadata;
}

export default async function Page({ params }: Props) {
  const serviceId = decodeURIComponent((await params).serviceId);
  const r = await load(serviceId);
  if (!r) notFound();
  const seo = serviceSeo(r.service, await getIndexingPolicy(), await getT());
  return (
    <HydrationBoundary state={dehydrate(r.qc)}>
      <JsonLd data={seo.jsonLd} />
      <ServiceDetailView serviceId={serviceId} />
    </HydrationBoundary>
  );
}
