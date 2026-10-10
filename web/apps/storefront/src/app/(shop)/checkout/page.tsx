import { type Metadata } from 'next';

import { CheckoutView } from '@/components/checkout/CheckoutView';
import { requireCustomer } from '@/lib/auth/session';

export const metadata: Metadata = { title: 'Checkout', robots: { index: false } };

export default async function Page() {
  await requireCustomer('/checkout');
  return <CheckoutView />;
}
