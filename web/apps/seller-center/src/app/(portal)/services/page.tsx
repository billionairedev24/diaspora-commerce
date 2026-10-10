import { type Metadata } from 'next';

import { ServicesView } from '@/components/provider/ServicesView';

export const metadata: Metadata = { title: 'Services' };

export default function ServicesPage() {
  return <ServicesView />;
}
