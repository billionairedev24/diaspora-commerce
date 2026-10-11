'use client';

import { CustomerNotificationsScreen, ProfileScreen, SecurityScreen, useAccountT } from '@afrotransact/account';
import { legalHref } from '@afrotransact/api-client';
import Link from 'next/link';
import { type ReactNode } from 'react';

import { AccountEnv } from './AccountEnv';
import styles from './Pages.module.css';

/** "Your Account › Section" breadcrumb and the section heading (Shop design). */
function Section({ title, children }: { title: string; children: ReactNode }) {
  const t = useAccountT();
  return (
    <div className={styles.accountPage}>
      <nav className={styles.crumb} aria-label={t('hub.crumb')}>
        <Link href="/account">{t('hub.title')}</Link>
        <span aria-hidden>›</span>
        <span aria-current="page" className={styles.crumbCurrent}>
          {title}
        </span>
      </nav>
      <h1 className={styles.h1}>{title}</h1>
      {children}
    </div>
  );
}

function SecurityContent() {
  const t = useAccountT();
  return (
    <Section title={t('hub.securityTitle')}>
      <SecurityScreen audience="customer" heading={false} />
    </Section>
  );
}

function ProfileContent() {
  const t = useAccountT();
  return (
    <Section title={t('hub.profile')}>
      <ProfileScreen heading={false} />
    </Section>
  );
}

function NotificationsContent() {
  const t = useAccountT();
  return (
    <Section title={t('hub.notifications')}>
      <CustomerNotificationsScreen heading={false} links={{ privacyChoices: legalHref('privacy-choices'), smsTerms: legalHref('sms-terms'), privacy: legalHref('privacy') }} />
    </Section>
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
