import { type Metadata } from 'next';

import { CreditView } from '@/components/credit/CreditView';
import { getT } from '@/i18n/server';
import { requireCustomer } from '@/lib/auth/session';

export async function generateMetadata(): Promise<Metadata> {
  const t = await getT();
  return { title: t('title.accountCredit'), robots: { index: false } };
}

export default async function Page() {
  await requireCustomer('/account/credit');
  return <CreditView />;
}
