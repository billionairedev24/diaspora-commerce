import { type Metadata } from 'next';
import { Suspense } from 'react';

import { PreordersView } from '@/components/preorders/PreordersView';
import { Spinner } from '@/components/ui/States';

export const metadata: Metadata = { title: 'Delivery runs' };

/** /preorders/runs: per-location run history, P&L before and after a run, subscribers and run advertorials. */
export default function PreorderRunsPage() {
  return (
    <Suspense fallback={<Spinner />}>
      <PreordersView screen={{ kind: 'runs' }} />
    </Suspense>
  );
}
