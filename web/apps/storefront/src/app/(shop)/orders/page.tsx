import { type Metadata } from 'next';
import { Suspense } from 'react';

import { OrdersView } from '@/components/orders/OrdersView';
import { requireCustomer } from '@/lib/auth/session';

export const metadata: Metadata = { title: 'Your orders', robots: { index: false } };

export default async function Page() {
  await requireCustomer('/orders');
  return (
    <Suspense>
      <OrdersView />
    </Suspense>
  );
}
