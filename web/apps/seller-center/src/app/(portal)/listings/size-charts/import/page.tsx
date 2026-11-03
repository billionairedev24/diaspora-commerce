import { type Metadata } from 'next';

import { SizeChartImportView } from '@/components/size-charts/SizeChartImportView';
import { SizeChartsGate } from '@/components/size-charts/SizeChartsGate';

export const metadata: Metadata = { title: 'Import size charts' };

export default function ImportSizeChartsPage() {
  return <SizeChartsGate>
      <SizeChartImportView />
    </SizeChartsGate>;
}
