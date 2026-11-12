import { type Metadata } from 'next';

import { LocationsIndex } from '@/components/runs/LocationPage';
import { getT } from '@/i18n/server';

export async function generateMetadata(): Promise<Metadata> {
  const t = await getT();
  return { title: t('runs.index.title'), description: t('runs.index.intro'), alternates: { canonical: '/delivery-runs' } };
}

/** /delivery-runs (S262): every location with delivery runs, and the advertorials for the shopper's place. */
export default function DeliveryRunsPage() {
  return <LocationsIndex />;
}
