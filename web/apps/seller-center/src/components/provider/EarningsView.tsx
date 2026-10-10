'use client';

import { type Earnings, type EarningsRow, type ProviderFees, sellerQueries } from '@afrotransact/api-client';
import { Button } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import { useState } from 'react';

import { Card } from '@/components/ui/Card';
import { DataTable, FilterChips } from '@/components/ui/DataTable';
import { ModulePage } from '@/components/ui/ModulePage';
import { Notice } from '@/components/ui/Notice';
import { ErrorState, Spinner } from '@/components/ui/States';
import { StatusPill, toneOf } from '@/components/ui/StatusPill';
import { sellerApi } from '@/lib/api';
import { toCsv } from '@/lib/export/csv';
import { download } from '@/lib/export/table';
import { dateTime, isoDay, money, shortDate } from '@/lib/format';
import { useSeller } from '@/lib/session';

type Range = '30' | '90' | 'ytd';

function rangeOf(r: Range, now = new Date()): { from: string; to: string } {
  const to = isoDay(now);
  if (r === 'ytd') return { from: `${now.getFullYear()}-01-01`, to };
  const d = new Date(now);
  d.setDate(d.getDate() - Number(r) + 1);
  return { from: isoDay(d), to };
}

const columns: ColumnDef<EarningsRow>[] = [
  { id: 'booking', header: 'Booking', size: 24, cell: ({ row }) => <span className="flex flex-col"><strong>{row.original.customerName}</strong><span className="text-xs text-muted">{row.original.serviceName} · {row.original.reference}</span></span> },
  { id: 'date', header: 'Date', size: 12, cell: ({ row }) => shortDate(row.original.startAt) },
  { id: 'status', header: 'Status', size: 12, cell: ({ row }) => <StatusPill tone={toneOf(row.original.status)}>{row.original.statusLabel}</StatusPill> },
  { id: 'collected', header: 'Collected', size: 11, cell: ({ row }) => money(row.original.collectedMinor) },
  { id: 'commission', header: 'Commission', size: 11, cell: ({ row }) => (row.original.commissionMinor ? `−${money(row.original.commissionMinor)}` : '—') },
  { id: 'card', header: 'Stripe fees', size: 10, cell: ({ row }) => (row.original.cardFeesMinor ? `−${money(row.original.cardFeesMinor)}` : '—') },
  { id: 'late', header: 'Late-cancel fee', size: 10, cell: ({ row }) => (row.original.lateCancelFeeMinor ? <span className="text-danger">−{money(row.original.lateCancelFeeMinor)}</span> : '—') },
  { id: 'net', header: 'Net', size: 10, cell: ({ row }) => <strong>{money(row.original.netMinor)}</strong> }
];

export function earningsCsv(e: Earnings): string {
  return toCsv({
    title: 'Earnings',
    headers: ['Reference', 'Customer', 'Service', 'Date', 'Status', 'Collected', 'Commission', 'Stripe fees', 'Late-cancel fee', 'Net'],
    rows: e.rows.map((r) => [r.reference, r.customerName, r.serviceName, r.startAt.slice(0, 10), r.statusLabel, r.collectedMinor / 100, r.commissionMinor / 100, r.cardFeesMinor / 100,
      r.lateCancelFeeMinor / 100, r.netMinor / 100])
  });
}

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

/** Earnings: what customers paid, AfroTransact commission, Stripe card fees, late-cancel fees and net, plus outstanding provider fees. */
export function EarningsView() {
  const api = sellerApi();
  const { orgId } = useSeller();
  const [range, setRange] = useState<Range>('30');
  const { from, to } = rangeOf(range);
  const q = useQuery(sellerQueries.earnings(api, orgId, from, to));
  const e = q.data;
  return (
    <ModulePage module="earnings"
      actions={e ? <Button variant="neutral" onClick={() => download(new Blob([earningsCsv(e)], { type: 'text/csv;charset=utf-8' }), `earnings-${from}-to-${to}.csv`)}>Export CSV</Button> : null}>
      <FilterChips<Range> label="Period" value={range} onChange={(v) => setRange(v || '30')}
        options={[{ value: '30', label: 'Last 30 days' }, { value: '90', label: 'Last 90 days' }, { value: 'ytd', label: 'This year' }]} />
      {q.isPending ? <Spinner /> : q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : e ? (
        <>
          <dl aria-label="Earnings totals" className="m-0 grid grid-cols-[repeat(auto-fit,minmax(min(100%,150px),1fr))] gap-3">
            {[
              ['Customers paid', money(e.totals.collectedMinor, e.currency), ''],
              [`Commission (${e.commissionPercent}%)`, `−${money(e.totals.commissionMinor, e.currency)}`, ''],
              ['Stripe card fees', `−${money(e.totals.cardFeesMinor, e.currency)}`, ''],
              ['Late-cancel fees', `−${money(e.totals.lateCancelFeesMinor, e.currency)}`, e.totals.lateCancelFeesMinor ? 'text-danger' : ''],
              ['Net earnings', money(e.totals.netMinor, e.currency), ''],
              ['Deposits held', money(e.totals.heldMinor, e.currency), 'text-muted']
            ].map(([k, v, cls]) => (
              <div key={k} className="flex flex-col gap-1 rounded-md border border-border p-4">
                <dt className="text-xs font-semibold text-muted">{k}</dt>
                <dd className={`m-0 text-2xl font-bold ${cls ?? ''}`}>{v}</dd>
              </div>
            ))}
          </dl>
          <p className="m-0 text-sm text-muted">Paid out every {e.payoutDay}. Deposits held are authorised but not yet captured.</p>
          <ProviderFeesCard fees={e.providerFees} currency={e.currency} />
          <DataTable label="Earnings by booking" columns={columns} data={e.rows} getRowId={(r) => r.bookingId} loading={q.isFetching} empty="No completed or charged bookings in this period." />
        </>
      ) : null}
    </ModulePage>
  );
}
