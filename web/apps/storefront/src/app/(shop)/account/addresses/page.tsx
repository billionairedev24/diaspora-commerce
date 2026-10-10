import { type Metadata } from 'next';

import { AddressBook } from '@/components/account/AddressBook';
import { requireCustomer } from '@/lib/auth/session';

export const metadata: Metadata = { title: 'Your addresses', robots: { index: false } };

export default async function Page() {
  await requireCustomer('/account/addresses');
  return <AddressBook />;
}
