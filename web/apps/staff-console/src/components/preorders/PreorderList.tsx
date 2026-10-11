'use client';

import { staffPreorderQueries } from '@afrotransact/api-client';
import { PREORDER_STATUS_LABEL, type PreorderListRow, type PreorderListTab } from '@afrotransact/api-client/staff-preorders';
import { DataTable, useTableState } from '@afrotransact/data-table';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import Link from 'next/link';
import { useRouter, useSearchParams } from 'next/navigation';
import { useMemo } from 'react';

import { ErrorState } from '@/components/ui/States';
import { exportDataset } from '@/lib/table-export';

import { AREA_OFF_PILL, fD, fS, href, num, STATUS_PILL, wholeMoney } from './model';
import { Pill, usePreordersApi } from './primitives';

/** The list label: "Area off" when the master switch or every target area is off (not for drafts and delivered). */
export const rowStatus = (r: Pick<PreorderListRow, 'status' | 'areaOff'>) =>
  r.areaOff && r.status !== 'DELIVERED' && r.status !== 'DRAFT' ? { label: 'Area off', className: AREA_OFF_PILL } : { label: PREORDER_STATUS_LABEL[r.status], className: STATUS_PILL[r.status] };

/** Pre-order windows of a status tab: name, where, order window, delivery date, count, revenue, status; one search over
    every column, sort, select and export (client-side: a tab holds every window). */
export function PreorderList({ tab }: { tab: PreorderListTab }) {
  const api = usePreordersApi();
  const qc = useQueryClient();
  const router = useRouter();
  const state = useTableState({ searchParams: useSearchParams(), prefix: 'list' });
  const query = useQuery(staffPreorderQueries.list(api, { status: tab, page: 0 }));
  const columns = useMemo<ColumnDef<PreorderListRow>[]>(
    () => [
      {
        id: 'name',
        accessorFn: (r) => r.name,
        header: 'Pre-order',
        size: 230,
        meta: { kind: 'strong' },
        cell: ({ row }) => (
          <Link href={href.window(row.original.id)} className="font-bold text-link">
            {row.original.name}
          </Link>
        )
      },
      { id: 'code', accessorFn: (r) => r.code, header: 'Code', size: 110, meta: { kind: 'mono' }, cell: ({ row }) => <span className="font-mono text-xs">{row.original.code}</span> },
      { id: 'where', accessorFn: (r) => r.where, header: 'Where', size: 200, cell: ({ row }) => <span className="block truncate text-muted">{row.original.where}</span> },
      { id: 'opensAt', accessorFn: (r) => r.opensAt, header: 'Order window', size: 160, meta: { kind: 'datetime' }, cell: ({ row }) => `${fS(row.original.opensAt)} – ${fS(row.original.closesAt)}` },
      { id: 'deliversOn', accessorFn: (r) => r.deliversOn, header: 'Delivers', size: 120, meta: { kind: 'date' }, cell: ({ row }) => fD(row.original.deliversOn) },
      { id: 'orderCount', accessorFn: (r) => r.orderCount, header: 'Pre-orders', size: 100, meta: { kind: 'number', align: 'end' }, cell: ({ row }) => <strong>{num(row.original.orderCount)}</strong> },
      { id: 'revenue', accessorFn: (r) => r.revenue, header: 'Revenue', size: 110, meta: { kind: 'money', align: 'end' }, cell: ({ row }) => wholeMoney(row.original.revenue, row.original.currency) },
      {
        id: 'status',
        accessorFn: (r) => rowStatus(r).label,
        header: 'Status',
        size: 140,
        meta: { kind: 'status' },
        cell: ({ row }) => {
          const s = rowStatus(row.original);
          return <Pill className={`rounded-xs ${s.className}`}>{s.label}</Pill>;
        }
      }
    ],
    []
  );
  return (
    <DataTable<PreorderListRow>
      id="staff.preorders"
      label="Pre-orders"
      columns={columns}
      data={query.data?.items ?? []}
      getRowId={(r) => r.id}
      rowLabel={(r) => r.name}
      rowTestId="preorder-row"
      state={state}
      mode="client"
      loading={query.isPending}
      fetching={query.isFetching}
      error={query.isError ? <ErrorState error={query.error} onRetry={() => void query.refetch()} /> : undefined}
      onRowClick={(r) => router.push(href.window(r.id))}
      empty="No pre-orders here yet."
      onExport={(req) => exportDataset(qc, req)}
      exportsHref="/exports"
    />
  );
}
