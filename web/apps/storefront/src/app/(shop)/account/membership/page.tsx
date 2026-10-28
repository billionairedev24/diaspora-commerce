import { type Metadata } from 'next';

import { MembershipManageView } from '@/components/membership/MembershipManageView';
import { getT } from '@/i18n/server';
import { requireCustomer } from '@/lib/auth/session';

export async function generateMetadata(): Promise<Metadata> {
  const t = await getT();
  return { title: t('title.accountMembership'), robots: { index: false } };
}

export default async function Page() {
  await requireCustomer('/account/membership');
  return <MembershipManageView />;
}
