import { type Metadata } from 'next';

import { ImportView } from '@/components/listings/bulk/ImportView';

export const metadata: Metadata = { title: 'Bulk upload' };

/** One bulk upload: preview with per-cell errors, then the import's progress and result (S308). */
export default async function ImportPage({ params }: { params: Promise<{ importId: string }> }) {
  const { importId } = await params;
  return <ImportView key={importId} importId={importId} />;
}
