import { type Metadata } from 'next';
import { Suspense } from 'react';

import { PreordersView } from '@/components/preorders/PreordersView';
import { Spinner } from '@/components/ui/States';

export const metadata: Metadata = { title: 'Pre-orders' };

/** /preorders?status=all|open|upcoming|closed|draft */
export default function PreordersPage() {
  return (
    <Suspense fallback={<Spinner />}>
      <PreordersView screen={{ kind: 'list' }} />
    </Suspense>
  );
}
