import { type Metadata } from 'next';

import { SellerNotificationsPage } from '@/components/account/AccountPages';

export const metadata: Metadata = { title: 'Notifications' };

export default function AccountNotificationsPage() {
  return <SellerNotificationsPage />;
}
