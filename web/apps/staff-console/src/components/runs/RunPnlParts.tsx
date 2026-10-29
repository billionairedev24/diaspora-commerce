'use client';

import { type DeliveryKind, type PlatformFees, type RunActuals } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { cx } from '@afrotransact/ui';

import { useT } from '@/i18n/t';

import { marginClass } from './shared';

/**
 * "Platform fees on goods" (S265): what the platform keeps on the goods of the orders (seller commission and fees), as
 * its own line. It is never part of the delivery profit; staff weigh it beside the delivery margin to decide fees and
 * minimums. Where the figure comes from is always said: reported by payments, estimated at the configured fee per
 * order, typed in, or not known.
 */
export function PlatformFeesLine({ fees, currency, testId = 'platform-fees' }: { fees: PlatformFees | null | undefined; currency: string; testId?: string }) {
  const t = useT();
  const fmt = useFormatters();
  if (!fees) return null;
  const estimate = fmt.money(fees.perOrderEstimateMinor, currency);
  const source = (() => {
    switch (fees.source) {
      case 'PAYMENTS':
        return t('st.runs.platform.source.PAYMENTS', { orders: fees.orders });
      case 'MIXED':
        return fees.perOrderEstimateMinor > 0
          ? t('st.runs.platform.source.MIXED', { reported: fees.ordersReported, orders: fees.orders, fee: estimate })
          : t('st.runs.platform.source.MIXED.partial', { reported: fees.ordersReported, orders: fees.orders });
      case 'ESTIMATE':
        return t('st.runs.platform.source.ESTIMATE', { orders: fees.orders, fee: estimate });
      case 'MANUAL':
        return t('st.runs.platform.source.MANUAL');
      default:
        return t('st.runs.platform.source.NONE');
    }
  })();
  return (
    <section aria-labelledby={`${testId}-title`} className="flex flex-col gap-1 rounded-md border border-border bg-ink-25 p-3" data-testid={testId}>
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h4 id={`${testId}-title`} className="m-0 text-sm font-bold">
          {t('st.runs.platform.title')}
        </h4>
        <strong className="text-lg font-black" data-testid={`${testId}-amount`}>
          {fees.commissionMinor === null ? t('st.runs.platform.none') : fmt.money(fees.commissionMinor, currency)}
        </strong>
      </div>
      <p className="m-0 text-xs text-muted" data-testid={`${testId}-source`}>
        {source}
      </p>
      <p className="m-0 text-xs text-muted">{t('st.runs.platform.help')}</p>
    </section>
  );
}

/** How many orders the run carried, delivered, lost, had given back and refunded. */
export function OrdersSummary({ a, phase }: { a: RunActuals; phase: 'BEFORE' | 'AFTER' }) {
  const t = useT();
  const fmt = useFormatters();
  const rows: { key: string; label: string; n: number }[] = [
    { key: 'delivered', label: t(phase === 'AFTER' ? 'st.runs.runs.pnl.orders.delivered' : 'st.runs.runs.pnl.orders.onRun'), n: a.ordersDelivered },
    { key: 'failed', label: t('st.runs.runs.pnl.orders.failed'), n: a.ordersFailed },
    { key: 'cancelled', label: t('st.runs.runs.pnl.orders.cancelled'), n: a.ordersCancelled },
    { key: 'refunded', label: t('st.runs.runs.pnl.orders.refunded'), n: a.ordersRefunded }
  ];
  return (
    <dl className="m-0 grid grid-cols-[repeat(auto-fit,minmax(140px,1fr))] gap-3 text-sm" data-testid="run-pnl-orders">
      {rows.map((r) => (
        <div key={r.key} className="flex flex-col gap-0.5">
          <dt className="text-xs text-subtle">{r.label}</dt>
          <dd className="m-0 text-lg font-black" data-testid={`run-pnl-orders-${r.key}`}>
            {fmt.number(r.n)}
          </dd>
        </div>
      ))}
    </dl>
  );
}

const KIND_KEY: Record<DeliveryKind, 'st.runs.delivery.kind.DOOR' | 'st.runs.delivery.kind.PICKUP' | 'st.runs.delivery.kind.CARRIER'> = {
  DOOR: 'st.runs.delivery.kind.DOOR',
  PICKUP: 'st.runs.delivery.kind.PICKUP',
  CARRIER: 'st.runs.delivery.kind.CARRIER'
};

/** The delivery fees the orders were actually charged, per way of delivery, with what was handed back and what is kept. */
export function FeeMixTable({ a, currency }: { a: RunActuals; currency: string }) {
  const t = useT();
  const fmt = useFormatters();
  return (
    <div className="flex flex-col gap-2" data-testid="run-pnl-fees">
      <div className="overflow-auto rounded-md border border-border">
        <table className="w-full min-w-[420px] border-collapse text-sm" aria-label={t('st.runs.runs.pnl.fees.table')}>
          <thead className="bg-surface-muted text-left">
            <tr className="text-2xs font-bold tracking-[0.06em] text-muted uppercase">
              <th scope="col" className="px-3 py-2">
                {t('st.runs.runs.pnl.fees.kind')}
              </th>
              <th scope="col" className="px-3 py-2 text-right">
                {t('st.runs.pnl.orders')}
              </th>
              <th scope="col" className="px-3 py-2 text-right">
                {t('st.runs.runs.pnl.fees.charged')}
              </th>
            </tr>
          </thead>
          <tbody>
            {a.feeMix.map((m) => (
              <tr key={m.kind} className="border-t border-border" data-testid={`run-pnl-fee-${m.kind}`}>
                <th scope="row" className="px-3 py-2 text-left text-xs font-semibold">
                  {t(KIND_KEY[m.kind])}
                </th>
                <td className="px-3 py-2 text-right">{fmt.number(m.orders)}</td>
                <td className="px-3 py-2 text-right">{fmt.money(m.feeMinor, currency)}</td>
              </tr>
            ))}
            <tr className="border-t border-border" data-testid="run-pnl-fee-refunded">
              <th scope="row" colSpan={2} className="px-3 py-2 text-left text-xs font-semibold">
                {t('st.runs.runs.pnl.fees.refunded')}
              </th>
              <td className="px-3 py-2 text-right">{a.refundedFeesMinor > 0 ? `-${fmt.money(a.refundedFeesMinor, currency)}` : fmt.money(0, currency)}</td>
            </tr>
            <tr className="border-t border-border" data-testid="run-pnl-fee-kept">
              <th scope="row" colSpan={2} className="px-3 py-2 text-left text-xs font-bold">
                {t('st.runs.runs.pnl.fees.kept')}
              </th>
              <td className="px-3 py-2 text-right font-bold">{fmt.money(a.netDeliveryFeesMinor, currency)}</td>
            </tr>
          </tbody>
        </table>
      </div>
      {a.feesEstimated ? (
        <p role="note" className="m-0 text-xs text-muted" data-testid="run-pnl-fees-estimated">
          {t('st.runs.runs.pnl.fees.estimated')}
        </p>
      ) : null}
      {a.customersPaidMinor > 0 ? (
        <p className="m-0 text-xs text-muted" data-testid="run-pnl-paid">
          {t(a.totalsComplete ? 'st.runs.runs.pnl.paid' : 'st.runs.runs.pnl.paidPartial', { amount: fmt.money(a.customersPaidMinor, currency), refunds: fmt.money(a.refundsMinor, currency) })}
        </p>
      ) : null}
    </div>
  );
}

/** How the cost was worked out, and the break-even at the fee the orders actually paid (not the run's quoted fee). */
export function ActualsNotes({ a, currency }: { a: RunActuals; currency: string }) {
  const t = useT();
  const fmt = useFormatters();
  return (
    <div className="flex flex-col gap-1 text-sm" data-testid="run-pnl-notes">
      <p className="m-0 text-xs text-muted" data-testid="run-pnl-cost-basis">
        {a.costBasis === 'ACTUAL_TIME' && a.actualMinutes !== null ? t('st.runs.runs.pnl.cost.actual', { minutes: fmt.number(Math.round(a.actualMinutes)) }) : t('st.runs.runs.pnl.cost.estimate')}
      </p>
      <p className={cx('m-0 font-semibold', marginClass(a.contributionMarginMinor))} data-testid="run-pnl-margin">
        {t('st.runs.runs.pnl.margin', { margin: fmt.money(a.contributionMarginMinor, currency) })}
      </p>
      {a.ordersDelivered === 0 ? null : a.breakEvenOrders === null ? (
        <p className="m-0" data-testid="run-pnl-actual-break-even">
          {t('st.runs.runs.pnl.breakEven.never')}
        </p>
      ) : (
        <p className="m-0" data-testid="run-pnl-actual-break-even">
          {t('st.runs.runs.pnl.breakEven', { fee: fmt.money(Math.round(a.averageFeeMinor ?? 0), currency), n: a.breakEvenOrders })}{' '}
          <span className={a.breakEvenCovered ? 'text-success' : 'text-danger'}>{t(a.breakEvenCovered ? 'st.runs.runs.pnl.breakEven.covered' : 'st.runs.runs.pnl.breakEven.short', { orders: a.ordersDelivered })}</span>
        </p>
      )}
    </div>
  );
}
