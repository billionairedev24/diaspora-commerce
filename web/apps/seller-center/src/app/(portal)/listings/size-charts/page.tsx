import { type Metadata } from 'next';

import { SizeChartsGate } from '@/components/size-charts/SizeChartsGate';
import { SizeChartsView } from '@/components/size-charts/SizeChartsView';

export const metadata: Metadata = { title: 'Size charts' };

export default function SizeChartsPage() {
  return <SizeChartsGate>
      <SizeChartsView />
    </SizeChartsGate>;
}
