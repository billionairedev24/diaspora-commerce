import { type Metadata } from 'next';

import { ImportHistory } from '@/components/listings/bulk/ImportHistory';

export const metadata: Metadata = { title: 'Upload history' };

export default function UploadHistoryPage() {
  return <ImportHistory />;
}
