'use client';

import { BellSimple, IdentificationCard, ShieldCheck, SlidersHorizontal } from '@phosphor-icons/react';
import { type ReactNode } from 'react';

import { useAccountEnv } from './context';
import { useAccountT } from './i18n/t';
import { styles } from './parts';

export type AccountSection = 'profile' | 'security' | 'notifications' | 'preferences';

/** In-app routes of the account pages (the same in Seller Center and the staff console). */
export const ACCOUNT_ROUTES: Record<AccountSection, string> = {
  profile: '/account',
  security: '/account/security',
  notifications: '/account/notifications',
  preferences: '/account/preferences'
};

/** Which account section a pathname belongs to (null outside /account). */
export function accountSectionOf(pathname: string): AccountSection | null {
  const m = /^\/account(?:\/([a-z-]+))?\/?$/.exec(pathname);
  if (!m) return null;
  const seg = m[1];
  if (!seg) return 'profile';
  return seg === 'security' || seg === 'notifications' || seg === 'preferences' ? seg : null;
}

/** Back-office "My account": heading, the sections as tabs (like the module pages) and the section. */
export function AccountShell({ active, children }: { active: AccountSection; children: ReactNode }) {
  const t = useAccountT();
  const { Link } = useAccountEnv();
  const items: Array<{ key: AccountSection; label: string; icon: ReactNode }> = [
    { key: 'profile', label: t('nav.profile'), icon: <IdentificationCard aria-hidden /> },
    { key: 'security', label: t('nav.security'), icon: <ShieldCheck aria-hidden /> },
    { key: 'notifications', label: t('nav.notifications'), icon: <BellSimple aria-hidden /> },
    { key: 'preferences', label: t('nav.preferences'), icon: <SlidersHorizontal aria-hidden /> }
  ];
  return (
    <div className={styles.shell}>
      <header className={styles.shellHead}>
        <h1 className={styles.h1}>{t('nav.title')}</h1>
        <p className={styles.lead}>{t('nav.subtitle')}</p>
      </header>
      <nav aria-label={t('nav.label')} className={styles.nav}>
        <p className={styles.navTitle}>{t('nav.label')}</p>
        {items.map((i) => (
          <Link key={i.key} href={ACCOUNT_ROUTES[i.key]} className={styles.navLink} aria-current={i.key === active ? 'page' : undefined}>
            {i.icon}
            {i.label}
          </Link>
        ))}
      </nav>
      <div className={styles.section}>{children}</div>
    </div>
  );
}
