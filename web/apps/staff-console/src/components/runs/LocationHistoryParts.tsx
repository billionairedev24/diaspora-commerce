'use client';

import { type NetGrowth, type WindowHistoryRow } from '@afrotransact/api-client';
import { PREORDER_STATUS_LABEL, type PreorderStatus } from '@afrotransact/api-client/staff-preorders';
import { useFormatters } from '@afrotransact/i18n/react';
import { cx } from '@afrotransact/ui';

import { STATUS_PILL } from '@/components/preorders/model';
import { Pill } from '@/components/preorders/primitives';
import { useT } from '@/i18n/t';

import { marginClass } from './shared';

/** Subscribers who joined, who left and the net, per month (S265): growth net of unsubscribes, not sign-ups alone. */
export function GrowthTable({ rows }: { rows: NetGrowth[] }) {
  const t = useT();
  const fmt = useFormatters();
  return (
    <section aria-label={t('st.runs.runs.subscribers.growth')} className="flex min-w-0 flex-col gap-2">
      <strong className="text-sm">{t('st.runs.runs.subscribers.growth')}</strong>
      {rows.length === 0 ? (
        <span className="text-sm text-muted">{t('st.runs.runs.growth.empty')}</span>
      ) : (
        <div className="overflow-auto rounded-md border border-border">
          <table className="w-full min-w-[320px] border-collapse text-sm" aria-label={t('st.runs.runs.growth.table')} data-testid="growth-table">
            <thead className="bg-surface-muted text-left">
              <tr className="text-2xs font-bold tracking-[0.06em] text-muted uppercase">
                <th scope="col" className="px-3 py-2">
                  {t('st.runs.runs.growth.month')}
                </th>
                <th scope="col" className="px-3 py-2 text-right">
                  {t('st.runs.runs.growth.joined')}
                </th>
                <th scope="col" className="px-3 py-2 text-right">
                  {t('st.runs.runs.growth.left')}
                </th>
                <th scope="col" className="px-3 py-2 text-right">
                  {t('st.runs.runs.growth.net')}
                </th>
              </tr>
            </thead>
            <tbody>
              {rows.map((g) => (
                <tr key={g.month} className="border-t border-border" data-testid={`growth-${g.month}`}>
                  <th scope="row" className="px-3 py-2 text-left text-xs font-semibold">
                    {g.month}
                  </th>
                  <td className="px-3 py-2 text-right">{fmt.number(g.signUps)}</td>
                  <td className="px-3 py-2 text-right">{fmt.number(g.unsubscribes)}</td>
                  <td className={cx('px-3 py-2 text-right font-bold', marginClass(g.net))}>{g.net > 0 ? `+${fmt.number(g.net)}` : fmt.number(g.net)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

/** The pre-orders a location delivered (S265): orders, delivery fees, refunds and whether the minimum was met. */
export function WindowsTable({ rows }: { rows: WindowHistoryRow[] }) {
  const t = useT();
  const fmt = useFormatters();
  return (
    <section aria-label={t('st.runs.runs.windows.title')} className="flex min-w-0 flex-col gap-2" data-testid="location-windows">
      <strong className="text-sm">{t('st.runs.runs.windows.title')}</strong>
      {rows.length === 0 ? (
        <span className="text-sm text-muted">{t('st.runs.runs.windows.empty')}</span>
      ) : (
        <div className="overflow-auto rounded-md border border-border">
          <table className="w-full min-w-[720px] border-collapse text-sm" aria-label={t('st.runs.runs.windows.table')}>
            <thead className="bg-surface-muted text-left">
              <tr className="text-2xs font-bold tracking-[0.06em] text-muted uppercase">
                <th scope="col" className="px-3 py-2">
                  {t('st.runs.runs.windows.name')}
                </th>
                <th scope="col" className="px-3 py-2">
                  {t('st.runs.runs.history.status')}
                </th>
                <th scope="col" className="px-3 py-2 text-right">
                  {t('st.runs.runs.history.orders')}
                </th>
                <th scope="col" className="px-3 py-2 text-right">
                  {t('st.runs.runs.windows.fees')}
                </th>
                <th scope="col" className="px-3 py-2 text-right">
                  {t('st.runs.runs.history.refunded')}
                </th>
                <th scope="col" className="px-3 py-2">
                  {t('st.runs.runs.history.minimum')}
                </th>
              </tr>
            </thead>
            <tbody>
              {rows.map((w) => (
                <tr key={w.id} className="border-t border-border" data-testid={`window-row-${w.code}`}>
                  <td className="px-3 py-2">
                    <span className="font-semibold">{w.name}</span>
                    <span className="block text-xs text-muted">{t('st.runs.runs.windows.delivers', { date: fmt.date(w.deliversOn, 'medium') })}</span>
                  </td>
                  <td className="px-3 py-2">
                    <Pill className={STATUS_PILL[w.status as PreorderStatus] ?? 'bg-ink-50 text-ink-600'}>{PREORDER_STATUS_LABEL[w.status as PreorderStatus] ?? w.status}</Pill>
                  </td>
                  <td className="px-3 py-2 text-right">{fmt.number(w.orders)}</td>
                  <td className="px-3 py-2 text-right">{fmt.money(w.deliveryFeesMinor, w.currency)}</td>
                  <td className="px-3 py-2 text-right">{w.ordersRefunded > 0 ? t('st.runs.runs.windows.refundedOrders', { n: w.ordersRefunded, amount: fmt.money(w.refundedMinor, w.currency) }) : '-'}</td>
                  <td className="px-3 py-2 text-xs">
                    {w.minimumMet === null ? t('st.runs.runs.windows.noMinimum') : w.minimumMet ? t('st.runs.runs.history.met', { min: w.minOrders }) : t('st.runs.runs.history.notMet', { min: w.minOrders })}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
