'use client';

import { type ReturnStatus, sellerQueries,type SellerReturn } from '@afrotransact/api-client';
import { DataTable, useTableState } from '@afrotransact/data-table';
import { Select, Sheet } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import { useSearchParams } from 'next/navigation';
import { useMemo, useState } from 'react';

import { Notice } from '@/components/ui/Notice';
import { PageHeader } from '@/components/ui/PageHeader';
import { ErrorState } from '@/components/ui/States';
import { StatusPill, type Tone } from '@/components/ui/StatusPill';
import { type MessageKey, useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { dateTime, money } from '@/lib/format';
import { moduleCopy } from '@/lib/modules';
import { useSeller } from '@/lib/session';

const STATUSES: ReturnStatus[] = ['REQUESTED', 'APPROVED', 'LABEL_ISSUED', 'IN_TRANSIT', 'RECEIVED', 'INSPECTED', 'REFUNDED', 'REJECTED', 'CANCELLED'];

const RETURN_TONE: Record<ReturnStatus, Tone> = {
  REQUESTED: 'warn', APPROVED: 'info', AUTO_APPROVED: 'info', LABEL_ISSUED: 'info', IN_TRANSIT: 'info', RECEIVED: 'brand', INSPECTED: 'brand',
  REFUNDED: 'good', REJECTED: 'bad', CANCELLED: 'bad'
};

const METHOD_KEY: Record<string, MessageKey> = { DROP_OFF: 'sc.returns.method.DROP_OFF', DRIVER_PICKUP: 'sc.returns.method.DRIVER_PICKUP', NO_RETURN: 'sc.returns.method.NO_RETURN' };

/** Returns: what customers sent back on the organisation's orders, with the refund each one leads to. Read only. */
export function ReturnsView() {
  const t = useT();
  const api = sellerApi();
  const { orgId, session } = useSeller();
  const state = useTableState({ searchParams: useSearchParams(), defaultPageSize: 20 });
  const [status, setStatus] = useState('');
  const [open, setOpen] = useState<SellerReturn | null>(null);
  const q = useQuery(sellerQueries.returns(api, orgId, status || undefined));

  const columns = useMemo<ColumnDef<SellerReturn>[]>(
    () => [
      {
        id: 'number', accessorFn: (r) => r.number, header: t('sc.returns.number'), size: 160, meta: { kind: 'mono' },
        cell: ({ row }) => (
          <button type="button" className="link-button p-0 text-left font-mono text-xs font-semibold text-link hover:underline" onClick={() => setOpen(row.original)}>
            {row.original.number}
          </button>
        )
      },
      { id: 'order', accessorFn: (r) => r.orderNumber, header: t('sc.returns.order'), size: 150, meta: { kind: 'mono' } },
      { id: 'items', accessorFn: (r) => r.title, header: t('sc.returns.items'), size: 240 },
      {
        id: 'status', accessorFn: (r) => r.statusLabel, header: t('sc.returns.status'), size: 160, meta: { kind: 'status' },
        cell: ({ row }) => <StatusPill tone={RETURN_TONE[row.original.status]}>{row.original.statusLabel}</StatusPill>
      },
      {
        id: 'refund', accessorFn: (r) => r.refundMinor, header: t('sc.returns.refund'), size: 120, meta: { kind: 'money', align: 'end' },
        cell: ({ row }) => money(row.original.refundMinor, row.original.currency)
      },
      {
        id: 'started', accessorFn: (r) => r.createdAt ?? '', header: t('sc.returns.started'), size: 160, meta: { kind: 'datetime' },
        cell: ({ row }) => (row.original.createdAt ? dateTime(row.original.createdAt) : '—')
      }
    ],
    [t]
  );

  if (q.isError) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  const copy = moduleCopy('returns', { provider: false, orgName: session.activeOrg?.name ?? '' }, t);

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title={copy.title}
        description={copy.description}
        actions={
          <Select
            label={t('sc.returns.status')}
            value={status}
            onChange={(e) => setStatus(e.target.value)}
            options={[{ value: '', label: t('sc.returns.allStatuses') }, ...STATUSES.map((s) => ({ value: s, label: t(`sc.returns.statusName.${s}` as MessageKey) }))]}
          />
        }
      />
      <Notice tone="info" title={t('sc.returns.note')} />
      <DataTable<SellerReturn>
        id="seller.returns"
        label={copy.title}
        columns={columns}
        data={q.data ?? []}
        getRowId={(r) => r.number}
        rowLabel={(r) => r.number}
        state={state}
        mode="client"
        loading={q.isPending}
        fetching={q.isFetching}
        empty={t('sc.returns.empty')}
      />
      <Sheet open={open !== null} onOpenChange={(o) => (o ? undefined : setOpen(null))} title={open ? open.number : t('sc.returns.title')}>
        {open ? <ReturnDetail r={open} /> : null}
      </Sheet>
    </div>
  );
}

function ReturnDetail({ r }: { r: SellerReturn }) {
  const t = useT();
  const methodKey = METHOD_KEY[r.method];
  return (
    <div className="flex flex-col gap-4 text-sm">
      <div className="flex flex-wrap items-center gap-2">
        <StatusPill tone={RETURN_TONE[r.status]}>{r.statusLabel}</StatusPill>
        <span className="text-muted">{t('sc.returns.forOrder', { order: r.orderNumber })}</span>
      </div>
      <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 m-0">
        <dt className="text-muted">{t('sc.returns.refund')}</dt>
        <dd className="m-0 font-semibold">{money(r.refundMinor, r.currency)}{r.refundState ? ` · ${r.refundState.toLowerCase()}` : ''}</dd>
        {methodKey ? (<><dt className="text-muted">{t('sc.returns.methodLabel')}</dt><dd className="m-0">{t(methodKey)}</dd></>) : null}
        {r.createdAt ? (<><dt className="text-muted">{t('sc.returns.started')}</dt><dd className="m-0">{dateTime(r.createdAt)}</dd></>) : null}
        {r.dropOffBy ? (<><dt className="text-muted">{t('sc.returns.dropOffBy')}</dt><dd className="m-0">{dateTime(r.dropOffBy)}</dd></>) : null}
        {r.carrier ? (<><dt className="text-muted">{t('sc.returns.tracking')}</dt><dd className="m-0 font-mono text-xs">{r.carrier.toUpperCase()} {r.trackingNumber ?? ''}</dd></>) : null}
      </dl>
      <section className="flex flex-col gap-2">
        <h3 className="m-0 text-md">{t('sc.returns.items')}</h3>
        <ul className="m-0 flex list-none flex-col gap-2 p-0">
          {r.items.map((l, i) => (
            <li key={`${l.title}-${i}`} className="flex flex-col gap-0.5 rounded-md border border-border p-3">
              <strong>{l.quantity} × {l.title}</strong>
              <span className="text-xs text-muted">{t('sc.returns.reason')}: {l.reason.replace(/_/g, ' ').toLowerCase()}</span>
            </li>
          ))}
        </ul>
      </section>
    </div>
  );
}
