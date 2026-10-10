import { AtinSchema, isApiError, type ProductDetail } from '@afrotransact/api-client';
import { dehydrate, HydrationBoundary } from '@tanstack/react-query';
import { type Metadata } from 'next';
import { notFound } from 'next/navigation';

import { ProductView } from '@/components/product/ProductView';
import { productQueries } from '@/lib/api/queries';
import { serverApi } from '@/lib/api/server';
import { getQueryClient } from '@/lib/query/getQueryClient';

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
  if (!r) return { title: 'Product not found' };
  const p = r.detail.product;
  return { title: p.title, description: `${p.bullets.join('. ')}. ${p.community} · ${p.unit}.`, alternates: { canonical: `/p/${p.atin}` } };
}

/** Product detail page — server-rendered with Product structured data. */
export default async function ProductPage({ params }: Props) {
  const { atin } = await params;
  const r = await load(atin);
  if (!r) notFound();
  const { product: p, buyBox } = r.detail;
  const jsonLd = {
    '@context': 'https://schema.org',
    '@type': 'Product',
    name: p.title,
    sku: p.atin,
    brand: { '@type': 'Brand', name: p.brand },
    description: p.bullets.join(' '),
    aggregateRating: { '@type': 'AggregateRating', ratingValue: p.rating, reviewCount: p.reviewCount },
    offers: buyBox
      ? { '@type': 'Offer', price: (buyBox.priceMinor / 100).toFixed(2), priceCurrency: buyBox.currency, availability: 'https://schema.org/InStock', seller: { '@type': 'Organization', name: buyBox.seller.name } }
      : { '@type': 'Offer', availability: 'https://schema.org/OutOfStock' }
  };
  return (
    <HydrationBoundary state={dehydrate(r.qc)}>
      <script type="application/ld+json" dangerouslySetInnerHTML={{ __html: JSON.stringify(jsonLd).replace(/</g, '\\u003c') }} />
      <ProductView atin={p.atin} />
    </HydrationBoundary>
  );
}
