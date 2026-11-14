'use client';

import { AlertsScreen, PreferencesScreen } from '@afrotransact/account';
import { useRouter } from 'next/navigation';

import { SettingsPage } from './SettingsShell';

/** Settings > Notifications: this member's alerts (personal: it never changes what teammates get). */
export function NotificationsSettingsView() {
  return (
    <SettingsPage section="notifications">
      <AlertsScreen heading={false} />
    </SettingsPage>
  );
}

/** Settings > Language & number format: language, currency and date display, and table density. */
export function LanguageSettingsView() {
  const router = useRouter();
  return (
    <SettingsPage section="language">
      <PreferencesScreen heading={false} showDensity onLanguageChanged={() => router.refresh()} />
    </SettingsPage>
  );
}
