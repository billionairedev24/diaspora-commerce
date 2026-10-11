import { HELP_HUB, type HelpArticle, helpHref, isApiError } from '@afrotransact/api-client';
import { type Metadata } from 'next';
import { notFound, permanentRedirect } from 'next/navigation';
import { cache } from 'react';

import { HelpArticleView } from '@/components/help/HelpArticleView';
import { getT } from '@/i18n/server';
import { helpQueries } from '@/lib/api/queries';
import { serverApi } from '@/lib/api/server';
import { getQueryClient } from '@/lib/query/getQueryClient';

type Props = { params: Promise<{ slug: string }> };

const SLUG = /^[a-z0-9][a-z0-9-]{1,59}$/;

/** The article for /help/<slug>: the hub's own slug redirects to /help, unknown or unpublished pages are 404. */
const load = cache(async (raw: string): Promise<HelpArticle> => {
  const slug = decodeURIComponent(raw).toLowerCase();
  if (!SLUG.test(slug)) notFound();
  if (slug === HELP_HUB) permanentRedirect('/help');
  if (slug !== raw) permanentRedirect(helpHref(slug));
  try {
    return await getQueryClient().fetchQuery(helpQueries.article(await serverApi(), slug));
  } catch (e) {
    if (isApiError(e) && e.status === 404) notFound();
    throw e;
  }
});

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const article = await load((await params).slug);
  const t = await getT();
  return { title: `${article.title} · ${t('title.help')}`, description: article.summary, alternates: { canonical: helpHref(article.slug) } };
}

/** A help page (shipping & delivery, returns, Fulfilled by AfroTransact, advertising, press…) from content-service. */
export default async function HelpArticlePage({ params }: Props) {
  const [article, t] = await Promise.all([load((await params).slug), getT()]);
  return <HelpArticleView article={article} t={t} />;
}
