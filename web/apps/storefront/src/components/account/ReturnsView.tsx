'use client';

import { isApiError, returnKeys, type ReturnSummary } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { Button, buttonClassName, QrCode, Skeleton, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { useState } from 'react';

import { type MessageKey, type Translator, useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { shopperQueries } from '@/lib/api/queries';

import { AccountCrumb } from './AccountCrumb';
import styles from './Pages.module.css';


export function nextStep(r: ReturnSummary, t: Translator<MessageKey>, day: (iso: string | null | undefined) => string): string {
  if (r.status === 'cancelled') return t('ret.nextCancelled');
  if (r.status === 'refunded') return t('ret.nextRefunded');
  if (r.method === 'DROP_OFF') return r.dropOffBy ? t('ret.nextDropOff', { date: day(r.dropOffBy) }) : t('ret.nextDropOffSoon');
  if (r.method === 'DRIVER_PICKUP') return t('ret.nextPickup');
  return r.status === 'requested' ? t('ret.nextReview') : t('ret.nextRefund');
}

function ReturnRow({ r }: { r: ReturnSummary }) {
  const fmt = useFormatters();
  const t = useT();
  const qc = useQueryClient();
  const toast = useToast();
  const [open, setOpen] = useState(false);
  const detail = useQuery({ ...shopperQueries.returnDetail(browserApi(), r.rmaId), enabled: open });
  const cancel = useMutation({
    mutationFn: () => browserApi().cancelReturn(r.rmaId),
    onSuccess: async () => { await qc.invalidateQueries({ queryKey: returnKeys.all }); toast.show(t('ret.cancelled')); },
    onError: (e) => toast.show(isApiError(e) ? e.message : t('auth.genericError'))
  });
  const q = detail.data?.quote;
  return (
    <section className={styles.card} data-testid="return" aria-label={r.title}>
      <div className={styles.cardHead}>
        <div className={styles.form} style={{ gap: 'var(--at-space-1)' }}>
          <span className={styles.status}>{r.status.replace(/_/g, ' ')}</span>
          <strong>{r.title}</strong>
          <span className={styles.muted}>
            {t('ret.meta', { number: r.number ?? r.rmaId, order: r.orderNumber })}
          </span>
          <span className={styles.muted}>{nextStep(r, t, (iso) => (iso ? fmt.date(iso, 'dayMonthShort') : ''))}</span>
        </div>
        <div className={styles.form} style={{ alignItems: 'flex-end', gap: 'var(--at-space-2)' }}>
          <strong>{t('ret.refund', { amount: fmt.money(r.refundMinor) })}</strong>
          <span className={styles.actions}>
            <button type="button" className="link-button" onClick={() => setOpen((o) => !o)} aria-expanded={open}>
              {open ? t('ret.hide') : t('ret.details')}
            </button>
            {r.cancellable && (
              <Button size="sm" variant="neutral" onClick={() => cancel.mutate()} loading={cancel.isPending}>
                {t('ret.cancel')}
              </Button>
            )}
          </span>
        </div>
      </div>
      {open && (
        <div className={styles.form}>
          {detail.isPending && <Skeleton height={60} />}
          {r.qrPayload && (
            <p className={styles.muted}>
              <QrCode aria-hidden /> {t('ret.qr')} <span className={styles.code}>{r.qrPayload}</span>
            </p>
          )}
          {r.labelUrl && (
            <a href={r.labelUrl} target="_blank" rel="noopener noreferrer">
              {t('ret.label')}
            </a>
          )}
          {r.handoverCode && (
            <p className={styles.muted}>
              {t('ret.handover')} <span className={styles.code}>{r.handoverCode}</span>
            </p>
          )}
          {detail.data?.lines.map((l) => (
            <p key={l.lineId} className={styles.muted}>
              {l.title} × {l.quantity} · {t(`ret.reason.${l.reason}` as Parameters<typeof t>[0])}
            </p>
          ))}
          {q && (
            <dl className={styles.dl}>
              <dt>{t('ret.items')}</dt>
              <dd>{fmt.money(q.itemsMinor)}</dd>
              <dt>{t('ret.tax')}</dt>
              <dd>{fmt.money(q.taxMinor)}</dd>
              {q.shippingMinor > 0 && (
                <>
                  <dt>{t('ret.shipping')}</dt>
                  <dd>{fmt.money(q.shippingMinor)}</dd>
                </>
              )}
              {q.restockingFeeMinor > 0 && (
                <>
                  <dt>{t('ret.restocking')}</dt>
                  <dd>−{fmt.money(q.restockingFeeMinor)}</dd>
                </>
              )}
              <dt>
                <strong>{t('ret.total')}</strong>
              </dt>
              <dd>
                <strong>{fmt.money(q.totalMinor)}</strong>
              </dd>
            </dl>
          )}
        </div>
      )}
    </section>
  );
}

/** Returns & refunds (S148): your returns with drop-off QR / label, refund estimate and cancel; delivered orders to return from. */
export function ReturnsView({ orderNumber }: { orderNumber?: string } = {}) {
  const t = useT();
  const api = browserApi();
  const returns = useQuery(shopperQueries.returns(api, orderNumber));
  const orders = useQuery(shopperQueries.orders(api));
  const delivered = (orders.data?.items ?? []).filter((o) => o.shipments.some((s) => s.deliveredAt));
  return (
    <div className={styles.page}>
      <AccountCrumb>{t('account.returns')}</AccountCrumb>
      <h1 className={styles.h1}>{t('account.returns')}</h1>
      {orderNumber && (
        <p className={styles.muted}>
          {t('ret.forOrder', { number: orderNumber })} <Link href="/account/returns">{t('ret.allReturns')}</Link>
        </p>
      )}
      {returns.isError && <p className={styles.error} role="alert">{t('acct.loadError')}</p>}
      {(returns.data?.length ?? 0) > 0 && (
        <>
          <h2 className={styles.h2}>{t('ret.yours')}</h2>
          {returns.data?.map((r) => (
            <ReturnRow key={r.rmaId} r={r} />
          ))}
        </>
      )}
      <h2 className={styles.h2}>{t('ret.returnOrReplace')}</h2>
      {orders.isPending && <Skeleton height={80} />}
      {orders.isSuccess && delivered.length === 0 && <p className={styles.muted}>{t('ret.nothingDelivered')}</p>}
      {delivered.map((o) => (
        <section key={o.orderNumber} className={styles.card} aria-label={o.orderNumber}>
          <div className={styles.cardHead}>
            <span>
              <strong>{t('ord.number', { number: o.orderNumber })}</strong>
              <br />
              <span className={styles.muted}>{o.shipments.map((s) => s.items).join(' · ')}</span>
            </span>
            <Link href={`/account/returns/new?order=${encodeURIComponent(o.orderNumber)}`} className={buttonClassName({ variant: 'neutral', size: 'sm' })}>
              {t('ret.chooseItems')}
            </Link>
          </div>
        </section>
      ))}
    </div>
  );
}
