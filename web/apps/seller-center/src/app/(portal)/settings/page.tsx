import { type Metadata } from 'next';

import { SettingsLanding } from '@/components/settings/SettingsLanding';

export const metadata: Metadata = { title: 'Settings' };

export default function Page() {
  return <SettingsLanding />;
}
