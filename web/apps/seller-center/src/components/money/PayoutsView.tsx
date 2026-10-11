'use client';

import { type Payout, sellerKeys, sellerQueries } from '@afrotransact/api-client';
import { DataTable, useTableState } from '@afrotransact/data-table';
import { Button, Dialog, TextField, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import { useSearchParams } from 'next/navigation';
import { useRef, useState } from 'react';

import { Card } from '@/components/ui/Card';
import { ModulePage } from '@/components/ui/ModulePage';
import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { StatusPill, toneOf } from '@/components/ui/StatusPill';
import { sellerApi } from '@/lib/api';
import { idempotencyKey, money, shortDate, toMinor } from '@/lib/format';
import { useSeller } from '@/lib/session';
import { exportDataset, pollExport } from '@/lib/table-export';

const columns: ColumnDef<Payout>[] = [
  { id: 'id', accessorFn: (p) => p.id, header: 'Payout', size: 200, meta: { kind: 'mono' }, cell: ({ row }) => <span className="font-mono text-xs">{row.original.id}</span> },
  { id: 'requestedAt', accessorFn: (p) => p.requestedAt, header: 'Requested', size: 140, meta: { kind: 'date' }, cell: ({ row }) => shortDate(row.original.requestedAt) },
  { id: 'amountMinor', accessorFn: (p) => p.amountMinor, header: 'Amount', size: 130, meta: { kind: 'money', align: 'end' }, cell: ({ row }) => money(row.original.amountMinor, row.original.currency) },
  { id: 'status', accessorFn: (p) => p.status, header: 'Status', size: 130, meta: { kind: 'status' }, cell: ({ row }) => <StatusPill tone={toneOf(row.original.status)}>{row.original.status.replace('_', ' ').toLowerCase()}</StatusPill> },
  { id: 'note', accessorFn: (p) => p.holdReason ?? p.failureMessage ?? undefined, header: 'Note', size: 260, cell: ({ row }) => <span className="text-xs text-muted">{row.original.holdReason ?? row.original.failureMessage ?? ''}</span> }
];

/** Payouts: available / on hold / on the way, holds per order, history and "Request payout" (payments). */
export function PayoutsView() {
  const api = sellerApi();
  const qc = useQueryClient();
  const toast = useToast();
  const { orgId, can } = useSeller();
  const q = useQuery(sellerQueries.payouts(api, orgId));
  const state = useTableState({ searchParams: useSearchParams() });
  const [open, setOpen] = useState(false);
  const [amount, setAmount] = useState('');
  const [error, setError] = useState<string | null>(null);
  const key = useRef(idempotencyKey());
  const request = useMutation({
    mutationFn: (body: { amountMinor?: number; all?: boolean }) => api.requestPayout(body, key.current),
    onSuccess: (p) => {
      key.current = idempotencyKey();
      setOpen(false);
      void qc.invalidateQueries({ queryKey: sellerKeys.payouts(orgId) });
      toast.show(`Payout of ${money(p.amountMinor, p.currency)} requested`);
    },
    onError: (e) => setError(problemMessage(e))
  });
  if (q.isPending) return <ModulePage module="payouts"><Spinner /></ModulePage>;
  if (q.isError) return <ModulePage module="payouts"><ErrorState error={q.error} onRetry={() => void q.refetch()} /></ModulePage>;
  const b = q.data.balance;
  const canRequest = can('payouts') && b.canRequestToday && !b.payoutsPaused && b.availableMinor >= b.minimumPayoutMinor;
  return (
    <ModulePage
      module="payouts"
      actions={
        can('payouts') ? (
          <Button disabled={!canRequest} onClick={() => { setError(null); setAmount(''); setOpen(true); }}>
            Request payout
          </Button>
        ) : null
      }
    >
      <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,200px),1fr))] gap-3">
        {[
          ['Available to request', money(b.availableMinor, b.currency), b.payoutsPaused ? 'Paused — see above' : `Minimum ${money(b.minimumPayoutMinor, b.currency)} · one request a day`],
          ['Still settling', money(b.onHoldMinor, b.currency), 'Released after delivery or completion'],
          ['On the way', money(b.requestedOrInTransitMinor, b.currency), 'Requested or in transit to your bank'],
          ['Paid out', money(b.paidMinor, b.currency), 'All time'],
          ...(b.providerFeesMinor ? [['Provider fees', money(b.providerFeesMinor, b.currency), 'Late cancellations debited from your balance']] : [])
        ].map(([label, value, detail]) => (
          <div key={label} className="flex flex-col gap-1 rounded-md border border-border p-4">
            <span className="text-xs font-semibold text-muted">{label}</span>
            <strong className="text-2xl">{value}</strong>
            <span className="text-xs text-muted">{detail}</span>
          </div>
        ))}
      </div>
      {b.holds.length ? (
        <Card title="On hold per order">
          <ul className="m-0 flex list-none flex-col gap-1 p-0 text-sm">
            {b.holds.map((h) => (
              <li key={h.orderId} className="flex gap-2">
                <span className="flex-1 font-mono text-xs">{h.orderId}</span>
                <span className="text-muted">{h.releaseAt ? `Releases ${shortDate(h.releaseAt)}` : 'Releases after delivery'}</span>
                <strong>{money(h.amountMinor, b.currency)}</strong>
              </li>
            ))}
          </ul>
        </Card>
      ) : null}
      <h2 className="m-0 text-lg font-semibold">History</h2>
      {q.data.historyAvailable ? (
        <DataTable<Payout> id="seller.payouts" label="Payouts" columns={columns} data={q.data.payouts} getRowId={(p) => p.id} rowLabel={(p) => p.id}
          state={state} mode="client" fetching={q.isFetching} empty="No payouts yet." onExport={exportDataset} pollExport={pollExport} />
      ) : (
        <p className="m-0 text-sm text-muted">Payout history isn’t available for your role yet.</p>
      )}
      <Dialog open={open} onOpenChange={setOpen} title="Request a payout" description={`Up to ${money(b.availableMinor, b.currency)} is available. No fee; it reaches your bank in 1–2 business days.`}>
        <form
          className="flex flex-col gap-3"
          onSubmit={(e) => {
            e.preventDefault();
            const minor = toMinor(amount);
            if (!Number.isFinite(minor) || minor < b.minimumPayoutMinor) return setError(`Enter at least ${money(b.minimumPayoutMinor, b.currency)}`);
            if (minor > b.availableMinor) return setError('That’s more than is available');
            request.mutate({ amountMinor: minor });
          }}
        >
          <TextField label="Amount (USD)" inputMode="decimal" value={amount} error={error} onChange={(e) => setAmount(e.target.value)} />
          <div className="flex flex-wrap gap-2">
            <Button type="submit" loading={request.isPending}>Request payout</Button>
            <Button variant="neutral" onClick={() => request.mutate({ all: true })} disabled={request.isPending}>
              Request everything ({money(b.availableMinor, b.currency)})
            </Button>
          </div>
        </form>
      </Dialog>
    </ModulePage>
  );
}
