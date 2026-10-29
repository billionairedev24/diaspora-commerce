import { type Metadata } from 'next';

import { ReferView } from '@/components/refer/ReferView';
import { getT } from '@/i18n/server';
import { requireCustomer } from '@/lib/auth/session';

export async function generateMetadata(): Promise<Metadata> {
  const t = await getT();
  return { title: t('title.accountRefer'), robots: { index: false } };
}

export default async function Page() {
  await requireCustomer('/account/refer');
  return <ReferView />;
}
