import { type Metadata } from 'next';

import { SellerSecurityPage } from '@/components/account/AccountPages';

export const metadata: Metadata = { title: 'Sign-in & security' };

export default function AccountSecurityPage() {
  return <SellerSecurityPage />;
}
