import { type PaymentMethod } from '@afrotransact/api-client';
import { PaymentMethodMark } from '@afrotransact/ui';

import styles from './Payments.module.css';

/** Brand mark for a saved method: the card network's own mark (Visa, Mastercard, Amex …), else the wallet or bank mark. */
export function PaymentChip({ method }: { method: PaymentMethod }) {
  return (
    <span aria-hidden className={styles.mark}>
      <PaymentMethodMark type={method.type} brand={method.brand} height={26} />
    </span>
  );
}
