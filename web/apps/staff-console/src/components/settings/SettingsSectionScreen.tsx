'use client';

import { Suspense } from 'react';

import { Spinner } from '@/components/ui/States';
import { type SettingsKey } from '@/lib/settings-sections';

import { SectionBody } from './SectionBodies';
import { SettingsPage } from './SettingsShell';

/** One section page: the header, the read-only note and the section's body (the route and the tests render this). */
export function SettingsSectionScreen({ section }: { section: SettingsKey }) {
  return (
    <SettingsPage section={section}>
      <Suspense fallback={<Spinner />}>
        <SectionBody section={section} />
      </Suspense>
    </SettingsPage>
  );
}
