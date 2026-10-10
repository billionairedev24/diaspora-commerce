import { type Metadata } from 'next';

import { SecurityView } from '@/components/account/SecurityView';
import { requireCustomer } from '@/lib/auth/session';

export const metadata: Metadata = { title: 'Login & security', robots: { index: false } };

export default async function Page() {
  await requireCustomer('/account/security');
  return <SecurityView />;
}
