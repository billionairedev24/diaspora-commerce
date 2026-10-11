import { type Metadata } from 'next';

import { SellerPreferencesPage } from '@/components/account/AccountPages';

export const metadata: Metadata = { title: 'Preferences' };

export default function AccountPreferencesPage() {
  return <SellerPreferencesPage />;
}
