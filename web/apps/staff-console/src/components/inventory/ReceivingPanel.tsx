'use client';

import { type InboundReceiptResult, type InboundShipment, inventoryHubQueries, receiptVariance, type ReceiveLineInput } from '@afrotransact/api-client';
import { Button, Checkbox, cx, parseGs1, TextField } from '@afrotransact/ui';
import { Barcode as BarcodeIcon } from '@phosphor-icons/react/ssr';
import { useQuery } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';

import { RetailReceiptScreen } from '@/components/retail/ReceiptScreen';
import { Card } from '@/components/ui/Card';
import { ErrorState, Spinner } from '@/components/ui/States';
import { StatusPill } from '@/components/ui/StatusPill';
import { idempotencyKey, shortDate } from '@/lib/format';

import { Timeline, useRetailItems } from './InboundPanel';
import { smallButton, todayIso, toneOf, useHub, useHubAccess, useHubMutation, useParam } from './shared';

interface Count {
  good: string;
  damaged: string;
  lotNo: string;
  mfgDate: string;
  expDate: string;
  bin: string;
  hold: boolean;
}

/** A scanned GS1 element string as AI → value, or null when it is not one. */
function parseGs1Safe(text: string): Map<string, string> | null {
  try {
    return new Map(parseGs1(text).map((e) => [e.ai, e.value]));
  } catch {
    return null;
  }
}

const VARIANCE: Record<string, [string, string]> = {
  match: ['Matches the ASN', 'text-success'],
  short: ['Short', 'text-warning'],
  over: ['Over (within 2 %)', 'text-warning'],
  overBlocked: ['Over the ASN', 'text-danger']
};

/** Counting one inbound shipment at the dock: scan, count good and damaged per line, confirm the batch, post. */
function InboundReceiving({ shipment }: { shipment: InboundShipment }) {
  const api = useHub();
  const items = useRetailItems();
  const { inventoryEdit } = useHubAccess();
  const s = shipment;
  const fresh = (): Record<number, Count> =>
    Object.fromEntries(s.lines.map((l) => [l.lineNo, { good: '', damaged: '', lotNo: l.lotNo ?? '', mfgDate: l.mfgDate ?? '', expDate: l.expDate ?? '', bin: '', hold: false }]));
  const [counts, setCounts] = useState<Record<number, Count>>(fresh);
  const [boxes, setBoxes] = useState<string[]>([]);
  const [scan, setScan] = useState('');
  const [scanMsg, setScanMsg] = useState<string | null>(null);
  const [key, setKey] = useState(idempotencyKey);
  const [result, setResult] = useState<InboundReceiptResult | null>(null);
  useEffect(() => setCounts(fresh()), [s.id]); // eslint-disable-line react-hooks/exhaustive-deps
  const set = (lineNo: number, patch: Partial<Count>) => setCounts((c) => ({ ...c, [lineNo]: { ...(c[lineNo] as Count), ...patch } }));
  const byGtin = useMemo(() => new Map([...items.values()].map((i) => [i.gtin.padStart(14, '0'), i.sku])), [items]);

  const onScan = () => {
    const v = scan.trim();
    setScan('');
    if (!v) return;
    const box = s.boxes.find((b) => b.lpn === v.toUpperCase());
    if (box) {
      setBoxes((b) => [...new Set([...b, box.lpn])]);
      box.contents.forEach((c) => {
        const line = s.lines.find((l) => l.sku === c.sku);
        if (line) set(line.lineNo, { good: String(Number(counts[line.lineNo]?.good || 0) + c.qty) });
      });
      setScanMsg(`Box ${box.lpn}: ${box.units} units counted`);
      return;
    }
    const gs1 = parseGs1Safe(v);
    if (gs1) {
      const gtin = gs1.get('01');
      const sku = gtin ? byGtin.get(gtin) : undefined;
      const line = s.lines.find((l) => l.sku === sku);
      if (!line) {
        setScanMsg(`No line of ${s.number} for GTIN ${gtin ?? '?'}`);
        return;
      }
      const yymmdd = (x: string | undefined) => (x ? `20${x.slice(0, 2)}-${x.slice(2, 4)}-${x.slice(4, 6)}` : undefined);
      const pack = Number(gs1.get('30') ?? line.casePack ?? 1);
      const c = counts[line.lineNo];
      set(line.lineNo, { good: String(Number(c?.good || 0) + pack), lotNo: gs1.get('10') ?? c?.lotNo ?? '', expDate: yymmdd(gs1.get('17')) ?? c?.expDate ?? '', mfgDate: yymmdd(gs1.get('11')) ?? c?.mfgDate ?? '' });
      setScanMsg(`Case of ${line.sku}: +${pack} · lot ${gs1.get('10') ?? '—'}`);
      return;
    }
    const line = s.lines.find((l) => l.unitLabel === v.toUpperCase() || l.sku === v.toUpperCase());
    if (line) {
      set(line.lineNo, { good: String(Number(counts[line.lineNo]?.good || 0) + 1) });
      setScanMsg(`${line.sku}: +1`);
    } else setScanMsg(`${v} is not on ${s.number}: units of other owners are never received here`);
  };

  const errors: Record<string, string> = {};
  s.lines.forEach((l) => {
    const c = counts[l.lineNo];
    if (!c) return;
    if (c.good && !/^\d+$/.test(c.good)) errors[`${l.lineNo}.good`] = 'Whole units';
    if (c.damaged && !/^\d+$/.test(c.damaged)) errors[`${l.lineNo}.damaged`] = 'Whole units';
    if ((c.good || c.damaged) && c.expDate && c.expDate < todayIso()) errors[`${l.lineNo}.expDate`] = 'This batch has expired: do not receive it';
  });
  const lines: ReceiveLineInput[] = s.lines
    .map((l) => ({ l, c: counts[l.lineNo] }))
    .filter(({ c }) => c && (Number(c.good) > 0 || Number(c.damaged) > 0))
    .map(({ l, c }) => ({
      sku: l.sku,
      qtyGood: Number(c?.good || 0),
      qtyDamaged: Number(c?.damaged || 0),
      lotNo: c?.lotNo.trim() || undefined,
      mfgDate: c?.mfgDate || undefined,
      expDate: c?.expDate || undefined,
      bin: c?.bin.trim() || undefined,
      holdReason: c?.hold ? 'QC hold at receiving' : undefined
    }));
  const receive = useHubMutation(
    () => api.receive(s.id, lines, key, boxes.length ? boxes : undefined),
    (r) => `${r.receiptRef ?? 'Receipt'} posted: ${r.lots.length} lots created.`,
    (r) => {
      setResult(r);
      setKey(idempotencyKey());
      setCounts(fresh());
      setBoxes([]);
    }
  );
  const close = useHubMutation(() => api.close(s.id), (x) => `${x.number} closed${x.discrepancy ? ' with discrepancies' : ''}.`);

  return (
    <div className="flex flex-col gap-3" data-testid="receiving-screen">
      <div className="flex flex-wrap items-center gap-2">
        <strong>{s.number}</strong>
        <StatusPill tone={toneOf(s.status)}>{s.status === 'SHIPPED' ? 'Arriving' : 'Receiving'}</StatusPill>
        <span className="text-sm text-muted">
          {s.ownerOrg} → {s.nodeId} · {s.receivedUnits} of {s.expectedUnits} received
        </span>
      </div>
      <Timeline shipment={s} />
      <form
        className="w-full"
        onSubmit={(e) => {
          e.preventDefault();
          onScan();
        }}
      >
        <TextField
          label="Barcode, LPN, GS1-128 or unit label (press Enter)"
          value={scan}
          onChange={(e) => setScan(e.target.value)}
          placeholder="Enter LPN, GS1-128, or SKU/unit label… (e.g. ATF…)"
          className="w-full"
          autoComplete="off"
        />
      </form>
      {scanMsg ? <span role="status" className="text-sm">{scanMsg}</span> : null}
      {boxes.length ? <span className="text-xs text-muted">Boxes scanned: {boxes.join(', ')}</span> : null}
      {s.lines.map((l) => {
        const c = counts[l.lineNo];
        if (!c) return null;
        const remaining = Math.max(0, l.expectedQty - l.receivedGood - l.receivedDamaged);
        const touched = c.good !== '' || c.damaged !== '';
        const v = receiptVariance(remaining, Number(c.good || 0), Number(c.damaged || 0));
        const [label, cls] = VARIANCE[v.kind] ?? ['', ''];
        return (
          <fieldset key={l.lineNo} className="m-0 flex flex-col gap-2 rounded-md border border-border p-3" aria-label={`Count ${l.sku}`} data-testid="receive-line">
            <legend className="px-1 text-sm font-semibold">
              {items.get(l.sku)?.name ?? l.sku} <span className="font-mono text-xs text-muted">{l.sku}</span>
            </legend>
            <span className="text-xs text-muted">
              ASN: {l.expectedQty} units{l.casePack ? ` (${l.cases} cases of ${l.casePack})` : ''} · lot {l.lotNo ?? '—'} · exp {shortDate(l.expDate)} · already received {l.receivedGood + l.receivedDamaged}
            </span>
            <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,130px),1fr))] gap-2">
              <TextField label="Good units" inputMode="numeric" value={c.good} onChange={(e) => set(l.lineNo, { good: e.target.value })} error={errors[`${l.lineNo}.good`]} />
              <TextField label="Damaged units" inputMode="numeric" value={c.damaged} onChange={(e) => set(l.lineNo, { damaged: e.target.value })} error={errors[`${l.lineNo}.damaged`]} />
              <TextField label="Lot / batch" value={c.lotNo} onChange={(e) => set(l.lineNo, { lotNo: e.target.value })} />
              <TextField label="Made on" type="date" value={c.mfgDate} onChange={(e) => set(l.lineNo, { mfgDate: e.target.value })} />
              <TextField label="Expires on" type="date" value={c.expDate} onChange={(e) => set(l.lineNo, { expDate: e.target.value })} error={errors[`${l.lineNo}.expDate`]} />
              <TextField label="Bin" value={c.bin} onChange={(e) => set(l.lineNo, { bin: e.target.value })} placeholder="A-01-02" />
            </div>
            <div className="flex flex-wrap items-center gap-3">
              <Checkbox label="QC hold (receive into quarantine)" checked={c.hold} onChange={(e) => set(l.lineNo, { hold: e.target.checked })} />
              {touched ? (
                <span className={cx('text-sm font-semibold', cls)} data-testid="variance">
                  {label} {v.diff ? `(${v.diff > 0 ? '+' : ''}${v.diff})` : ''}
                </span>
              ) : null}
              {touched && Number(c.damaged) > 0 ? <span className="text-xs text-warning">{c.damaged} damaged units go to a -DMG quarantine lot</span> : null}
            </div>
          </fieldset>
        );
      })}
      {inventoryEdit ? (
        <div className="flex flex-wrap gap-2">
          <Button loading={receive.isPending} disabled={lines.length === 0 || Object.keys(errors).length > 0} onClick={() => receive.mutate(undefined)}>
            Post receipt
          </Button>
          {s.status === 'RECEIVING' ? (
            <Button variant="neutral" loading={close.isPending} onClick={() => close.mutate(undefined)}>
              Close receiving
            </Button>
          ) : null}
        </div>
      ) : null}
      {result ? (
        <Card title={`Lots created by ${result.receiptRef ?? 'the receipt'}`}>
          <ul className="m-0 flex list-none flex-col gap-1 p-0 text-sm" data-testid="created-lots">
            {result.lots.map((l) => (
              <li key={l.id} className="flex flex-wrap gap-2">
                <span className="font-mono">{l.lotNo}</span>
                <span>{l.sku}</span>
                <span>{l.qtyOnHand} units</span>
                <span>exp {shortDate(l.expDate)}</span>
                <StatusPill tone={toneOf(l.status)}>{l.status === 'SELLABLE' ? 'Available' : l.status.toLowerCase()}</StatusPill>
                <a className="text-link" href={`/inventory?tab=stock&lot=${l.id}`}>Open lot</a>
              </li>
            ))}
          </ul>
        </Card>
      ) : null}
    </div>
  );
}

/** Receiving at the fulfilment centre: what is arriving, the count at the dock, and retail goods receipts. */
export function ReceivingPanel() {
  const api = useHub();
  const [receive, setReceive] = useParam('receive');
  const [grn, setGrn] = useParam('grn');
  const shipped = useQuery(inventoryHubQueries.inbound(api, { status: 'SHIPPED', page: 0, size: 100 }));
  const receiving = useQuery(inventoryHubQueries.inbound(api, { status: 'RECEIVING', page: 0, size: 100 }));
  const { retailView } = useHubAccess();
  const openGrns = useQuery({ ...inventoryHubQueries.receipts(api, { status: 'OPEN', page: 0, size: 50 }), enabled: retailView });
  const arriving = [...(receiving.data?.items ?? []), ...(shipped.data?.items ?? [])];
  // The open shipment is read by id: it moves from SHIPPED to RECEIVING (and CLOSED) while it is counted, and the two
  // lists refetch one after the other, so looking it up in them would unmount the counting screen and its results.
  const opened = useQuery({ ...inventoryHubQueries.shipment(api, receive ?? ''), enabled: receive !== null });
  const current = opened.data ?? arriving.find((s) => s.id === receive);
  const retailShipments = useQuery({ ...inventoryHubQueries.retailShipments(api), enabled: retailView });
  const openGrn = useHubMutation((shipmentRef: string) => {
    const sh = retailShipments.data?.items.find((x) => x.reference === shipmentRef);
    if (!sh) throw new Error(`No retail shipment ${shipmentRef}`);
    return api.retail.openReceipt({ shipmentId: sh.id, dock: 'Dock 1' });
  }, (r) => `${r.number} opened against ${r.poNumber ?? 'the PO'}.`, (r) => {
    setReceive(null);
    setGrn(r.id);
  });
  return (
    <div className="grid gap-4 lg:grid-cols-[minmax(260px,340px)_minmax(0,1fr)]">
      <Card title="At the dock">
        {shipped.isPending || receiving.isPending ? <Spinner /> : null}
        {shipped.isError ? <ErrorState error={shipped.error} onRetry={() => void shipped.refetch()} /> : null}
        <ul className="m-0 flex list-none flex-col gap-1 p-0" aria-label="Shipments to receive">
          {arriving.map((s) => (
            <li key={s.id}>
              <button
                type="button"
                data-testid="dock-shipment"
                aria-pressed={s.id === receive}
                onClick={() => {
                  setGrn(null);
                  if (s.source === 'RETAIL_ASN' && retailView && s.externalRef) openGrn.mutate(s.externalRef);
                  else setReceive(s.id);
                }}
                className={cx('flex w-full flex-col gap-0.5 rounded-md border px-3 py-2 text-left text-sm', s.id === receive ? 'border-ink-1000 bg-surface-muted' : 'border-border bg-surface hover:bg-surface-muted')}
              >
                <span className="flex items-center gap-2">
                  <strong className="flex-1 font-mono text-xs">{s.number}</strong>
                  <StatusPill tone={toneOf(s.status)}>{s.status === 'SHIPPED' ? 'Arriving' : 'Receiving'}</StatusPill>
                </span>
                <span className="text-xs text-muted">
                  {s.source === 'RETAIL_ASN' ? 'Retail PO · goods receipt' : 'FBA'} · {s.nodeId} · {s.expectedUnits} units · ETA {shortDate(s.expectedArrival)}
                </span>
              </button>
            </li>
          ))}
          {!arriving.length && !shipped.isPending ? <li className="text-sm text-muted">Nothing is arriving.</li> : null}
        </ul>
        {retailView && openGrns.data?.items.length ? (
          <>
            <h3 className="m-0 text-sm font-bold">Open goods receipts</h3>
            <ul className="m-0 flex list-none flex-col gap-1 p-0">
              {openGrns.data.items.map((r) => (
                <li key={r.id}>
                  <button type="button" className={cx(smallButton, 'w-full justify-between')} onClick={() => { setReceive(null); setGrn(r.id); }}>
                    <span className="font-mono text-xs">{r.number}</span>
                    <span className="text-xs text-muted">{r.poNumber}</span>
                  </button>
                </li>
              ))}
            </ul>
          </>
        ) : null}
      </Card>
      <div className="min-w-0">
        {grn ? (
          <RetailReceiptScreen id={grn} />
        ) : current ? (
          <InboundReceiving key={current.id} shipment={current} />
        ) : (
          <Card>
            <p className="m-0 text-sm text-muted">
              Pick a shipment at the dock. FBA shipments are counted here line by line; AfroTransact Retail shipments open a goods receipt against their purchase order (over-receipts above 2 % need
              purchasing approval).
            </p>
          </Card>
        )}
      </div>
    </div>
  );
}
