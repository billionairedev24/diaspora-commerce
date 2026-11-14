import { type Metadata } from 'next';

import { PickupView } from '@/components/settings/PickupView';

export const metadata: Metadata = { title: 'Pickup' };

export default function Page() {
  return <PickupView />;
}
