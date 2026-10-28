import { type ReactNode } from 'react';

import { LogoMark } from '../brand/Logo';
import { cx } from '../cx';
import styles from './MaintenancePage.module.css';

export interface MaintenancePageProps {
  title: string;
  /** What is happening, as written by staff. */
  message: string;
  /** "Back around 3:30 PM" (already formatted for the reader's language); omitted when unknown. */
  expected?: string;
  /** Actions under the message (e.g. a "Check again" button, a support link). */
  children?: ReactNode;
  className?: string;
}

/** The page shown while the platform is in maintenance: calm, brand-coloured, says what and when. */
export function MaintenancePage({ title, message, expected, children, className }: MaintenancePageProps) {
  return (
    <main id="main" className={cx(styles.page, className)}>
      <div className={styles.card}>
        <LogoMark size={56} />
        <h1 className={styles.title}>{title}</h1>
        <p className={styles.message}>{message}</p>
        {expected ? <p className={styles.expected}>{expected}</p> : null}
        {children ? <div className={styles.actions}>{children}</div> : null}
      </div>
    </main>
  );
}
