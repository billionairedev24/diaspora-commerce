import { type Metadata } from 'next';
import { Suspense } from 'react';

import { OrdersView } from '@/components/orders/OrdersView';
import { getT } from '@/i18n/server';
import { requireCustomer } from '@/lib/auth/session';

export async function generateMetadata(): Promise<Metadata> {
  const t = await getT();
  return { title: t('title.orders'), robots: { index: false } };
}

export default async function Page() {
  await requireCustomer('/orders');
  return (
    <Suspense>
      <OrdersView />
    </Suspense>
  );
}
