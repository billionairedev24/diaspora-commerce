'use client';

import { type HelpRequest } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { Button, buttonClassName, Skeleton } from '@afrotransact/ui';
import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import Link from 'next/link';
import { useState } from 'react';

import { type MessageKey, useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { shopperQueries } from '@/lib/api/queries';

import { AccountCrumb } from './AccountCrumb';
import help from './HelpRequests.module.css';
import styles from './Pages.module.css';

function RequestCard({ r }: { r: HelpRequest }) {
  const fmt = useFormatters();
  const t = useT();
  const [open, setOpen] = useState(false);
  const detailId = `hr-${r.id}`;
  const tone = r.status === 'RESOLVED' ? help.statusResolved : r.status === 'CLOSED' || r.status === 'WITHDRAWN' ? help.statusClosed : '';
  return (
    <li>
      <section className={styles.card} data-testid="help-request" aria-label={`${t(`hr.type.${r.type}` as MessageKey)} · ${r.reference}`}>
        <div className={styles.cardHead}>
          <div className={help.titleRow}>
            <span className={help.statusRow}>
              <span className={`${styles.status} ${tone}`}>{t(`hr.status.${r.status}` as MessageKey)}</span>
              <span className={help.reference}>{r.reference}</span>
            </span>
            <strong>{t(`hr.type.${r.type}` as MessageKey)}</strong>
            <span className={styles.muted}>{t('hr.meta', { order: r.orderNumber, date: fmt.date(r.createdAt, 'dayMonthShort') })}</span>
          </div>
          <button type="button" className="link-button" onClick={() => setOpen((o) => !o)} aria-expanded={open} aria-controls={detailId}>
            {open ? t('hr.hide') : t('hr.details')}
          </button>
        </div>
        <p className={styles.muted}>{t(`hr.next.${r.status}` as MessageKey)}</p>
        {r.resolution && (
          <div className={styles.form} style={{ gap: 'var(--at-space-2)' }}>
            <strong>
              {r.resolution.outcome === 'REFUNDED' && r.resolution.refundMinor !== null
                ? t('hr.outcome.REFUNDED', { amount: fmt.money(r.resolution.refundMinor, r.currency) })
                : t(`hr.outcome.${r.resolution.outcome}` as MessageKey)}
            </strong>
            {r.resolution.note && <p className={help.note}>{r.resolution.note}</p>}
            {r.resolution.outcome === 'REFUNDED' && <p className={styles.muted}>{t('hr.refundNote')}</p>}
          </div>
        )}
        {open && (
          <div id={detailId} className={styles.form}>
            <strong>{t('hr.yourWords')}</strong>
            <p className={help.description}>{r.description}</p>
            <p className={styles.muted}>{t('hr.photos', { n: r.evidenceCount })}</p>
          </div>
        )}
      </section>
    </li>
  );
}

/** Get help with an order: the customer's requests with their status in plain words, and the orders to ask about. */
export function HelpView() {
  const t = useT();
  const fmt = useFormatters();
  const api = browserApi();
  const requests = useInfiniteQuery(shopperQueries.helpRequests(api));
  const orders = useQuery(shopperQueries.orders(api));
  const delivered = (orders.data?.items ?? []).filter((o) => o.shipments.some((s) => s.deliveredAt));
  const items = requests.data?.pages.flatMap((page) => page.items) ?? [];
  return (
    <div className={styles.page}>
      <AccountCrumb>{t('hr.title')}</AccountCrumb>
      <h1 className={styles.h1}>{t('hr.title')}</h1>
      <p className={help.lead}>{t('hr.intro')}</p>
      <p className={help.policy}>{t('hr.policy')}</p>

      <h2 className={styles.h2}>{t('hr.yours')}</h2>
      {requests.isPending && <Skeleton height={96} />}
      {requests.isError && (
        <p className={styles.error} role="alert">
          {t('acct.loadError')}
        </p>
      )}
      {requests.isSuccess && items.length === 0 && <p className={styles.muted}>{t('hr.empty')}</p>}
      {items.length > 0 && (
        <ul className={help.requests} aria-label={t('hr.yours')}>
          {items.map((r) => (
            <RequestCard key={r.id} r={r} />
          ))}
        </ul>
      )}
      {requests.hasNextPage && (
        <div className={styles.actions}>
          <Button variant="neutral" size="sm" onClick={() => void requests.fetchNextPage()} loading={requests.isFetchingNextPage}>
            {t('hr.loadMore')}
          </Button>
        </div>
      )}

      <h2 className={styles.h2}>{t('hr.chooseOrder')}</h2>
      {orders.isPending && <Skeleton height={80} />}
      {orders.isSuccess && delivered.length === 0 && <p className={styles.muted}>{t('hr.noOrders')}</p>}
      <ul className={help.orderList}>
        {delivered.map((o) => (
          <li key={o.orderNumber}>
            <section className={styles.card} aria-label={t('ord.number', { number: o.orderNumber })}>
              <div className={styles.cardHead}>
                <span>
                  <strong>{t('ord.number', { number: o.orderNumber })}</strong>
                  <br />
                  <span className={styles.muted}>{o.shipments.map((s) => s.items).join(' · ')}</span>
                  {o.placedAt && (
                    <>
                      <br />
                      <span className={styles.muted}>{fmt.date(o.placedAt, 'dayMonthShort')}</span>
                    </>
                  )}
                </span>
                <Link href={`/account/help/new?order=${encodeURIComponent(o.orderNumber)}`} className={buttonClassName({ variant: 'neutral', size: 'sm' })}>
                  {t('hr.getHelp')}
                  <span className={help.srOnly}> {t('ord.number', { number: o.orderNumber })}</span>
                </Link>
              </div>
            </section>
          </li>
        ))}
      </ul>
    </div>
  );
}
