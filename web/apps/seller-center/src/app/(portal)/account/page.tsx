import { type Metadata } from 'next';

import { SellerProfilePage } from '@/components/account/AccountPages';

export const metadata: Metadata = { title: 'My account' };

export default function AccountPage() {
  return <SellerProfilePage />;
}
