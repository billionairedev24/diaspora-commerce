import { type Metadata } from 'next';
import { Suspense } from 'react';

import { PreordersView } from '@/components/preorders/PreordersView';
import { Spinner } from '@/components/ui/States';

export const metadata: Metadata = { title: 'Pre-order areas & settings' };

export default function PreorderSettingsPage() {
  return (
    <Suspense fallback={<Spinner />}>
      <PreordersView screen={{ kind: 'settings' }} />
    </Suspense>
  );
}
