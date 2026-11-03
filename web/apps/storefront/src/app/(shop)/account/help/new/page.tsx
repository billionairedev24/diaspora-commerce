import { type Metadata } from 'next';
import { redirect } from 'next/navigation';

import { HelpNewView } from '@/components/account/HelpNewView';
import { getT } from '@/i18n/server';
import { requireCustomer } from '@/lib/auth/session';

type Props = { searchParams: Promise<{ order?: string }> };

export async function generateMetadata(): Promise<Metadata> {
  const t = await getT();
  return { title: t('title.accountHelpNew'), robots: { index: false } };
}

export default async function Page({ searchParams }: Props) {
  const { order } = await searchParams;
  if (!order) redirect('/account/help');
  await requireCustomer(`/account/help/new?order=${encodeURIComponent(order)}`);
  return <HelpNewView orderNumber={order} />;
}
