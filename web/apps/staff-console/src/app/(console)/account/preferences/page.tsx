import { type Metadata } from 'next';

import { StaffPreferencesPage } from '@/components/account/AccountPages';

export const metadata: Metadata = { title: 'Preferences' };

export default function AccountPreferencesPage() {
  return <StaffPreferencesPage />;
}
