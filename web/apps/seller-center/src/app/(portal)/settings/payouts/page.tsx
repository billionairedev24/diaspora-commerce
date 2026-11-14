import { type Metadata } from 'next';

import { PayoutsSettingsView } from '@/components/settings/MoneySettingsViews';

export const metadata: Metadata = { title: 'Payout settings' };

export default function Page() {
  return <PayoutsSettingsView />;
}
