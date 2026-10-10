import { type Metadata } from 'next';

import { ReturnsView } from '@/components/account/ReturnsView';
import { requireCustomer } from '@/lib/auth/session';

export const metadata: Metadata = { title: 'Returns & refunds', robots: { index: false } };

export default async function Page({ searchParams }: { searchParams: Promise<{ order?: string }> }) {
  await requireCustomer('/account/returns');
  const { order } = await searchParams;
  return <ReturnsView orderNumber={typeof order === 'string' && order ? order : undefined} />;
}
