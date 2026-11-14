import { type Metadata } from 'next';

import { LocationsView } from '@/components/settings/LocationsView';

export const metadata: Metadata = { title: 'Locations' };

export default function Page() {
  return <LocationsView />;
}
