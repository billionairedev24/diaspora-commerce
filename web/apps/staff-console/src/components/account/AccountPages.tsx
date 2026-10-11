'use client';

import { AccountShell, AlertsScreen, PreferencesScreen, ProfileScreen, SecurityScreen } from '@afrotransact/account';
import { useRouter } from 'next/navigation';

/** My account › Profile: details and platform roles (read-only). */
export function StaffProfilePage() {
  return (
    <AccountShell active="profile">
      <ProfileScreen heading={false} />
    </AccountShell>
  );
}

/** Sign-in & security: two-step verification is mandatory for staff (methods can be switched, never all turned off). */
export function StaffSecurityPage() {
  return (
    <AccountShell active="security">
      <SecurityScreen audience="staff" heading={false} />
    </AccountShell>
  );
}

export function StaffNotificationsPage() {
  return (
    <AccountShell active="notifications">
      <AlertsScreen heading={false} />
    </AccountShell>
  );
}

export function StaffPreferencesPage() {
  const router = useRouter();
  return (
    <AccountShell active="preferences">
      <PreferencesScreen heading={false} showDensity onLanguageChanged={() => router.refresh()} />
    </AccountShell>
  );
}
