'use client';

import { type DisputeStatus, type SellerDispute, sellerQueries } from '@afrotransact/api-client';
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

const STATUSES: DisputeStatus[] = ['AWAITING_SELLER', 'UNDER_REVIEW', 'ESCALATED', 'RESOLVED_REFUND', 'RESOLVED_DENIED', 'WITHDRAWN'];

const DISPUTE_TONE: Record<DisputeStatus, Tone> = {
  AWAITING_SELLER: 'warn', UNDER_REVIEW: 'info', ESCALATED: 'bad', RESOLVED_REFUND: 'good', RESOLVED_DENIED: 'brand', WITHDRAWN: 'info'
};

const DEADLINE_TONE: Record<string, Tone> = { ON_TRACK: 'good', AT_RISK: 'warn', BREACHED: 'bad' };

/** Disputes and A-to-z claims against the organisation, with the deadline to respond. Read only; the buyer is never named. */
export function DisputesView() {
  const t = useT();
  const api = sellerApi();
  const { orgId, session } = useSeller();
  const state = useTableState({ searchParams: useSearchParams(), defaultPageSize: 20 });
  const [status, setStatus] = useState('');
  const [open, setOpen] = useState<SellerDispute | null>(null);
  const q = useQuery(sellerQueries.disputes(api, orgId, status || undefined));

  const columns = useMemo<ColumnDef<SellerDispute>[]>(
    () => [
      {
        id: 'reference', accessorFn: (d) => d.reference, header: t('sc.disputes.reference'), size: 130, meta: { kind: 'mono' },
        cell: ({ row }) => (
          <button type="button" className="link-button p-0 text-left font-mono text-xs font-semibold text-link hover:underline" onClick={() => setOpen(row.original)}>
            {row.original.reference}
          </button>
        )
      },
      { id: 'type', accessorFn: (d) => d.typeLabel, header: t('sc.disputes.type'), size: 170 },
      { id: 'order', accessorFn: (d) => d.orderNumber, header: t('sc.disputes.order'), size: 150, meta: { kind: 'mono' } },
      {
        id: 'amount', accessorFn: (d) => d.amountMinor, header: t('sc.disputes.amount'), size: 110, meta: { kind: 'money', align: 'end' },
        cell: ({ row }) => money(row.original.amountMinor, row.original.currency)
      },
      {
        id: 'status', accessorFn: (d) => d.statusLabel, header: t('sc.disputes.status'), size: 210, meta: { kind: 'status' },
        cell: ({ row }) => <StatusPill tone={DISPUTE_TONE[row.original.status]}>{row.original.statusLabel}</StatusPill>
      },
      {
        id: 'respondBy', accessorFn: (d) => d.respondBy ?? '', header: t('sc.disputes.respondBy'), size: 200, meta: { kind: 'datetime' },
        cell: ({ row }) => {
          const d = row.original;
          if (!d.respondBy) return '—';
          return (
            <span className="flex items-center gap-2">
              {dateTime(d.respondBy)}
              {d.deadlineState ? <StatusPill tone={DEADLINE_TONE[d.deadlineState] ?? 'info'}>{t(`sc.disputes.deadline.${d.deadlineState}` as MessageKey)}</StatusPill> : null}
            </span>
          );
        }
      }
    ],
    [t]
  );

  if (q.isError) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  const copy = moduleCopy('disputes', { provider: false, orgName: session.activeOrg?.name ?? '' }, t);

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title={copy.title}
        description={copy.description}
        actions={
          <Select
            label={t('sc.disputes.status')}
            value={status}
            onChange={(e) => setStatus(e.target.value)}
            options={[{ value: '', label: t('sc.disputes.allStatuses') }, ...STATUSES.map((s) => ({ value: s, label: t(`sc.disputes.statusName.${s}` as MessageKey) }))]}
          />
        }
      />
      <Notice tone="info" title={t('sc.disputes.note')} />
      <DataTable<SellerDispute>
        id="seller.disputes"
        label={copy.title}
        columns={columns}
        data={q.data ?? []}
        getRowId={(d) => d.reference}
        rowLabel={(d) => d.reference}
        state={state}
        mode="client"
        loading={q.isPending}
        fetching={q.isFetching}
        empty={t('sc.disputes.empty')}
      />
      <Sheet open={open !== null} onOpenChange={(o) => (o ? undefined : setOpen(null))} title={open ? open.reference : t('sc.disputes.title')}>
        {open ? <DisputeDetail d={open} /> : null}
      </Sheet>
    </div>
  );
}

function DisputeDetail({ d }: { d: SellerDispute }) {
  const t = useT();
  return (
    <div className="flex flex-col gap-4 text-sm">
      <div className="flex flex-wrap items-center gap-2">
        <StatusPill tone={DISPUTE_TONE[d.status]}>{d.statusLabel}</StatusPill>
        {d.escalated ? <StatusPill tone="bad">{t('sc.disputes.escalated')}</StatusPill> : null}
        <span className="text-muted">{d.typeLabel} · {d.orderNumber}</span>
      </div>
      {d.respondBy ? (
        <Notice tone={d.deadlineState === 'BREACHED' ? 'danger' : 'warning'} title={t('sc.disputes.respondByLine', { when: dateTime(d.respondBy) })} />
      ) : null}
      <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 m-0">
        <dt className="text-muted">{t('sc.disputes.amount')}</dt>
        <dd className="m-0 font-semibold">{money(d.amountMinor, d.currency)}</dd>
        {d.openedAt ? (<><dt className="text-muted">{t('sc.disputes.opened')}</dt><dd className="m-0">{dateTime(d.openedAt)}</dd></>) : null}
        {d.outcome ? (<><dt className="text-muted">{t('sc.disputes.outcome')}</dt><dd className="m-0">{d.outcome.toLowerCase()}{d.refundMinor > 0 ? ` · ${money(d.refundMinor, d.currency)}` : ''}</dd></>) : null}
      </dl>
      <section className="flex flex-col gap-1">
        <h3 className="m-0 text-md">{t('sc.disputes.customerSays')}</h3>
        <p className="m-0 whitespace-pre-wrap text-muted">{d.description || '—'}</p>
      </section>
      {d.responded && d.sellerResponse ? (
        <section className="flex flex-col gap-1">
          <h3 className="m-0 text-md">{t('sc.disputes.yourResponse')}</h3>
          <p className="m-0 whitespace-pre-wrap text-muted">{d.sellerResponse}</p>
        </section>
      ) : null}
    </div>
  );
}
