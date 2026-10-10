import { type Metadata } from 'next';

import { FeesView } from '@/components/money/FeesView';

export const metadata: Metadata = { title: 'Fees & commission' };

export default function FeesPage() {
  return <FeesView />;
}
