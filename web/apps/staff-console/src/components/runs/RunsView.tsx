'use client';

import { type RunHistoryRow, type RunPnl, staffRunQueries } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { Select, TextField } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';

import { HBars } from '@/components/preorders/PreorderCharts';
import { Pill } from '@/components/preorders/primitives';
import { EmptyState, ErrorState, Spinner } from '@/components/ui/States';
import { type MessageKey, useT } from '@/i18n/t';

import { AdvertorialsPanel } from './AdvertorialsPanel';
import { breakEvenText, CARD, EconomicsTable, useRunsApi } from './shared';

const STATUS_PILL: Record<string, string> = {
  OPEN: 'bg-green-50 text-green-800',
  EXTENDED: 'bg-green-50 text-green-800',
  ANNOUNCED: 'bg-ink-100 text-ink-800',
  DRAFT: 'bg-ink-50 text-ink-600',
  CUT_OFF: 'bg-gold-100 text-gold-900',
  CONFIRMED: 'bg-gold-100 text-gold-900',
  COLLECTING: 'bg-gold-100 text-gold-900',
  OUT_FOR_DELIVERY: 'bg-gold-100 text-gold-900',
  COMPLETED: 'bg-ink-100 text-ink-600',
  CANCELLED: 'bg-red-50 text-red-700'
};

const statusLabel = (t: ReturnType<typeof useT>, status: string): string => {
  const key = `st.runs.status.${status}` as MessageKey;
  const label = t(key);
  return label === key ? status : label;
};

/** The P&L of one run: the estimate before it, the result after it, or what went back to customers when it was cancelled. */
export function RunPnlPanel({ runId }: { runId: string }) {
  const t = useT();
  const fmt = useFormatters();
  const api = useRunsApi();
  const [commission, setCommission] = useState('');
  const parsed = commission.trim() === '' ? undefined : /^\d{1,7}$/.test(commission.trim()) ? Number(commission.trim()) * 100 : Number.NaN;
  const bad = parsed !== undefined && Number.isNaN(parsed);
  const q = useQuery({ ...staffRunQueries.runPnl(api, runId, bad ? undefined : parsed) });
  if (q.isPending) return <Spinner />;
  if (q.isError) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  const p: RunPnl = q.data;
  const e = p.economics;
  return (
    <section aria-labelledby={`pnl-title-${runId}`} className={CARD} data-testid="run-pnl">
      <div className="flex flex-wrap items-center gap-2">
        <h3 id={`pnl-title-${runId}`} className="m-0 text-md font-bold">
          {t('st.runs.runs.pnl.title')}
        </h3>
        <Pill className={STATUS_PILL[p.status] ?? 'bg-ink-50 text-ink-600'}>{statusLabel(t, p.status)}</Pill>
        <span className="text-xs text-muted" data-testid="run-pnl-phase">
          {t(`st.runs.phase.${p.phase}`)}
        </span>
      </div>
      {p.phase === 'CANCELLED' ? (
        <div className="flex flex-col gap-1 text-sm" data-testid="run-pnl-cancelled">
          <p className="m-0">{t('st.runs.runs.pnl.cancelledHelp')}</p>
          {p.cancelReason ? <p className="m-0 text-muted">{t('st.runs.runs.pnl.reason', { reason: p.cancelReason })}</p> : null}
          <p className="m-0 font-semibold">{t('st.runs.runs.pnl.refunded', { orders: p.ordersRefunded, fees: fmt.money(p.refundedFeesMinor, e.currency) })}</p>
        </div>
      ) : (
        <>
          <p className="m-0 text-sm font-semibold">{breakEvenText(t, e)}</p>
          <EconomicsTable e={e} labelOrders={t(p.phase === 'AFTER' ? 'st.runs.pnl.atActual' : 'st.runs.pnl.soFar')} />
          <TextField
            label={t('st.runs.runs.pnl.commission')}
            help={t('st.runs.runs.pnl.commissionHelp')}
            inputMode="decimal"
            value={commission}
            error={bad ? t('st.runs.settings.problem.notAmount') : null}
            onChange={(ev) => setCommission(ev.currentTarget.value)}
            className="max-w-xs"
          />
        </>
      )}
    </section>
  );
}

function HistoryTable({ rows, currency, selected, onSelect }: { rows: RunHistoryRow[]; currency: string; selected: string | null; onSelect: (id: string) => void }) {
  const t = useT();
  const fmt = useFormatters();
  return (
    <div className="overflow-auto rounded-md border border-border">
      <table className="w-full min-w-[720px] border-collapse text-sm" aria-label={t('st.runs.runs.history.table')}>
        <thead className="bg-surface-muted text-left">
          <tr className="text-2xs font-bold tracking-[0.06em] text-muted uppercase">
            <th scope="col" className="px-3 py-2">
              {t('st.runs.runs.history.run')}
            </th>
            <th scope="col" className="px-3 py-2">
              {t('st.runs.runs.history.status')}
            </th>
            <th scope="col" className="px-3 py-2 text-right">
              {t('st.runs.runs.history.orders')}
            </th>
            <th scope="col" className="px-3 py-2 text-right">
              {t('st.runs.runs.history.packages')}
            </th>
            <th scope="col" className="px-3 py-2 text-right">
              {t('st.runs.runs.history.fees')}
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
          {rows.map((r) => (
            <tr key={r.id} className={`border-t border-border ${selected === r.id ? 'bg-ink-25' : ''}`} data-testid={`run-row-${r.status}`}>
              <td className="px-3 py-2">
                <button type="button" aria-pressed={selected === r.id} className="cursor-pointer border-0 bg-transparent p-0 text-left font-semibold text-link underline" onClick={() => onSelect(r.id)}>
                  {fmt.date(r.windowStart, 'medium')}
                </button>
              </td>
              <td className="px-3 py-2">
                <Pill className={STATUS_PILL[r.status] ?? 'bg-ink-50 text-ink-600'}>{statusLabel(t, r.status)}</Pill>
              </td>
              <td className="px-3 py-2 text-right">{fmt.number(r.orders)}</td>
              <td className="px-3 py-2 text-right">{fmt.number(r.packages)}</td>
              <td className="px-3 py-2 text-right">{fmt.money(r.feeRevenueMinor, currency)}</td>
              <td className="px-3 py-2 text-right">{r.ordersRefunded > 0 ? t('st.runs.runs.history.refundedOrders', { n: r.ordersRefunded }) : '-'}</td>
              <td className="px-3 py-2 text-xs">{r.minimumMet ? t('st.runs.runs.history.met', { min: r.minOrders }) : t('st.runs.runs.history.notMet', { min: r.minOrders })}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function LocationHistory({ locationId, currency }: { locationId: string; currency: string }) {
  const t = useT();
  const fmt = useFormatters();
  const api = useRunsApi();
  const q = useQuery(staffRunQueries.history(api, locationId));
  const [selected, setSelected] = useState<string | null>(null);
  if (q.isPending) return <Spinner />;
  if (q.isError) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  const h = q.data;
  const oldestFirst = [...h.runs].reverse();
  const channels = Object.entries(h.subscribers.byChannel);
  return (
    <div className="flex flex-col gap-4" data-testid="location-history">
      <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,320px),1fr))] gap-5">
        <section aria-label={t('st.runs.runs.subscribers.title')} className="flex min-w-0 flex-col gap-2">
          <strong className="text-sm">{t('st.runs.runs.subscribers.title')}</strong>
          <dl className="m-0 flex flex-wrap gap-x-6 gap-y-1 text-sm" data-testid="subscriber-counts">
            <div className="flex gap-1">
              <dt className="text-muted">{t('st.runs.runs.subscribers.active')}</dt>
              <dd className="m-0 font-bold">{fmt.number(h.subscribers.active)}</dd>
            </div>
            <div className="flex gap-1">
              <dt className="text-muted">{t('st.runs.runs.subscribers.pending')}</dt>
              <dd className="m-0 font-bold">{fmt.number(h.subscribers.pending)}</dd>
            </div>
          </dl>
          <HBars title={t('st.runs.runs.subscribers.byChannel')} color="green" labelWidth={110} rows={channels.map(([k, n]) => ({ k: t(`st.runs.channel.${k}` as MessageKey), n }))} />
        </section>
        <HBars title={t('st.runs.runs.subscribers.growth')} color="gold" labelWidth={80} rows={h.subscriberGrowth.map((g) => ({ k: g.month, n: g.count }))} />
      </div>
      <HBars title={t('st.runs.runs.history.chart')} color="ink" labelWidth={110} rows={oldestFirst.map((r) => ({ k: fmt.date(r.windowStart, 'short'), n: r.orders }))} />
      {h.runs.length === 0 ? <EmptyState title={t('st.runs.runs.history.empty')} /> : <HistoryTable rows={h.runs} currency={currency} selected={selected} onSelect={setSelected} />}
      {selected ? (
        <div className="flex flex-col gap-4">
          <RunPnlPanel key={selected} runId={selected} />
          <AdvertorialsPanel key={`adv-${selected}`} source="RUN" sourceId={selected} />
        </div>
      ) : h.runs.length > 0 ? (
        <p className="m-0 text-xs text-muted">{t('st.runs.runs.history.pick')}</p>
      ) : null}
    </div>
  );
}

/**
 * Pre-orders › Delivery runs (S265): per location, the orders of every run, the P&L of a run before and after it (or
 * the refunds when it was cancelled), subscriber counts and growth, and the location-targeted advertorials of a run.
 */
export function RunsView() {
  const t = useT();
  const api = useRunsApi();
  const locations = useQuery(staffRunQueries.locations(api));
  const [chosen, setChosen] = useState('');
  if (locations.isPending) return <Spinner />;
  if (locations.isError) return <ErrorState error={locations.error} onRetry={() => void locations.refetch()} />;
  const items = locations.data.items;
  if (items.length === 0) return <EmptyState title={t('st.runs.runs.empty.title')}>{t('st.runs.runs.empty.help')}</EmptyState>;
  const location = items.find((l) => l.id === chosen) ?? items[0];
  if (!location) return null;
  return (
    <div className="flex flex-col gap-4 p-5" data-testid="runs-view">
      <div className="flex flex-col gap-1">
        <h2 className="m-0 text-xl font-black">{t('st.runs.runs.title')}</h2>
        <p className="m-0 max-w-[80ch] text-sm text-muted">{t('st.runs.runs.intro')}</p>
      </div>
      <Select label={t('st.runs.runs.location')} value={location.id} onChange={(e) => setChosen(e.currentTarget.value)} options={items.map((l) => ({ value: l.id, label: l.name }))} className="max-w-xs" />
      <LocationHistory key={location.id} locationId={location.id} currency={location.currency ?? 'USD'} />
    </div>
  );
}
