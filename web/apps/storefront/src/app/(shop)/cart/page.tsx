import { type Metadata } from 'next';

import { CartView } from '@/components/cart/CartView';

export const metadata: Metadata = { title: 'Shopping Cart', robots: { index: false } };

/** Cart (hydrated from the shop layout's prefetch). */
export default function CartPage() {
  return <CartView />;
}
