import { type Metadata } from 'next';

import { BillingView } from '@/components/money/BillingView';

export const metadata: Metadata = { title: 'Plan & billing' };

export default function BillingPage() {
  return <BillingView />;
}
