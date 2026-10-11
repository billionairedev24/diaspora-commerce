import { type Metadata } from 'next';

import { StaffProfilePage } from '@/components/account/AccountPages';

export const metadata: Metadata = { title: 'My account' };

export default function AccountPage() {
  return <StaffProfilePage />;
}
