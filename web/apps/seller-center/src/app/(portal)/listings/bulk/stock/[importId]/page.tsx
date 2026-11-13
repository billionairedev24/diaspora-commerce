import { type Metadata } from 'next';

import { StockImportView } from '@/components/listings/bulk/StockImportView';

export const metadata: Metadata = { title: 'Stock file' };

/** One stock file: preview with before and after counts, then the import's progress and result (S395). */
export default async function StockImportPage({ params }: { params: Promise<{ importId: string }> }) {
  const { importId } = await params;
  return <StockImportView key={importId} importId={importId} />;
}
