'use client';

import { inventoryHubQueries, type Movement } from '@afrotransact/api-client';
import { DataTable, useTableState } from '@afrotransact/data-table';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import { useSearchParams } from 'next/navigation';
import { useMemo } from 'react';

import { ErrorState } from '@/components/ui/States';
import { dateTime, humanize } from '@/lib/format';
import { exportDataset } from '@/lib/table-export';

import { useHub } from './shared';

/** Movements: the append-only stock ledger (receipts, picks, adjustments, transfers, write-offs) for recalls and audits. */
export function MovementsPanel() {
  const api = useHub();
  const qc = useQueryClient();
  const state = useTableState({ searchParams: useSearchParams(), prefix: 'mv', defaultSort: [{ id: 'occurredAt', desc: true }] });
  const query = useQuery(inventoryHubQueries.movements(api, { page: 0, size: 200 }));
  const columns = useMemo<ColumnDef<Movement>[]>(
    () => [
      { id: 'occurredAt', accessorFn: (m) => m.occurredAt, header: 'When', size: 150, meta: { kind: 'datetime' }, cell: ({ row }) => dateTime(row.original.occurredAt) },
      { id: 'type', accessorFn: (m) => humanize(m.type), header: 'Movement', size: 120, meta: { kind: 'strong' } },
      { id: 'sku', accessorFn: (m) => m.sku, header: 'SKU', size: 150, meta: { kind: 'mono' } },
      { id: 'lotNo', accessorFn: (m) => m.lotNo ?? '', header: 'Lot', size: 130, meta: { kind: 'mono' } },
      { id: 'nodeId', accessorFn: (m) => m.nodeId, header: 'FC', size: 90 },
      { id: 'qty', accessorFn: (m) => m.qty, header: 'Units', size: 80, meta: { kind: 'number', align: 'end' }, cell: ({ row }) => (row.original.qty > 0 ? `+${row.original.qty}` : row.original.qty) },
      { id: 'reference', accessorFn: (m) => m.reference ?? '', header: 'Reference', size: 150, meta: { kind: 'mono' } },
      { id: 'actor', accessorFn: (m) => m.actor ?? '', header: 'By', size: 140 },
      { id: 'reason', accessorFn: (m) => m.reason ?? '', header: 'Reason', size: 220 }
    ],
    []
  );
  return (
    <DataTable<Movement>
      id="staff.inventory.movements"
      label="Stock movements"
      columns={columns}
      data={query.data?.items ?? []}
      getRowId={(m) => String(m.id)}
      state={state}
      mode="client"
      loading={query.isPending}
      fetching={query.isFetching}
      error={query.isError ? <ErrorState error={query.error} onRetry={() => void query.refetch()} /> : undefined}
      empty="No movements yet."
      onExport={(req) => exportDataset(qc, req)}
      exportsHref="/exports"
    />
  );
}
