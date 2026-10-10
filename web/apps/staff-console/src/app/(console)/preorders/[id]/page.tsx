import { type Metadata } from 'next';
import { Suspense } from 'react';

import { PreordersView } from '@/components/preorders/PreordersView';
import { Spinner } from '@/components/ui/States';

export const metadata: Metadata = { title: 'Pre-order' };

/** /preorders/{id}?tab=overview|orders|creatives|analytics */
export default async function PreorderPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return (
    <Suspense fallback={<Spinner />}>
      <PreordersView screen={{ kind: 'detail', id: decodeURIComponent(id) }} />
    </Suspense>
  );
}
