'use client';

import { staffSearchQueries } from '@afrotransact/api-client';
import { type StaffSearchDay, type StaffSearchQueryStat } from '@afrotransact/api-client/staff-search';
import { DataTable, useTableState } from '@afrotransact/data-table';
import { Button } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import { useSearchParams } from 'next/navigation';
import { useEffect, useMemo, useRef } from 'react';

import { ErrorState } from '@/components/ui/States';
import { type MessageKey, useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';
import { count, share, shortDate } from '@/lib/format';

import { Sparkline } from './Sparkline';

/** What a panel needs from the shared header: the market and the days. */
export interface Window {
  market: string;
  from: string;
  to: string;
}

/** A panel hands the header the function that builds its CSV (the current search and sort, every page). */
export type RegisterExport = (fn: (() => Promise<CsvTable>) | null) => void;
export interface CsvTable {
  name: string;
  headers: string[];
  rows: (string | number)[][];
}

/** Back to the first page when the header's market or days change (not on the first render, which keeps the URL's page). */
function useResetPaging(resetPaging: () => void, signature: string) {
  const first = useRef(true);
  useEffect(() => {
    if (first.current) {
      first.current = false;
      return;
    }
    resetPaging();
    // eslint-disable-next-line react-hooks/exhaustive-deps -- resetPaging is recreated each render; only the window matters
  }, [signature]);
}

const pct = (rate: number) => Math.round(rate * 1000) / 10;

/** Top queries and No results: one server-driven table of normalised queries over the header's window. */
export function QueriesPanel({ kind, window, onAddSynonym, registerExport }: { kind: 'top-queries' | 'zero-results'; window: Window; onAddSynonym?: (query: string) => void; registerExport: RegisterExport }) {
  const t = useT();
  const api = staffApi();
  const zero = kind === 'zero-results';
  const state = useTableState({ searchParams: useSearchParams(), prefix: zero ? 'zero' : 'top', defaultSort: [{ id: zero ? 'zeroResults' : 'searches', desc: true }] });
  useResetPaging(state.resetPaging, `${window.market}|${window.from}|${window.to}`);
  const base = { market: window.market, from: window.from, to: window.to, q: state.q || undefined, sort: state.sort };
  const query = useQuery(staffSearchQueries.queries(api.search, kind, { ...base, page: state.pagination.pageIndex + 1, size: state.pagination.pageSize }));
  const data = query.data;

  const columns = useMemo<ColumnDef<StaffSearchQueryStat>[]>(() => {
    const num = (key: 'searches' | 'zeroResults' | 'clicks', label: MessageKey): ColumnDef<StaffSearchQueryStat> => ({
      id: key,
      accessorKey: key,
      header: t(label),
      size: 120,
      meta: { label: t(label), kind: 'number', align: 'end' },
      cell: ({ row }) => count(row.original[key])
    });
    const rate = (key: 'zeroResultRate' | 'ctr', label: MessageKey): ColumnDef<StaffSearchQueryStat> => ({
      id: key,
      accessorKey: key,
      header: t(label),
      size: 130,
      meta: { label: t(label), kind: 'number', align: 'end' },
      cell: ({ row }) => share(row.original[key])
    });
    const cols: ColumnDef<StaffSearchQueryStat>[] = [
      { id: 'query', accessorKey: 'query', header: t('st.search.col.query'), size: 260, meta: { label: t('st.search.col.query'), kind: 'text' }, cell: ({ row }) => <strong className="font-semibold">{row.original.query}</strong> },
      num('searches', 'st.search.col.searches'),
      ...(zero ? [num('zeroResults', 'st.search.col.zeroResults')] : []),
      rate('zeroResultRate', 'st.search.col.zeroRate'),
      num('clicks', 'st.search.col.clicks'),
      rate('ctr', 'st.search.col.ctr')
    ];
    if (zero && onAddSynonym) {
      cols.push({
        id: 'actions',
        header: t('st.search.col.action'),
        size: 150,
        enableSorting: false,
        meta: { label: t('st.search.col.action'), export: false },
        cell: ({ row }) => (
          <Button variant="link" size="sm" aria-label={t('st.search.synonym.addFor', { query: row.original.query })} onClick={() => onAddSynonym(row.original.query)}>
            {t('st.search.synonym.add')}
          </Button>
        )
      });
    }
    return cols;
  }, [t, zero, onAddSynonym]);

  // The CSV covers every page of the current search and sort (the service lists at most 500 queries).
  const sortKey = state.sort.join('|');
  useEffect(() => {
    registerExport(async () => {
      const all = await api.search.queries(kind, { ...base, page: 1, size: 500 });
      return {
        name: zero ? 'no-results' : 'top-queries',
        headers: [t('st.search.col.query'), t('st.search.col.searches'), t('st.search.col.zeroResults'), t('st.search.csv.zeroRate'), t('st.search.col.clicks'), t('st.search.csv.ctr')],
        rows: all.items.map((i) => [i.query, i.searches, i.zeroResults, pct(i.zeroResultRate), i.clicks, pct(i.ctr)])
      };
    });
    return () => registerExport(null);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- rebuilt when the search, sort or window change
  }, [kind, window.market, window.from, window.to, state.q, sortKey, t]);

  return (
    <div className="flex flex-col gap-3">
      <p className="m-0 max-w-[80ch] text-muted">{t(zero ? 'st.search.explain.zero' : 'st.search.explain.top')}</p>
      {data ? (
        <p className="m-0 text-sm text-muted" data-testid="search-totals">
          {t('st.search.totals', { searches: count(data.totals.searches), zero: count(data.totals.zeroResults), zeroRate: share(data.totals.zeroResultRate), clicks: count(data.totals.clicks), ctr: share(data.totals.ctr) })}
        </p>
      ) : null}
      <DataTable<StaffSearchQueryStat>
        id={`staff.search.${kind}`}
        label={t(zero ? 'st.search.tab.zero' : 'st.search.tab.top')}
        columns={columns}
        data={data?.items ?? []}
        getRowId={(r) => r.query}
        state={state}
        mode="server"
        total={data?.total ?? null}
        loading={query.isPending}
        fetching={query.isFetching}
        error={query.isError ? <ErrorState error={query.error} onRetry={() => void query.refetch()} /> : undefined}
        searchPlaceholder={t('st.search.searchQueries')}
        enableSelection={false}
        empty={t(zero ? 'st.search.empty.zero' : 'st.search.empty.top')}
        footer={data?.truncated ? <p className="m-0 text-xs text-subtle">{t('st.search.truncated')}</p> : null}
      />
    </div>
  );
}

/** Click-through: searches, clicks and rates by day, with the trend of the whole window. */
export function ClickThroughPanel({ window, registerExport }: { window: Window; registerExport: RegisterExport }) {
  const t = useT();
  const api = staffApi();
  const state = useTableState({ searchParams: useSearchParams(), prefix: 'ctr', defaultSort: [{ id: 'day', desc: true }] });
  useResetPaging(state.resetPaging, `${window.market}|${window.from}|${window.to}`);
  const base = { market: window.market, from: window.from, to: window.to, q: state.q || undefined, sort: state.sort };
  const query = useQuery(staffSearchQueries.clickThrough(api.search, { ...base, page: state.pagination.pageIndex + 1, size: state.pagination.pageSize }));
  const data = query.data;

  const columns = useMemo<ColumnDef<StaffSearchDay>[]>(() => {
    const num = (key: 'searches' | 'zeroResults' | 'clicks', label: MessageKey): ColumnDef<StaffSearchDay> => ({
      id: key,
      accessorKey: key,
      header: t(label),
      size: 120,
      meta: { label: t(label), kind: 'number', align: 'end' },
      cell: ({ row }) => count(row.original[key])
    });
    const rate = (key: 'zeroResultRate' | 'ctr', label: MessageKey): ColumnDef<StaffSearchDay> => ({
      id: key,
      accessorKey: key,
      header: t(label),
      size: 130,
      meta: { label: t(label), kind: 'number', align: 'end' },
      cell: ({ row }) => share(row.original[key])
    });
    return [
      { id: 'day', accessorKey: 'day', header: t('st.search.col.day'), size: 170, meta: { label: t('st.search.col.day'), kind: 'date' }, cell: ({ row }) => shortDate(row.original.day) },
      num('searches', 'st.search.col.searches'),
      num('zeroResults', 'st.search.col.zeroResults'),
      rate('zeroResultRate', 'st.search.col.zeroRate'),
      num('clicks', 'st.search.col.clicks'),
      rate('ctr', 'st.search.col.ctr')
    ];
  }, [t]);

  const sortKey = state.sort.join('|');
  useEffect(() => {
    registerExport(async () => {
      const all = await api.search.clickThrough({ ...base, page: 1, size: 366 });
      return {
        name: 'click-through',
        headers: [t('st.search.col.day'), t('st.search.col.searches'), t('st.search.col.zeroResults'), t('st.search.csv.zeroRate'), t('st.search.col.clicks'), t('st.search.csv.ctr')],
        rows: all.items.map((d) => [d.day, d.searches, d.zeroResults, pct(d.zeroResultRate), d.clicks, pct(d.ctr)])
      };
    });
    return () => registerExport(null);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- rebuilt when the search, sort or window change
  }, [window.market, window.from, window.to, state.q, sortKey, t]);

  const trend = data?.trend ?? [];
  const first = trend[0];
  const last = trend[trend.length - 1];
  return (
    <div className="flex flex-col gap-3">
      <p className="m-0 max-w-[80ch] text-muted">{t('st.search.explain.ctr')}</p>
      {data && data.totals.searches > 0 ? (
        <dl className="m-0 flex flex-wrap gap-x-10 gap-y-3" data-testid="search-trend">
          {[
            { key: 'ctr', label: t('st.search.col.ctr'), total: data.totals.ctr, values: trend.map((d) => d.ctr) },
            { key: 'zero', label: t('st.search.col.zeroRate'), total: data.totals.zeroResultRate, values: trend.map((d) => d.zeroResultRate) }
          ].map((m) => (
            <div key={m.key} className="flex items-center gap-3">
              <div>
                <dt className="text-xs text-muted">{m.label}</dt>
                <dd className="m-0 text-xl font-bold">{share(m.total)}</dd>
              </div>
              {first && last ? <Sparkline values={m.values} label={t('st.search.trendLabel', { name: m.label, from: shortDate(first.day), to: shortDate(last.day) })} /> : null}
            </div>
          ))}
        </dl>
      ) : null}
      <DataTable<StaffSearchDay>
        id="staff.search.ctr"
        label={t('st.search.tab.ctr')}
        columns={columns}
        data={data?.items ?? []}
        getRowId={(r) => r.day}
        state={state}
        mode="server"
        total={data?.total ?? null}
        loading={query.isPending}
        fetching={query.isFetching}
        error={query.isError ? <ErrorState error={query.error} onRetry={() => void query.refetch()} /> : undefined}
        searchPlaceholder={t('st.search.searchDays')}
        enableSelection={false}
        empty={t('st.search.empty.ctr')}
      />
    </div>
  );
}
