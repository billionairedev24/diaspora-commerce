import { Warning } from '@afrotransact/ui';

import { useT } from '@/i18n/t';

import styles from './Payments.module.css';

/** Shown above the stand-in form when no Stripe key is configured (local only: the BFF refuses to start without one
 *  in dev, uat and prod). */
export function StripeDevNotice() {
  const t = useT();
  return (
    <div className={styles.devNotice} role="note" data-testid="stripe-dev-notice">
      <Warning weight="fill" aria-hidden />
      <span>
        <strong>{t('pay.devNotice.title')}</strong>
        <br />
        {t('pay.devNotice.body')}
      </span>
    </div>
  );
}
