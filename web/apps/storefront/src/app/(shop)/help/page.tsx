import { HELP_HUB, isApiError } from '@afrotransact/api-client';
import { dehydrate, HydrationBoundary } from '@tanstack/react-query';
import { type Metadata } from 'next';
import { notFound } from 'next/navigation';
import { Suspense } from 'react';

import { HelpArticleList } from '@/components/help/HelpArticleList';
import { HelpArticleView } from '@/components/help/HelpArticleView';
import { getT } from '@/i18n/server';
import { helpQueries } from '@/lib/api/queries';
import { serverApi } from '@/lib/api/server';
import { HELP_PAGE_SIZE, helpTopicOf } from '@/lib/help';
import { getQueryClient } from '@/lib/query/getQueryClient';

type Props = { searchParams: Promise<{ topic?: string | string[] }> };

export async function generateMetadata(): Promise<Metadata> {
  const t = await getT();
  return { title: t('title.help'), alternates: { canonical: '/help' } };
}

/** Help center hub (content-service help-center page) and every help article by topic, loaded as the shopper scrolls. */
export default async function HelpCenterPage({ searchParams }: Props) {
  const sp = await searchParams;
  const topic = helpTopicOf(Array.isArray(sp.topic) ? sp.topic[0] : sp.topic);
  const [qc, api, t] = [getQueryClient(), await serverApi(), await getT()];
  const article = await qc.fetchQuery(helpQueries.article(api, HELP_HUB)).catch((e: unknown) => {
    if (isApiError(e) && e.status === 404) notFound();
    throw e;
  });
  await qc.prefetchInfiniteQuery(helpQueries.infiniteArticles(api, { topic, limit: HELP_PAGE_SIZE }));
  return (
    <HydrationBoundary state={dehydrate(qc)}>
      <HelpArticleView article={article} t={t}>
        <Suspense>
          <HelpArticleList />
        </Suspense>
      </HelpArticleView>
    </HydrationBoundary>
  );
}
