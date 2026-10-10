import { type Metadata } from 'next';
import { Suspense } from 'react';

import { PreordersView } from '@/components/preorders/PreordersView';
import { Spinner } from '@/components/ui/States';

export const metadata: Metadata = { title: 'Edit pre-order' };

export default async function EditPreorderPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return (
    <Suspense fallback={<Spinner />}>
      <PreordersView screen={{ kind: 'edit', id: decodeURIComponent(id) }} />
    </Suspense>
  );
}
