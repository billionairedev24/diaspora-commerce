'use client';

import { type CheckoutBlockedLine } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { Button, buttonClassName } from '@afrotransact/ui';
import Link from 'next/link';

import { useT } from '@/i18n/t';

import styles from './Checkout.module.css';

type Props = {
  problem: { kind: 'blocked'; lines: CheckoutBlockedLine[] } | { kind: 'incomplete' } | { kind: 'retry' };
  /** Takes a blocked line out of the order. Without it the blocked items are only named. */
  onRemoveLine?: (line: CheckoutBlockedLine) => void;
  /** Opens the address editor (checkout). Without it the row links to the address book (cart). */
  onEditAddress?: () => void;
  onRetry?: () => void;
  busy?: boolean;
};

/**
 * The tax row when there is no estimate for a reason the shopper can act on, worded from the BFF's own
 * `taxUnavailableReason` and shared by the cart and the checkout: items we can't sell yet (Remove), an address without a
 * state or ZIP code (Edit address) or an estimate that could not be worked out right now (Retry).
 */
export function TaxProblem({ problem, onRemoveLine, onEditAddress, onRetry, busy = false }: Props) {
  const t = useT();
  const fmt = useFormatters();

  if (problem.kind === 'blocked') {
    const titles = problem.lines.map((l) => l.title);
    return (
      <div className={styles.taxState} data-testid="tax-zip-blocked">
        <p className={styles.taxStateText} role="alert">
          {titles.length ? t('co.taxBlockedNamed', { names: fmt.list(titles), n: titles.length }) : t('co.taxBlocked')}
        </p>
        {onRemoveLine &&
          problem.lines.map((l) => (
            <Button key={l.lineId} size="sm" variant="secondary" onClick={() => onRemoveLine(l)} aria-label={t('co.blocked.removeAria', { title: l.title })} data-testid="tax-zip-blocked-remove">
              {t('co.blocked.remove')}
            </Button>
          ))}
      </div>
    );
  }

  if (problem.kind === 'incomplete') {
    return (
      <div className={styles.taxState} data-testid="tax-zip-incomplete">
        <p className={styles.taxStateText}>{t('co.taxAddress.incomplete')}</p>
        {onEditAddress ? (
          <Button size="sm" variant="secondary" onClick={onEditAddress}>
            {t('co.taxAddress.edit')}
          </Button>
        ) : (
          <Link href="/account/addresses" className={buttonClassName({ size: 'sm', variant: 'secondary' })}>
            {t('co.taxAddress.edit')}
          </Link>
        )}
      </div>
    );
  }

  return (
    <div className={styles.taxState} data-testid="tax-zip-retry">
      <p className={styles.taxStateText} role="alert">
        {t('co.taxRetry.message')}
      </p>
      {onRetry && (
        <Button size="sm" variant="secondary" loading={busy} onClick={onRetry} data-testid="tax-zip-retry-button">
          {t('co.taxRetry.button')}
        </Button>
      )}
    </div>
  );
}
