import { type Metadata } from 'next';

import { DeliveryQuotesView } from '@/components/checkout/DeliveryQuotesView';
import { getT } from '@/i18n/server';
import { requireCustomer } from '@/lib/auth/session';

export async function generateMetadata(): Promise<Metadata> {
  const t = await getT();
  return { title: t('title.accountQuotes'), robots: { index: false } };
}

export default async function Page() {
  await requireCustomer('/account/delivery-quotes');
  return <DeliveryQuotesView />;
}
