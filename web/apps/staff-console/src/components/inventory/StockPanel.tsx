'use client';

import { daysToExpiry, expiryTone, fefoOrder, inventoryHubQueries, type Lot } from '@afrotransact/api-client';
import { DataTable, useTableState } from '@afrotransact/data-table';
import { Button, Select, Sheet, TextField } from '@afrotransact/ui';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import { useSearchParams } from 'next/navigation';
import { useMemo, useState } from 'react';

import { ErrorState, Spinner } from '@/components/ui/States';
import { StatusPill } from '@/components/ui/StatusPill';
import { useT } from '@/i18n/t';
import { dateTime, humanize, idempotencyKey, money, shortDate } from '@/lib/format';
import { exportDataset } from '@/lib/table-export';

import { EXPIRY_CLASS, expiryLabel, smallButton, todayIso, toneOf, useHub, useHubAccess, useHubMutation, useParam } from './shared';

/** FEFO pick rank of each sellable lot within its SKU × FC (1 = picked next), as inventory reserves. */
export function pickRanks(lots: Lot[]): Map<string, number> {
  const out = new Map<string, number>();
  const groups = new Map<string, Lot[]>();
  lots.forEach((l) => groups.set(`${l.sku}|${l.nodeId}`, [...(groups.get(`${l.sku}|${l.nodeId}`) ?? []), l]));
  groups.forEach((g) => fefoOrder(g).forEach((l, i) => out.set(l.id, i + 1)));
  return out;
}

type Action = 'quarantine' | 'release' | 'transfer' | 'write-off' | 'adjust' | null;

/** One lot: batch, dates, receipt, movements trace, related lots and its actions (quarantine, release, transfer, write-off, count). */
export function LotSheet({ id, onClose }: { id: string | null; onClose: () => void }) {
  const t = useT();
  const api = useHub();
  const { inventoryEdit, inventoryApprove } = useHubAccess();
  const trace = useQuery({ ...inventoryHubQueries.lotTrace(api, id ?? ''), enabled: id !== null });
  const nodes = useQuery(inventoryHubQueries.nodes(api));
  const siblings = useQuery({ ...inventoryHubQueries.lots(api, { sku: trace.data?.lot.sku, page: 0, size: 200 }), enabled: Boolean(trace.data) });
  const [action, setAction] = useState<Action>(null);
  const [reason, setReason] = useState('');
  const [qty, setQty] = useState('');
  const [toNode, setToNode] = useState('');
  const [toBin, setToBin] = useState('');
  const [error, setError] = useState<string | null>(null);
  const lot = trace.data?.lot;
  const reset = () => {
    setAction(null);
    setReason('');
    setQty('');
    setError(null);
  };
  const run = useHubMutation(
    async () => {
      if (!lot) throw new Error('No lot');
      switch (action) {
        case 'quarantine':
        case 'release':
          return api.lotAction(lot.id, action, reason.trim());
        case 'write-off':
          return api.writeOff(lot.id, qty ? Number(qty) : undefined, reason.trim(), idempotencyKey());
        case 'transfer':
          return (await api.transfer(lot.id, { toNodeId: toNode, toBin: toBin.trim() || undefined, qty: Number(qty), reason: reason.trim() }, idempotencyKey())).from;
        default:
          return api.adjustLot(lot.id, Number(qty), reason.trim(), idempotencyKey());
      }
    },
    (l) => `Lot ${l.lotNo}: ${humanize(action ?? 'updated')} done.`,
    reset
  );
  const rank = siblings.data ? pickRanks(siblings.data.items.filter((l) => l.nodeId === lot?.nodeId)).get(lot?.id ?? '') : undefined;
  const days = daysToExpiry(lot?.expDate, todayIso());
  return (
    <Sheet open={id !== null} onOpenChange={(o) => !o && onClose()} title={lot ? `Lot ${lot.lotNo}` : 'Lot'} className="w-[min(100vw,640px)]">
      {trace.isPending ? <Spinner /> : null}
      {trace.isError ? <ErrorState error={trace.error} onRetry={() => void trace.refetch()} /> : null}
      {lot && trace.data ? (
        <div className="flex flex-col gap-4" data-testid="lot-sheet">
          <div className="flex flex-wrap items-center gap-2">
            <StatusPill tone={toneOf(lot.status)}>{humanize(lot.status)}</StatusPill>
            <span className={EXPIRY_CLASS[expiryTone(days)]}>{expiryLabel(days)}</span>
            {rank ? <StatusPill tone="brand">{rank === 1 ? 'Picks next (FEFO)' : `FEFO pick #${rank}`}</StatusPill> : null}
            {lot.markdownFlag !== 'NONE' ? <StatusPill tone="warn">{lot.markdownFlag === 'WITHIN_30' ? 'Markdown: ≤ 30 days' : 'Markdown: ≤ 60 days'}</StatusPill> : null}
          </div>
          <dl className="m-0 grid grid-cols-[minmax(0,2fr)_minmax(0,3fr)] gap-x-3 gap-y-1 text-sm">
            {[
              ['SKU', lot.sku],
              ['Batch / lot', lot.lotNo],
              ['Fulfilment centre · bin', `${lot.nodeId} · ${lot.bin ?? '—'}`],
              ['Owner', lot.ownerOrg ?? '—'],
              ['On hand · reserved · available', `${lot.qtyOnHand} · ${lot.qtyReserved} · ${lot.qtyAvailable}`],
              ['Made', shortDate(lot.mfgDate)],
              ['Expires', shortDate(lot.expDate)],
              ['Received', `${dateTime(lot.receivedAt)} · ${lot.receiptRef ?? '—'}`],
              [t('st.lot.costPaid'), `${money(lot.unitLandedCostMinor, lot.currency)} / unit`],
              ...(lot.currency === 'USD'
                ? []
                : [
                    [t('st.lot.rate'), t('st.lot.rateValue', { currency: lot.currency, rate: String(lot.fxRateToUsd), date: shortDate(lot.fxRateDate), source: t(`st.lot.source.${lot.fxRateSource}`) })],
                    [t('st.lot.costUsd'), `${money(lot.unitCostUsdMinor)} / unit`]
                  ]),
              ...(lot.costComponents && (lot.costComponents.freightMinor || lot.costComponents.dutyMinor || lot.costComponents.otherMinor)
                ? [[t('st.lot.components'), t('st.lot.componentsValue', {
                    goods: money(lot.costComponents.goodsMinor, lot.currency), freight: money(lot.costComponents.freightMinor, lot.currency),
                    duty: money(lot.costComponents.dutyMinor, lot.currency), other: money(lot.costComponents.otherMinor, lot.currency)
                  })]]
                : []),
              ['Reason', lot.reason ?? '—']
            ].map(([k, v]) => (
              <div key={k} className="contents">
                <dt className="text-muted">{k}</dt>
                <dd className="m-0 [overflow-wrap:anywhere]">{v}</dd>
              </div>
            ))}
          </dl>
          {inventoryEdit ? (
            <div className="flex flex-wrap gap-2" aria-label="Lot actions">
              {lot.status === 'SELLABLE' ? <button type="button" className={smallButton} onClick={() => setAction('quarantine')}>Quarantine</button> : null}
              {lot.status === 'QUARANTINE' || lot.status === 'DAMAGED' ? <button type="button" className={smallButton} onClick={() => setAction('release')}>Release to sale</button> : null}
              {lot.status === 'SELLABLE' && lot.qtyAvailable > 0 ? <button type="button" className={smallButton} onClick={() => setAction('transfer')}>Transfer</button> : null}
              {lot.status !== 'WRITTEN_OFF' ? <button type="button" className={smallButton} onClick={() => setAction('adjust')}>Count</button> : null}
              {inventoryApprove && lot.status !== 'WRITTEN_OFF' && lot.qtyOnHand > lot.qtyReserved ? <button type="button" className={smallButton} onClick={() => setAction('write-off')}>Write off</button> : null}
            </div>
          ) : null}
          {action ? (
            <form
              className="flex flex-col gap-2 rounded-md border border-border p-3"
              aria-label={humanize(action)}
              onSubmit={(e) => {
                e.preventDefault();
                if (!reason.trim()) return setError('Give a reason (it is kept in the movement log)');
                if ((action === 'transfer' || action === 'adjust') && !/^\d+$/.test(qty)) return setError('Enter whole units');
                if (action === 'transfer' && !toNode) return setError('Pick the destination fulfilment centre');
                setError(null);
                run.mutate(undefined);
              }}
            >
              <strong className="text-sm">{humanize(action)}</strong>
              {action === 'transfer' ? (
                <>
                  <Select label="To fulfilment centre" value={toNode} onChange={(e) => setToNode(e.target.value)} placeholder="Choose…" options={(nodes.data ?? []).filter((n) => n.id !== lot.nodeId).map((n) => ({ value: n.id, label: n.name }))} />
                  <TextField label="To bin (optional)" value={toBin} onChange={(e) => setToBin(e.target.value)} />
                </>
              ) : null}
              {action === 'transfer' || action === 'adjust' || action === 'write-off' ? (
                <TextField label={action === 'adjust' ? 'Counted units' : action === 'write-off' ? 'Units (empty = all unreserved)' : 'Units'} inputMode="numeric" value={qty} onChange={(e) => setQty(e.target.value)} />
              ) : null}
              <TextField label="Reason" value={reason} onChange={(e) => setReason(e.target.value)} />
              {error ? <span role="alert" className="text-sm text-danger">{error}</span> : null}
              <div className="flex gap-2">
                <Button type="submit" size="sm" loading={run.isPending}>Confirm</Button>
                <Button type="button" size="sm" variant="neutral" onClick={reset}>Cancel</Button>
              </div>
            </form>
          ) : null}
          <section aria-label="Movements" className="flex flex-col gap-1">
            <h3 className="m-0 text-sm font-bold">Movements (recall trace)</h3>
            <ul className="m-0 flex list-none flex-col gap-1 p-0 text-xs">
              {trace.data.movements.map((m) => (
                <li key={m.id} className="flex flex-wrap gap-2 border-t border-border pt-1">
                  <span className="text-muted">{dateTime(m.occurredAt)}</span>
                  <strong>{humanize(m.type)}</strong>
                  <span>{m.qty > 0 ? `+${m.qty}` : m.qty}</span>
                  <span className="text-muted">{m.reference}</span>
                  <span className="text-muted">{m.actor}</span>
                  {m.reason ? <span>{m.reason}</span> : null}
                </li>
              ))}
            </ul>
          </section>
          {trace.data.relatedLots.length ? (
            <section aria-label="Same batch elsewhere" className="flex flex-col gap-1 text-xs">
              <h3 className="m-0 text-sm font-bold">Same batch elsewhere</h3>
              {trace.data.relatedLots.map((r) => (
                <span key={r.id}>
                  <span className="font-mono">{r.lotNo}</span> · {r.nodeId} · {r.qtyOnHand} units · {humanize(r.status)}
                </span>
              ))}
            </section>
          ) : null}
          {trace.data.orders.length ? (
            <section aria-label="Orders" className="flex flex-col gap-1 text-xs">
              <h3 className="m-0 text-sm font-bold">Orders holding or shipped from this lot</h3>
              {trace.data.orders.map((o) => (
                <span key={o.orderId}>
                  {o.orderId} · {o.qty ?? ''} units · {o.status ?? ''}
                </span>
              ))}
            </section>
          ) : null}
        </div>
      ) : null}
    </Sheet>
  );
}

/** Stock & lots: every batch with FC, bin, dates, days left, FEFO pick order and status; the lot sheet. */
export function StockPanel() {
  const api = useHub();
  const qc = useQueryClient();
  const params = useSearchParams();
  const state = useTableState({ searchParams: params, prefix: 'lots', defaultPageSize: 25 });
  const [lotId, setLotId] = useParam('lot');
  const { inventoryEdit } = useHubAccess();
  const query = useQuery(inventoryHubQueries.lots(api, { page: 0, size: 200 }));
  const lots = useMemo(() => query.data?.items ?? [], [query.data]);
  const ranks = useMemo(() => pickRanks(lots), [lots]);
  const today = todayIso();
  const quarantine = useHubMutation((ids: string[]) => Promise.all(ids.map((i) => api.lotAction(i, 'quarantine', 'Held from the lots table'))), (r) => `${r.length} lots quarantined.`);
  const columns = useMemo<ColumnDef<Lot>[]>(
    () => [
      { id: 'sku', accessorFn: (l) => l.sku, header: 'SKU', size: 160, meta: { kind: 'mono' }, cell: ({ row }) => <span className="font-mono text-xs">{row.original.sku}</span> },
      { id: 'lotNo', accessorFn: (l) => l.lotNo, header: 'Lot', size: 140, meta: { kind: 'mono' }, cell: ({ row }) => <span className="font-mono text-xs font-semibold">{row.original.lotNo}</span> },
      { id: 'nodeId', accessorFn: (l) => l.nodeId, header: 'FC', size: 90 },
      { id: 'bin', accessorFn: (l) => l.bin ?? '', header: 'Bin', size: 90 },
      { id: 'qtyOnHand', accessorFn: (l) => l.qtyOnHand, header: 'On hand', size: 90, meta: { kind: 'number', align: 'end' } },
      { id: 'qtyReserved', accessorFn: (l) => l.qtyReserved, header: 'Reserved', size: 90, meta: { kind: 'number', align: 'end' } },
      { id: 'qtyAvailable', accessorFn: (l) => l.qtyAvailable, header: 'Available', size: 90, meta: { kind: 'number', align: 'end' } },
      { id: 'mfgDate', accessorFn: (l) => l.mfgDate ?? '', header: 'Made', size: 110, meta: { kind: 'date' }, cell: ({ row }) => shortDate(row.original.mfgDate) },
      { id: 'expDate', accessorFn: (l) => l.expDate ?? '', header: 'Expires', size: 110, meta: { kind: 'date' }, cell: ({ row }) => shortDate(row.original.expDate) },
      {
        id: 'days', accessorFn: (l) => daysToExpiry(l.expDate, today) ?? 99_999, header: 'Days left', size: 110, meta: { kind: 'number', align: 'end' },
        cell: ({ row }) => {
          const d = daysToExpiry(row.original.expDate, today);
          return <span className={EXPIRY_CLASS[expiryTone(d)]}>{expiryLabel(d)}</span>;
        }
      },
      { id: 'pick', accessorFn: (l) => (ranks.get(l.id) ? `#${ranks.get(l.id)}` : '—'), header: 'FEFO pick', size: 100, cell: ({ row }) => (ranks.get(row.original.id) === 1 ? <StatusPill tone="brand">Next</StatusPill> : (ranks.get(row.original.id) ? `#${ranks.get(row.original.id)}` : '—')) },
      { id: 'status', accessorFn: (l) => humanize(l.status), header: 'Status', size: 120, meta: { kind: 'status' }, cell: ({ row }) => <StatusPill tone={toneOf(row.original.status)}>{humanize(row.original.status)}</StatusPill> }
    ],
    [ranks, today]
  );
  return (
    <>
      <p className="m-0 text-sm text-muted">Picking: FEFO (first-expiry, first-out) within each SKU and fulfilment centre · costing: each lot at its landed cost.</p>
      <DataTable<Lot>
        id="staff.inventory.lots"
        label="Lots"
        columns={columns}
        data={lots}
        getRowId={(l) => l.id}
        rowLabel={(l) => `${l.sku} lot ${l.lotNo}`}
        rowTestId="lot-row"
        state={state}
        mode="client"
        loading={query.isPending}
        fetching={query.isFetching}
        error={query.isError ? <ErrorState error={query.error} onRetry={() => void query.refetch()} /> : undefined}
        onRowClick={(l) => setLotId(l.id)}
        activeRowId={lotId}
        searchPlaceholder="Search SKU, lot, FC, bin, status…"
        bulkActions={
          inventoryEdit
            ? (rows, clear) => (
                <button type="button" className={smallButton} disabled={quarantine.isPending} onClick={() => quarantine.mutate(rows.filter((r) => r.status === 'SELLABLE').map((r) => r.id), { onSuccess: clear })}>
                  Quarantine
                </button>
              )
            : undefined
        }
        empty="No stock yet: lots are created when shipments are received."
        onExport={(req) => exportDataset(qc, req)}
        exportsHref="/exports"
      />
      <LotSheet id={lotId} onClose={() => setLotId(null)} />
    </>
  );
}
