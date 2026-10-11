import { type Metadata } from 'next';

import { StaffSecurityPage } from '@/components/account/AccountPages';

export const metadata: Metadata = { title: 'Sign-in & security' };

export default function AccountSecurityPage() {
  return <StaffSecurityPage />;
}
