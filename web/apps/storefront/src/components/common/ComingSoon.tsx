import { buttonClassName } from '@afrotransact/ui';
import Link from 'next/link';

import { type MessageKey, t } from '@/i18n/t';

import styles from './Content.module.css';

/** Placeholder for routes delivered in the next phase (account, orders, checkout, services, help, legal). */
export function ComingSoon({ page }: { page: MessageKey }) {
  return (
    <section className={styles.soon} aria-labelledby="soon-title">
      <span className={styles.kicker}>{t(page)}</span>
      <h1 id="soon-title" className={styles.soonTitle}>
        {t('soon.title')}
      </h1>
      <p className={styles.lead}>{t('soon.body', { page: t(page) })}</p>
      <div className={styles.actions}>
        <Link href="/" className={buttonClassName()}>
          {t('soon.home')}
        </Link>
        <Link href="/cart" className={buttonClassName({ variant: 'neutral' })}>
          {t('soon.cart')}
        </Link>
      </div>
    </section>
  );
}
