import { type Metadata } from 'next';

import { PaymentsView } from '@/components/account/PaymentsView';
import { getT } from '@/i18n/server';
import { requireCustomer } from '@/lib/auth/session';

export async function generateMetadata(): Promise<Metadata> {
  const t = await getT();
  return { title: t('title.accountPayments'), robots: { index: false } };
}

export default async function Page() {
  await requireCustomer('/account/payments');
  return <PaymentsView />;
}
