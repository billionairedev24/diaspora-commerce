import { AtinSchema, isApiError, type ProductDetail } from '@afrotransact/api-client';
import { dehydrate, HydrationBoundary } from '@tanstack/react-query';
import { type Metadata } from 'next';
import { notFound } from 'next/navigation';

import { ProductView } from '@/components/product/ProductView';
import { TrackView } from '@/components/product/TrackView';
import { JsonLd } from '@/components/seo/JsonLd';
import { getT } from '@/i18n/server';
import { productQueries } from '@/lib/api/queries';
import { serverApi } from '@/lib/api/server';
import { getQueryClient } from '@/lib/query/getQueryClient';
import { productSeo } from '@/lib/seo/pages';
import { getIndexingPolicy } from '@/lib/seo/server';

type Props = { params: Promise<{ atin: string }> };

async function load(atin: string): Promise<{ qc: ReturnType<typeof getQueryClient>; detail: ProductDetail } | null> {
  if (!AtinSchema.safeParse(atin).success) return null;
  const qc = getQueryClient();
  try {
    const detail = await qc.fetchQuery(productQueries.detail(await serverApi(), atin));
    return { qc, detail };
  } catch (e) {
    if (isApiError(e) && e.status === 404) return null;
    throw e;
  }
}

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const { atin } = await params;
  const r = await load(atin);
  // Next adds noindex to a not-found response; a product the catalog no longer has answers 404 and leaves the sitemap.
  if (!r) return { title: (await getT())('seo.notFound.title'), robots: { index: false, follow: false } };
  return productSeo(r.detail, await getIndexingPolicy(), await getT()).metadata;
}

/** Product detail page: server-rendered with Product + Offer and BreadcrumbList structured data in the first HTML response. */
export default async function ProductPage({ params }: Props) {
  const { atin } = await params;
  const r = await load(atin);
  if (!r) notFound();
  const seo = productSeo(r.detail, await getIndexingPolicy(), await getT());
  return (
    <HydrationBoundary state={dehydrate(r.qc)}>
      <JsonLd data={seo.jsonLd} />
      <TrackView atin={r.detail.product.atin} />
      <ProductView atin={atin} />
    </HydrationBoundary>
  );
}
