'use client';

import { staffSearchQueries } from '@afrotransact/api-client';
import { Button, ChipGroup, Select, Tabs, TextField, useToast } from '@afrotransact/ui';
import { DownloadSimple } from '@phosphor-icons/react/ssr';
import { useQuery } from '@tanstack/react-query';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { type ReactNode,useCallback, useRef, useState } from 'react';

import { ModulePage } from '@/components/module/ModulePage';
import { ErrorState, NoAccess, Spinner } from '@/components/ui/States';
import { useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';
import { toCsv } from '@/lib/export/csv';
import { download } from '@/lib/export/table';
import { shortDate } from '@/lib/format';
import { useStaff } from '@/lib/session';

import { type Period, PERIODS, presetRange, todayUtc, useSearchFilters } from './filters';
import { ClickThroughPanel, type CsvTable, QueriesPanel, type RegisterExport, type Window } from './Panels';
import { AddSynonymSheet, SynonymRules } from './Synonyms';

type Tab = 'top' | 'zero' | 'ctr' | 'rules';
const TABS: Tab[] = ['top', 'zero', 'ctr', 'rules'];

/**
 * Search analytics: what shoppers search for, which searches find nothing and which results get clicked, with the
 * synonym rules that fix zero-result queries. One header (market, days, Export CSV) serves the three report tabs;
 * the choices are kept in the address bar. Counts come from aggregated search events: a good indication, not accounting.
 */
export function SearchAnalyticsView() {
  const { can, hasPermission, session } = useStaff();
  if (!can('search') || !hasPermission('search:analytics.view')) return <NoAccess roles={session.roleLabel || '—'} />;
  return <Loaded canEdit={can('search', 'EDIT') && hasPermission('search:synonyms.edit')} />;
}

function Loaded({ canEdit }: { canEdit: boolean }) {
  const t = useT();
  const api = staffApi();
  const toast = useToast();
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const markets = useQuery(staffSearchQueries.markets(api.search));
  const filters = useSearchFilters(params, markets.data?.default ?? null);
  const tab: Tab = TABS.find((x) => x === params.get('tab')) ?? 'top';
  const [synonymFor, setSynonymFor] = useState<string | null>(null);
  const [exporting, setExporting] = useState(false);
  const exporter = useRef<(() => Promise<CsvTable>) | null>(null);
  const register = useCallback<RegisterExport>((fn) => {
    exporter.current = fn;
  }, []);

  const go = (change: (p: URLSearchParams) => void) => {
    const next = new URLSearchParams(params.toString());
    change(next);
    const qs = next.toString();
    router.replace(qs ? `${pathname}?${qs}` : pathname, { scroll: false });
  };
  const setPeriod = (period: Period) =>
    go((p) => {
      p.set('period', period);
      if (period === 'custom') {
        // Start the custom range from the days on screen.
        const from = filters.from || presetRange('30').from;
        const to = filters.to || todayUtc();
        p.set('from', p.get('from') || from);
        p.set('to', p.get('to') || to);
      }
    });
  const setDay = (name: 'from' | 'to', value: string) => go((p) => p.set(name, value));

  const exportCsv = async () => {
    const build = exporter.current;
    if (!build || exporting) return;
    setExporting(true);
    try {
      const table = await build();
      const csv = toCsv({ title: table.name, headers: table.headers, rows: table.rows });
      download(new Blob([csv], { type: 'text/csv;charset=utf-8' }), `search-${table.name}-${filters.from}_${filters.to}.csv`);
    } catch {
      toast.show(t('st.search.exportFailed'), 'error');
    } finally {
      setExporting(false);
    }
  };

  const ready = markets.data && filters.market && filters.from && filters.to;
  const window: Window | null = ready ? { market: filters.market ?? '', from: filters.from, to: filters.to } : null;
  const periodLabels: Record<Period, string> = { '7': t('st.search.period.7'), '30': t('st.search.period.30'), '90': t('st.search.period.90'), custom: t('st.search.period.custom') };
  const problem = filters.period === 'custom' && filters.problem ? t(`st.search.dates.${filters.problem}`) : null;
  // The raw custom inputs, so a half-typed date is not thrown away while the range is invalid.
  const dateField = (name: 'from' | 'to') => params.get(name) ?? '';

  return (
    <ModulePage
      module="search"
      actions={
        tab === 'rules' ? null : (
          <Button variant="neutral" icon={<DownloadSimple size={16} aria-hidden />} onClick={() => void exportCsv()} disabled={!window || exporting}>
            {exporting ? t('st.search.exporting') : t('st.search.exportCsv')}
          </Button>
        )
      }
    >
      {tab === 'rules' ? null : (
        <div className="flex flex-wrap items-end gap-x-6 gap-y-3">
          <Select
            label={t('st.search.market')}
            className="min-w-48"
            value={filters.market ?? ''}
            disabled={!markets.data}
            options={(markets.data?.items ?? []).map((m) => ({ value: m.code, label: m.name }))}
            onChange={(e) => go((p) => p.set('market', e.currentTarget.value))}
          />
          <ChipGroup<Period> label={t('st.search.period')} value={filters.period} onChange={setPeriod} options={PERIODS.map((p) => ({ value: p, label: periodLabels[p] }))} />
          {filters.period === 'custom' ? (
            <>
              <TextField label={t('st.search.from')} type="date" value={dateField('from')} max={dateField('to') || undefined} onChange={(e) => setDay('from', e.currentTarget.value)} error={problem} />
              <TextField label={t('st.search.to')} type="date" value={dateField('to')} min={dateField('from') || undefined} onChange={(e) => setDay('to', e.currentTarget.value)} />
            </>
          ) : filters.from ? (
            <p className="m-0 pb-2 text-sm text-muted">{t('st.search.rangeLabel', { from: shortDate(filters.from), to: shortDate(filters.to) })}</p>
          ) : null}
        </div>
      )}
      {tab === 'rules' ? null : <p className="m-0 text-xs text-subtle">{t('st.search.approx')}</p>}
      <Tabs
        label={t('st.search.tabs')}
        value={tab}
        onValueChange={(v) => go((p) => p.set('tab', v))}
        items={[
          {
            value: 'top',
            label: t('st.search.tab.top'),
            content: panel(window, markets, (w) => <QueriesPanel kind="top-queries" window={w} registerExport={register} />, t('st.search.loading'))
          },
          {
            value: 'zero',
            label: t('st.search.tab.zero'),
            content: panel(window, markets, (w) => <QueriesPanel kind="zero-results" window={w} registerExport={register} onAddSynonym={canEdit ? setSynonymFor : undefined} />, t('st.search.loading'))
          },
          {
            value: 'ctr',
            label: t('st.search.tab.ctr'),
            content: panel(window, markets, (w) => <ClickThroughPanel window={w} registerExport={register} />, t('st.search.loading'))
          },
          { value: 'rules', label: t('st.search.tab.rules'), content: <SynonymRules canEdit={canEdit} /> }
        ]}
      />
      <AddSynonymSheet query={synonymFor} onClose={() => setSynonymFor(null)} />
    </ModulePage>
  );
}

/** A report panel once the market and days are known; the market list's loading or failure otherwise. */
function panel(window: Window | null, markets: { isPending: boolean; isError: boolean; error: unknown; refetch: () => unknown }, render: (w: Window) => ReactNode, loading: string) {
  if (window) return <div className="pt-3">{render(window)}</div>;
  if (markets.isError) return <ErrorState error={markets.error} onRetry={() => void markets.refetch()} />;
  if (markets.isPending) return <Spinner label={loading} />;
  return null;
}
