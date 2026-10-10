import { type Metadata } from 'next';

import { PaymentsView } from '@/components/account/PaymentsView';
import { requireCustomer } from '@/lib/auth/session';

export const metadata: Metadata = { title: 'Your payments', robots: { index: false } };

export default async function Page() {
  await requireCustomer('/account/payments');
  return <PaymentsView />;
}
