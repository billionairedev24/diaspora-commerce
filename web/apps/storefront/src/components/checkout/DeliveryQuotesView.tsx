'use client';

import { deliveryQuoteQueries,type QuoteRequest } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { Badge, type BadgeTone, buttonClassName, EmptyState, FlatPage, Message, Skeleton, TextLinkButton } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import Link from 'next/link';

import { AccountHeader } from '@/components/account/AccountHeader';
import { type MessageKey, useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';

import styles from './DeliveryQuotes.module.css';

const TONES: Record<QuoteRequest['status'], BadgeTone> = { REQUESTED: 'neutral', IN_PROGRESS: 'brand', READY: 'success', EXPIRED: 'neutral', DECLINED: 'danger', USED: 'neutral' };

/**
 * "My quote requests" (listing rules D20): every delivery quote the shopper asked for with a plain status (Requested, Being
 * quoted, Quote ready, Expired, Declined), the agreed amount and until when it can be used. A ready quote links back to
 * checkout, where it is applied. Who agreed it and why are never shown.
 */
export function DeliveryQuotesView() {
  const t = useT();
  const fmt = useFormatters();
  const api = browserApi();
  const list = useQuery(deliveryQuoteQueries.mine(api));
  const items = list.data?.items ?? [];

  return (
    <FlatPage width="wide">
      <AccountHeader title={t('dq.title')} lead={t('dq.intro')} />
      {list.isPending && <Skeleton height={120} />}
      {list.isError && (
        <Message tone="error">
          {t('dq.loadError')} <TextLinkButton onClick={() => void list.refetch()}>{t('acct.retry')}</TextLinkButton>
        </Message>
      )}
      {list.isSuccess && items.length === 0 && (
        <EmptyState testId="quotes-empty" action={<Link href="/checkout">{t('common.goToCart')}</Link>}>
          {t('dq.none')}
        </EmptyState>
      )}
      {items.length > 0 && (
        <ul className={styles.list} data-testid="quote-list">
          {items.map((q) => (
            <li key={q.id} className={styles.card} data-testid="quote-card">
              <div className={styles.cardHead}>
                <h2 className={styles.cardTitle}>{t('dq.items', { n: q.lines.reduce((n, l) => n + l.quantity, 0) || q.packages.reduce((n, p) => n + p.itemCount, 0), place: q.place })}</h2>
                <Badge tone={TONES[q.status]}>{t(`dq.status.${q.status}` as MessageKey)}</Badge>
              </div>
              <span className={styles.meta}>{t('dq.reference', { ref: q.reference })} · {fmt.date(q.createdAt)}</span>
              {q.amountMinor !== null && (
                <p className={styles.amount} data-testid="quote-amount">
                  {t('dq.charge')}: {fmt.money(q.amountMinor, q.currency)}
                </p>
              )}
              {q.status === 'READY' && q.expiresAt && q.usable && <span className={styles.meta}>{t('dq.validUntil', { date: fmt.dateTime(q.expiresAt) })}</span>}
              <p className={styles.text}>{t(`dq.msg.${q.usable || q.status !== 'READY' ? q.status : 'EXPIRED'}` as MessageKey)}</p>
              {q.usable && (
                <div className={styles.actions}>
                  <Link href={`/checkout?quote=${encodeURIComponent(q.id)}`} className={buttonClassName()} data-testid="quote-use">
                    {t('dq.useIt')}
                  </Link>
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
    </FlatPage>
  );
}
