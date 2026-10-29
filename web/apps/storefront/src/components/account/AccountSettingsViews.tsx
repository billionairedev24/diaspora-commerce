'use client';

import { CustomerNotificationsScreen, flatAccountClassName, ProfileScreen, SecurityScreen, useAccountT } from '@afrotransact/account';
import { legalHref } from '@afrotransact/api-client';
import { FlatPage } from '@afrotransact/ui';
import { type ReactNode } from 'react';

import { AccountEnv } from './AccountEnv';
import { AccountHeader } from './AccountHeader';

/** "Your account › Section", the page title and the shared settings screens in their flat (no boxed cards) variant. */
export function SettingsPage({ title, children }: { title: string; children: ReactNode }) {
  return (
    <FlatPage>
      <AccountHeader title={title} />
      <div className={flatAccountClassName}>{children}</div>
    </FlatPage>
  );
}

function SecurityContent() {
  const t = useAccountT();
  return (
    <SettingsPage title={t('hub.securityTitle')}>
      <SecurityScreen audience="customer" heading={false} />
    </SettingsPage>
  );
}

function ProfileContent() {
  const t = useAccountT();
  return (
    <SettingsPage title={t('hub.profile')}>
      <ProfileScreen heading={false} />
    </SettingsPage>
  );
}

function NotificationsContent() {
  const t = useAccountT();
  return (
    <SettingsPage title={t('hub.notifications')}>
      <CustomerNotificationsScreen heading={false} links={{ privacyChoices: legalHref('privacy-choices'), smsTerms: legalHref('sms-terms'), privacy: legalHref('privacy') }} />
    </SettingsPage>
  );
}

/** Login & security: password, two-step verification with the authenticator QR, passkeys, trusted browsers, sessions, activity, linked accounts. */
export function SecurityView() {
  return (
    <AccountEnv>
      <SecurityContent />
    </AccountEnv>
  );
}

/** Your profile: name and mobile number (with SMS verification); the sign-in email is read-only. */
export function ProfileView() {
  return (
    <AccountEnv>
      <ProfileContent />
    </AccountEnv>
  );
}

/** Notifications & privacy: delivery channels and marketing consent, linked to Your Privacy Choices. */
export function NotificationsView() {
  return (
    <AccountEnv>
      <NotificationsContent />
    </AccountEnv>
  );
}
