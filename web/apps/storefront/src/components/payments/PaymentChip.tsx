import { type PaymentMethod } from '@afrotransact/api-client';

import styles from './Payments.module.css';

const SHORT: Record<string, string> = { visa: 'VISA', mastercard: 'MC', amex: 'AMEX', discover: 'DISC' };

/** Brand chip for a saved method (card brand, BANK, CASH, LINK). */
export function PaymentChip({ method }: { method: PaymentMethod }) {
  const label = method.type === 'us_bank_account' ? 'BANK' : method.type === 'cashapp' ? 'CASH' : method.type === 'link' ? 'LINK' : (SHORT[method.brand ?? ''] ?? 'CARD');
  return (
    <span className={styles.chip} aria-hidden>
      {label}
    </span>
  );
}
