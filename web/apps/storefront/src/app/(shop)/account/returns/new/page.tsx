import { type Metadata } from 'next';
import { redirect } from 'next/navigation';

import { StartReturnView } from '@/components/account/StartReturnView';
import { getT } from '@/i18n/server';
import { requireCustomer } from '@/lib/auth/session';

type Props = { searchParams: Promise<{ order?: string }> };

export async function generateMetadata(): Promise<Metadata> {
  const t = await getT();
  return { title: t('title.accountReturnsNew'), robots: { index: false } };
}

export default async function Page({ searchParams }: Props) {
  const { order } = await searchParams;
  if (!order) redirect('/account/returns');
  await requireCustomer(`/account/returns/new?order=${encodeURIComponent(order)}`);
  return <StartReturnView orderNumber={order} />;
}
