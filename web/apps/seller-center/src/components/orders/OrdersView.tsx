'use client';

import { sellerKeys, type SellerOrderRow, sellerQueries, type SellerStatusAction } from '@afrotransact/api-client';
import { DataTable, useTableState } from '@afrotransact/data-table';
import { Badge, Button, buttonClassName, useToast } from '@afrotransact/ui';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import Link from 'next/link';
import { useSearchParams } from 'next/navigation';
import { useMemo, useState } from 'react';

import { Notice } from '@/components/ui/Notice';
import { PageHeader } from '@/components/ui/PageHeader';
import { ErrorState, problemMessage } from '@/components/ui/States';
import { StatusPill, toneOf } from '@/components/ui/StatusPill';
import { useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { dateTime, idempotencyKey, money } from '@/lib/format';
import { moduleCopy } from '@/lib/modules';
import { useSeller } from '@/lib/session';
import { exportTable, pollExport, runForEach } from '@/lib/table-export';

import { ACTION_LABEL, OrderSheet } from './OrderSheet';

/** Orders: server-driven table (one search over every column, sort, cursor pages, selection, exports); rows open in a
    sheet; sellers confirm, pack and mark ready only (one row or many). */
export function OrdersView() {
  const t = useT();
  const api = sellerApi();
  const qc = useQueryClient();
  const toast = useToast();
  const { orgId, can, session, isProvider } = useSeller();
  const editable = can('orders');
  const state = useTableState({ searchParams: useSearchParams(), defaultPageSize: 25 });
  const [open, setOpen] = useState<string | null>(null);
  const query = { q: state.q || undefined, sort: state.sort, cursor: state.cursor, limit: state.pagination.pageSize };
  const q = useQuery({ ...sellerQueries.orders(api, orgId, query), placeholderData: keepPreviousData });

  const advance = useMutation({
    mutationFn: (r: { row: SellerOrderRow; to: SellerStatusAction }) => api.updateShipmentStatus(r.row.orderNumber, r.row.shipmentId, r.to, idempotencyKey()),
    onSuccess: (o) => {
      qc.setQueryData(sellerKeys.order(orgId, o.orderNumber), o);
      void qc.invalidateQueries({ queryKey: sellerKeys.orders(orgId) });
      toast.show(`Order ${o.orderNumber} updated`);
    },
    onError: (e) => toast.show(problemMessage(e), 'error')
  });

  const columns = useMemo<ColumnDef<SellerOrderRow>[]>(
    () => [
      {
        id: 'orderNumber', accessorFn: (r) => r.orderNumber, header: 'Order', size: 160, meta: { kind: 'mono', label: 'Order' },
        cell: ({ row }) => (
          <span className="flex flex-col">
            <button type="button" className="link-button p-0 text-left font-mono text-xs font-semibold text-link hover:underline" onClick={() => setOpen(row.original.orderNumber)}>
              {row.original.orderNumber}
            </button>
          </span>
        )
      },
      {
        id: 'buyerName', accessorFn: (r) => r.buyerName, header: 'Buyer', size: 180, meta: { kind: 'strong' },
        cell: ({ row }) => (
          <span className="flex flex-col">
            <strong className="font-semibold">{row.original.buyerName}</strong>
          </span>
        )
      },
      { id: 'placedAt', accessorFn: (r) => r.placedAt, header: 'Placed', size: 150, meta: { kind: 'datetime' }, cell: ({ row }) => dateTime(row.original.placedAt) },
      { id: 'shipTo', accessorFn: (r) => r.shipTo, header: 'Ship to', size: 140 },
      { id: 'itemsSummary', accessorFn: (r) => r.itemsSummary, header: 'Items', size: 230 },
      { id: 'itemCount', accessorFn: (r) => r.itemCount, header: 'Units', size: 80, meta: { kind: 'number', align: 'end' } },
      { id: 'totalMinor', accessorFn: (r) => r.totalMinor, header: 'Total', size: 110, meta: { kind: 'money', align: 'end' }, cell: ({ row }) => money(row.original.totalMinor, row.original.currency) },
      {
        id: 'fulfilment', accessorFn: (r) => t(`sc.ff.${r.fulfilment}`), header: 'Fulfilment', size: 140,
        cell: ({ row }) => <Badge tone={row.original.fulfilment === 'SELLER' ? 'seller' : 'fulfilled'}>{t(`sc.ff.${row.original.fulfilment}`)}</Badge>
      },
      { id: 'status', accessorFn: (r) => r.statusLabel, header: 'Status', size: 130, meta: { kind: 'status' }, cell: ({ row }) => <StatusPill tone={toneOf(row.original.status)}>{row.original.statusLabel}</StatusPill> },
      {
        id: 'trackingNumber', accessorFn: (r) => r.trackingNumber ?? undefined, header: 'Tracking', size: 170, meta: { kind: 'mono' },
        cell: ({ row }) => <span className="font-mono text-xs">{row.original.trackingNumber ?? '—'}</span>
      },
      {
        id: 'action', header: () => <span className="at-visually-hidden">Action</span>, size: 130, enableSorting: false, meta: { export: false, label: 'Action' },
        cell: ({ row }) => {
          const r = row.original;
          const next = r.nextActions[0];
          if (!editable) return null;
          if (r.canBuyLabel && !next) {
            return (
              <Button size="sm" variant="neutral" onClick={() => setOpen(r.orderNumber)}>
                Buy label
              </Button>
            );
          }
          // Handing a pickup order over asks for the buyer's code, which the order sheet does.
          if (next === 'DELIVERED') {
            return (
              <Button size="sm" variant="neutral" onClick={() => setOpen(r.orderNumber)}>
                {ACTION_LABEL[next]}
              </Button>
            );
          }
          return next ? (
            <Button size="sm" variant="neutral" disabled={advance.isPending} onClick={() => advance.mutate({ row: r, to: next })}>
              {ACTION_LABEL[next].replace(' order', '').replace(' to ship', '')}
            </Button>
          ) : null;
        }
      }
    ],
    [editable, advance, t]
  );

  const copy = moduleCopy('orders', { provider: isProvider, orgName: session.activeOrg?.name ?? '' }, t);
  return (
    <>
      <PageHeader
        title={copy.title}
        description={copy.description}
        actions={
          isProvider ? null : (
            <Link href="/orders/ship-from" className={buttonClassName({ variant: 'neutral' })}>
              {t('sc.shipFrom.link')}
            </Link>
          )
        }
      />
      {!editable ? (
        <Notice tone="info" icon="eye" title="Read-only.">
          {session.activeOrg?.roleLabel} can view orders but not change them.
        </Notice>
      ) : null}
      <DataTable<SellerOrderRow>
        id="seller.orders"
        label="Orders"
        columns={columns}
        data={q.data?.items ?? []}
        getRowId={(r) => r.shipmentId}
        rowLabel={(r) => r.orderNumber}
        rowTestId="order-row"
        state={state}
        paging="cursor"
        total={q.data?.total ?? null}
        nextCursor={q.data?.nextCursor ?? null}
        loading={q.isPending}
        fetching={q.isFetching}
        partial={q.data?.partial}
        error={q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : undefined}
        onRowClick={(r) => setOpen(r.orderNumber)}
        activeRowId={q.data?.items.find((r) => r.orderNumber === open)?.shipmentId ?? null}
        searchPlaceholder="Search order, buyer, items, total, status, tracking…"
        empty="No orders yet — they appear here as soon as shoppers buy."
        noMatches="No orders match."
        onExport={(req) => exportTable('orders', req)}
        pollExport={pollExport}
        bulkActions={
          editable
            ? (rows, clear) => {
                const next = rows.filter((r) => r.nextActions[0]);
                return (
                  <Button
                    size="sm"
                    variant="neutral"
                    disabled={!next.length}
                    onClick={async () => {
                      const r = await runForEach(next, (row) => api.updateShipmentStatus(row.orderNumber, row.shipmentId, row.nextActions[0] as SellerStatusAction, idempotencyKey()), 'moved to the next step', problemMessage);
                      void qc.invalidateQueries({ queryKey: sellerKeys.orders(orgId) });
                      toast.show(r.text, r.tone);
                      clear();
                    }}
                  >
                    Next step for {next.length} {next.length === 1 ? 'order' : 'orders'}
                  </Button>
                );
              }
            : undefined
        }
      />
      <OrderSheet orderNumber={open} onClose={() => setOpen(null)} />
    </>
  );
}
