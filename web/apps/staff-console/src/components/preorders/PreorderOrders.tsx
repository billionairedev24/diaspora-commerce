'use client';

import { staffKeys, staffPreorderQueries } from '@afrotransact/api-client';
import { PREORDER_CHANNEL_LABEL, PREORDER_ORDER_STATUS_LABEL, type PreorderOrder, type PreorderOrdersQuery, type PreorderWindow } from '@afrotransact/api-client/staff-preorders';
import { DataTable, type TableExportRequest, useTableState } from '@afrotransact/data-table';
import { useToast } from '@afrotransact/ui';
import { ListChecks } from '@phosphor-icons/react/ssr';
import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import { useSearchParams } from 'next/navigation';
import { useMemo, useState } from 'react';

import { ErrorState, problemMessage } from '@/components/ui/States';
import { staffApi } from '@/lib/api';
import { idempotencyKey } from '@/lib/format';

import { fS, fTime, money2, ORDER_PILL } from './model';
import { Pill, usePreordersApi } from './primitives';

const PER = 12;
const exportClass = 'flex h-9 cursor-pointer items-center gap-1 rounded-pill border border-border-strong bg-surface px-3 text-sm font-semibold hover:bg-surface-hover disabled:opacity-45';

/** Orders of a window on the shared table: one search over every column, sort, select, export (Excel and PDF with
    charts, CSV) and the picking list. */
export function PreorderOrders({ w, canExport }: { w: PreorderWindow; canExport: boolean }) {
  const api = usePreordersApi();
  const qc = useQueryClient();
  const toast = useToast();
  const state = useTableState({ searchParams: useSearchParams(), prefix: 'orders', defaultPageSize: PER });
  const filters: PreorderOrdersQuery = { q: state.q || undefined, sort: state.sort, page: state.pagination.pageIndex, size: state.pagination.pageSize };
  const query = useQuery({ ...staffPreorderQueries.orders(api, w.id, filters), placeholderData: keepPreviousData });
  const [busy, setBusy] = useState(false);
  const pickingList = async () => {
    setBusy(true);
    try {
      const r = await api.downloadCsv(w.id, 'picking-list', { page: 0, size: PER });
      toast.show(`Exported ${r.rows} rows · ${r.fileName}`);
    } catch (e) {
      toast.show(problemMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };
  const columns = useMemo<ColumnDef<PreorderOrder>[]>(
    () => [
      { id: 'number', accessorFn: (o) => o.number, meta: { kind: 'mono', label: 'Order' }, header: 'Order', size: 110, cell: ({ row }) => <span className="font-mono text-xs">{row.original.number}</span> },
      {
        id: 'customerName',
        accessorFn: (o) => o.customerName,
        meta: { kind: 'strong', label: 'Customer', exportValue: (o) => o.customerName },
        header: 'Customer · phone',
        size: 170,
        cell: ({ row }) => (
          <span className="flex min-w-0 flex-col">
            <span className="truncate">{row.original.customerName}</span>
            <span className="text-2xs text-subtle">{row.original.phone}</span>
          </span>
        )
      },
      {
        id: 'addressLine',
        accessorFn: (o) => [o.addressLine, o.city, o.zip].filter(Boolean).join(' '),
        meta: { label: 'Deliver to' },
        header: 'Deliver to',
        size: 230,
        cell: ({ row }) => {
          const o = row.original;
          const line = [o.addressLine, o.city].filter(Boolean).join(', ') + (o.zip ? ` ${o.zip}` : '');
          const sub = o.deliveryNote || (o.status === 'LATE_ADD' ? o.note : '');
          return (
            <span className="flex min-w-0 flex-col text-xs">
              <span className="truncate">{line}</span>
              <span className="truncate text-2xs text-subtle">{sub}</span>
            </span>
          );
        }
      },
      { id: 'placedAt', accessorFn: (o) => o.placedAt, meta: { kind: 'datetime' }, header: 'Placed', size: 130, cell: ({ row }) => <span className="text-xs">{`${fS(row.original.placedAt)} ${fTime(row.original.placedAt)}`}</span> },
      {
        id: 'channel',
        accessorFn: (o) => o.channel,
        meta: { kind: 'status', exportValue: (o) => PREORDER_CHANNEL_LABEL[o.channel] },
        header: 'Channel',
        size: 140,
        cell: ({ row }) => (
          <span className="flex flex-col text-xs">
            <span>{PREORDER_CHANNEL_LABEL[row.original.channel]}</span>
            <span className="truncate font-mono text-2xs text-subtle">{row.original.trackingTag}</span>
          </span>
        )
      },
      { id: 'units', accessorFn: (o) => o.units, meta: { kind: 'number', align: 'end' }, header: 'Units', size: 72, cell: ({ row }) => row.original.units },
      { id: 'fulfilment', accessorFn: (o) => o.fulfilment, meta: { kind: 'status' }, header: 'Fulfil', size: 90, cell: ({ row }) => <span className="text-xs">{row.original.fulfilment === 'DOOR' ? 'Door' : 'Pickup'}</span> },
      { id: 'total', accessorFn: (o) => o.total, header: 'Total', size: 100, meta: { kind: 'money', align: 'end' }, cell: ({ row }) => <strong>{money2(row.original.total, w.currency)}</strong> },
      {
        id: 'status',
        accessorFn: (o) => o.status,
        meta: { kind: 'status', exportValue: (o) => PREORDER_ORDER_STATUS_LABEL[o.status] },
        header: 'Status',
        size: 110,
        cell: ({ row }) => <Pill className={`px-2 py-px text-2xs ${ORDER_PILL[row.original.status]}`}>{PREORDER_ORDER_STATUS_LABEL[row.original.status]}</Pill>
      }
    ],
    [w.currency]
  );
  const onExport = async (req: TableExportRequest) => {
    const job = await staffApi().createExport(
      { table: 'preorder-orders', parent: w.id, format: req.format, q: req.q || undefined, sort: req.sort, ids: req.ids.length ? req.ids : undefined, columns: req.columns },
      idempotencyKey()
    );
    void qc.invalidateQueries({ queryKey: staffKeys.exports() });
    return job;
  };
  return (
    <DataTable<PreorderOrder>
      id="staff.preorder-orders"
      label="Pre-order orders"
      columns={columns}
      data={query.data?.items ?? []}
      getRowId={(o) => o.orderId}
      rowLabel={(o) => o.number}
      rowTestId="order-row"
      state={state}
      mode="server"
      total={query.data?.total ?? null}
      loading={query.isPending}
      fetching={query.isFetching}
      error={query.isError ? <ErrorState error={query.error} onRetry={() => void query.refetch()} /> : undefined}
      partial={query.data?.partial}
      searchPlaceholder="Search every column (order, customer, phone, address, channel, total…)"
      empty="No pre-orders yet."
      noMatches="No pre-orders match."
      onExport={canExport ? onExport : undefined}
      exportsHref="/exports"
      pageSizes={[12, 25, 50, 100]}
      toolbar={
        canExport ? (
          <button type="button" onClick={() => void pickingList()} disabled={busy} className={exportClass}>
            <ListChecks aria-hidden />
            Picking list
          </button>
        ) : null
      }
    />
  );
}
