import { type Metadata } from 'next';
import { Suspense } from 'react';

import { GiftCardView } from '@/components/credit/GiftCardView';
import { getT } from '@/i18n/server';
import { requireCustomer } from '@/lib/auth/session';

export async function generateMetadata(): Promise<Metadata> {
  const t = await getT();
  return { title: t('title.giftCards'), robots: { index: false } };
}

export default async function Page() {
  await requireCustomer('/gift-cards');
  return (
    <Suspense>
      <GiftCardView />
    </Suspense>
  );
}
