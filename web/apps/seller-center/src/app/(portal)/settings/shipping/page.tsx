import { type Metadata } from 'next';

import { ShippingView } from '@/components/settings/ShippingView';

export const metadata: Metadata = { title: 'Shipping & delivery' };

export default function Page() {
  return <ShippingView />;
}
