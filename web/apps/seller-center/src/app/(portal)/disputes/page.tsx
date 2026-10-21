import { type Metadata } from 'next';

import { DisputesView } from '@/components/cases/DisputesView';

export const metadata: Metadata = { title: 'Disputes' };

export default function DisputesPage() {
  return <DisputesView />;
}
