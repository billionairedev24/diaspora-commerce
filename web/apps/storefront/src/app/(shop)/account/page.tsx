import { type Metadata } from 'next';

import { AccountHome } from '@/components/account/AccountHome';
import { getT } from '@/i18n/server';
import { requireCustomer } from '@/lib/auth/session';

export async function generateMetadata(): Promise<Metadata> {
  const t = await getT();
  return { title: t('title.account'), robots: { index: false } };
}

export default async function Page() {
  await requireCustomer('/account');
  return <AccountHome />;
}
