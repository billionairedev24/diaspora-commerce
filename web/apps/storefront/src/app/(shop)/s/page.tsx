import { parseSearchUrl } from '@afrotransact/api-client';
import { dehydrate, HydrationBoundary } from '@tanstack/react-query';
import { type Metadata } from 'next';
import { cache, Suspense } from 'react';

import { SearchResults } from '@/components/search/SearchResults';
import { CategoryPagination } from '@/components/seo/CategoryPagination';
import { JsonLd } from '@/components/seo/JsonLd';
import { getT } from '@/i18n/server';
import { searchQueries } from '@/lib/api/queries';
import { serverApi } from '@/lib/api/server';
import { getQueryClient } from '@/lib/query/getQueryClient';
import { classifySearch, facetCanonicalPath } from '@/lib/seo/canonical';
import { noindexMetadata } from '@/lib/seo/metadata';
import { categorySeo } from '@/lib/seo/pages';
import { getIndexingPolicy } from '@/lib/seo/server';

type Props = { searchParams: Promise<Record<string, string | string[] | undefined>> };

/** The first page of results, fetched once per request (metadata and page share it). */
const firstPage = cache(async (key: string) => {
  const query = parseSearchUrl(new URLSearchParams(key));
  const qc = getQueryClient();
  const api = await serverApi();
  await qc.prefetchInfiniteQuery(searchQueries.infiniteResults(api, query));
  return { qc, query, data: qc.getQueryData(searchQueries.infiniteResults(api, query).queryKey)?.pages[0] };
});

const keyOf = (sp: Record<string, string | string[] | undefined>): string => {
  const p = new URLSearchParams();
  for (const [k, v] of Object.entries(sp)) {
    const one = Array.isArray(v) ? v[0] : v;
    if (one !== undefined) p.set(k, one);
  }
  return p.toString();
};

/** Only a category page (ONE department or ONE community, optionally paginated) is indexable: a free-text search, a sort or any
    filter is a faceted duplicate: noindex, canonical to the category page when there is one. */
export async function generateMetadata({ searchParams }: Props): Promise<Metadata> {
  const { query, data } = await firstPage(keyOf(await searchParams));
  const [t, policy] = await Promise.all([getT(), getIndexingPolicy()]);
  const kind = classifySearch(query);
  if (kind.kind === 'category') {
    return categorySeo({ dimension: kind.dimension, value: kind.value, page: kind.page, total: data?.total ?? 0, firstImage: data?.hits[0]?.imageUrl }, policy, t).metadata;
  }
  const subject = query.q || query.department || query.community;
  const canonical = facetCanonicalPath(query);
  return { ...noindexMetadata(subject ? t('seo.search.titleFor', { subject }) : t('seo.search.title')), ...(canonical ? { alternates: { canonical } } : {}) };
}

/** Search results: the first page rendered on the server for SEO, more loaded on the client as the shopper scrolls. A category
    page also carries its breadcrumb structured data and crawlable pagination links in the first HTML response. */
export default async function SearchPage({ searchParams }: Props) {
  const { qc, query, data } = await firstPage(keyOf(await searchParams));
  const kind = classifySearch(query);
  const [t, policy] = await Promise.all([getT(), getIndexingPolicy()]);
  const seo = kind.kind === 'category' ? categorySeo({ dimension: kind.dimension, value: kind.value, page: kind.page, total: data?.total ?? 0 }, policy, t) : undefined;
  return (
    <HydrationBoundary state={dehydrate(qc)}>
      {seo ? <JsonLd data={seo.jsonLd} /> : null}
      <Suspense>
        <SearchResults {...(kind.kind === 'category' ? { heading: kind.value } : {})} />
      </Suspense>
      {kind.kind === 'category' && data ? <CategoryPagination dimension={kind.dimension} value={kind.value} page={kind.page} pageCount={data.pageCount} /> : null}
    </HydrationBoundary>
  );
}
