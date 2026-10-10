import { type Metadata } from 'next';
import { Suspense } from 'react';

import { PreordersView } from '@/components/preorders/PreordersView';
import { Spinner } from '@/components/ui/States';

export const metadata: Metadata = { title: 'Open a pre-order' };

export default function NewPreorderPage() {
  return (
    <Suspense fallback={<Spinner />}>
      <PreordersView screen={{ kind: 'new' }} />
    </Suspense>
  );
}
