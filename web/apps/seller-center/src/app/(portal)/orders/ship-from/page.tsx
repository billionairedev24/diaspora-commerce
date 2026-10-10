import { type Metadata } from 'next';

import { ShipFromView } from '@/components/orders/ShipFromView';

export const metadata: Metadata = { title: 'Ship-from & AfroTransact pickup' };

export default function ShipFromPage() {
  return <ShipFromView />;
}
