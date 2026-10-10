import { type Metadata } from 'next';

import { RequestsView } from '@/components/provider/RequestsView';

export const metadata: Metadata = { title: 'Requests' };

export default function RequestsPage() {
  return <RequestsView />;
}
