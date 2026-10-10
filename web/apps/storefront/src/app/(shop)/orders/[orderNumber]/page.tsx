import { type Metadata } from 'next';

import { OrderDetailView } from '@/components/orders/OrderDetailView';
import { requireCustomer } from '@/lib/auth/session';

type Props = { params: Promise<{ orderNumber: string }> };

export const metadata: Metadata = { title: 'Order details', robots: { index: false } };

export default async function Page({ params }: Props) {
  const { orderNumber } = await params;
  await requireCustomer(`/orders/${encodeURIComponent(orderNumber)}`);
  return <OrderDetailView orderNumber={decodeURIComponent(orderNumber)} />;
}
