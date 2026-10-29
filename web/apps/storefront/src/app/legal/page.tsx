import { legalQueries } from '@afrotransact/api-client';
import { type Metadata } from 'next';

import { LegalCenter } from '@/components/legal/LegalCenter';
import { getLocale, getT } from '@/i18n/server';
import { serverApi } from '@/lib/api/server';
import { getQueryClient } from '@/lib/query/getQueryClient';

export async function generateMetadata(): Promise<Metadata> {
  const t = await getT();
  return { title: t('legal.center'), description: t('legal.centerIntro'), alternates: { canonical: '/legal' } };
}

/** Legal & privacy center index: every document grouped by who it is for, with its current version. */
export default async function LegalCenterPage() {
  const [locale, t, api] = await Promise.all([getLocale(), getT(), serverApi()]);
  // The index only adds version details: if content is unreachable the page still lists and links every document.
  const index = await getQueryClient()
    .fetchQuery(legalQueries.index(api))
    .catch(() => undefined);
  return <LegalCenter documents={index?.documents ?? []} locale={locale} t={t} />;
}
