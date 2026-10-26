'use client';

import {
  INBOUND_TIMELINE,
  type InboundPlanInput,
  type InboundShipment,
  inboundStep,
  inventoryHubQueries,
  requiresExpiry,
  RETAIL_ORG,
  type RetailItem,
  validateInboundLine
} from '@afrotransact/api-client';
import { DataTable, useTableState } from '@afrotransact/data-table';
import { Button, Checkbox, cx, Select, Sheet, SheetTableScroll, TextField } from '@afrotransact/ui';
import { Plus, Trash } from '@phosphor-icons/react/ssr';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import { useRouter, useSearchParams } from 'next/navigation';
import { useMemo, useState } from 'react';

import { ErrorState, Spinner } from '@/components/ui/States';
import { StatusPill } from '@/components/ui/StatusPill';
import { dateTime, humanize, idempotencyKey, shortDate } from '@/lib/format';
import { exportDataset } from '@/lib/table-export';

import { PrintButton, ShipmentLabels } from './Labels';
import { smallButton, todayIso, toneOf, useHub, useHubAccess, useHubMutation, useParam } from './shared';

const STATUS_LABEL: Record<string, string> = { WORKING: 'Draft', SHIPPED: 'Shipped', RECEIVING: 'Receiving', CLOSED: 'Closed', CANCELLED: 'Cancelled' };

/** Retail items by SKU (GTIN, case pack and shelf life for labels and expiry rules). */
export function useRetailItems() {
  const api = useHub();
  const { retailView } = useHubAccess();
  const q = useQuery({ ...inventoryHubQueries.items(api, { page: 0, size: 200 }), enabled: retailView });
  return useMemo(() => new Map((q.data?.items ?? []).map((i) => [i.sku, i])), [q.data]);
}

/** The shipment timeline: draft, shipped, in transit, arrived, receiving, closed. */
export function Timeline({ shipment }: { shipment: InboundShipment }) {
  const step = inboundStep(shipment, todayIso());
  if (step < 0) return <StatusPill tone="bad">Cancelled</StatusPill>;
  return (
    <ol className="m-0 flex list-none flex-wrap gap-1 p-0" aria-label="Shipment timeline">
      {INBOUND_TIMELINE.map((label, i) => (
        <li
          key={label}
          aria-current={i === step ? 'step' : undefined}
          className={cx('rounded-pill px-2.5 py-1 text-xs font-semibold', i < step ? 'bg-success-bg text-success' : i === step ? 'bg-ink-1000 text-gold-500' : 'bg-surface-muted text-muted')}
        >
          {label}
        </li>
      ))}
    </ol>
  );
}

interface DraftLine {
  key: string;
  itemSku: string;
  atin: string;
  sku: string;
  qty: string;
  casePack: string;
  lotNo: string;
  mfgDate: string;
  expDate: string;
  perishable: boolean;
}

const emptyLine = (): DraftLine => ({ key: idempotencyKey(), itemSku: '', atin: '', sku: '', qty: '', casePack: '', lotNo: '', mfgDate: '', expDate: '', perishable: false });

/** Validation of the whole draft: per-line field errors keyed `lines.<i>.<field>`. */
export function draftErrors(lines: DraftLine[], items: Map<string, RetailItem>, today: string): Record<string, string> {
  const e: Record<string, string> = {};
  if (lines.length === 0) e.lines = 'Add at least one line';
  lines.forEach((l, i) => {
    const item = items.get(l.itemSku);
    const atin = (item?.atin ?? l.atin).trim().toUpperCase();
    if (!/^AT[0-9A-Z]{8}$/.test(atin)) e[`lines.${i}.atin`] = item ? 'Link this item to its catalog ATIN first (Retail → Items)' : 'A variant ATIN (AT + 8 letters or digits)';
    const expiryRequired = l.perishable || (item ? requiresExpiry({ storage: item.storage, shelfLifeDays: item.shelfLifeDays }) : false);
    Object.entries(validateInboundLine(l, { expiryRequired, today })).forEach(([k, v]) => {
      e[`lines.${i}.${k}`] = v;
    });
  });
  return e;
}

/** New inbound shipment (ASN): destination, lines with case pack and the packing-list batch, carrier and tracking. */
function InboundForm({ onDone }: { onDone: (s: InboundShipment) => void }) {
  const api = useHub();
  const items = useRetailItems();
  const nodes = useQuery(inventoryHubQueries.nodes(api));
  const [owner, setOwner] = useState('');
  const [name, setName] = useState('');
  const [nodeId, setNodeId] = useState('HOU-FC');
  const [eta, setEta] = useState('');
  const [carrier, setCarrier] = useState('');
  const [tracking, setTracking] = useState('');
  const [lines, setLines] = useState<DraftLine[]>([emptyLine()]);
  const [shown, setShown] = useState(false);
  const [key] = useState(idempotencyKey);
  const errors = draftErrors(lines, items, todayIso());
  const create = useHubMutation(
    async (body: InboundPlanInput) => {
      const s = await api.createPlan(body, key);
      return carrier.trim() && tracking.trim() ? api.setTracking(s.id, carrier.trim(), tracking.trim()) : s;
    },
    (s) => `${s.number} created — print the labels, then mark it shipped.`,
    onDone
  );
  const set = (i: number, patch: Partial<DraftLine>) => setLines((ls) => ls.map((l, k) => (k === i ? { ...l, ...patch } : l)));
  const err = (k: string) => (shown ? errors[k] : undefined);
  const itemOptions = [{ value: '', label: 'Other product (enter the ATIN)' }, ...[...items.values()].map((it) => ({ value: it.sku, label: `${it.name} (${it.sku})` }))];
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
          nodeId,
          ownerOrg: owner.trim() || RETAIL_ORG,
          expectedArrival: eta || undefined,
          lines: lines.map((l) => {
            const item = items.get(l.itemSku);
            return {
              atin: (item?.atin ?? l.atin).trim().toUpperCase(),
              sku: (item?.sku ?? l.sku).trim().toUpperCase() || undefined,
              qty: Number(l.qty),
              casePack: l.casePack ? Number(l.casePack) : undefined,
              lotNo: l.lotNo.trim() || undefined,
              mfgDate: l.mfgDate || undefined,
              expDate: l.expDate || undefined
            };
          }),
          boxes: lines.map((l) => ({ contents: [{ sku: (items.get(l.itemSku)?.sku ?? l.sku).trim().toUpperCase() || `ATF${(items.get(l.itemSku)?.atin ?? l.atin).trim().toUpperCase().slice(-7)}`, qty: Number(l.qty) }] }))
        });
      }}
    >
      <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,200px),1fr))] gap-2">
        <TextField label="Shipment name" value={name} onChange={(e) => setName(e.target.value)} placeholder="October restock" />
        <Select label="Destination fulfilment centre" value={nodeId} onChange={(e) => setNodeId(e.target.value)} options={(nodes.data ?? []).map((n) => ({ value: n.id, label: `${n.name} (${n.id})` }))} />
        <TextField label="Expected arrival" type="date" value={eta} min={todayIso()} onChange={(e) => setEta(e.target.value)} />
        <TextField label="Stock owner" value={owner} onChange={(e) => setOwner(e.target.value)} placeholder="AfroTransact Retail" help="Leave empty for AfroTransact Retail (1P); a seller org id for FBA" />
        <TextField label="Carrier" value={carrier} onChange={(e) => setCarrier(e.target.value)} placeholder="ups, fedex, MSC…" />
        <TextField label="Tracking number" value={tracking} onChange={(e) => setTracking(e.target.value)} />
      </div>
      <h3 className="m-0 text-sm font-bold">What is in it</h3>
      {lines.map((l, i) => {
        const item = items.get(l.itemSku);
        const req = l.perishable || (item ? requiresExpiry({ storage: item.storage, shelfLifeDays: item.shelfLifeDays }) : false);
        return (
          <fieldset key={l.key} className="m-0 grid grid-cols-[repeat(auto-fit,minmax(min(100%,150px),1fr))] gap-2 rounded-md border border-border p-3" aria-label={`Line ${i + 1}`}>
            <legend className="px-1 text-xs font-bold text-muted">Line {i + 1}</legend>
            <Select label="Product" value={l.itemSku} onChange={(e) => set(i, { itemSku: e.target.value, casePack: e.target.value ? String(items.get(e.target.value)?.casePack ?? '') : l.casePack })} options={itemOptions} className="col-span-full sm:col-span-2" />
            {!item ? (
              <>
                <TextField label="Variant ATIN" value={l.atin} onChange={(e) => set(i, { atin: e.target.value })} error={err(`lines.${i}.atin`)} />
                <TextField label="Inventory SKU (optional)" value={l.sku} onChange={(e) => set(i, { sku: e.target.value })} help="Default: the seller’s AT-FN label" />
              </>
            ) : err(`lines.${i}.atin`) ? (
              <span role="alert" className="col-span-full text-sm text-danger">{err(`lines.${i}.atin`)}</span>
            ) : null}
            <TextField label="Units" inputMode="numeric" value={l.qty} onChange={(e) => set(i, { qty: e.target.value })} error={err(`lines.${i}.qty`)} />
            <TextField label="Units per case" inputMode="numeric" value={l.casePack} onChange={(e) => set(i, { casePack: e.target.value })} error={err(`lines.${i}.casePack`)} />
            <TextField label={req ? 'Lot / batch' : 'Lot / batch (optional)'} value={l.lotNo} onChange={(e) => set(i, { lotNo: e.target.value })} error={err(`lines.${i}.lotNo`)} />
            <TextField label="Made on" type="date" value={l.mfgDate} onChange={(e) => set(i, { mfgDate: e.target.value })} error={err(`lines.${i}.mfgDate`)} />
            <TextField label={req ? 'Expires on' : 'Expires on (optional)'} type="date" value={l.expDate} onChange={(e) => set(i, { expDate: e.target.value })} error={err(`lines.${i}.expDate`)} />
            {!item ? <Checkbox label="Food or perishable (expiry required)" checked={l.perishable} onChange={(e) => set(i, { perishable: e.target.checked })} /> : null}
            {lines.length > 1 ? (
              <button type="button" className={cx(smallButton, 'self-end')} onClick={() => setLines((ls) => ls.filter((_, k) => k !== i))}>
                <Trash size={14} aria-hidden /> Remove line
              </button>
            ) : null}
          </fieldset>
        );
      })}
      <button type="button" className={cx(smallButton, 'self-start')} onClick={() => setLines((ls) => [...ls, emptyLine()])}>
        <Plus size={14} aria-hidden /> Add a line
      </button>
      {shown && errors.lines ? <span role="alert" className="text-sm text-danger">{errors.lines}</span> : null}
      <Button type="submit" loading={create.isPending}>
        Create inbound shipment
      </Button>
    </form>
  );
}

/** One inbound shipment: timeline, lines with the announced batch, boxes, labels and its actions. */
export function ShipmentSheet({ id, onClose }: { id: string | null; onClose: () => void }) {
  const api = useHub();
  const router = useRouter();
  const items = useRetailItems();
  const { inventoryEdit } = useHubAccess();
  const q = useQuery({ ...inventoryHubQueries.shipment(api, id ?? ''), enabled: id !== null });
  const s = q.data;
  const [carrier, setCarrier] = useState('');
  const [tracking, setTracking] = useState('');
  const ship = useHubMutation(() => api.ship(s?.id ?? '', s?.version), (x) => `${x.number} is shipped: its units are inbound at ${x.nodeId}.`);
  const cancel = useHubMutation(() => api.cancel(s?.id ?? ''), (x) => `${x.number} cancelled.`);
  const close = useHubMutation(() => api.close(s?.id ?? ''), (x) => `${x.number} closed${x.discrepancy ? ' with discrepancies' : ''}.`);
  const track = useHubMutation(() => api.setTracking(s?.id ?? '', carrier.trim(), tracking.trim()), (x) => `Tracking saved on ${x.number}.`);
  return (
    <Sheet open={id !== null} onOpenChange={(o) => !o && onClose()} title={s ? `Inbound ${s.number}` : 'Inbound shipment'} width={760}>
      {q.isPending ? <Spinner /> : null}
      {q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : null}
      {s ? (
        <div className="flex flex-col gap-4" data-testid="shipment-sheet">
          <div className="flex flex-wrap items-center gap-2">
            <StatusPill tone={toneOf(s.status)}>{STATUS_LABEL[s.status] ?? s.status}</StatusPill>
            <span className="text-sm text-muted">{s.source === 'RETAIL_ASN' ? 'AfroTransact Retail ASN' : 'FBA inbound plan'}</span>
          </div>
          <Timeline shipment={s} />
          <dl className="m-0 grid grid-cols-[minmax(0,2fr)_minmax(0,3fr)] gap-x-3 gap-y-1 text-sm">
            {[
              ['Stock owner', s.ownerOrgName ?? '—'],
              ['Fulfilment centre', `${s.nodeId}${s.assignmentReason ? ` · ${s.assignmentReason}` : ''}`],
              ['Carrier · tracking', s.carrier ? `${s.carrier} · ${s.trackingNumber ?? ''}` : '—'],
              ['Expected arrival', shortDate(s.expectedArrival)],
              ['Units', `${s.receivedUnits} of ${s.expectedUnits} received`],
              ['Shipped', dateTime(s.shippedAt)],
              ['Closed', dateTime(s.closedAt)]
            ].map(([k, v]) => (
              <div key={k} className="contents">
                <dt className="text-muted">{k}</dt>
                <dd className="m-0">{v}</dd>
              </div>
            ))}
          </dl>
          <div className="overflow-hidden rounded-md border border-border">
            <SheetTableScroll>
            <table className="at-table text-xs" aria-label="Shipment lines">
              <thead className="bg-surface-muted text-left text-muted">
                <tr>
                  {['SKU', 'Case pack', 'Lot', 'Made', 'Expires', 'Expected', 'Good', 'Damaged', 'Short / over'].map((h) => (
                    <th key={h} scope="col" className="px-2 py-1.5 font-bold">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {s.lines.map((l) => (
                  <tr key={l.lineNo} className="border-t border-border">
                    <td className="px-2 py-1.5 font-mono">{l.sku}{l.atin ? <span className="at-cell-stack block text-muted">{l.atin}</span> : null}</td>
                    <td className="px-2 py-1.5">{l.casePack ? `${l.casePack} × ${l.cases}` : '—'}</td>
                    <td className="px-2 py-1.5 font-mono">{l.lotNo ?? '—'}</td>
                    <td className="px-2 py-1.5">{shortDate(l.mfgDate)}</td>
                    <td className="px-2 py-1.5">{shortDate(l.expDate)}</td>
                    <td className="px-2 py-1.5 text-end">{l.expectedQty}</td>
                    <td className="px-2 py-1.5 text-end">{l.receivedGood}</td>
                    <td className="px-2 py-1.5 text-end">{l.receivedDamaged}</td>
                    <td className={cx('px-2 py-1.5 text-end', (l.shortQty || l.overQty) && s.status !== 'WORKING' ? 'font-semibold text-warning' : '')}>
                      {l.shortQty ? `−${l.shortQty}` : l.overQty ? `+${l.overQty}` : '0'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            </SheetTableScroll>
          </div>
          {s.discrepancies.length ? (
            <p className="m-0 text-sm text-warning" role="status">
              Discrepancies: {s.discrepancies.map((d) => `${humanize(d.type)} ${d.sku ?? ''} ${d.qty ?? ''}`).join(' · ')}
            </p>
          ) : null}
          {inventoryEdit ? (
            <div className="flex flex-wrap gap-2">
              {s.status === 'WORKING' ? (
                <Button size="sm" loading={ship.isPending} onClick={() => ship.mutate(undefined)}>
                  Mark shipped
                </Button>
              ) : null}
              {s.status === 'SHIPPED' || s.status === 'RECEIVING' ? (
                <Button size="sm" onClick={() => router.push(`/inventory?tab=receiving&receive=${encodeURIComponent(s.id)}`)}>
                  Receive at the dock
                </Button>
              ) : null}
              {s.status === 'RECEIVING' ? (
                <Button size="sm" variant="neutral" loading={close.isPending} onClick={() => close.mutate(undefined)}>
                  Close receiving
                </Button>
              ) : null}
              {s.status === 'WORKING' || s.status === 'SHIPPED' ? (
                <Button size="sm" variant="neutral" loading={cancel.isPending} onClick={() => cancel.mutate(undefined)}>
                  Cancel shipment
                </Button>
              ) : null}
            </div>
          ) : null}
          {inventoryEdit && s.source === 'FBA' && (s.status === 'WORKING' || s.status === 'SHIPPED') ? (
            <form
              className="flex flex-wrap at-align gap-2"
              onSubmit={(e) => {
                e.preventDefault();
                if (carrier.trim() && tracking.trim()) track.mutate(undefined);
              }}
            >
              <TextField label="Carrier" value={carrier} onChange={(e) => setCarrier(e.target.value)} className="min-w-[120px] flex-1" />
              <TextField label="Tracking number" value={tracking} onChange={(e) => setTracking(e.target.value)} className="min-w-[180px] flex-1" />
              <Button type="submit" variant="neutral" size="sm" loading={track.isPending}>
                Save tracking
              </Button>
            </form>
          ) : null}
          <section aria-label="Labels" className="flex flex-col gap-2">
            <div className="flex flex-wrap items-center gap-2">
              <h3 className="m-0 flex-1 text-sm font-bold">Labels</h3>
              <PrintButton>
                <ShipmentLabels shipment={s} items={items} />
              </PrintButton>
              {s.source === 'FBA' ? (
                <>
                  <a className={smallButton} href={api.labelsUrl(s.id, 'UNIT')} target="_blank" rel="noreferrer">Unit labels (PDF)</a>
                  <a className={smallButton} href={api.labelsUrl(s.id, 'BOX')} target="_blank" rel="noreferrer">Box labels (PDF)</a>
                </>
              ) : null}
            </div>
            <ShipmentLabels shipment={s} items={items} />
          </section>
        </div>
      ) : null}
    </Sheet>
  );
}

/** Inbound shipments (FBA plans and retail ASNs): one table, a creation sheet and the shipment sheet. */
export function InboundPanel() {
  const api = useHub();
  const qc = useQueryClient();
  const { inventoryEdit } = useHubAccess();
  const state = useTableState({ searchParams: useSearchParams(), prefix: 'in' });
  const [id, setId] = useParam('id');
  const [creating, setCreating] = useState(false);
  const query = useQuery(inventoryHubQueries.inbound(api, { page: 0, size: 200 }));
  const columns = useMemo<ColumnDef<InboundShipment>[]>(
    () => [
      { id: 'number', accessorFn: (s) => s.number, header: 'Shipment', size: 170, meta: { kind: 'mono' }, cell: ({ row }) => <span className="font-mono text-xs font-semibold">{row.original.number}</span> },
      { id: 'source', accessorFn: (s) => (s.source === 'RETAIL_ASN' ? 'Retail ASN' : 'FBA'), header: 'Type', size: 100 },
      { id: 'ownerOrg', accessorFn: (s) => s.ownerOrgName ?? '', header: 'Owner', size: 190 },
      { id: 'nodeId', accessorFn: (s) => s.nodeId, header: 'FC', size: 90 },
      { id: 'skus', accessorFn: (s) => s.lines.map((l) => l.sku).join(', '), header: 'SKUs', size: 200, cell: ({ row }) => <span className="block truncate font-mono text-xs">{row.original.lines.map((l) => l.sku).join(', ')}</span> },
      { id: 'expectedUnits', accessorFn: (s) => s.expectedUnits, header: 'Units', size: 80, meta: { kind: 'number', align: 'end' } },
      { id: 'receivedUnits', accessorFn: (s) => s.receivedUnits, header: 'Received', size: 90, meta: { kind: 'number', align: 'end' } },
      { id: 'expectedArrival', accessorFn: (s) => s.expectedArrival ?? '', header: 'Arrives', size: 110, meta: { kind: 'date' }, cell: ({ row }) => shortDate(row.original.expectedArrival) },
      { id: 'status', accessorFn: (s) => STATUS_LABEL[s.status] ?? s.status, header: 'Status', size: 110, meta: { kind: 'status' }, cell: ({ row }) => <StatusPill tone={toneOf(row.original.status)}>{STATUS_LABEL[row.original.status]}</StatusPill> }
    ],
    []
  );
  return (
    <div className="flex flex-col gap-4">
      <DataTable<InboundShipment>
        id="staff.inventory.inbound"
        label="Inbound shipments"
        columns={columns}
        data={query.data?.items ?? []}
        getRowId={(s) => s.id}
        rowLabel={(s) => s.number}
        rowTestId="inbound-row"
        state={state}
        mode="client"
        loading={query.isPending}
        fetching={query.isFetching}
        error={query.isError ? <ErrorState error={query.error} onRetry={() => void query.refetch()} /> : undefined}
        onRowClick={(s) => setId(s.id)}
        activeRowId={id}
        toolbar={inventoryEdit ? <Button size="sm" icon={<Plus size={16} aria-hidden />} onClick={() => setCreating(true)}>New inbound shipment</Button> : undefined}
        searchPlaceholder="Search shipment, owner, SKU, status…"
        empty="Nothing is on its way. Create an inbound shipment or book one from a purchase order."
        onExport={(req) => exportDataset(qc, req)}
        exportsHref="/exports"
      />
      <Sheet open={creating} onOpenChange={setCreating} title="New inbound shipment" width={760}>
        <InboundForm
          onDone={(s) => {
            setCreating(false);
            setId(s.id);
          }}
        />
      </Sheet>
      <ShipmentSheet id={creating ? null : id} onClose={() => setId(null)} />
    </div>
  );
}
