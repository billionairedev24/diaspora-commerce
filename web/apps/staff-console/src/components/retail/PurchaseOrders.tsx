'use client';

import { inventoryHubQueries, type PoInput, type PurchaseOrder, type ShipmentDocumentStatus } from '@afrotransact/api-client';
import { DataTable, useTableState } from '@afrotransact/data-table';
import { Button, cx, Select, Sheet, TextField } from '@afrotransact/ui';
import { ArrowRight, Plus, Trash } from '@phosphor-icons/react/ssr';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import Link from 'next/link';
import { useRouter, useSearchParams } from 'next/navigation';
import { useMemo, useState } from 'react';

import { smallButton, todayIso, toneOf, useHub, useHubAccess, useHubMutation, useParam } from '@/components/inventory/shared';
import { ErrorState, Spinner } from '@/components/ui/States';
import { StatusPill } from '@/components/ui/StatusPill';
import { dateTime, humanize, idempotencyKey, money, shortDate } from '@/lib/format';
import { exportDataset } from '@/lib/table-export';

const LIFECYCLE = ['DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'SENT', 'CONFIRMED', 'RECEIVED', 'CLOSED'];

interface DraftLine {
  key: string;
  itemId: string;
  qty: string;
  cost: string;
}

/** New PO: supplier, destination FC, Incoterm, expected date and lines of retail items in whole cases. */
function PoForm({ onDone }: { onDone: (po: PurchaseOrder) => void }) {
  const api = useHub();
  const suppliers = useQuery(inventoryHubQueries.suppliers(api));
  const items = useQuery(inventoryHubQueries.items(api, { page: 0, size: 200 }));
  const nodes = useQuery(inventoryHubQueries.nodes(api));
  const [supplierId, setSupplierId] = useState('');
  const [nodeId, setNodeId] = useState('HOU-FC');
  const [incoterm, setIncoterm] = useState('FOB');
  const [expected, setExpected] = useState('');
  const [lines, setLines] = useState<DraftLine[]>([{ key: idempotencyKey(), itemId: '', qty: '', cost: '' }]);
  const [shown, setShown] = useState(false);
  const [key] = useState(idempotencyKey);
  const supplier = suppliers.data?.items.find((s) => s.id === supplierId);
  const supplied = (items.data?.items ?? []).filter((i) => !supplierId || i.supplierItems.some((x) => x.supplierId === supplierId));
  const errors: Record<string, string> = {};
  if (!supplierId) errors.supplierId = 'Pick the supplier';
  lines.forEach((l, i) => {
    const it = supplied.find((x) => x.id === l.itemId);
    const si = it?.supplierItems.find((x) => x.supplierId === supplierId) ?? it?.supplierItems[0];
    if (!it) errors[`${i}.itemId`] = 'Pick an item this supplier sells';
    const qty = Number(l.qty);
    if (!Number.isInteger(qty) || qty < 1) errors[`${i}.qty`] = 'Whole units';
    else if (si && qty % si.casePack !== 0) errors[`${i}.qty`] = `Order whole cases of ${si.casePack}`;
    else if (si?.minOrderQty && qty < si.minOrderQty) errors[`${i}.qty`] = `At least ${si.minOrderQty} (minimum order)`;
    if (l.cost && !(Number(l.cost) > 0)) errors[`${i}.cost`] = 'A positive price';
  });
  const create = useHubMutation((body: PoInput) => api.retail.createPo(body, key), (po) => `${po.number} created as a draft.`, onDone);
  const err = (k: string) => (shown ? errors[k] : undefined);
  const total = lines.reduce((a, l) => {
    const it = supplied.find((x) => x.id === l.itemId);
    const cost = l.cost ? Math.round(Number(l.cost) * 100) : (it?.supplierItems.find((x) => x.supplierId === supplierId)?.unitCostMinor ?? 0);
    return a + (Number(l.qty) || 0) * cost;
  }, 0);
  return (
    <form
      className="flex flex-col gap-3"
      noValidate
      onSubmit={(e) => {
        e.preventDefault();
        setShown(true);
        if (Object.keys(errors).length) return;
        create.mutate({
          supplierId,
          destinationNodeId: nodeId,
          incoterm,
          expectedDate: expected || undefined,
          lines: lines.map((l) => ({ itemId: l.itemId, qty: Number(l.qty), unitCostMinor: l.cost ? Math.round(Number(l.cost) * 100) : undefined }))
        });
      }}
    >
      <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,200px),1fr))] gap-2">
        <Select label="Supplier" value={supplierId} placeholder="Choose…" onChange={(e) => setSupplierId(e.target.value)} options={(suppliers.data?.items ?? []).filter((s) => s.canReceivePurchaseOrders).map((s) => ({ value: s.id, label: `${s.name} (${s.code} · ${s.currency})` }))} error={err('supplierId')} />
        <Select label="Ship to fulfilment centre" value={nodeId} onChange={(e) => setNodeId(e.target.value)} options={(nodes.data ?? []).map((n) => ({ value: n.id, label: n.name }))} />
        <TextField label="Incoterm" value={incoterm} onChange={(e) => setIncoterm(e.target.value)} placeholder="FOB Tema" />
        <TextField label="Expected at the FC" type="date" min={todayIso()} value={expected} onChange={(e) => setExpected(e.target.value)} />
      </div>
      {lines.map((l, i) => {
        const it = supplied.find((x) => x.id === l.itemId);
        const si = it?.supplierItems.find((x) => x.supplierId === supplierId) ?? it?.supplierItems[0];
        return (
          <fieldset key={l.key} className="m-0 grid grid-cols-[repeat(auto-fit,minmax(min(100%,150px),1fr))] gap-2 rounded-md border border-border p-3" aria-label={`PO line ${i + 1}`}>
            <legend className="px-1 text-xs font-bold text-muted">Line {i + 1}</legend>
            <Select label="Item" value={l.itemId} placeholder="Choose…" onChange={(e) => setLines((ls) => ls.map((x, k) => (k === i ? { ...x, itemId: e.target.value } : x)))} options={supplied.map((x) => ({ value: x.id, label: `${x.name} (${x.sku})` }))} error={err(`${i}.itemId`)} className="col-span-full sm:col-span-2" />
            <TextField label="Units" inputMode="numeric" value={l.qty} onChange={(e) => setLines((ls) => ls.map((x, k) => (k === i ? { ...x, qty: e.target.value } : x)))} error={err(`${i}.qty`)} help={si ? `Case of ${si.casePack}` : undefined} />
            <TextField label={`Unit cost (${supplier?.currency ?? 'supplier currency'})`} inputMode="decimal" value={l.cost} onChange={(e) => setLines((ls) => ls.map((x, k) => (k === i ? { ...x, cost: e.target.value } : x)))} error={err(`${i}.cost`)} help={si ? `Agreed: ${money(si.unitCostMinor, si.currency)}` : undefined} />
            {lines.length > 1 ? (
              <button type="button" className={cx(smallButton, 'self-end')} onClick={() => setLines((ls) => ls.filter((_, k) => k !== i))}>
                <Trash size={14} aria-hidden /> Remove
              </button>
            ) : null}
          </fieldset>
        );
      })}
      <button type="button" className={cx(smallButton, 'self-start')} onClick={() => setLines((ls) => [...ls, { key: idempotencyKey(), itemId: '', qty: '', cost: '' }])}>
        <Plus size={14} aria-hidden /> Add a line
      </button>
      <span className="text-sm">Total {money(total, supplier?.currency ?? 'USD')}</span>
      <Button type="submit" loading={create.isPending}>
        Create purchase order
      </Button>
    </form>
  );
}

/** Book the inbound shipment of a confirmed PO: transport, carrier, dates and the ASN with each line's batch. */
function BookShipment({ po }: { po: PurchaseOrder }) {
  const api = useHub();
  const [mode, setMode] = useState('OCEAN_FCL');
  const [carrier, setCarrier] = useState('');
  const [tracking, setTracking] = useState('');
  const [etd, setEtd] = useState('');
  const [eta, setEta] = useState('');
  const [asn, setAsn] = useState(() => Object.fromEntries(po.lines.map((l) => [l.lineNo, { qty: String(l.openQty), lotNo: '', mfgDate: '', expDate: '' }])));
  const book = useHubMutation(
    () =>
      api.retail.bookShipment({
        poId: po.id, mode, carrier: carrier || undefined, trackingRef: tracking || undefined, etd: etd || undefined, eta: eta || undefined,
        lines: po.lines.map((l) => ({ poLineNo: l.lineNo, qty: Number(asn[l.lineNo]?.qty ?? l.openQty), lotNo: asn[l.lineNo]?.lotNo || undefined, mfgDate: asn[l.lineNo]?.mfgDate || undefined, expDate: asn[l.lineNo]?.expDate || undefined }))
      }),
    (s) => `Shipment ${s.reference} booked: the ASN is announced to ${s.destinationNodeId ?? 'the FC'}.`
  );
  return (
    <form
      className="flex flex-col gap-2 rounded-md border border-border p-3"
      aria-label="Book the inbound shipment"
      onSubmit={(e) => {
        e.preventDefault();
        book.mutate(undefined);
      }}
    >
      <strong className="text-sm">Book the inbound shipment (ASN)</strong>
      <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,140px),1fr))] gap-2">
        <Select label="Mode" value={mode} onChange={(e) => setMode(e.target.value)} options={[{ value: 'OCEAN_FCL', label: 'Ocean FCL' }, { value: 'OCEAN_LCL', label: 'Ocean LCL' }, { value: 'AIR', label: 'Air' }, { value: 'ROAD', label: 'Road' }]} />
        <TextField label="Carrier" value={carrier} onChange={(e) => setCarrier(e.target.value)} />
        <TextField label="Container / AWB / tracking" value={tracking} onChange={(e) => setTracking(e.target.value)} />
        <TextField label="Departs" type="date" value={etd} onChange={(e) => setEtd(e.target.value)} />
        <TextField label="Arrives" type="date" value={eta} onChange={(e) => setEta(e.target.value)} />
      </div>
      {po.lines.map((l) => (
        <div key={l.lineNo} className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,120px),1fr))] gap-2">
          <span className="col-span-full text-xs font-semibold">
            {l.sku} · {l.description}
          </span>
          <TextField label="Units shipped" inputMode="numeric" value={asn[l.lineNo]?.qty ?? ''} onChange={(e) => setAsn((a) => ({ ...a, [l.lineNo]: { ...(a[l.lineNo] ?? { qty: '', lotNo: '', mfgDate: '', expDate: '' }), qty: e.target.value } }))} />
          <TextField label="Batch / lot" value={asn[l.lineNo]?.lotNo ?? ''} onChange={(e) => setAsn((a) => ({ ...a, [l.lineNo]: { ...(a[l.lineNo] ?? { qty: '', lotNo: '', mfgDate: '', expDate: '' }), lotNo: e.target.value } }))} />
          <TextField label="Made on" type="date" value={asn[l.lineNo]?.mfgDate ?? ''} onChange={(e) => setAsn((a) => ({ ...a, [l.lineNo]: { ...(a[l.lineNo] ?? { qty: '', lotNo: '', mfgDate: '', expDate: '' }), mfgDate: e.target.value } }))} />
          <TextField label="Expires on" type="date" value={asn[l.lineNo]?.expDate ?? ''} onChange={(e) => setAsn((a) => ({ ...a, [l.lineNo]: { ...(a[l.lineNo] ?? { qty: '', lotNo: '', mfgDate: '', expDate: '' }), expDate: e.target.value } }))} />
        </div>
      ))}
      <Button type="submit" size="sm" loading={book.isPending}>
        Book shipment
      </Button>
    </form>
  );
}

/** One PO: lifecycle, lines, approval, documents and dispatch, its shipment (ASN), receipts and invoices. */
export function PoSheet({ id, onClose }: { id: string | null; onClose: () => void }) {
  const api = useHub();
  const router = useRouter();
  const { retailEdit, retailApprove } = useHubAccess();
  const q = useQuery({ ...inventoryHubQueries.po(api, id ?? ''), enabled: id !== null });
  const po = q.data;
  const ships = useQuery({ ...inventoryHubQueries.poShipments(api, id ?? ''), enabled: id !== null });
  const dispatches = useQuery({ ...inventoryHubQueries.poDispatches(api, id ?? ''), enabled: id !== null });
  const receipts = useQuery({ ...inventoryHubQueries.receipts(api, { poId: id ?? '', page: 0, size: 20 }), enabled: id !== null });
  const invoices = useQuery({ ...inventoryHubQueries.invoices(api, { poId: id ?? '', page: 0, size: 20 }), enabled: id !== null });
  const [note, setNote] = useState('');
  const [inv, setInv] = useState({ number: '', date: todayIso() });
  const act = useHubMutation((a: 'submit' | 'approve' | 'send-back' | 'send' | 'confirm' | 'close' | 'cancel') => api.retail.poAction(po?.id ?? '', a, po?.version, note ? { note } : {}), (x) => `${x.number}: ${humanize(x.status).toLowerCase()}.`, () => setNote(''));
  const advance = useHubMutation((s: { id: string; version: number }) => api.retail.advanceShipment(s.id, s.version), (s) => `${s.reference}: ${s.stageLabel ?? humanize(s.stage)}.`);
  const setDoc = useHubMutation(
    (d: { id: string; name: string; status: ShipmentDocumentStatus }) => api.retail.setDocument(d.id, d.name, d.status),
    (s) => `${s.reference}: documents updated.`
  );
  const openGrn = useHubMutation((shipmentId: string | undefined) => api.retail.openReceipt({ shipmentId, poId: shipmentId ? undefined : po?.id, dock: 'Dock 1' }), (r) => `${r.number} opened at the dock.`, (r) => router.push(`/inventory?tab=receiving&grn=${r.id}`));
  const register = useHubMutation(
    () => api.retail.registerInvoice({ poId: po?.id ?? '', invoiceNumber: inv.number.trim(), invoiceDate: inv.date, lines: (po?.lines ?? []).filter((l) => l.qtyReceived > 0).map((l) => ({ poLineNo: l.lineNo, qty: l.qtyReceived, unitPriceMinor: l.unitCostMinor })) }),
    (x) => `Invoice ${x.invoiceNumber}: ${humanize(x.status).toLowerCase()}.`
  );
  const step = po ? LIFECYCLE.indexOf(po.status === 'PARTIALLY_RECEIVED' ? 'CONFIRMED' : po.status) : -1;
  return (
    <Sheet open={id !== null} onOpenChange={(o) => !o && onClose()} title={po ? `Purchase order ${po.number}` : 'Purchase order'} width={820}>
      {q.isPending ? <Spinner /> : null}
      {q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : null}
      {po ? (
        <div className="flex flex-col gap-4" data-testid="po-sheet">
          <ol className="m-0 flex list-none flex-wrap gap-1 p-0" aria-label="PO lifecycle">
            {LIFECYCLE.map((s, i) => (
              <li key={s} aria-current={po.status === s ? 'step' : undefined} className={cx('rounded-pill px-2.5 py-1 text-xs font-semibold', i < step ? 'bg-success-bg text-success' : i === step ? 'bg-ink-1000 text-gold-500' : 'bg-surface-muted text-muted')}>
                {humanize(s)}
              </li>
            ))}
            {po.status === 'CANCELLED' ? <li><StatusPill tone="bad">Cancelled</StatusPill></li> : null}
          </ol>
          <dl className="m-0 grid grid-cols-[minmax(0,2fr)_minmax(0,3fr)] gap-x-3 gap-y-1 text-sm">
            {[
              ['Supplier', po.supplierCode],
              ['Ship to', `${po.destinationNodeId} · ${po.incoterm}`],
              ['Total', `${money(po.totalMinor, po.currency)} (${money(po.totalBaseMinor, po.baseCurrency)} at ${po.fxRate ?? 1})`],
              ['Approval', `${humanize(po.approvalTier ?? 'BUYER')}${po.approvedBy ? ` · approved by ${po.approvedBy}` : ''}`],
              ['Ordered · expected', `${shortDate(po.orderDate)} · ${shortDate(po.expectedDate)}`]
            ].map(([k, v]) => (
              <div key={k} className="contents">
                <dt className="text-muted">{k}</dt>
                <dd className="m-0">{v}</dd>
              </div>
            ))}
          </dl>
          <div className="overflow-x-auto rounded-md border border-border">
            <table className="at-table w-full text-xs" aria-label="PO lines">
              <thead className="bg-surface-muted text-left text-muted">
                <tr>{['SKU', 'Item', 'Ordered', 'Received', 'Damaged', 'Invoiced', 'Unit cost', 'Amount'].map((h) => <th key={h} scope="col" className="px-2 py-1.5">{h}</th>)}</tr>
              </thead>
              <tbody>
                {po.lines.map((l) => (
                  <tr key={l.lineNo} className="border-t border-border">
                    <td className="px-2 py-1.5 font-mono">{l.sku}</td>
                    <td className="px-2 py-1.5">{l.description}</td>
                    <td className="px-2 py-1.5 text-end">{l.qtyOrdered}</td>
                    <td className="px-2 py-1.5 text-end">{l.qtyReceived}</td>
                    <td className="px-2 py-1.5 text-end">{l.qtyDamaged}</td>
                    <td className="px-2 py-1.5 text-end">{l.qtyInvoiced}</td>
                    <td className="px-2 py-1.5 text-end">{money(l.unitCostMinor, po.currency)}</td>
                    <td className="px-2 py-1.5 text-end">{money(l.amountMinor, po.currency)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {retailEdit ? (
            <div className="flex flex-col gap-2">
              <TextField label="Note (approval, send back, cancel)" value={note} onChange={(e) => setNote(e.target.value)} />
              <div className="flex flex-wrap gap-2">
                {po.status === 'DRAFT' ? <Button size="sm" loading={act.isPending} onClick={() => act.mutate('submit')}>Submit for approval</Button> : null}
                {po.status === 'PENDING_APPROVAL' && retailApprove ? <Button size="sm" loading={act.isPending} onClick={() => act.mutate('approve')}>Approve</Button> : null}
                {po.status === 'PENDING_APPROVAL' && retailApprove ? <Button size="sm" variant="neutral" onClick={() => act.mutate('send-back')}>Send back</Button> : null}
                {po.status === 'APPROVED' ? <Button size="sm" loading={act.isPending} onClick={() => act.mutate('send')}>Send to supplier (PDF + EDI 850)</Button> : null}
                {po.status === 'SENT' ? <Button size="sm" loading={act.isPending} onClick={() => act.mutate('confirm')}>Supplier confirmed</Button> : null}
                {po.status === 'RECEIVED' || po.status === 'PARTIALLY_RECEIVED' ? <Button size="sm" variant="neutral" onClick={() => act.mutate('close')}>Close PO</Button> : null}
                {['DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'SENT', 'CONFIRMED'].includes(po.status) && !po.shipmentBooked ? <Button size="sm" variant="neutral" onClick={() => act.mutate('cancel')}>Cancel PO</Button> : null}
              </div>
            </div>
          ) : null}
          <section aria-label="Documents" className="flex flex-wrap items-center gap-2 text-sm">
            <strong className="mr-2">Documents</strong>
            <a className={smallButton} href={api.retail.poDocumentUrl(po.id, 'pdf')} target="_blank" rel="noreferrer">PO (PDF)</a>
            <a className={smallButton} href={api.retail.poDocumentUrl(po.id, 'edi850')} target="_blank" rel="noreferrer">EDI 850</a>
            {(dispatches.data ?? []).map((d) => (
              <StatusPill key={d.channel} tone={toneOf(d.status === 'DELIVERED' || d.status === 'ACKNOWLEDGED' ? 'DONE' : d.status === 'FAILED' ? 'BROKEN' : 'WAITING')}>
                {humanize(d.channel)} · {humanize(d.status).toLowerCase()}
              </StatusPill>
            ))}
          </section>
          <section aria-label="Shipments" className="flex flex-col gap-2">
            <strong className="text-sm">Inbound shipment</strong>
            {(ships.data?.items ?? []).map((s) => (
              <div key={s.id} className="flex flex-col gap-1 rounded-md border border-border p-3 text-sm" data-testid="po-shipment">
                <span className="flex flex-wrap items-center gap-2">
                  <strong className="font-mono">{s.reference}</strong>
                  <StatusPill tone="info">{s.stageLabel ?? humanize(s.stage)}</StatusPill>
                  <span className="text-muted">
                    {humanize(s.mode)} · {s.carrier ?? '—'} {s.trackingRef ?? ''} · ETA {shortDate(s.eta)}
                  </span>
                </span>
                <span className="text-xs text-muted">
                  ASN: {s.lines.map((l) => `${l.qty} × ${l.sku} lot ${l.lotNo ?? '—'} exp ${l.expDate ?? '—'}`).join(' · ')}
                </span>
                <span className="flex flex-wrap items-center gap-2 text-xs text-muted" data-testid="po-shipment-documents">
                  Documents:
                  {s.documents.map((d) => (
                    <span key={d.name} className="inline-flex items-center gap-1">
                      <StatusPill tone={toneOf(d.status)}>
                        {d.name} · {humanize(d.status).toLowerCase()}
                      </StatusPill>
                      {retailEdit && d.status !== 'OK' ? (
                        <button type="button" className={smallButton} onClick={() => setDoc.mutate({ id: s.id, name: d.name, status: 'OK' })}>
                          Mark {d.name.toLowerCase()} OK
                        </button>
                      ) : null}
                      {retailEdit && d.status === 'OK' ? (
                        <button type="button" className={smallButton} onClick={() => setDoc.mutate({ id: s.id, name: d.name, status: 'HOLD' })}>
                          Hold
                        </button>
                      ) : null}
                    </span>
                  ))}
                </span>
                <span className="flex flex-wrap gap-2">
                  {retailEdit && !['DELIVERED_TO_FC', 'RECEIVED'].includes(s.stage) ? <button type="button" className={smallButton} onClick={() => advance.mutate({ id: s.id, version: s.version })}>Next milestone</button> : null}
                  {retailEdit && s.stage !== 'RECEIVED' ? <button type="button" className={smallButton} onClick={() => openGrn.mutate(s.id)}>Receive at the dock</button> : null}
                  <Link className={smallButton} href={`/inventory?tab=inbound`}>
                    Inbound ASN <ArrowRight size={12} aria-hidden />
                  </Link>
                </span>
              </div>
            ))}
            {retailEdit && ['CONFIRMED', 'SENT', 'APPROVED'].includes(po.status) && !po.shipmentBooked ? <BookShipment po={po} /> : null}
            {retailEdit && ['CONFIRMED', 'PARTIALLY_RECEIVED'].includes(po.status) && !po.shipmentBooked ? (
              <button type="button" className={cx(smallButton, 'self-start')} onClick={() => openGrn.mutate(undefined)}>
                Receive without an ASN
              </button>
            ) : null}
          </section>
          <section aria-label="Receipts" className="flex flex-col gap-1 text-sm">
            <strong>Goods receipts</strong>
            {(receipts.data?.items ?? []).map((r) => (
              <Link key={r.id} href={`/inventory?tab=receiving&grn=${r.id}`} className="text-link">
                {r.number} · {humanize(r.status).toLowerCase()} · {r.postedLots.length} lots posted
              </Link>
            ))}
            {!receipts.data?.items.length ? <span className="text-muted">None yet.</span> : null}
          </section>
          <section aria-label="Invoices" className="flex flex-col gap-2 text-sm">
            <strong>Supplier invoices · three-way match</strong>
            {(invoices.data?.items ?? []).map((i) => (
              <div key={i.id} className="flex flex-col gap-0.5" data-testid="po-invoice">
                <span className="flex flex-wrap items-center gap-2">
                  {i.invoiceNumber} · {money(i.totalMinor, i.currency)}
                  <StatusPill tone={toneOf(i.status)}>{humanize(i.status)}</StatusPill>
                </span>
                {i.match ? (
                  <span className="text-xs text-muted">
                    PO price × received {money(i.match.expectedTotalMinor, i.currency)} vs invoiced {money(i.match.invoicedTotalMinor, i.currency)}
                    {i.match.reasons.length ? ` · ${i.match.reasons.join(' · ')}` : ''}
                  </span>
                ) : null}
              </div>
            ))}
            {retailEdit && po.lines.some((l) => l.qtyReceived > 0) ? (
              <form
                className="flex flex-wrap items-end gap-2"
                onSubmit={(e) => {
                  e.preventDefault();
                  if (inv.number.trim()) register.mutate(undefined);
                }}
              >
                <TextField label="Invoice number" value={inv.number} onChange={(e) => setInv({ ...inv, number: e.target.value })} />
                <TextField label="Invoice date" type="date" value={inv.date} onChange={(e) => setInv({ ...inv, date: e.target.value })} />
                <Button type="submit" size="sm" variant="neutral" loading={register.isPending}>
                  Register invoice (received quantities)
                </Button>
              </form>
            ) : null}
          </section>
          <section aria-label="History" className="flex flex-col gap-1 text-xs">
            <strong className="text-sm">History</strong>
            {po.history.map((h, i) => (
              <span key={`${h.at}-${i}`}>
                {dateTime(h.at)} · {humanize(h.status)} · {h.by ?? ''}
                {h.note ? ` — ${h.note}` : ''}
              </span>
            ))}
          </section>
        </div>
      ) : null}
    </Sheet>
  );
}

/** AfroTransact Retail purchase orders: one table, the new-PO sheet and the PO sheet. */
export function PurchaseOrdersPanel() {
  const api = useHub();
  const qc = useQueryClient();
  const { retailEdit } = useHubAccess();
  const state = useTableState({ searchParams: useSearchParams(), prefix: 'po' });
  const [id, setId] = useParam('po');
  const [creating, setCreating] = useState(false);
  const query = useQuery(inventoryHubQueries.pos(api, { page: 0, size: 200 }));
  const columns = useMemo<ColumnDef<PurchaseOrder>[]>(
    () => [
      { id: 'number', accessorFn: (p) => p.number, header: 'PO', size: 140, meta: { kind: 'mono' } },
      { id: 'supplierCode', accessorFn: (p) => p.supplierCode, header: 'Supplier', size: 120, meta: { kind: 'strong' } },
      { id: 'destinationNodeId', accessorFn: (p) => p.destinationNodeId, header: 'Ship to', size: 100 },
      { id: 'skus', accessorFn: (p) => p.lines.map((l) => l.sku).join(', '), header: 'Items', size: 200 },
      { id: 'expectedDate', accessorFn: (p) => p.expectedDate ?? '', header: 'Expected', size: 110, meta: { kind: 'date' }, cell: ({ row }) => shortDate(row.original.expectedDate) },
      { id: 'totalMinor', accessorFn: (p) => p.totalBaseMinor, header: 'Total (USD)', size: 120, meta: { kind: 'money', align: 'end' }, cell: ({ row }) => money(row.original.totalBaseMinor, row.original.baseCurrency) },
      { id: 'status', accessorFn: (p) => humanize(p.status), header: 'Status', size: 150, meta: { kind: 'status' }, cell: ({ row }) => <StatusPill tone={toneOf(row.original.status)}>{humanize(row.original.status)}</StatusPill> }
    ],
    []
  );
  return (
    <>
      <DataTable<PurchaseOrder>
        id="staff.retail.pos"
        label="Purchase orders"
        columns={columns}
        data={query.data?.items ?? []}
        getRowId={(p) => p.id}
        rowLabel={(p) => p.number}
        rowTestId="po-row"
        state={state}
        mode="client"
        loading={query.isPending}
        fetching={query.isFetching}
        error={query.isError ? <ErrorState error={query.error} onRetry={() => void query.refetch()} /> : undefined}
        onRowClick={(p) => setId(p.id)}
        activeRowId={id}
        toolbar={retailEdit ? <Button size="sm" icon={<Plus size={16} aria-hidden />} onClick={() => setCreating(true)}>New purchase order</Button> : undefined}
        empty="No purchase orders yet."
        onExport={(req) => exportDataset(qc, req)}
        exportsHref="/exports"
      />
      <Sheet open={creating} onOpenChange={setCreating} title="New purchase order" width={760}>
        <PoForm
          onDone={(po) => {
            setCreating(false);
            setId(po.id);
          }}
        />
      </Sheet>
      <PoSheet id={creating ? null : id} onClose={() => setId(null)} />
    </>
  );
}
