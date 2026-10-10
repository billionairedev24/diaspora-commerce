import { cx } from '../cx';
import styles from './commerce.module.css';

export interface PriceProps {
  /** Amount in minor units (cents). */
  amountMinor: number;
  listPriceMinor?: number | null;
  size?: 'sm' | 'md' | 'lg' | 'xl';
  /** Show "-12%" before the price (product page). */
  showDeal?: boolean;
  /** Overrides the currency symbol (defaults to the symbol Intl uses for `currency` in `locale`). */
  currencySymbol?: string;
  /** ISO 4217 code (default USD). */
  currency?: string;
  /** BCP 47 tag of the active language (default en-US); grouping, decimal mark and symbol position follow it. */
  locale?: string;
  className?: string;
  listLabel?: string;
}

function parts(minor: number, locale: string, currency: string) {
  const ps = new Intl.NumberFormat(locale, { style: 'currency', currency }).formatToParts(Math.abs(minor) / 100);
  const symbolIndex = ps.findIndex((x) => x.type === 'currency');
  const integerIndex = ps.findIndex((x) => x.type === 'integer');
  return {
    symbol: ps.find((x) => x.type === 'currency')?.value ?? '',
    whole: ps.filter((x) => x.type === 'integer' || x.type === 'group').map((x) => x.value).join(''),
    cents: ps.find((x) => x.type === 'fraction')?.value ?? '00',
    symbolAfter: symbolIndex > integerIndex
  };
}

const full = (minor: number, locale: string, currency: string) => new Intl.NumberFormat(locale, { style: 'currency', currency }).format(minor / 100);

/** Storefront price: superscript currency and cents, optional strikethrough list price and deal percentage. */
export function Price({ amountMinor, listPriceMinor, size = 'md', showDeal, currencySymbol, currency = 'USD', locale = 'en-US', className, listLabel = 'List:' }: PriceProps) {
  const p = parts(amountMinor, locale, currency);
  const symbol = currencySymbol ?? p.symbol;
  const { whole, cents } = p;
  const hasList = !!listPriceMinor && listPriceMinor > amountMinor;
  const pct = hasList && listPriceMinor ? Math.round((1 - amountMinor / listPriceMinor) * 100) : 0;
  return (
    <span className={cx(styles.price, styles[`p-${size}`], className)}>
      {showDeal && hasList && <span className={styles.deal}>-{pct}%</span>}
      <span className={styles.amount} aria-hidden>
        {p.symbolAfter ? null : <span className={styles.sup}>{symbol}</span>}
        <span className={styles.whole}>{whole}</span>
        <span className={styles.sup}>{cents}</span>
        {p.symbolAfter ? <span className={styles.sup}>{` ${symbol}`}</span> : null}
      </span>
      <span className="at-visually-hidden">{currencySymbol ? `${currencySymbol}${(amountMinor / 100).toFixed(2)}` : full(amountMinor, locale, currency)}</span>
      {hasList && listPriceMinor && !showDeal && (
        <span className={styles.list}>
          {listLabel} <s>{currencySymbol ? `${currencySymbol}${(listPriceMinor / 100).toFixed(2)}` : full(listPriceMinor, locale, currency)}</s>
        </span>
      )}
    </span>
  );
}
