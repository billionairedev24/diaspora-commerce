import { type Metadata } from 'next';

import { AddressBook } from '@/components/account/AddressBook';
import { getT } from '@/i18n/server';
import { requireCustomer } from '@/lib/auth/session';

export async function generateMetadata(): Promise<Metadata> {
  const t = await getT();
  return { title: t('title.accountAddresses'), robots: { index: false } };
}

export default async function Page() {
  await requireCustomer('/account/addresses');
  return <AddressBook />;
}
