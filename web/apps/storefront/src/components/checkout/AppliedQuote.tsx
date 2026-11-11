'use client';

import { type CheckoutPackage, deliveryQuoteQueries,type QuoteRequest } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { useQuery } from '@tanstack/react-query';
import Link from 'next/link';
import { useSearchParams } from 'next/navigation';

import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';

import styles from './DeliveryQuotes.module.css';

/** What checkout knows about the quote the shopper came back with (the pay link in the email: /checkout?quote=<id>). */
export interface AppliedQuote {
  /** The quote in the link, once loaded (any status: a used or expired one is explained). */
  quote: QuoteRequest | null;
  /** The quote can be charged now: ready, not expired, not used. Null otherwise. */
  usable: QuoteRequest | null;
  loading: boolean;
  /** The link names a quote that isn't the shopper's or doesn't exist (the BFF answers 404 for both). */
  missing: boolean;
}

/** Reads ?quote=<id> and loads the shopper's own quote (404 for anyone else's: it is then shown as void). */
export function useAppliedQuote(): AppliedQuote {
  const id = useSearchParams().get('quote');
  const api = browserApi();
  const q = useQuery({ ...deliveryQuoteQueries.one(api, id ?? ''), enabled: !!id });
  const quote = q.data ?? null;
  return { quote, usable: quote?.usable ? quote : null, loading: !!id && q.isPending, missing: !!id && q.isError };
}

/**
 * Packages the quote is charged on: delivered ones (never pickup, never a pre-order window's package). orders puts the
 * amount on the order's first delivered shipment once, whatever the number of packages.
 */
export const quotedPackage = (p: CheckoutPackage, option: string | undefined): boolean => !p.preorder && option !== 'pickup';

/** The shipping of the order with the quote applied: the agreed amount replaces the fees of the delivered packages. */
export function quotedShipping(packages: CheckoutPackage[], optionOf: (p: CheckoutPackage) => string | undefined, feeOf: (p: CheckoutPackage) => number, amountMinor: number): number {
  const other = packages.filter((p) => !quotedPackage(p, optionOf(p))).reduce((sum, p) => sum + feeOf(p), 0);
  return other + amountMinor;
}

/** The notice above the delivery step: the quote applied (amount, until when), or why it can't be used. */
export function AppliedQuoteNotice({ applied }: { applied: AppliedQuote }) {
  const t = useT();
  const fmt = useFormatters();
  const id = useSearchParams().get('quote');
  if (!id || applied.loading) return null;
  const q = applied.quote;
  if (applied.usable && q?.amountMinor != null) {
    return (
      <div className={styles.notice} role="status" data-testid="quote-applied">
        <p className={styles.noticeTitle}>{t('dq.applied', { amount: fmt.money(q.amountMinor, q.currency) })}</p>
        <p className={styles.text}>{t('dq.appliedHint', { date: fmt.dateTime(q.expiresAt ?? '') })}</p>
      </div>
    );
  }
  const reason = !q || applied.missing ? 'dq.void' : q.status === 'USED' ? 'dq.void.used' : q.status === 'READY' || q.status === 'EXPIRED' ? 'dq.void.expired' : q.status === 'DECLINED' ? 'dq.msg.DECLINED' : 'dq.void.notReady';
  return (
    <div className={`${styles.notice} ${styles.noticeVoid}`} role="alert" data-testid="quote-void">
      <p className={styles.noticeTitle}>{t('dq.void')}</p>
      <p className={styles.text}>{t(reason)}</p>
      <Link href="/account/delivery-quotes">{t('dq.void.ask')}</Link>
    </div>
  );
}
