import { type Metadata } from 'next';

import { CartView } from '@/components/cart/CartView';
import { getT } from '@/i18n/server';

export async function generateMetadata(): Promise<Metadata> {
  const t = await getT();
  return { title: t('title.cart'), robots: { index: false } };
}

/** Cart (hydrated from the shop layout's prefetch). */
export default function CartPage() {
  return <CartView />;
}
