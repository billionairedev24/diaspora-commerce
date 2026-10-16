'use client';

import {
  daysToExpiry,
  expiryTone,
  fefoOrder,
  INBOUND_TIMELINE,
  type InboundPlanInput,
  type InboundShipment,
  inboundStep,
  inventoryHubKeys,
  inventoryHubQueries,
  type Lot,
  sellerKeys,
  sellerQueries,
  validateInboundLine
} from '@afrotransact/api-client';
import { DataTable, useTableState } from '@afrotransact/data-table';
import { Barcode, Button, Checkbox, cx, Select, Sheet, SheetTableScroll, TextField, useToast } from '@afrotransact/ui';
import { Plus, Trash } from '@phosphor-icons/react/ssr';
import { type MutationFunction, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useCallback, useMemo, useState } from 'react';

import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { StatusPill, type Tone } from '@/components/ui/StatusPill';
import { sellerApi } from '@/lib/api';
import { idempotencyKey, shortDate } from '@/lib/format';
import { useSeller } from '@/lib/session';
import { exportDataset, pollExport } from '@/lib/table-export';

const STATUS: Record<string, [string, Tone]> = {
  WORKING: ['Draft', 'info'], SHIPPED: ['Shipped', 'info'], RECEIVING: ['Receiving', 'warn'], CLOSED: ['Received', 'good'], CANCELLED: ['Cancelled', 'bad'],
  SELLABLE: ['Available', 'good'], QUARANTINE: ['Quarantine', 'warn'], DAMAGED: ['Damaged', 'warn'], EXPIRED: ['Expired', 'bad'], WRITTEN_OFF: ['Written off', 'bad']
};
const pill = (s: string) => <StatusPill tone={STATUS[s]?.[1] ?? 'info'}>{STATUS[s]?.[0] ?? s}</StatusPill>;
const smallButton = 'inline-flex h-8 items-center gap-1 rounded-pill border border-border-strong bg-surface px-3 text-sm font-semibold hover:bg-surface-muted';

function today(): string {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

function useParam(name: string): [string | null, (v: string | null) => void] {
  const params = useSearchParams();
  const router = useRouter();
  const pathname = usePathname();
  const set = useCallback(
    (v: string | null) => {
      const next = new URLSearchParams(params.toString());
      if (v) next.set(name, v);
      else next.delete(name);
      router.replace(`${pathname}?${next.toString()}`, { scroll: false });
    },
    [name, params, pathname, router]
  );
  return [params.get(name), set];
}

function useFbaMutation<V, D>(fn: MutationFunction<D, V>, ok: (d: D) => string, onDone?: (d: D) => void) {
  const qc = useQueryClient();
  const toast = useToast();
  const { orgId } = useSeller();
  return useMutation({
    mutationFn: fn,
    onSuccess: (d) => {
      void qc.invalidateQueries({ queryKey: inventoryHubKeys.all });
      void qc.invalidateQueries({ queryKey: sellerKeys.org(orgId) });
      toast.show(ok(d));
      onDone?.(d);
    },
    onError: (e) => toast.show(problemMessage(e), 'error')
  });
}

export function Timeline({ shipment }: { shipment: InboundShipment }) {
  const step = inboundStep(shipment, today());
  if (step < 0) return pill('CANCELLED');
  return (
    <ol className="m-0 flex list-none flex-wrap gap-1 p-0" aria-label="Shipment timeline">
      {INBOUND_TIMELINE.map((label, i) => (
        <li key={label} aria-current={i === step ? 'step' : undefined} className={cx('rounded-pill px-2.5 py-1 text-xs font-semibold', i < step ? 'bg-success-bg text-success' : i === step ? 'bg-ink-1000 text-gold-500' : 'bg-surface-muted text-muted')}>
          {label}
        </li>
      ))}
    </ol>
  );
}

interface Draft {
  key: string;
  atin: string;
  qty: string;
  casePack: string;
  lotNo: string;
  mfgDate: string;
  expDate: string;
  perishable: boolean;
}
const blank = (): Draft => ({ key: idempotencyKey(), atin: '', qty: '', casePack: '', lotNo: '', mfgDate: '', expDate: '', perishable: false });

/** Field errors of a seller's plan, keyed `<line>.<field>`. */
export function planErrors(lines: Draft[], todayIso: string): Record<string, string> {
  const e: Record<string, string> = {};
  lines.forEach((l, i) => {
    if (!/^AT[0-9A-Z]{8}$/.test(l.atin.trim().toUpperCase())) e[`${i}.atin`] = 'A variant ATIN (AT + 8 letters or digits)';
    Object.entries(validateInboundLine(l, { expiryRequired: l.perishable, today: todayIso })).forEach(([k, v]) => {
      e[`${i}.${k}`] = v;
    });
  });
  return e;
}

/** Send to AfroTransact: a new inbound plan with the batch of every line. */
function PlanForm({ onDone }: { onDone: (s: InboundShipment) => void }) {
  const api = sellerApi();
  const { orgId } = useSeller();
  const nodes = useQuery(inventoryHubQueries.nodes(api.fba));
  const listings = useQuery(sellerQueries.listings(api, orgId, { q: '', page: 0, size: 100, sort: [] }));
  const [nodeId, setNodeId] = useState('');
  const [eta, setEta] = useState('');
  const [name, setName] = useState('');
  const [lines, setLines] = useState<Draft[]>([blank()]);
  const [shown, setShown] = useState(false);
  const [key] = useState(idempotencyKey);
  const errors = planErrors(lines, today());
  const create = useFbaMutation((body: InboundPlanInput) => api.fba.createPlan(body, key), (s) => `${s.number} created: print the labels, then mark it shipped.`, onDone);
  const set = (i: number, patch: Partial<Draft>) => setLines((ls) => ls.map((l, k) => (k === i ? { ...l, ...patch } : l)));
  const err = (k: string) => (shown ? errors[k] : undefined);
  return (
    <form
      className="flex flex-col gap-3"
      noValidate
      onSubmit={(e) => {
        e.preventDefault();
        setShown(true);
        if (Object.keys(errors).length) return;
        create.mutate({
          name: name.trim() || undefined,
          nodeId: nodeId || undefined,
          expectedArrival: eta || undefined,
          lines: lines.map((l) => ({ atin: l.atin.trim().toUpperCase(), qty: Number(l.qty), casePack: l.casePack ? Number(l.casePack) : undefined, lotNo: l.lotNo.trim() || undefined, mfgDate: l.mfgDate || undefined, expDate: l.expDate || undefined }))
        });
      }}
    >
      <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,200px),1fr))] gap-2">
        <TextField label="Shipment name" value={name} onChange={(e) => setName(e.target.value)} placeholder="October restock" />
        <Select label="Fulfilment centre" value={nodeId} onChange={(e) => setNodeId(e.target.value)} options={[{ value: '', label: 'Assign the best one for me' }, ...(nodes.data ?? []).map((n) => ({ value: n.id, label: n.name }))]} />
        <TextField label="Expected arrival" type="date" min={today()} value={eta} onChange={(e) => setEta(e.target.value)} />
      </div>
      {lines.map((l, i) => (
        <fieldset key={l.key} className="m-0 grid grid-cols-[repeat(auto-fit,minmax(min(100%,150px),1fr))] gap-2 rounded-md border border-border p-3" aria-label={`Line ${i + 1}`}>
          <legend className="px-1 text-xs font-bold text-muted">Line {i + 1}</legend>
          <Select
            label="Your listing"
            value={l.atin}
            onChange={(e) => set(i, { atin: e.target.value })}
            options={[{ value: '', label: 'Pick a listing…' }, ...(listings.data?.items ?? []).filter((x) => x.atin).map((x) => ({ value: x.atin ?? '', label: `${x.title} (${x.atin ?? ''})` }))]}
            className="col-span-full sm:col-span-2"
          />
          <TextField label="Variant ATIN" value={l.atin} onChange={(e) => set(i, { atin: e.target.value })} error={err(`${i}.atin`)} />
          <TextField label="Units" inputMode="numeric" value={l.qty} onChange={(e) => set(i, { qty: e.target.value })} error={err(`${i}.qty`)} />
          <TextField label="Units per case" inputMode="numeric" value={l.casePack} onChange={(e) => set(i, { casePack: e.target.value })} error={err(`${i}.casePack`)} />
          <TextField label={l.perishable ? 'Lot / batch' : 'Lot / batch (optional)'} value={l.lotNo} onChange={(e) => set(i, { lotNo: e.target.value })} error={err(`${i}.lotNo`)} />
          <TextField label="Made on" type="date" value={l.mfgDate} onChange={(e) => set(i, { mfgDate: e.target.value })} error={err(`${i}.mfgDate`)} />
          <TextField label={l.perishable ? 'Expires on' : 'Expires on (optional)'} type="date" value={l.expDate} onChange={(e) => set(i, { expDate: e.target.value })} error={err(`${i}.expDate`)} />
          <Checkbox label="Food or perishable (expiry required)" checked={l.perishable} onChange={(e) => set(i, { perishable: e.target.checked })} />
          {lines.length > 1 ? (
            <button type="button" className={cx(smallButton, 'self-end')} onClick={() => setLines((ls) => ls.filter((_, k) => k !== i))}>
              <Trash size={14} aria-hidden /> Remove
            </button>
          ) : null}
        </fieldset>
      ))}
      <button type="button" className={cx(smallButton, 'self-start')} onClick={() => setLines((ls) => [...ls, blank()])}>
        <Plus size={14} aria-hidden /> Add a line
      </button>
      <Button type="submit" loading={create.isPending}>
        Create shipping plan
      </Button>
    </form>
  );
}

/** One plan: timeline, the batch of each line, labels, tracking, shipping and what the FC received. */
function PlanSheet({ id, onClose }: { id: string | null; onClose: () => void }) {
  const api = sellerApi();
  const { can } = useSeller();
  const q = useQuery({ ...inventoryHubQueries.shipment(api.fba, id ?? ''), enabled: id !== null });
  const s = q.data;
  const [deliveryMethod, setDeliveryMethod] = useState<'carrier' | 'self'>('carrier');
  const [carrier, setCarrier] = useState('');
  const [tracking, setTracking] = useState('');
  const ship = useFbaMutation(() => api.fba.ship(s?.id ?? '', s?.version), (x) => `${x.number} shipped: AfroTransact expects it at ${x.nodeId}.`);
  const cancel = useFbaMutation(() => api.fba.cancel(s?.id ?? ''), (x) => `${x.number} cancelled.`);
  const track = useFbaMutation(() => api.fba.setTracking(s?.id ?? '', carrier.trim(), tracking.trim()), (x) => `Tracking saved on ${x.number}.`);
  const setSelfDelivery = useFbaMutation(() => api.fba.setTracking(s?.id ?? '', 'self_delivery', `DROP-OFF-${s?.number ?? 'FC'}`), (x) => `Self-delivery drop-off confirmed for ${x.number}.`);
  const edit = can('inventory');
  return (
    <Sheet open={id !== null} onOpenChange={(o) => !o && onClose()} title={s ? `Shipment ${s.number}` : 'Shipment'} width={720}>
      {q.isPending ? <Spinner /> : null}
      {q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : null}
      {s ? (
        <div className="flex flex-col gap-4" data-testid="fba-plan">
          <div className="flex flex-wrap items-center gap-2">
            {pill(s.status)}
            <span className="text-sm text-muted">
              To {s.nodeId}
              {s.shipTo?.city ? ` · ${s.shipTo.city}, ${s.shipTo.region ?? ''}` : ''} · {s.receivedUnits} of {s.expectedUnits} received
            </span>
          </div>
          <Timeline shipment={s} />
          <div className="overflow-hidden rounded-md border border-border">
            <SheetTableScroll>
            <table className="at-table text-xs" aria-label="Plan lines">
              <thead className="bg-surface-muted text-left text-muted">
                <tr>{['SKU', 'Case pack', 'Lot', 'Expires', 'Sent', 'Received', 'Damaged', 'Short'].map((h) => <th key={h} scope="col" className="px-2 py-1.5">{h}</th>)}</tr>
              </thead>
              <tbody>
                {s.lines.map((l) => (
                  <tr key={l.lineNo} className="border-t border-border">
                    <td className="px-2 py-1.5 font-mono">{l.sku}</td>
                    <td className="px-2 py-1.5">{l.casePack ? `${l.casePack} × ${l.cases}` : '—'}</td>
                    <td className="px-2 py-1.5 font-mono">{l.lotNo ?? '—'}</td>
                    <td className="px-2 py-1.5">{shortDate(l.expDate)}</td>
                    <td className="px-2 py-1.5 text-end">{l.expectedQty}</td>
                    <td className="px-2 py-1.5 text-end">{l.receivedGood}</td>
                    <td className="px-2 py-1.5 text-end">{l.receivedDamaged}</td>
                    <td className="px-2 py-1.5 text-end">{s.status === 'CLOSED' ? l.shortQty : '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            </SheetTableScroll>
          </div>
          {edit && s.status === 'WORKING' ? (
            <div className="flex flex-col gap-3 rounded-md border border-border p-3">
              <label className="text-xs font-semibold text-muted">Delivery arrangement to AfroTransact FC</label>
              <div className="flex flex-wrap gap-4 text-sm">
                <label className="flex items-center gap-2 cursor-pointer">
                  <input
                    type="radio"
                    name="deliveryMethod"
                    value="carrier"
                    checked={deliveryMethod === 'carrier'}
                    onChange={() => setDeliveryMethod('carrier')}
                  />
                  <span>Ship via carrier (UPS, FedEx, DHL, etc.)</span>
                </label>
                <label className="flex items-center gap-2 cursor-pointer">
                  <input
                    type="radio"
                    name="deliveryMethod"
                    value="self"
                    checked={deliveryMethod === 'self'}
                    onChange={() => setDeliveryMethod('self')}
                  />
                  <span>Deliver ourselves (direct drop-off to FC)</span>
                </label>
              </div>

              {deliveryMethod === 'carrier' ? (
                <form
                  className="flex flex-wrap items-end gap-2 pt-2"
                  onSubmit={(e) => {
                    e.preventDefault();
                    if (carrier.trim() && tracking.trim()) track.mutate(undefined);
                  }}
                >
                  <TextField label="Carrier" value={carrier} onChange={(e) => setCarrier(e.target.value)} placeholder={s.carrier ?? 'ups'} />
                  <TextField label="Tracking number" value={tracking} onChange={(e) => setTracking(e.target.value)} placeholder={s.trackingNumber ?? ''} />
                  <Button type="submit" size="sm" variant="neutral" loading={track.isPending}>Save tracking</Button>
                </form>
              ) : (
                <div className="flex flex-wrap items-center justify-between gap-3 pt-2 text-sm bg-surface-muted p-2 rounded">
                  <span className="text-xs text-muted">
                    Seller will deliver stock directly to {s.nodeId}. No third-party carrier tracking is required.
                  </span>
                  <Button
                    type="button"
                    size="sm"
                    variant="neutral"
                    loading={setSelfDelivery.isPending}
                    onClick={() => setSelfDelivery.mutate(undefined)}
                  >
                    Confirm self-delivery arrangement
                  </Button>
                </div>
              )}
            </div>
          ) : null}
          {edit ? (
            <div className="flex flex-wrap gap-2">
              {s.status === 'WORKING' ? <Button size="sm" loading={ship.isPending} onClick={() => ship.mutate(undefined)}>Mark shipped</Button> : null}
              {s.status === 'WORKING' || s.status === 'SHIPPED' ? <Button size="sm" variant="neutral" loading={cancel.isPending} onClick={() => cancel.mutate(undefined)}>Cancel shipment</Button> : null}
            </div>
          ) : null}
          <section aria-label="Labels" className="flex flex-col gap-2">
            <div className="flex flex-wrap items-center gap-2">
              <h3 className="m-0 flex-1 text-sm font-bold">Labels</h3>
              <a className={smallButton} href={api.fba.labelsUrl(s.id, 'UNIT')} target="_blank" rel="noreferrer">Unit labels (PDF)</a>
              <a className={smallButton} href={api.fba.labelsUrl(s.id, 'BOX')} target="_blank" rel="noreferrer">Box labels (PDF)</a>
            </div>
            <div className="grid grid-cols-[repeat(auto-fill,minmax(min(100%,280px),1fr))] gap-3">
              {s.boxes.map((b) => (
                <figure key={b.lpn} className="m-0 flex flex-col gap-1 rounded-sm border-2 border-ink-1000 p-3" data-testid="fba-box-label">
                  <figcaption className="text-xs font-bold uppercase">Box {b.boxNo} of {s.boxes.length} · {s.number} → {s.nodeId}</figcaption>
                  <Barcode value={b.lpn} />
                  <span className="text-xs">{b.contents.map((c) => `${c.qty} × ${c.sku}`).join(' · ')}</span>
                </figure>
              ))}
              {s.lines.map((l) => (
                <figure key={l.lineNo} className="m-0 flex flex-col gap-1 rounded-sm border-2 border-ink-1000 p-3" data-testid="fba-unit-label">
                  <figcaption className="text-xs font-bold uppercase">Unit label · stick on every unit</figcaption>
                  <Barcode value={l.unitLabel ?? l.sku} />
                  <span className="text-xs">{l.expectedQty} labels · lot {l.lotNo ?? '—'} · exp {l.expDate ?? '—'}</span>
                </figure>
              ))}
            </div>
          </section>
        </div>
      ) : null}
    </Sheet>
  );
}

/** Your shipments to AfroTransact fulfilment centres. */
export function FbaShipments() {
  const api = sellerApi();
  const { can } = useSeller();
  const state = useTableState({ searchParams: useSearchParams(), prefix: 'in' });
  const [id, setId] = useParam('plan');
  const [creating, setCreating] = useState(false);
  const q = useQuery(inventoryHubQueries.inbound(api.fba, { page: 0, size: 200 }));
  const columns = useMemo<ColumnDef<InboundShipment>[]>(
    () => [
      { id: 'number', accessorFn: (s) => s.number, header: 'Shipment', size: 150, meta: { kind: 'mono' } },
      { id: 'name', accessorFn: (s) => s.name ?? '', header: 'Name', size: 170 },
      { id: 'nodeId', accessorFn: (s) => s.nodeId, header: 'FC', size: 90 },
      { id: 'expectedUnits', accessorFn: (s) => s.expectedUnits, header: 'Units', size: 80, meta: { kind: 'number', align: 'end' } },
      { id: 'receivedUnits', accessorFn: (s) => s.receivedUnits, header: 'Received', size: 90, meta: { kind: 'number', align: 'end' } },
      { id: 'tracking', accessorFn: (s) => [s.carrier, s.trackingNumber].filter(Boolean).join(' '), header: 'Tracking', size: 180 },
      { id: 'expectedArrival', accessorFn: (s) => s.expectedArrival ?? '', header: 'Arrives', size: 110, meta: { kind: 'date' }, cell: ({ row }) => shortDate(row.original.expectedArrival) },
      { id: 'status', accessorFn: (s) => STATUS[s.status]?.[0] ?? s.status, header: 'Status', size: 110, meta: { kind: 'status' }, cell: ({ row }) => pill(row.original.status) }
    ],
    []
  );
  return (
    <>
      <DataTable<InboundShipment>
        id="seller.fba.inbound"
        label="Shipments to AfroTransact"
        columns={columns}
        data={q.data?.items ?? []}
        getRowId={(s) => s.id}
        rowLabel={(s) => s.number}
        rowTestId="fba-plan-row"
        state={state}
        mode="client"
        loading={q.isPending}
        fetching={q.isFetching}
        error={q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : undefined}
        onRowClick={(s) => setId(s.id)}
        activeRowId={id}
        toolbar={can('inventory') ? <Button size="sm" icon={<Plus size={16} aria-hidden />} onClick={() => setCreating(true)}>Send to AfroTransact</Button> : undefined}
        empty="No shipments yet. Send stock to an AfroTransact fulfilment centre to earn the “Fulfilled by AfroTransact” badge."
        onExport={exportDataset}
        pollExport={pollExport}
      />
      <Sheet open={creating} onOpenChange={setCreating} title="Send to AfroTransact" width={720}>
        <PlanForm
          onDone={(s) => {
            setCreating(false);
            setId(s.id);
          }}
        />
      </Sheet>
      <PlanSheet id={creating ? null : id} onClose={() => setId(null)} />
    </>
  );
}

/** Your lots in AfroTransact fulfilment centres: batch, expiry, FEFO order and status. */
export function FbaLots() {
  const api = sellerApi();
  const state = useTableState({ searchParams: useSearchParams(), prefix: 'lots' });
  const q = useQuery(inventoryHubQueries.lots(api.fba, { page: 0, size: 200 }));
  const lots = useMemo(() => q.data?.items ?? [], [q.data]);
  const next = useMemo(() => {
    const out = new Set<string>();
    const groups = new Map<string, Lot[]>();
    lots.forEach((l) => groups.set(`${l.sku}|${l.nodeId}`, [...(groups.get(`${l.sku}|${l.nodeId}`) ?? []), l]));
    groups.forEach((g) => {
      const first = fefoOrder(g)[0];
      if (first) out.add(first.id);
    });
    return out;
  }, [lots]);
  const t = today();
  const tone: Record<string, string> = { expired: 'text-danger font-semibold', within30: 'text-danger', within60: 'text-warning', ok: 'text-success', none: 'text-muted' };
  const columns = useMemo<ColumnDef<Lot>[]>(
    () => [
      { id: 'sku', accessorFn: (l) => l.sku, header: 'SKU', size: 140, meta: { kind: 'mono' } },
      { id: 'lotNo', accessorFn: (l) => l.lotNo, header: 'Lot', size: 130, meta: { kind: 'mono' } },
      { id: 'nodeId', accessorFn: (l) => l.nodeId, header: 'FC', size: 90 },
      { id: 'qtyOnHand', accessorFn: (l) => l.qtyOnHand, header: 'On hand', size: 90, meta: { kind: 'number', align: 'end' } },
      { id: 'qtyAvailable', accessorFn: (l) => l.qtyAvailable, header: 'Available', size: 90, meta: { kind: 'number', align: 'end' } },
      { id: 'expDate', accessorFn: (l) => l.expDate ?? '', header: 'Expires', size: 110, meta: { kind: 'date' }, cell: ({ row }) => shortDate(row.original.expDate) },
      {
        id: 'days', accessorFn: (l) => daysToExpiry(l.expDate, t) ?? 99_999, header: 'Days left', size: 100, meta: { kind: 'number', align: 'end' },
        cell: ({ row }) => {
          const d = daysToExpiry(row.original.expDate, t);
          return <span className={tone[expiryTone(d)]}>{d === null ? 'No expiry' : d < 0 ? 'Expired' : `${d} d`}</span>;
        }
      },
      { id: 'next', accessorFn: (l) => (next.has(l.id) ? 'Ships next' : ''), header: 'FEFO', size: 100, cell: ({ row }) => (next.has(row.original.id) ? <StatusPill tone="brand">Ships next</StatusPill> : null) },
      { id: 'status', accessorFn: (l) => STATUS[l.status]?.[0] ?? l.status, header: 'Status', size: 110, meta: { kind: 'status' }, cell: ({ row }) => pill(row.original.status) }
    ],
    [next, t] // eslint-disable-line react-hooks/exhaustive-deps
  );
  return (
    <>
      <p className="m-0 text-sm text-muted">Orders ship the batch that expires first (FEFO). Lots within 30 days of expiry are flagged for markdown; expired lots are removed from sale.</p>
      <DataTable<Lot>
        id="seller.fba.lots"
        label="Lots and expiry"
        columns={columns}
        data={lots}
        getRowId={(l) => l.id}
        rowLabel={(l) => `${l.sku} lot ${l.lotNo}`}
        rowTestId="fba-lot-row"
        state={state}
        mode="client"
        loading={q.isPending}
        fetching={q.isFetching}
        error={q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : undefined}
        empty="No lots yet: they appear once AfroTransact receives your shipment."
        onExport={exportDataset}
        pollExport={pollExport}
      />
    </>
  );
}
