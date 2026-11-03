import { type Metadata } from 'next';

import { HelpView } from '@/components/account/HelpView';
import { getT } from '@/i18n/server';
import { requireCustomer } from '@/lib/auth/session';

export async function generateMetadata(): Promise<Metadata> {
  const t = await getT();
  return { title: t('title.accountHelp'), robots: { index: false } };
}

export default async function Page() {
  await requireCustomer('/account/help');
  return <HelpView />;
}
