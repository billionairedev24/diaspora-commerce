import { type Metadata } from 'next';
import { Suspense } from 'react';

import { CheckoutComplete } from '@/components/checkout/CheckoutComplete';
import { getT } from '@/i18n/server';
import { requireCustomer } from '@/lib/auth/session';

export async function generateMetadata(): Promise<Metadata> {
  const t = await getT();
  return { title: t('title.checkoutComplete'), robots: { index: false } };
}

/** Stripe's return_url after a redirect (3-D Secure page, Cash App Pay, Affirm, bank): ?order=…&payment_intent=…&redirect_status=…. */
export default async function Page() {
  await requireCustomer('/checkout');
  return (
    <Suspense>
      <CheckoutComplete />
    </Suspense>
  );
}
