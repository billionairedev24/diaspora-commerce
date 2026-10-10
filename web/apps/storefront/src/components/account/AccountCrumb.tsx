import Link from 'next/link';
import { type ReactNode } from 'react';

import { useT } from '@/i18n/t';

import styles from './Pages.module.css';

/** "Your Account › Section" breadcrumb (as in the design). */
export function AccountCrumb({ children }: { children?: ReactNode }) {
  const t = useT();
  return (
    <nav className={styles.crumb} aria-label={t('acct.breadcrumb')}>
      <Link href="/account">{t('account.yourAccount')}</Link>
      {children && (
        <>
          <span aria-hidden>›</span>
          <span aria-current="page">{children}</span>
        </>
      )}
    </nav>
  );
}
