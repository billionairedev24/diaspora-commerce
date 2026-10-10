import { type Metadata } from 'next';

import { OrderDetailView } from '@/components/orders/OrderDetailView';
import { getT } from '@/i18n/server';
import { requireCustomer } from '@/lib/auth/session';

type Props = { params: Promise<{ orderNumber: string }> };

export async function generateMetadata(): Promise<Metadata> {
  const t = await getT();
  return { title: t('title.orderDetail'), robots: { index: false } };
}

export default async function Page({ params }: Props) {
  const { orderNumber } = await params;
  await requireCustomer(`/orders/${encodeURIComponent(orderNumber)}`);
  return <OrderDetailView orderNumber={decodeURIComponent(orderNumber)} />;
}
