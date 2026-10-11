import { type Metadata } from 'next';

import { StaffNotificationsPage } from '@/components/account/AccountPages';

export const metadata: Metadata = { title: 'Notifications' };

export default function AccountNotificationsPage() {
  return <StaffNotificationsPage />;
}
