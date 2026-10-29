'use client';

import { CardBrandRow } from '@afrotransact/ui';

import { useT } from '@/i18n/t';

import styles from './Payments.module.css';

/** Card brands accepted alongside Stripe's Payment Element (wallets and ACH live in the element). */
export function AcceptedPaymentsHint() {
  const t = useT();
  return (
    <div className={styles.accepted}>
      <CardBrandRow height={16} />
      <span className={styles.acceptedWallets}>{t('pay.acceptedWallets')}</span>
    </div>
  );
}
