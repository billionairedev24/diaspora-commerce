'use client';

import { type RunHistoryRow, staffRunKeys, staffRunQueries } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { Button, cx, Select, useToast } from '@afrotransact/ui';
import { PencilSimple, Plus } from '@phosphor-icons/react/ssr';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';

import { HBars } from '@/components/preorders/PreorderCharts';
import { Pill } from '@/components/preorders/primitives';
import { EmptyState, ErrorState, Spinner } from '@/components/ui/States';
import { type MessageKey, useT } from '@/i18n/t';

import { AdvertorialsPanel } from './AdvertorialsPanel';
import { AttributionPanel } from './AttributionPanel';
import { LocationFormSheet } from './LocationForm';
import { GrowthTable, WindowsTable } from './LocationHistoryParts';
import { RunFormSheet } from './RunForm';
import { RunList, RunsNeedingDecision } from './RunList';
import { RunPnlPanel } from './RunPnlPanel';
import { ActionDialog, RunSheet } from './RunSheet';
import { RunTrend } from './RunTrend';
import { marginClass, RUN_STATUS_PILL, runStatusLabel, useCanManageRuns, useRunsApi } from './shared';

function HistoryTable({ rows, currency, selected, onSelect }: { rows: RunHistoryRow[]; currency: string; selected: string | null; onSelect: (id: string) => void }) {
  const t = useT();
  const fmt = useFormatters();
  return (
    <div className="overflow-auto rounded-md border border-border">
      <table className="w-full min-w-[980px] border-collapse text-sm" aria-label={t('st.runs.runs.history.table')}>
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
            <th scope="col" className="px-3 py-2 text-right">
              {t('st.runs.runs.history.cost')}
            </th>
            <th scope="col" className="px-3 py-2 text-right">
              {t('st.runs.runs.history.margin')}
            </th>
            <th scope="col" className="px-3 py-2 text-right">
              {t('st.runs.runs.history.breakEven')}
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
                <Pill className={RUN_STATUS_PILL[r.status] ?? 'bg-ink-50 text-ink-600'}>{runStatusLabel(t, r.status)}</Pill>
              </td>
              <td className="px-3 py-2 text-right">
                {fmt.number(r.orders)}
                {r.ordersCancelled > 0 ? <span className="block text-xs text-muted">{t('st.runs.runs.history.cancelledOrders', { n: r.ordersCancelled })}</span> : null}
              </td>
              <td className="px-3 py-2 text-right">{fmt.number(r.packages)}</td>
              <td className="px-3 py-2 text-right">{fmt.money(r.feeRevenueMinor, currency)}</td>
              <td className="px-3 py-2 text-right">
                {r.ordersRefunded > 0 ? t('st.runs.runs.history.refundedOrders', { n: r.ordersRefunded }) : '-'}
                {r.refundedFeesMinor > 0 ? (
                  <span className="block text-xs text-muted">{t(r.feesEstimated ? 'st.runs.runs.history.refundedFeesEstimated' : 'st.runs.runs.history.refundedFees', { fees: fmt.money(r.refundedFeesMinor, currency) })}</span>
                ) : null}
              </td>
              <td className="px-3 py-2 text-right" data-testid="history-cost">
                {r.costMinor === null ? '-' : fmt.money(r.costMinor, currency)}
              </td>
              <td className={cx('px-3 py-2 text-right font-bold', r.marginMinor === null ? '' : marginClass(r.marginMinor))} data-testid="history-margin">
                {r.marginMinor === null ? '-' : fmt.money(r.marginMinor, currency)}
              </td>
              <td className="px-3 py-2 text-right" data-testid="history-break-even">
                {r.breakEvenOrders === null ? '-' : t('st.runs.runs.history.breakEvenOrders', { n: r.breakEvenOrders })}
              </td>
              <td className="px-3 py-2 text-xs">
                {r.minimumMet ? t('st.runs.runs.history.met', { min: r.minOrders }) : t('st.runs.runs.history.notMet', { min: r.minOrders })}
                {r.minimumCoveredBreakEven === null ? null : (
                  <span className={cx('block', r.minimumCoveredBreakEven ? 'text-success' : 'text-danger')} data-testid="history-covers">
                    {t(r.minimumCoveredBreakEven ? 'st.runs.runs.history.covers' : 'st.runs.runs.history.belowBreakEven')}
                  </span>
                )}
              </td>
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
        <GrowthTable rows={h.subscriberNetGrowth} />
      </div>
      <AttributionPanel locationId={locationId} town={h.location.name} />
      {h.runs.length > 0 ? <RunTrend rows={h.runs} currency={currency} /> : null}
      {h.runs.length === 0 ? <EmptyState title={t('st.runs.runs.history.empty')} /> : <HistoryTable rows={h.runs} currency={currency} selected={selected} onSelect={setSelected} />}
      <WindowsTable rows={h.windows} />
      {selected ? (
        <div className="flex flex-col gap-4">
          <RunPnlPanel key={selected} runId={selected} />
          <AttributionPanel key={`attr-${selected}`} locationId={locationId} town={h.location.name} runId={selected} />
          <AdvertorialsPanel key={`adv-${selected}`} source="RUN" sourceId={selected} />
        </div>
      ) : h.runs.length > 0 ? (
        <p className="m-0 text-xs text-muted">{t('st.runs.runs.history.pick')}</p>
      ) : null}
    </div>
  );
}

/**
 * Pre-orders › Delivery runs (S260, S265): schedule a run (New run), see the runs of a location by stage with their orders
 * against the minimum, open one for its timeline, decisions and actions (announce, open, extend, confirm, cancel), and see
 * the runs that need a decision across every location. Below: the orders of every run of the location, the P&L of a run
 * before, after or when cancelled, subscriber counts and growth, and the location-targeted advertorials of a run.
 */
export function RunsView() {
  const t = useT();
  const api = useRunsApi();
  const manage = useCanManageRuns();
  const locations = useQuery(staffRunQueries.locations(api));
  const [chosen, setChosen] = useState('');
  const qc = useQueryClient();
  const toast = useToast();
  const [sheet, setSheet] = useState<{ kind: 'view'; id: string } | { kind: 'form'; id: string | null } | null>(null);
  const [place, setPlace] = useState<{ kind: 'form'; id: string | null } | { kind: 'off' | 'on' } | null>(null);
  if (locations.isPending) return <Spinner />;
  if (locations.isError) return <ErrorState error={locations.error} onRetry={() => void locations.refetch()} />;
  const items = locations.data.items;
  const canManage = locations.data.can.manage && manage;
  const location = items.find((l) => l.id === chosen) ?? items.find((l) => l.active !== false) ?? items[0];
  const addButton = (
    <Button icon={<Plus aria-hidden size={18} />} variant="neutral" disabled={!canManage} title={canManage ? undefined : t('st.runs.loc.noAccess')} onClick={() => setPlace({ kind: 'form', id: null })}>
      {t('st.runs.loc.add')}
    </Button>
  );
  const locationSheet = (
    <LocationFormSheet
      open={place?.kind === 'form'}
      location={place?.kind === 'form' && place.id ? (items.find((l) => l.id === place.id) ?? null) : null}
      onClose={() => setPlace(null)}
      onSaved={(saved) => {
        setChosen(saved.id);
        setPlace(null);
      }}
    />
  );
  if (!location) {
    return (
      <div className="flex flex-col gap-4 p-5" data-testid="runs-view">
        <EmptyState title={t('st.runs.loc.empty.title')}>{t('st.runs.runs.empty.help')}</EmptyState>
        <div>{addButton}</div>
        {locationSheet}
      </div>
    );
  }
  const off = location.active === false;
  const view = (id: string) => setSheet({ kind: 'view', id });
  const switched = (on: boolean) => (reason: string) =>
    (on ? api.activateLocation(location.id, reason || undefined) : api.deactivateLocation(location.id, reason)).then(() => {
      void qc.invalidateQueries({ queryKey: staffRunKeys.all });
      toast.show(t(on ? 'st.runs.loc.toast.on' : 'st.runs.loc.toast.off'));
    });
  return (
    <div className="flex flex-col gap-4 p-5" data-testid="runs-view">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex flex-col gap-1">
          <h2 className="m-0 text-xl font-black">{t('st.runs.runs.title')}</h2>
          <p className="m-0 max-w-[80ch] text-sm text-muted">{t('st.runs.runs.intro')}</p>
        </div>
        <Button icon={<Plus aria-hidden size={18} />} disabled={!canManage} title={canManage ? undefined : t('st.runs.lc.newNoAccess')} onClick={() => setSheet({ kind: 'form', id: null })}>
          {t('st.runs.lc.new')}
        </Button>
      </div>
      <RunsNeedingDecision onOpen={view} />
      <div className="flex flex-wrap items-end gap-3">
        <Select
          label={t('st.runs.runs.location')}
          value={location.id}
          onChange={(e) => setChosen(e.currentTarget.value)}
          options={items.map((l) => ({ value: l.id, label: l.active === false ? t('st.runs.loc.offOption', { name: l.name }) : l.name }))}
          className="max-w-xs"
        />
        <Button variant="neutral" icon={<PencilSimple aria-hidden size={18} />} disabled={!canManage} title={canManage ? undefined : t('st.runs.loc.noAccess')} onClick={() => setPlace({ kind: 'form', id: location.id })}>
          {t('st.runs.loc.edit')}
        </Button>
        <Button variant="neutral" disabled={!canManage} title={canManage ? undefined : t('st.runs.loc.noAccess')} onClick={() => setPlace({ kind: off ? 'on' : 'off' })}>
          {t(off ? 'st.runs.loc.switchOn' : 'st.runs.loc.switchOff')}
        </Button>
        {addButton}
      </div>
      {off ? (
        <p className="m-0 max-w-[80ch] rounded-md border border-border bg-surface-muted p-3 text-sm" data-testid="location-off-notice">
          {t('st.runs.loc.offNotice', { town: location.name })}
        </p>
      ) : null}
      <RunList location={location} onOpen={view} />
      <LocationHistory key={location.id} locationId={location.id} currency={location.currency ?? 'USD'} />
      <RunSheet
        runId={sheet?.kind === 'view' ? sheet.id : null}
        onClose={() => setSheet(null)}
        onEdit={(run) => setSheet({ kind: 'form', id: run.id })}
      />
      {locationSheet}
      <ActionDialog
        open={place?.kind === 'off'}
        title={t('st.runs.loc.off.title', { town: location.name })}
        help={t('st.runs.loc.off.help')}
        reasonLabel={t('st.runs.loc.off.reason')}
        confirmLabel={t('st.runs.loc.off.confirm')}
        danger
        onClose={() => setPlace(null)}
        onSubmit={switched(false)}
      />
      <ActionDialog
        open={place?.kind === 'on'}
        title={t('st.runs.loc.on.title', { town: location.name })}
        help={t('st.runs.loc.on.help')}
        reasonLabel={t('st.runs.loc.on.reason')}
        reasonRequired={false}
        confirmLabel={t('st.runs.loc.on.confirm')}
        onClose={() => setPlace(null)}
        onSubmit={switched(true)}
      />
      <RunFormSheet
        open={sheet?.kind === 'form'}
        runId={sheet?.kind === 'form' ? sheet.id : null}
        defaultLocationId={location.id}
        onClose={() => setSheet((s) => (s?.kind === 'form' && s.id ? { kind: 'view', id: s.id } : null))}
        onSaved={(run) => {
          setChosen(run.locationId);
          setSheet({ kind: 'view', id: run.id });
        }}
      />
    </div>
  );
}
