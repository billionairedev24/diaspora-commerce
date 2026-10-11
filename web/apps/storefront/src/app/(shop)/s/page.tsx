import { parseSearchUrl } from '@afrotransact/api-client';
import { dehydrate, HydrationBoundary } from '@tanstack/react-query';
import { type Metadata } from 'next';
import { Suspense } from 'react';

import { SearchResults } from '@/components/search/SearchResults';
import { searchQueries } from '@/lib/api/queries';
import { serverApi } from '@/lib/api/server';
import { getQueryClient } from '@/lib/query/getQueryClient';

type Props = { searchParams: Promise<Record<string, string | string[] | undefined>> };

export async function generateMetadata({ searchParams }: Props): Promise<Metadata> {
  const q = parseSearchUrl(await searchParams);
  const subject = q.q || q.department || q.community;
  return { title: subject ? `${subject} — search` : 'Search', robots: q.sort !== 'relevance' ? { index: false } : undefined };
}

/** Search results: the first page rendered on the server for SEO, more loaded on the client as the shopper scrolls. */
export default async function SearchPage({ searchParams }: Props) {
  const query = parseSearchUrl(await searchParams);
  const qc = getQueryClient();
  await qc.prefetchInfiniteQuery(searchQueries.infiniteResults(await serverApi(), query));
  return (
    <HydrationBoundary state={dehydrate(qc)}>
      <Suspense>
        <SearchResults />
      </Suspense>
    </HydrationBoundary>
  );
}
