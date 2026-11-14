import { type Metadata } from 'next';

import { NotificationsSettingsView } from '@/components/settings/PersonalSettingsViews';

export const metadata: Metadata = { title: 'Notifications' };

export default function Page() {
  return <NotificationsSettingsView />;
}
