import { type Metadata } from 'next';

import { SellingApprovalsView } from '@/components/selling/SellingApprovalsView';

export const metadata: Metadata = { title: 'Selling approvals' };

export default function SellingApprovalsPage() {
  return <SellingApprovalsView />;
}
