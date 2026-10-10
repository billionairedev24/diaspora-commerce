import { type Metadata } from 'next';

import { SecurityView } from '@/components/account/SecurityView';
import { getT } from '@/i18n/server';
import { requireCustomer } from '@/lib/auth/session';

export async function generateMetadata(): Promise<Metadata> {
  const t = await getT();
  return { title: t('title.accountSecurity'), robots: { index: false } };
}

export default async function Page() {
  await requireCustomer('/account/security');
  return <SecurityView />;
}
