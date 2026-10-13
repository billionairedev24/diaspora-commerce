import { type ReactNode } from 'react';

import { cx } from '../cx';
import { CARD_BRAND_LABEL, cardBrandOf, PaymentMethodMark } from './PaymentBrandMarks';
import styles from './SavedPaymentCard.module.css';

export interface SavedPaymentCardLabels {
  /** "Default" tag. */
  defaultTag: string;
  /** "Expires {date}" with {date} replaced, e.g. "Expires 04/27". */
  expires: (date: string) => string;
  /** Shown when a card has expired. */
  expired?: string;
}

export interface SavedPaymentCardProps {
  /** Stripe's payment method type: card, us_bank_account, cashapp, link, amazon_pay (or bank). */
  type: string;
  /** The card network as Stripe reports it (visa, mastercard, amex …). */
  brand?: string | null;
  last4?: string | null;
  /** MM/YY. */
  expires?: string | null;
  /** The name to show (cardholder, bank name, "Cash App Pay" …); defaults to the network or method name. */
  title?: ReactNode;
  /** A second line under the number (account type, "Receives payouts" …). */
  detail?: ReactNode;
  isDefault?: boolean;
  /** Highlighted as the chosen method (checkout, plan payment). */
  selected?: boolean;
  /** Badges and status under the card (verified, pending …). */
  status?: ReactNode;
  /** Buttons under the card (Make default, Remove). */
  actions?: ReactNode;
  labels: SavedPaymentCardLabels;
  className?: string;
  testId?: string;
}

const KIND_NAME: Record<string, string> = { us_bank_account: 'Bank account', bank: 'Bank account', cashapp: 'Cash App Pay', link: 'Link', amazon_pay: 'Amazon Pay' };

/** "MM/YY" is in the past (cards expire at the end of the month). */
export function cardExpired(expires: string | null | undefined, now: Date = new Date()): boolean {
  const m = /^(\d{1,2})\/(\d{2,4})$/.exec(expires ?? '');
  if (!m) return false;
  const year = Number(m[2]) < 100 ? 2000 + Number(m[2]) : Number(m[2]);
  return new Date(year, Number(m[1]), 1) <= now;
}

/**
 * A saved payment method drawn as a wallet card: the network's own mark (Visa, Mastercard, Amex, Discover, JCB,
 * Diners, UnionPay) or the bank / wallet mark, the masked number and the expiry. Used everywhere a customer, seller or
 * provider sees saved methods, so cards look the same in the storefront, checkout and Seller Center billing.
 */
export function SavedPaymentCard({ type, brand, last4, expires, title, detail, isDefault, selected, status, actions, labels, className, testId }: SavedPaymentCardProps) {
  const t = type.toLowerCase();
  const isCard = !KIND_NAME[t];
  const network = isCard ? cardBrandOf(brand) : null;
  const name = title ?? (isCard ? (network ? CARD_BRAND_LABEL[network] : 'Card') : KIND_NAME[t]);
  const expired = isCard && cardExpired(expires);
  return (
    <div className={cx(styles.wrap, className)} data-testid={testId}>
      <div className={cx(styles.card, selected && styles.selected, isDefault && styles.isDefault)} data-kind={isCard ? 'card' : t}>
        <div className={styles.top}>
          {isCard ? <span className={styles.chip} aria-hidden /> : <span className={styles.name}>{name}</span>}
          <PaymentMethodMark type={t} brand={brand} height={28} className={styles.mark} />
        </div>
        {last4 ? (
          <div className={styles.number} aria-label={`${typeof name === 'string' ? name : ''} ending in ${last4}`}>
            <span aria-hidden>•••• </span>
            {isCard ? <span aria-hidden className={styles.groups}>•••• •••• </span> : null}
            <span>{last4}</span>
          </div>
        ) : <div className={styles.number} />}
        <div className={styles.bottom}>
          <span className={styles.holder}>
            {isCard ? <span className={styles.name}>{name}</span> : null}
            {detail ? <span className={styles.detail}>{detail}</span> : null}
          </span>
          {expires ? <span className={cx(styles.exp, expired && styles.expired)}>{expired && labels.expired ? labels.expired : labels.expires(expires)}</span> : null}
        </div>
        {isDefault ? <span className={styles.defaultTag}>{labels.defaultTag}</span> : null}
      </div>
      {status ? <div className={styles.status}>{status}</div> : null}
      {actions ? <div className={styles.actions}>{actions}</div> : null}
    </div>
  );
}
