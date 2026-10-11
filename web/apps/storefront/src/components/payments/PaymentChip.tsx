import { type PaymentMethod } from '@afrotransact/api-client';
import { type CardBrand, CardBrandMark } from '@afrotransact/ui';

import styles from './Payments.module.css';

const BRANDS: readonly CardBrand[] = ['visa', 'mastercard', 'amex', 'discover'];

/** Brand mark for a saved method: the card network's own mark, else a chip (BANK, CASH, LINK, CARD). */
export function PaymentChip({ method }: { method: PaymentMethod }) {
  const brand = (method.brand ?? '').toLowerCase() as CardBrand;
  if (method.type !== 'us_bank_account' && method.type !== 'cashapp' && method.type !== 'link' && BRANDS.includes(brand)) {
    return (
      <span aria-hidden className={styles.mark}>
        <CardBrandMark brand={brand} height={26} />
      </span>
    );
  }
  const label = method.type === 'us_bank_account' ? 'BANK' : method.type === 'cashapp' ? 'CASH' : method.type === 'link' ? 'LINK' : 'CARD';
  return (
    <span className={styles.chip} aria-hidden>
      {label}
    </span>
  );
}
