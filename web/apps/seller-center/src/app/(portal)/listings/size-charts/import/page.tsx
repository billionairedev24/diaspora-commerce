import { type Metadata } from 'next';

import { SizeChartImportView } from '@/components/size-charts/SizeChartImportView';

export const metadata: Metadata = { title: 'Import size charts' };

export default function ImportSizeChartsPage() {
  return <SizeChartImportView />;
}
