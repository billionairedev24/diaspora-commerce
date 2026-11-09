'use client';

import { type CheckoutBlockedLine } from '@afrotransact/api-client';
import { Button, Warning } from '@afrotransact/ui';

import { useT } from '@/i18n/t';

import styles from './Checkout.module.css';

/**
 * Items that can't be sold yet (no definite tax decision): said in plain words with the item's name, no ids and no tax
 * terms. Checkout can't be placed while one remains; the shopper removes it from this order (it stays in the cart).
 * The wording is built here from the title so it follows the shopper's language; the server's own message is English.
 */
export function BlockedLines({ lines, removing, onRemove }: { lines: CheckoutBlockedLine[]; removing: string | null; onRemove: (line: CheckoutBlockedLine) => void }) {
  const t = useT();
  if (!lines.length) return null;
  return (
    <section className={styles.localProblem} role="alert" aria-labelledby="co-blocked-title" data-testid="checkout-blocked">
      <strong id="co-blocked-title">
        <Warning weight="fill" aria-hidden /> {t('co.blocked.title')}
      </strong>
      <ul className={styles.blockedList}>
        {lines.map((l) => (
          <li key={l.lineId} className={styles.blockedItem} data-testid="checkout-blocked-line">
            <span className={styles.blockedText}>
              <strong>{t('co.blocked.item', { title: l.title })}</strong>
              <span className={styles.muted}>{t('co.blocked.next')}</span>
            </span>
            <Button size="sm" variant="secondary" loading={removing === l.lineId} onClick={() => onRemove(l)} aria-label={t('co.blocked.removeAria', { title: l.title })}>
              {t('co.blocked.remove')}
            </Button>
          </li>
        ))}
      </ul>
    </section>
  );
}
