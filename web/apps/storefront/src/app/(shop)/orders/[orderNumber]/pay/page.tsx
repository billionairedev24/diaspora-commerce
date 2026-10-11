import { type Metadata } from 'next';
import { Suspense } from 'react';

import { PayNowView } from '@/components/paynow/PayNowView';
import { getT } from '@/i18n/server';

type Props = { params: Promise<{ orderNumber: string }> };

export async function generateMetadata(): Promise<Metadata> {
  const t = await getT();
  return { title: t('title.payNow'), robots: { index: false, follow: false }, referrer: 'no-referrer' };
}

/**
 * "Complete your pre-order payment" (late pre-order adds): the pay-now link customer service texts opens this
 * AfroTransact page, never a Stripe-hosted one. No sign-in: the link's signed token (?token=) is the credential.
 */
export default async function Page({ params }: Props) {
  const { orderNumber } = await params;
  return (
    <Suspense>
      <PayNowView orderNumber={decodeURIComponent(orderNumber)} />
    </Suspense>
  );
}
