import { type Metadata } from 'next';

import { EarningsView } from '@/components/provider/EarningsView';

export const metadata: Metadata = { title: 'Earnings' };

export default function EarningsPage() {
  return <EarningsView />;
}
