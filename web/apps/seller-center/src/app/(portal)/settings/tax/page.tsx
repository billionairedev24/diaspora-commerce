import { type Metadata } from 'next';

import { TaxSettingsView } from '@/components/settings/MoneySettingsViews';

export const metadata: Metadata = { title: 'Tax information' };

export default function Page() {
  return <TaxSettingsView />;
}
