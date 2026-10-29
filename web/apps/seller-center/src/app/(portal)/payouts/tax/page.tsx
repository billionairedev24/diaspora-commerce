import { type Metadata } from 'next';

import { TaxStatementView } from '@/components/money/TaxStatementView';

export const metadata: Metadata = { title: 'Sales tax statement' };

export default function TaxStatementPage() {
  return <TaxStatementView />;
}
