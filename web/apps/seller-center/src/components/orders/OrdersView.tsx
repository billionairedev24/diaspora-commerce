'use client';

import { sellerKeys, type SellerOrderRow, sellerQueries, type SellerStatusAction, type ShipmentStatus } from '@afrotransact/api-client';
import { Badge, Button, buttonClassName, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import Link from 'next/link';
import { useMemo, useState } from 'react';

import { DataTable, FilterChips } from '@/components/ui/DataTable';
import { Notice } from '@/components/ui/Notice';
import { PageHeader } from '@/components/ui/PageHeader';
import { ErrorState, problemMessage } from '@/components/ui/States';
import { StatusPill, toneOf } from '@/components/ui/StatusPill';
import { useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { dateTime, idempotencyKey, money } from '@/lib/format';
import { moduleCopy } from '@/lib/modules';
import { useSeller } from '@/lib/session';

import { ACTION_LABEL, OrderSheet } from './OrderSheet';

const PAGE_SIZE = 20;
const STATUS_FILTERS: { value: ShipmentStatus | ''; label: string }[] = [
  { value: '', label: 'All' },
  { value: 'PLACED', label: 'New' },
  { value: 'CONFIRMED', label: 'Confirmed' },
  { value: 'PACKED', label: 'Packed' },
  { value: 'READY', label: 'Ready to ship' },
  { value: 'IN_TRANSIT', label: 'In transit' },
  { value: 'DELIVERED', label: 'Delivered' },
  { value: 'CANCELLED', label: 'Cancelled' }
];

/** Orders: server-driven table (status filter, cursor pages); rows open in a sheet; sellers confirm, pack and mark ready only. */
export function OrdersView() {
  const t = useT();
  const api = sellerApi();
  const qc = useQueryClient();
  const toast = useToast();
  const { orgId, can, session, isProvider } = useSeller();
  const editable = can('orders');
  const [status, setStatus] = useState<ShipmentStatus | ''>('');
  const [cursors, setCursors] = useState<(string | undefined)[]>([undefined]);
  const [pageIndex, setPageIndex] = useState(0);
  const [open, setOpen] = useState<string | null>(null);
  const query = { status: status || undefined, cursor: cursors[pageIndex], limit: PAGE_SIZE };
  const q = useQuery(sellerQueries.orders(api, orgId, query));

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
        id: 'order', header: 'Order', size: 15,
        cell: ({ row }) => (
          <span className="flex flex-col">
            <button type="button" className="link-button p-0 text-left font-mono text-xs font-semibold text-link hover:underline" onClick={() => setOpen(row.original.orderNumber)}>
              {row.original.orderNumber}
            </button>
            <span className="text-xs text-muted">{dateTime(row.original.placedAt)}</span>
          </span>
        )
      },
      {
        id: 'buyer', header: 'Buyer', size: 16,
        cell: ({ row }) => (
          <span className="flex flex-col">
            <strong className="font-semibold">{row.original.buyerName}</strong>
            <span className="text-xs text-muted">{row.original.shipTo}</span>
          </span>
        )
      },
      { id: 'items', header: 'Items', size: 23, cell: ({ row }) => row.original.itemsSummary },
      { id: 'total', header: 'Total', size: 10, cell: ({ row }) => money(row.original.totalMinor, row.original.currency) },
      {
        id: 'fulfilment', header: 'Fulfilment', size: 12,
        cell: ({ row }) => <Badge tone={row.original.fulfilment === 'SELLER' ? 'seller' : 'fulfilled'}>{t(`sc.ff.${row.original.fulfilment}`)}</Badge>
      },
      { id: 'status', header: 'Status', size: 11, cell: ({ row }) => <StatusPill tone={toneOf(row.original.status)}>{row.original.statusLabel}</StatusPill> },
      {
        id: 'action', header: () => <span className="at-visually-hidden">Action</span>, size: 13,
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
      <FilterChips
        label="Status"
        options={STATUS_FILTERS}
        value={status}
        onChange={(v) => {
          setStatus(v);
          setCursors([undefined]);
          setPageIndex(0);
        }}
      />
      {q.isError ? (
        <ErrorState error={q.error} onRetry={() => void q.refetch()} />
      ) : (
        <DataTable
          label="Orders"
          columns={columns}
          data={q.data?.items ?? []}
          getRowId={(r) => r.shipmentId}
          loading={q.isFetching}
          activeRowId={q.data?.items.find((r) => r.orderNumber === open)?.shipmentId ?? null}
          empty={status ? 'No orders with this status.' : 'No orders yet — they appear here as soon as shoppers buy.'}
          page={{
            pageIndex,
            pageSize: PAGE_SIZE,
            total: q.data?.total ?? 0,
            hasNext: !!q.data?.nextCursor,
            onPage: (i) => {
              if (i > pageIndex && q.data?.nextCursor) setCursors((c) => [...c.slice(0, i), q.data?.nextCursor ?? undefined]);
              setPageIndex(i);
            }
          }}
        />
      )}
      <OrderSheet orderNumber={open} onClose={() => setOpen(null)} />
    </>
  );
}
