import { type Metadata } from 'next';

import { BulkUploadView } from '@/components/listings/bulk/BulkUploadView';

export const metadata: Metadata = { title: 'Bulk upload' };

export default function BulkUploadPage() {
  return <BulkUploadView />;
}
