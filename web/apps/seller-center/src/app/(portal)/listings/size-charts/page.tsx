import { type Metadata } from 'next';

import { SizeChartsView } from '@/components/size-charts/SizeChartsView';

export const metadata: Metadata = { title: 'Size charts' };

export default function SizeChartsPage() {
  return <SizeChartsView />;
}
