import { type Metadata } from 'next';
import { Suspense } from 'react';

import { SettingsHub } from '@/components/settings/SettingsHub';
import { Spinner } from '@/components/ui/States';

export const metadata: Metadata = { title: 'Settings' };

export default function SettingsRoute() {
  return (
    <Suspense fallback={<Spinner />}>
      <SettingsHub />
    </Suspense>
  );
}
