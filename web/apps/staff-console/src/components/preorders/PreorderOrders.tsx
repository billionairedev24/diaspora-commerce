'use client';

import { staffPreorderQueries } from '@afrotransact/api-client';
import {
  PREORDER_CHANNEL_LABEL,
  PREORDER_FILTER_CHANNELS,
  PREORDER_ORDER_STATUS_LABEL,
  type PreorderChannel,
  PreorderChannelSchema,
  type PreorderOrder,
  type PreorderOrdersQuery,
  type PreorderOrderStatus,
  PreorderOrderStatusSchema,
  type PreorderWindow
} from '@afrotransact/api-client/staff-preorders';
import { useToast } from '@afrotransact/ui';
import { FileCsv, ListChecks, MagnifyingGlass } from '@phosphor-icons/react/ssr';
import { useQuery } from '@tanstack/react-query';
import { type ColumnDef, flexRender, getCoreRowModel, useReactTable } from '@tanstack/react-table';
import { useMemo, useState } from 'react';

import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { useDebounced } from '@/lib/use-debounced';

import { fS, fTime, money2, ORDER_PILL } from './model';
import { Pill, usePreordersApi } from './primitives';

const PER = 12;
const filterClass = 'h-10 rounded-sm border border-border-strong bg-ink-0 px-2 text-sm';
const exportClass = 'flex h-10 cursor-pointer items-center gap-1 rounded-sm border border-border-strong bg-ink-0 px-3 font-semibold disabled:opacity-45';

/** Orders of a window: search (order, customer, phone, address, postal code), status and channel filters, 12 per page. */
export function PreorderOrders({ w, canExport }: { w: PreorderWindow; canExport: boolean }) {
  const api = usePreordersApi();
  const toast = useToast();
  const [text, setText] = useState('');
  const [status, setStatus] = useState<PreorderOrderStatus | ''>('');
  const [channel, setChannel] = useState<PreorderChannel | ''>('');
  const [page, setPage] = useState(0);
  const q = useDebounced(text.trim(), 250);
  const filters: PreorderOrdersQuery = { q: q || undefined, status, channel, page, size: PER };
  const query = useQuery(staffPreorderQueries.orders(api, w.id, filters));
  const [busy, setBusy] = useState<string | null>(null);
  const exportCsv = async (kind: 'orders-filtered' | 'picking-list') => {
    setBusy(kind);
    try {
      const r = await api.downloadCsv(w.id, kind, { ...filters, page: 0 });
      toast.show(`Exported ${r.rows} rows · ${r.fileName}`);
    } catch (e) {
      toast.show(problemMessage(e), 'error');
    } finally {
      setBusy(null);
    }
  };
  const columns = useMemo<ColumnDef<PreorderOrder>[]>(
    () => [
      { id: 'order', header: 'Order', size: 9, cell: ({ row }) => <span className="font-mono text-xs">{row.original.number}</span> },
      {
        id: 'customer',
        header: 'Customer · phone',
        size: 14,
        cell: ({ row }) => (
          <span className="flex min-w-0 flex-col">
            <span className="truncate">{row.original.customerName}</span>
            <span className="text-2xs text-subtle">{row.original.phone}</span>
          </span>
        )
      },
      {
        id: 'address',
        header: 'Deliver to',
        size: 20,
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
      { id: 'placed', header: 'Placed', size: 10, cell: ({ row }) => <span className="text-xs">{`${fS(row.original.placedAt)} ${fTime(row.original.placedAt)}`}</span> },
      {
        id: 'channel',
        header: 'Channel',
        size: 12,
        cell: ({ row }) => (
          <span className="flex flex-col text-xs">
            <span>{PREORDER_CHANNEL_LABEL[row.original.channel]}</span>
            <span className="truncate font-mono text-[10px] text-subtle">{row.original.trackingTag}</span>
          </span>
        )
      },
      { id: 'units', header: 'Units', size: 5, cell: ({ row }) => row.original.units },
      { id: 'fulfil', header: 'Fulfil', size: 7, cell: ({ row }) => <span className="text-xs">{row.original.fulfilment === 'DOOR' ? 'Door' : 'Pickup'}</span> },
      { id: 'total', header: 'Total', size: 8, meta: { right: true }, cell: ({ row }) => <strong>{money2(row.original.total, w.currency)}</strong> },
      {
        id: 'status',
        header: 'Status',
        size: 9,
        cell: ({ row }) => <Pill className={`px-2 py-px text-2xs ${ORDER_PILL[row.original.status]}`}>{PREORDER_ORDER_STATUS_LABEL[row.original.status]}</Pill>
      }
    ],
    [w.currency]
  );
  const table = useReactTable({ data: query.data?.items ?? [], columns, getCoreRowModel: getCoreRowModel(), manualPagination: true, manualFiltering: true, getRowId: (o) => o.orderId });
  const total = query.data?.total ?? 0;
  const cur = query.data?.page ?? 0;
  const pages = Math.max(1, Math.ceil(total / PER));
  const right = (meta: unknown) => (meta as { right?: boolean } | undefined)?.right;
  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-wrap items-center gap-2">
        <label className="flex h-10 flex-[1_1_220px] items-center gap-2 rounded-sm border border-border-strong px-3">
          <MagnifyingGlass size={16} aria-hidden className="text-ink-500" />
          <span className="at-visually-hidden">Search orders</span>
          <input
            type="search"
            value={text}
            onChange={(e) => {
              setText(e.currentTarget.value);
              setPage(0);
            }}
            placeholder="Search order # or customer"
            className="min-w-0 flex-1 border-0 bg-transparent text-sm outline-none"
          />
        </label>
        <select
          aria-label="Status"
          value={status}
          onChange={(e) => {
            const v = PreorderOrderStatusSchema.safeParse(e.currentTarget.value);
            setStatus(v.success ? v.data : '');
            setPage(0);
          }}
          className={filterClass}
        >
          <option value="">All statuses</option>
          {PreorderOrderStatusSchema.options.map((s) => (
            <option key={s} value={s}>
              {PREORDER_ORDER_STATUS_LABEL[s]}
            </option>
          ))}
        </select>
        <select
          aria-label="Channel"
          value={channel}
          onChange={(e) => {
            const v = PreorderChannelSchema.safeParse(e.currentTarget.value);
            setChannel(v.success ? v.data : '');
            setPage(0);
          }}
          className={filterClass}
        >
          <option value="">All channels</option>
          {PREORDER_FILTER_CHANNELS.map((c) => (
            <option key={c} value={c}>
              {PREORDER_CHANNEL_LABEL[c]}
            </option>
          ))}
        </select>
        {canExport ? (
          <>
            <button type="button" onClick={() => void exportCsv('orders-filtered')} disabled={busy !== null} className={exportClass}>
              <FileCsv aria-hidden />
              Export {total} rows
            </button>
            <button type="button" onClick={() => void exportCsv('picking-list')} disabled={busy !== null} className={exportClass}>
              <ListChecks aria-hidden />
              Picking list
            </button>
          </>
        ) : null}
      </div>
      {query.isPending ? <Spinner /> : null}
      {query.isError ? <ErrorState error={query.error} onRetry={() => void query.refetch()} /> : null}
      {query.data ? (
        <>
          <div className="overflow-auto rounded-md border border-border">
            <table className="w-full min-w-[1060px] border-collapse text-sm" aria-label="Pre-order orders" aria-busy={query.isFetching || undefined}>
              <colgroup>
                {table.getAllLeafColumns().map((c) => (
                  <col key={c.id} style={{ width: `${c.getSize()}%` }} />
                ))}
              </colgroup>
              <thead className="bg-surface-muted text-left">
                {table.getHeaderGroups().map((hg) => (
                  <tr key={hg.id}>
                    {hg.headers.map((h) => (
                      <th key={h.id} scope="col" className={`px-3 py-2 text-2xs font-bold tracking-[0.06em] text-muted uppercase ${right(h.column.columnDef.meta) ? 'text-right' : ''}`}>
                        {flexRender(h.column.columnDef.header, h.getContext())}
                      </th>
                    ))}
                  </tr>
                ))}
              </thead>
              <tbody>
                {table.getRowModel().rows.map((row) => (
                  <tr key={row.id} data-testid="order-row" className="border-t border-border">
                    {row.getVisibleCells().map((cell) => (
                      <td key={cell.id} className={`px-3 py-2 align-middle ${right(cell.column.columnDef.meta) ? 'text-right' : ''}`}>
                        {flexRender(cell.column.columnDef.cell, cell.getContext())}
                      </td>
                    ))}
                  </tr>
                ))}
                {query.data.items.length === 0 ? (
                  <tr>
                    <td colSpan={columns.length} className="p-6 text-center text-muted">
                      No pre-orders match.
                    </td>
                  </tr>
                ) : null}
              </tbody>
            </table>
          </div>
          <div className="flex items-center justify-between text-sm text-muted">
            <span aria-live="polite" data-testid="orders-page">{`${total ? cur * PER + 1 : 0}–${Math.min(total, cur * PER + PER)} of ${total}`}</span>
            <span className="flex gap-1">
              <button type="button" onClick={() => setPage(Math.max(0, cur - 1))} disabled={cur === 0} className="h-8 cursor-pointer rounded-sm border border-border-strong bg-ink-0 px-3 disabled:opacity-45">
                Prev
              </button>
              <button type="button" onClick={() => setPage(Math.min(pages - 1, cur + 1))} disabled={cur >= pages - 1} className="h-8 cursor-pointer rounded-sm border border-border-strong bg-ink-0 px-3 disabled:opacity-45">
                Next
              </button>
            </span>
          </div>
        </>
      ) : null}
    </div>
  );
}
