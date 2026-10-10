import { dehydrate, HydrationBoundary } from '@tanstack/react-query';
import { type Metadata } from 'next';
import { Suspense } from 'react';

import { CareersView } from '@/components/careers/CareersView';
import { getT } from '@/i18n/server';
import { careersQueries } from '@/lib/api/queries';
import { serverApi } from '@/lib/api/server';
import { getQueryClient } from '@/lib/query/getQueryClient';

export async function generateMetadata(): Promise<Metadata> {
  const t = await getT();
  return { title: t('title.careers'), description: t('careers.intro') };
}

export default async function CareersPage() {
  const qc = getQueryClient();
  await qc.prefetchQuery(careersQueries.jobs(await serverApi()));
  return (
    <HydrationBoundary state={dehydrate(qc)}>
      <Suspense>
        <CareersView />
      </Suspense>
    </HydrationBoundary>
  );
}
