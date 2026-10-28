'use client';

import { runNeedsAttention, type RunOutlook, runOutlook, type StaffRun, type StaffRunLocation, staffRunQueries } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { cx } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';

import { Pill } from '@/components/preorders/primitives';
import { EmptyState, ErrorState, Spinner } from '@/components/ui/States';
import { useT } from '@/i18n/t';

import { zoneOf } from './model';
import { CARD, RUN_STATUS_PILL, runStatusLabel, useNow, useRunsApi } from './shared';

/** The tabs of the runs list. */
export const RUN_TABS = ['all', 'attention', 'open', 'scheduled', 'confirmed', 'done', 'cancelled'] as const;
export type RunTab = (typeof RUN_TABS)[number];

const GROUPS: Record<Exclude<RunTab, 'all' | 'attention'>, string[]> = {
  open: ['OPEN', 'EXTENDED', 'CUT_OFF'],
  scheduled: ['DRAFT', 'ANNOUNCED'],
  confirmed: ['CONFIRMED', 'COLLECTING', 'OUT_FOR_DELIVERY'],
  done: ['COMPLETED'],
  cancelled: ['CANCELLED']
};

/** Whether a run belongs under a tab ("Needs a decision" is open, short of the minimum, with the cutoff near). */
export const inRunTab = (run: StaffRun, tab: RunTab, nowMs: number): boolean =>
  tab === 'all' ? true : tab === 'attention' ? runNeedsAttention(run as Parameters<typeof runNeedsAttention>[0], nowMs) : GROUPS[tab].includes(run.status);

/**
 * Orders against the minimum and the capacity: a bar filled to the orders, with a mark at the minimum. Green once the
 * minimum is met, gold while it is not. The words beside it say the same, so the colour is never the only signal.
 */
export function OrdersMeter({ orders, min, capacity, label }: { orders: number; min: number; capacity?: number | null; label: string }) {
  const scale = Math.max(capacity ?? 0, min, orders, 1) * (capacity ? 1 : 1.25);
  const pct = (n: number) => `${Math.min(100, Math.round((n / scale) * 100))}%`;
  const met = orders >= min;
  return (
    <div role="img" aria-label={label} className="relative h-2 w-full min-w-[80px] rounded-pill bg-ink-100" data-testid="orders-meter" data-met={met}>
      <div className={cx('absolute inset-y-0 start-0 rounded-pill', met ? 'bg-green-600' : 'bg-gold-500')} style={{ width: pct(orders) }} />
      <div className="absolute -top-0.5 h-3 w-0.5 bg-ink-900" style={{ insetInlineStart: pct(min) }} data-testid="orders-meter-min" />
    </div>
  );
}

/** The words for what the cutoff job will do. */
export function useOutcomeText() {
  const t = useT();
  return (run: { extendDays?: number }, outcome: RunOutlook['outcome']): string =>
    t(`st.runs.lc.outcome.${outcome}`, { days: run.extendDays ?? 7 });
}

/** Runs of one location with tabs by stage; a run that needs a decision is marked. */
export function RunList({ location, onOpen }: { location: StaffRunLocation; onOpen: (id: string) => void }) {
  const t = useT();
  const fmt = useFormatters();
  const api = useRunsApi();
  const now = useNow();
  const q = useQuery(staffRunQueries.runs(api, { locationId: location.id, limit: 200 }));
  const [tab, setTab] = useState<RunTab>('all');
  if (q.isPending) return <Spinner />;
  if (q.isError) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  const runs = q.data;
  const counts = Object.fromEntries(RUN_TABS.map((k) => [k, runs.filter((r) => inRunTab(r, k, now)).length])) as Record<RunTab, number>;
  const shown = runs.filter((r) => inRunTab(r, tab, now));
  return (
    <section aria-labelledby="run-list-title" className="flex flex-col gap-3" data-testid="run-list">
      <h3 id="run-list-title" className="m-0 text-md font-bold">
        {t('st.runs.lc.list.title', { town: location.name })}
      </h3>
      <div role="tablist" aria-label={t('st.runs.lc.tabs')} className="flex max-w-full gap-0.5 overflow-x-auto overscroll-x-contain rounded-md border border-border bg-surface-muted p-0.5">
        {RUN_TABS.map((k) => (
          <button
            key={k}
            type="button"
            role="tab"
            aria-selected={tab === k}
            onClick={() => setTab(k)}
            className={cx('flex h-8 shrink-0 cursor-pointer items-center gap-1.5 rounded-sm border-0 px-3 text-sm font-semibold text-text', tab === k ? 'bg-surface shadow-sm' : 'bg-transparent')}
          >
            {t(`st.runs.lc.tab.${k}`)}
            <span className="text-xs text-muted" data-testid={`run-tab-count-${k}`}>
              {counts[k]}
            </span>
          </button>
        ))}
      </div>
      {shown.length === 0 ? (
        runs.length === 0 ? (
          <EmptyState title={t('st.runs.lc.empty.title')}>{t('st.runs.lc.empty.help', { town: location.name })}</EmptyState>
        ) : (
          <p className="m-0 text-sm text-muted" data-testid="run-list-empty">
            {t('st.runs.lc.empty.filter')}
          </p>
        )
      ) : (
        <div className="overflow-auto rounded-md border border-border">
          <table className="w-full min-w-[760px] border-collapse text-sm" aria-label={t('st.runs.lc.table')}>
            <thead className="bg-surface-muted text-left">
              <tr className="text-2xs font-bold tracking-[0.06em] text-muted uppercase">
                <th scope="col" className="px-3 py-2">
                  {t('st.runs.lc.col.delivery')}
                </th>
                <th scope="col" className="px-3 py-2">
                  {t('st.runs.lc.col.status')}
                </th>
                <th scope="col" className="px-3 py-2">
                  {t('st.runs.lc.col.orders')}
                </th>
                <th scope="col" className="px-3 py-2">
                  {t('st.runs.lc.col.cutoff')}
                </th>
                <th scope="col" className="px-3 py-2 text-right">
                  {t('st.runs.lc.col.fee')}
                </th>
              </tr>
            </thead>
            <tbody>
              {shown.map((r) => {
                const rowZone = zoneOf(r.locationTimeZone ?? location.timeZone);
                const outlook = runOutlook(r as Parameters<typeof runOutlook>[0], now);
                const needs = runNeedsAttention(r as Parameters<typeof runNeedsAttention>[0], now);
                const ordersLabel = t('st.runs.lc.ordersOf', { orders: r.reservedOrders, min: r.minOrders });
                return (
                  <tr key={r.id} className="border-t border-border" data-testid={`run-line-${r.status}`} data-attention={needs || undefined}>
                    <td className="px-3 py-2">
                      <button
                        type="button"
                        className="cursor-pointer border-0 bg-transparent p-0 text-start font-semibold text-link underline"
                        aria-label={t('st.runs.lc.openRun', { town: r.locationName ?? location.name, date: fmt.date(r.windowStart, 'medium', rowZone) })}
                        onClick={() => onOpen(r.id)}
                      >
                        {fmt.date(r.windowStart, 'medium', rowZone)}
                      </button>
                      <div className="text-xs text-muted">{`${fmt.time(r.windowStart, rowZone)} - ${fmt.time(r.windowEnd ?? r.windowStart, rowZone)}`}</div>
                    </td>
                    <td className="px-3 py-2">
                      <div className="flex flex-wrap items-center gap-1">
                        <Pill className={RUN_STATUS_PILL[r.status] ?? 'bg-ink-50 text-ink-600'}>{runStatusLabel(t, r.status)}</Pill>
                        {needs ? <Pill className="bg-gold-100 text-gold-900">{t('st.runs.lc.needsDecision')}</Pill> : null}
                        {outlook?.kind === 'DECIDING' && !needs ? <Pill className="bg-ink-100 text-ink-800">{t('st.runs.lc.deciding')}</Pill> : null}
                      </div>
                    </td>
                    <td className="px-3 py-2">
                      <div className="flex min-w-[140px] flex-col gap-1">
                        <span className="text-sm font-semibold">{ordersLabel}</span>
                        <OrdersMeter orders={r.reservedOrders} min={r.minOrders} capacity={r.capacityOrders} label={ordersLabel} />
                      </div>
                    </td>
                    <td className="px-3 py-2">{fmt.dateTime(r.cutoffAt, rowZone)}</td>
                    <td className="px-3 py-2 text-right">{fmt.money(r.feeMinor, r.currency)}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

/** Open runs short of their minimum with the cutoff near (or just passed), across every location: the ones to decide on. */
export function RunsNeedingDecision({ onOpen }: { onOpen: (id: string) => void }) {
  const t = useT();
  const fmt = useFormatters();
  const api = useRunsApi();
  const now = useNow();
  const q = useQuery(staffRunQueries.runs(api, { status: ['OPEN', 'EXTENDED'], limit: 200 }));
  const outcomeText = useOutcomeText();
  const needing = (q.data ?? []).filter((r) => runNeedsAttention(r as Parameters<typeof runNeedsAttention>[0], now));
  if (needing.length === 0) return null;
  return (
    <section aria-labelledby="needs-decision-title" className={cx(CARD, 'border-gold-500 bg-gold-50')} data-testid="runs-need-decision">
      <div className="flex flex-col gap-1">
        <strong id="needs-decision-title" className="text-md">
          {t('st.runs.lc.decide.title', { n: needing.length })}
        </strong>
        <p className="m-0 text-sm text-ink-800">{t('st.runs.lc.decide.help')}</p>
      </div>
      <ul className="m-0 flex list-none flex-col gap-2 p-0">
        {needing.map((r) => {
          const o = runOutlook(r as Parameters<typeof runOutlook>[0], now);
          const tz = zoneOf(r.locationTimeZone);
          return (
            <li key={r.id} className="flex flex-wrap items-center justify-between gap-2 text-sm" data-testid="needs-decision-row">
              <span>
                <strong>{r.locationName ?? ''}</strong>
                {`, ${fmt.date(r.windowStart, 'medium', tz)}: `}
                {t('st.runs.lc.decide.row', { orders: r.reservedOrders, min: r.minOrders, when: fmt.dateTime(r.cutoffAt, tz) })}{' '}
                {o ? outcomeText({ extendDays: typeof r.extendDays === 'number' ? r.extendDays : undefined }, o.outcome) : ''}
              </span>
              <button type="button" onClick={() => onOpen(r.id)} className="h-8 cursor-pointer rounded-sm border border-border-strong bg-ink-0 px-3 text-xs font-semibold">
                {t('st.runs.lc.decide.review')}
              </button>
            </li>
          );
        })}
      </ul>
    </section>
  );
}
