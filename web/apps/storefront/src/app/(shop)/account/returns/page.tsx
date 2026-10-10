import { type Metadata } from 'next';

import { ReturnsView } from '@/components/account/ReturnsView';
import { getT } from '@/i18n/server';
import { requireCustomer } from '@/lib/auth/session';

export async function generateMetadata(): Promise<Metadata> {
  const t = await getT();
  return { title: t('title.accountReturns'), robots: { index: false } };
}

export default async function Page({ searchParams }: { searchParams: Promise<{ order?: string }> }) {
  await requireCustomer('/account/returns');
  const { order } = await searchParams;
  return <ReturnsView orderNumber={typeof order === 'string' && order ? order : undefined} />;
}
