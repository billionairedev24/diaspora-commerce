'use client';

import { staffPreorderQueries } from '@afrotransact/api-client';
import { PREORDER_STATUS_LABEL, type PreorderListRow, type PreorderListTab } from '@afrotransact/api-client/staff-preorders';
import { useQuery } from '@tanstack/react-query';
import { type ColumnDef, flexRender, getCoreRowModel, useReactTable } from '@tanstack/react-table';
import Link from 'next/link';
import { useMemo } from 'react';

import { ErrorState, Spinner } from '@/components/ui/States';

import { AREA_OFF_PILL, fD, fS, href, num, STATUS_PILL, wholeMoney } from './model';
import { Pill, usePreordersApi } from './primitives';

/** The list label: "Area off" when the master switch or every target area is off (not for drafts and delivered). */
export const rowStatus = (r: Pick<PreorderListRow, 'status' | 'areaOff'>) =>
  r.areaOff && r.status !== 'DELIVERED' && r.status !== 'DRAFT' ? { label: 'Area off', className: AREA_OFF_PILL } : { label: PREORDER_STATUS_LABEL[r.status], className: STATUS_PILL[r.status] };

/** Pre-order windows of a status tab (server-filtered): name, where, order window, delivery date, count, revenue, status. */
export function PreorderList({ tab }: { tab: PreorderListTab }) {
  const api = usePreordersApi();
  const query = useQuery(staffPreorderQueries.list(api, { status: tab, page: 0 }));
  const columns = useMemo<ColumnDef<PreorderListRow>[]>(
    () => [
      {
        id: 'name',
        header: 'Pre-order',
        size: 21,
        cell: ({ row }) => (
          <Link href={href.window(row.original.id)} className="font-bold text-link">
            {row.original.name}
          </Link>
        )
      },
      { id: 'where', header: 'Where', size: 19, cell: ({ row }) => <span className="block truncate text-muted">{row.original.where}</span> },
      { id: 'window', header: 'Order window', size: 15, cell: ({ row }) => `${fS(row.original.opensAt)} – ${fS(row.original.closesAt)}` },
      { id: 'delivers', header: 'Delivers', size: 11, cell: ({ row }) => fD(row.original.deliversOn) },
      { id: 'count', header: 'Pre-orders', size: 9, meta: { right: true }, cell: ({ row }) => <strong>{num(row.original.orderCount)}</strong> },
      { id: 'revenue', header: 'Revenue', size: 10, meta: { right: true }, cell: ({ row }) => wholeMoney(row.original.revenue, row.original.currency) },
      {
        id: 'status',
        header: 'Status',
        size: 15,
        cell: ({ row }) => {
          const s = rowStatus(row.original);
          return <Pill className={`rounded-xs ${s.className}`}>{s.label}</Pill>;
        }
      }
    ],
    []
  );
  const table = useReactTable({ data: query.data?.items ?? [], columns, getCoreRowModel: getCoreRowModel(), manualFiltering: true, manualPagination: true, getRowId: (r) => r.id });
  if (query.isPending) return <Spinner />;
  if (query.isError) return <ErrorState error={query.error} onRetry={() => void query.refetch()} />;
  const right = (meta: unknown) => (meta as { right?: boolean } | undefined)?.right;
  return (
    <div className="overflow-x-auto rounded-md border border-border bg-surface">
      <table className="w-full min-w-[920px] table-fixed border-collapse text-sm" aria-label="Pre-orders" aria-busy={query.isFetching || undefined}>
        <colgroup>
          {table.getAllLeafColumns().map((c) => (
            <col key={c.id} style={{ width: `${c.getSize()}%` }} />
          ))}
        </colgroup>
        <thead className="bg-surface-muted text-left">
          {table.getHeaderGroups().map((hg) => (
            <tr key={hg.id}>
              {hg.headers.map((h) => (
                <th key={h.id} scope="col" className={`px-4 py-2 text-xs font-bold text-muted ${right(h.column.columnDef.meta) ? 'text-right' : ''}`}>
                  {flexRender(h.column.columnDef.header, h.getContext())}
                </th>
              ))}
            </tr>
          ))}
        </thead>
        <tbody>
          {table.getRowModel().rows.map((row) => (
            <tr key={row.id} data-testid="preorder-row" className="border-t border-border bg-surface hover:bg-surface-hover">
              {row.getVisibleCells().map((cell) => (
                <td key={cell.id} className={`px-4 py-3 ${right(cell.column.columnDef.meta) ? 'text-right' : ''}`}>
                  {flexRender(cell.column.columnDef.cell, cell.getContext())}
                </td>
              ))}
            </tr>
          ))}
          {query.data.items.length === 0 ? (
            <tr>
              <td colSpan={columns.length} className="border-t border-border p-8 text-center text-sm text-muted">
                No pre-orders here yet.
              </td>
            </tr>
          ) : null}
        </tbody>
      </table>
    </div>
  );
}
