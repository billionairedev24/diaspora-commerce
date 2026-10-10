import { type Metadata } from 'next';

import { BookingsView } from '@/components/services/BookingsView';
import { requireCustomer } from '@/lib/auth/session';

export const metadata: Metadata = { title: 'Your appointments', robots: { index: false } };

export default async function Page() {
  await requireCustomer('/account/bookings');
  return <BookingsView />;
}
