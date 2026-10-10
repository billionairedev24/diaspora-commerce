import { cx } from '../cx';
import styles from './commerce.module.css';

export interface PriceProps {
  /** Amount in minor units (cents). */
  amountMinor: number;
  listPriceMinor?: number | null;
  size?: 'sm' | 'md' | 'lg' | 'xl';
  /** Show "-12%" before the price (product page). */
  showDeal?: boolean;
  currencySymbol?: string;
  className?: string;
  listLabel?: string;
}

const fmt = (minor: number, sym: string) => `${sym}${(minor / 100).toFixed(2)}`;

/** Storefront price: superscript currency and cents, optional strikethrough list price and deal percentage. */
export function Price({ amountMinor, listPriceMinor, size = 'md', showDeal, currencySymbol = '$', className, listLabel = 'List:' }: PriceProps) {
  const whole = Math.floor(Math.abs(amountMinor) / 100).toLocaleString('en-US');
  const cents = String(Math.abs(amountMinor) % 100).padStart(2, '0');
  const hasList = !!listPriceMinor && listPriceMinor > amountMinor;
  const pct = hasList && listPriceMinor ? Math.round((1 - amountMinor / listPriceMinor) * 100) : 0;
  return (
    <span className={cx(styles.price, styles[`p-${size}`], className)}>
      {showDeal && hasList && <span className={styles.deal}>-{pct}%</span>}
      <span className={styles.amount} aria-hidden>
        <span className={styles.sup}>{currencySymbol}</span>
        <span className={styles.whole}>{whole}</span>
        <span className={styles.sup}>{cents}</span>
      </span>
      <span className="at-visually-hidden">{fmt(amountMinor, currencySymbol)}</span>
      {hasList && listPriceMinor && !showDeal && (
        <span className={styles.list}>
          {listLabel} <s>{fmt(listPriceMinor, currencySymbol)}</s>
        </span>
      )}
    </span>
  );
}
