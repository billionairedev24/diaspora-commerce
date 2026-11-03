import { type Metadata } from 'next';
import { Suspense } from 'react';

import { SearchAnalyticsView } from '@/components/search/SearchAnalyticsView';
import { Spinner } from '@/components/ui/States';

export const metadata: Metadata = { title: 'Search analytics' };

/** /search/analytics?tab=top|zero|ctr|rules&market=us&period=7|30|90|custom&from=YYYY-MM-DD&to=YYYY-MM-DD */
export default function SearchAnalyticsPage() {
  return (
    <Suspense fallback={<Spinner />}>
      <SearchAnalyticsView />
    </Suspense>
  );
}
