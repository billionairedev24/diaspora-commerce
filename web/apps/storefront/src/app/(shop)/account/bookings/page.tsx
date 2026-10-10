import { type Metadata } from 'next';

import { BookingsView } from '@/components/services/BookingsView';
import { getT } from '@/i18n/server';
import { requireCustomer } from '@/lib/auth/session';

export async function generateMetadata(): Promise<Metadata> {
  const t = await getT();
  return { title: t('title.accountBookings'), robots: { index: false } };
}

export default async function Page() {
  await requireCustomer('/account/bookings');
  return <BookingsView />;
}
