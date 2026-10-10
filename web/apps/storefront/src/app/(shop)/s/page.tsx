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
  return { title: subject ? `${subject} — search` : 'Search', robots: q.page > 0 || q.sort !== 'relevance' ? { index: false } : undefined };
}

/** Search results — first page rendered on the server for SEO, then URL-driven on the client. */
export default async function SearchPage({ searchParams }: Props) {
  const query = parseSearchUrl(await searchParams);
  const qc = getQueryClient();
  await qc.prefetchQuery(searchQueries.results(await serverApi(), query));
  return (
    <HydrationBoundary state={dehydrate(qc)}>
      <Suspense>
        <SearchResults />
      </Suspense>
    </HydrationBoundary>
  );
}
