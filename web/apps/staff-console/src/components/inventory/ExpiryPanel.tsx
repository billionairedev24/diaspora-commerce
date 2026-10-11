'use client';

import { daysToExpiry, inventoryHubQueries, type Lot } from '@afrotransact/api-client';
import { DataTable, useTableState } from '@afrotransact/data-table';
import { Button, cx } from '@afrotransact/ui';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import { useSearchParams } from 'next/navigation';
import { useMemo } from 'react';

import { Card } from '@/components/ui/Card';
import { ErrorState } from '@/components/ui/States';
import { StatusPill } from '@/components/ui/StatusPill';
import { humanize, idempotencyKey, money, shortDate } from '@/lib/format';
import { exportDataset } from '@/lib/table-export';

import { expiryLabel, smallButton, todayIso, toneOf, useHub, useHubAccess, useHubMutation, useParam } from './shared';
import { LotSheet } from './StockPanel';

const BUCKETS: [string, string, string][] = [
  ['EXPIRED', 'Expired', 'bg-danger'],
  ['D0_30', '0–30 days', 'bg-danger'],
  ['D31_60', '31–60 days', 'bg-warning'],
  ['D61_90', '61–90 days', 'bg-warning'],
  ['D91_180', '91–180 days', 'bg-success'],
  ['D181_PLUS', '181+ days', 'bg-success'],
  ['NO_EXPIRY', 'No expiry', 'bg-ink-400']
];

/** Markdown suggestion for a lot about to expire (design: 30 % off within 30 days, 15 % within 60). */
export function markdownSuggestion(days: number | null): string {
  if (days === null || days > 60) return '—';
  if (days < 0) return 'Write off';
  return days <= 30 ? '30 % off' : '15 % off';
}

/** Expiry: units by days to expiry, markdown suggestions and runs, expired lots to write off. */
export function ExpiryPanel() {
  const api = useHub();
  const qc = useQueryClient();
  const { inventoryEdit, inventoryApprove } = useHubAccess();
  const state = useTableState({ searchParams: useSearchParams(), prefix: 'exp' });
  const [lotId, setLotId] = useParam('lot');
  const overview = useQuery(inventoryHubQueries.overview(api));
  const lots = useQuery(inventoryHubQueries.lots(api, { page: 0, size: 200 }));
  const today = todayIso();
  const atRisk = useMemo(
    () =>
      (lots.data?.items ?? [])
        .filter((l) => l.qtyOnHand > 0 && l.status !== 'WRITTEN_OFF')
        .filter((l) => {
          const d = daysToExpiry(l.expDate, today);
          return l.status === 'EXPIRED' || (d !== null && d <= 60);
        })
        .sort((a, b) => (a.expDate ?? '').localeCompare(b.expDate ?? '')),
    [lots.data, today]
  );
  const expiryRun = useHubMutation(() => api.expiryRun(), (r) => `Expiry run: ${r.expired} lots expired${r.deferred ? `, ${r.deferred} deferred (held by checkouts)` : ''}.`);
  const markdownRun = useHubMutation(() => api.markdownRun(), (r) => `Markdown flags recomputed: ${r.length} lots changed.`);
  const writeOff = useHubMutation((rows: Lot[]) => Promise.all(rows.map((l) => api.writeOff(l.id, undefined, 'Expired: written off at cost', idempotencyKey()))), (r) => `${r.length} expired lots written off.`);
  const total = (overview.data?.expiry ?? []).reduce((a, b) => a + b.units, 0) || 1;
  const columns = useMemo<ColumnDef<Lot>[]>(
    () => [
      { id: 'sku', accessorFn: (l) => l.sku, header: 'SKU', size: 160, meta: { kind: 'mono' } },
      { id: 'lotNo', accessorFn: (l) => l.lotNo, header: 'Lot', size: 140, meta: { kind: 'mono' } },
      { id: 'nodeId', accessorFn: (l) => l.nodeId, header: 'FC', size: 90 },
      { id: 'qtyOnHand', accessorFn: (l) => l.qtyOnHand, header: 'Units', size: 80, meta: { kind: 'number', align: 'end' } },
      { id: 'value', accessorFn: (l) => l.qtyOnHand * l.unitLandedCostMinor, header: 'Value', size: 100, meta: { kind: 'money', align: 'end' }, cell: ({ row }) => money(row.original.qtyOnHand * row.original.unitLandedCostMinor) },
      { id: 'expDate', accessorFn: (l) => l.expDate ?? '', header: 'Expires', size: 110, meta: { kind: 'date' }, cell: ({ row }) => shortDate(row.original.expDate) },
      { id: 'days', accessorFn: (l) => expiryLabel(daysToExpiry(l.expDate, today)), header: 'Days left', size: 120 },
      { id: 'suggestion', accessorFn: (l) => (l.status === 'EXPIRED' ? 'Write off' : markdownSuggestion(daysToExpiry(l.expDate, today))), header: 'Suggestion', size: 110, cell: ({ getValue }) => <strong>{String(getValue())}</strong> },
      { id: 'status', accessorFn: (l) => humanize(l.status), header: 'Status', size: 110, meta: { kind: 'status' }, cell: ({ row }) => <StatusPill tone={toneOf(row.original.status)}>{humanize(row.original.status)}</StatusPill> }
    ],
    [today]
  );
  return (
    <div className="flex flex-col gap-4">
      <Card
        title="Shelf life · units by days to expiry"
        actions={
          inventoryEdit ? (
            <div className="flex flex-wrap gap-2">
              <Button size="sm" variant="neutral" loading={markdownRun.isPending} onClick={() => markdownRun.mutate(undefined)}>
                Run markdown flags
              </Button>
              <Button size="sm" variant="neutral" loading={expiryRun.isPending} onClick={() => expiryRun.mutate(undefined)}>
                Run expiry now
              </Button>
            </div>
          ) : null
        }
      >
        {overview.isError ? <ErrorState error={overview.error} onRetry={() => void overview.refetch()} /> : null}
        <div className="flex flex-col gap-2" data-testid="expiry-buckets">
          {BUCKETS.map(([key, label, color]) => {
            const b = overview.data?.expiry.find((x) => x.bucket === key);
            const units = b?.units ?? 0;
            return (
              <div key={key} className="grid grid-cols-[96px_minmax(0,1fr)_96px] items-center gap-2 text-sm">
                <span>{label}</span>
                <div className="h-3 overflow-hidden rounded-pill bg-surface-muted" role="img" aria-label={`${label}: ${units} units`}>
                  <div className={cx('h-full', color)} style={{ width: `${Math.round((units / total) * 100)}%` }} />
                </div>
                <span className="text-end">
                  {units} u · {b?.lots ?? 0} lots
                </span>
              </div>
            );
          })}
        </div>
        <p className="m-0 text-xs text-muted">The nightly run expires lots past their date (blocked from sale) and flags lots within 60 and 30 days for markdown; short-dated lots at receipt go to quarantine.</p>
      </Card>
      <DataTable<Lot>
        id="staff.inventory.expiry"
        label="Expiring and expired lots"
        columns={columns}
        data={atRisk}
        getRowId={(l) => l.id}
        rowLabel={(l) => `${l.sku} lot ${l.lotNo}`}
        rowTestId="expiry-row"
        state={state}
        mode="client"
        loading={lots.isPending}
        fetching={lots.isFetching}
        error={lots.isError ? <ErrorState error={lots.error} onRetry={() => void lots.refetch()} /> : undefined}
        onRowClick={(l) => setLotId(l.id)}
        activeRowId={lotId}
        bulkActions={
          inventoryApprove
            ? (rows, clear) => (
                <button type="button" className={smallButton} disabled={writeOff.isPending} onClick={() => writeOff.mutate(rows.filter((r) => r.status === 'EXPIRED'), { onSuccess: clear })}>
                  Write off expired
                </button>
              )
            : undefined
        }
        empty="Nothing expires within 60 days."
        onExport={(req) => exportDataset(qc, req)}
        exportsHref="/exports"
      />
      <LotSheet id={lotId} onClose={() => setLotId(null)} />
    </div>
  );
}
