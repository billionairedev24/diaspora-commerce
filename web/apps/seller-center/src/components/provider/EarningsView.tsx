'use client';

import { type EarningsRow, type ProviderFees, sellerQueries } from '@afrotransact/api-client';
import { DataTable, useTableState } from '@afrotransact/data-table';
import { ChipGroup, StatStrip } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import { useSearchParams } from 'next/navigation';
import { useState } from 'react';

import { ConnectionFeesCard } from '@/components/money/FeesView';
import { Card } from '@/components/ui/Card';
import { ModulePage } from '@/components/ui/ModulePage';
import { Notice } from '@/components/ui/Notice';
import { ErrorState, Spinner } from '@/components/ui/States';
import { StatusPill, toneOf } from '@/components/ui/StatusPill';
import { useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { dateTime, isoDay, money, shortDate } from '@/lib/format';
import { useSeller } from '@/lib/session';
import { exportDataset, pollExport } from '@/lib/table-export';

type Range = '30' | '90' | 'ytd';

function rangeOf(r: Range, now = new Date()): { from: string; to: string } {
  const to = isoDay(now);
  if (r === 'ytd') return { from: `${now.getFullYear()}-01-01`, to };
  const d = new Date(now);
  d.setDate(d.getDate() - Number(r) + 1);
  return { from: isoDay(d), to };
}

const columns: ColumnDef<EarningsRow>[] = [
  { id: 'reference', accessorFn: (r) => r.reference, header: 'Reference', size: 130, meta: { kind: 'mono' } },
  { id: 'customerName', accessorFn: (r) => r.customerName, header: 'Booking', size: 220, meta: { kind: 'strong', label: 'Customer', exportValue: (r) => r.customerName }, cell: ({ row }) => <span className="flex flex-col"><strong>{row.original.customerName}</strong><span className="text-xs text-muted">{row.original.serviceName} · {row.original.reference}</span></span> },
  { id: 'serviceName', accessorFn: (r) => r.serviceName, header: 'Service', size: 180 },
  { id: 'startAt', accessorFn: (r) => r.startAt, header: 'Date', size: 120, meta: { kind: 'date' }, cell: ({ row }) => shortDate(row.original.startAt) },
  { id: 'status', accessorFn: (r) => r.statusLabel, header: 'Status', size: 130, meta: { kind: 'status' }, cell: ({ row }) => <StatusPill tone={toneOf(row.original.status)}>{row.original.statusLabel}</StatusPill> },
  { id: 'collectedMinor', accessorFn: (r) => r.collectedMinor, header: 'Collected', size: 110, meta: { kind: 'money', align: 'end' }, cell: ({ row }) => money(row.original.collectedMinor) },
  { id: 'commissionMinor', accessorFn: (r) => r.commissionMinor, header: 'Connection fee', size: 120, meta: { kind: 'money', align: 'end' }, cell: ({ row }) => (row.original.commissionMinor ? `−${money(row.original.commissionMinor)}` : '—') },
  { id: 'cardFeesMinor', accessorFn: (r) => r.cardFeesMinor, header: 'Stripe fees', size: 110, meta: { kind: 'money', align: 'end' }, cell: ({ row }) => (row.original.cardFeesMinor ? `−${money(row.original.cardFeesMinor)}` : '—') },
  { id: 'lateCancelFeeMinor', accessorFn: (r) => r.lateCancelFeeMinor, header: 'Late-cancel fee', size: 120, meta: { kind: 'money', align: 'end' }, cell: ({ row }) => (row.original.lateCancelFeeMinor ? <span className="text-danger">−{money(row.original.lateCancelFeeMinor)}</span> : '—') },
  { id: 'netMinor', accessorFn: (r) => r.netMinor, header: 'Net', size: 110, meta: { kind: 'money', align: 'end' }, cell: ({ row }) => <strong>{money(row.original.netMinor)}</strong> }
];

/** Outstanding provider fees (late-cancellation fees the platform could not net from a payout yet). */
export function ProviderFeesCard({ fees, currency }: { fees: ProviderFees; currency: string }) {
  if (!fees.available) {
    return (
      <Card title="Provider fees">
        <p className="m-0 text-sm text-muted">Provider fees can’t be shown right now. They are still deducted from your next payout as usual.</p>
      </Card>
    );
  }
  const open = fees.items.filter((f) => f.status === 'OUTSTANDING');
  return (
    <Card title="Provider fees" actions={<span className="text-sm text-muted">Collected so far {money(fees.collectedMinor, currency)}</span>}>
      {fees.outstandingMinor > 0 ? (
        <Notice tone="warning" title={`${money(fees.outstandingMinor, currency)} outstanding.`}>
          These fees are taken from your next payout. If your balance can’t cover them, we retry your card on file.
        </Notice>
      ) : (
        <p className="m-0 text-sm text-muted">No provider fees outstanding.</p>
      )}
      {fees.items.length ? (
        <ul aria-label="Provider fee history" className="m-0 flex list-none flex-col divide-y divide-border p-0 text-sm">
          {[...open, ...fees.items.filter((f) => f.status !== 'OUTSTANDING')].map((f) => (
            <li key={f.id} className="flex flex-wrap items-center gap-x-3 gap-y-1 py-2">
              <span className="min-w-0 flex-1 basis-48"><strong>{f.reason}</strong> <span className="text-muted">· {f.bookingReference} · {shortDate(f.createdAt)}</span></span>
              {f.status === 'OUTSTANDING' && f.nextAttemptAt ? <span className="text-xs text-muted">Next attempt {dateTime(f.nextAttemptAt)}</span> : null}
              <StatusPill tone={f.status === 'OUTSTANDING' ? 'warn' : 'good'}>{f.status === 'OUTSTANDING' ? 'Outstanding' : 'Collected'}</StatusPill>
              <strong className="w-20 text-right">{money(f.amountMinor, f.currency)}</strong>
            </li>
          ))}
        </ul>
      ) : null}
    </Card>
  );
}

/** Earnings: what customers paid, the AfroTransact connection fee, Stripe card fees, late-cancel fees and net, plus outstanding provider fees. */
export function EarningsView() {
  const t = useT();
  const api = sellerApi();
  const { orgId } = useSeller();
  const [range, setRange] = useState<Range>('30');
  const { from, to } = rangeOf(range);
  const q = useQuery(sellerQueries.earnings(api, orgId, from, to));
  const state = useTableState({ searchParams: useSearchParams() });
  const e = q.data;
  return (
    <ModulePage module="earnings">
      {/* The report period chooses which bookings are loaded; the table then has its one search over every column. */}
      <ChipGroup<Range> label="Period" value={range} onChange={setRange}
        options={[{ value: '30', label: 'Last 30 days' }, { value: '90', label: 'Last 90 days' }, { value: 'ytd', label: 'This year' }]} />
      {q.isPending ? <Spinner /> : q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : e ? (
        <>
          <StatStrip
            label="Earnings totals"
            items={[
              { id: 'paid', label: 'Customers paid', value: money(e.totals.collectedMinor, e.currency) },
              { id: 'fee', label: t('sc.earnings.connectionFeeTotal', { pct: String(e.commissionPercent) }), value: `−${money(e.totals.commissionMinor, e.currency)}` },
              { id: 'stripe', label: 'Stripe card fees', value: `−${money(e.totals.cardFeesMinor, e.currency)}` },
              { id: 'late', label: 'Late-cancel fees', value: `−${money(e.totals.lateCancelFeesMinor, e.currency)}`, tone: e.totals.lateCancelFeesMinor ? 'negative' : 'neutral' },
              { id: 'net', label: 'Net earnings', value: money(e.totals.netMinor, e.currency) },
              { id: 'held', label: 'Deposits held', value: money(e.totals.heldMinor, e.currency) }
            ]}
          />
          <p className="m-0 text-sm text-muted">Paid out every {e.payoutDay}. Deposits held are authorised but not yet captured.</p>
          {e.connectionFee ? <ConnectionFeesCard card={e.connectionFee} t={t} /> : null}
          <ProviderFeesCard fees={e.providerFees} currency={e.currency} />
          <DataTable<EarningsRow> id="seller.earnings" label="Earnings by booking" columns={columns} data={e.rows} getRowId={(r) => r.bookingId} rowLabel={(r) => r.reference}
            state={state} mode="client" fetching={q.isFetching} empty="No completed or charged bookings in this period." onExport={exportDataset} pollExport={pollExport} />
        </>
      ) : null}
    </ModulePage>
  );
}
