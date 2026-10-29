'use client';

import { inventoryHubQueries, type TraceStep } from '@afrotransact/api-client';
import { Button, cx, TextField } from '@afrotransact/ui';
import { ArrowRight, CheckCircle, Circle, WarningCircle } from '@phosphor-icons/react/ssr';
import { useQuery } from '@tanstack/react-query';
import Link from 'next/link';
import { useState } from 'react';

import { Card } from '@/components/ui/Card';
import { ErrorState, Spinner } from '@/components/ui/States';
import { StatusPill } from '@/components/ui/StatusPill';
import { useStorefrontUrl } from '@/lib/runtime-config';

import { toneOf, useHub, useParam } from './shared';

const LIFECYCLES: { label: string; states: [string, string][]; note: string }[] = [
  { label: 'Purchase order', states: [['Draft', 'DRAFT'], ['Pending approval', 'PENDING_APPROVAL'], ['Approved', 'APPROVED'], ['Sent to supplier', 'SENT'], ['Confirmed', 'CONFIRMED'], ['Received', 'RECEIVED'], ['Closed', 'CLOSED']], note: 'Cancel is allowed until a shipment is booked.' },
  { label: 'Inbound shipment', states: [['Draft', 'WORKING'], ['Shipped', 'SHIPPED'], ['In transit', 'SHIPPED'], ['Arrived', 'SHIPPED'], ['Receiving', 'RECEIVING'], ['Closed', 'CLOSED']], note: 'Shipping makes the units inbound at the FC; closing reports what never arrived as short.' },
  { label: 'Lot (batch)', states: [['Sellable', 'SELLABLE'], ['Expiring (≤ 60 d)', 'QUARANTINE'], ['Quarantine', 'QUARANTINE'], ['Expired', 'EXPIRED'], ['Written off', 'WRITTEN_OFF']], note: 'Quarantine → release (QA pass) or write off (Approve). Expiry runs every night.' },
  { label: 'Listing', states: [['Draft', 'DRAFT'], ['Live', 'LIVE'], ['Paused', 'PAUSED']], note: 'Listing points the storefront offer at the retail SKU; stock always comes from lots, never typed in.' }
];

const FIELDS: [string, string, string][] = [
  ['SKU · GTIN', 'Item master · when the retail item is created', 'Unique; GTIN check digit from the AfroTransact GS1 prefix'],
  ['Ordered quantity · unit cost', 'Buyer · on the PO', 'Whole cases; above the buyer’s limit needs approval; currency and FX rate stored'],
  ['Case pack', 'Supplier item · on the ASN line', 'Quantities are sent in whole cases'],
  ['Lot / batch number', 'Packing list (ASN) → receiver scans the GS1-128 case label (AI 10)', 'Letters, digits, . _ / -; unique per SKU and FC'],
  ['Manufacture date', 'Packing list (AI 11) → confirmed at receiving', 'Not in the future; before expiry'],
  ['Expiry / best-before', 'Packing list (AI 17) → confirmed at receiving', 'Required for food, chilled and frozen items; short-dated lots go to quarantine'],
  ['Received good / damaged', 'Receiver counts at the dock', 'Damaged units become a -DMG quarantine lot; over 2 % needs approval; shortages raise claims'],
  ['Bin location', 'Receiver at put-away', 'Chilled and frozen items go to K- bins'],
  ['Available to sell', 'System, continuously', 'Sellable lots − reservations; pushed to offers, search and the product page on every change']
];

function StepIcon({ status }: { status: TraceStep['status'] }) {
  if (status === 'DONE') return <CheckCircle size={20} weight="fill" className="shrink-0 text-success" aria-hidden />;
  if (status === 'BROKEN') return <WarningCircle size={20} weight="fill" className="shrink-0 text-danger" aria-hidden />;
  return <Circle size={20} className="shrink-0 text-muted" aria-hidden />;
}

/** Follows one product from its retail item to the storefront product page and names the broken link. */
export function TraceCard() {
  const api = useHub();
  const storefront = useStorefrontUrl();
  const [trace, setTrace] = useParam('trace');
  const [input, setInput] = useState(trace ?? '');
  const target = (trace ?? '').trim().toUpperCase();
  const q = /^AT[0-9A-Z]{8}$/.test(target) ? { atin: target } : { sku: target };
  const query = useQuery(inventoryHubQueries.trace(api, target ? q : {}));
  const t = query.data;
  return (
    <Card title="Trace a product to the storefront">
      <p className="m-0 text-sm text-muted">
        Follows one AfroTransact-fulfilled product step by step (retail item, inbound shipment, lots, available stock, offer, search index, product page) and shows the first step that is broken or missing, with a link to fix it.
      </p>
      <form
        className="flex flex-wrap at-align gap-2"
        onSubmit={(e) => {
          e.preventDefault();
          setTrace(input.trim() || null);
        }}
      >
        <TextField label="Variant ATIN or inventory SKU" value={input} onChange={(e) => setInput(e.target.value)} placeholder="ATJOLLOF01 or AT-JOL-200061" className="min-w-[240px] flex-1" />
        <Button type="submit">Trace</Button>
      </form>
      {target && query.isPending ? <Spinner /> : null}
      {query.isError ? <ErrorState error={query.error} onRetry={() => void query.refetch()} /> : null}
      {t ? (
        <div className="flex flex-col gap-3" data-testid="trace">
          <div className="flex flex-wrap items-baseline gap-2 text-sm">
            <strong className="text-md">{t.title || t.sku}</strong>
            {t.sku ? <span className="font-mono text-xs text-muted">SKU {t.sku}</span> : null}
            {t.atin ? <span className="font-mono text-xs text-muted">ATIN {t.atin}</span> : null}
            <span className="flex-1" />
            {t.availableUnits !== null ? <span className="text-muted">{t.availableUnits} available</span> : null}
            {t.badges.includes('FULFILLED_BY_AFROTRANSACT') ? <StatusPill tone="brand">Fulfilled by AfroTransact</StatusPill> : null}
            {t.deliveryDays !== null ? <span className="text-muted">Delivery in {t.deliveryDays} days</span> : null}
          </div>
          <ol className="m-0 grid list-none grid-cols-[repeat(auto-fill,minmax(min(100%,220px),1fr))] gap-2 p-0" aria-label="Storefront pipeline">
            {t.steps.map((s) => (
              <li key={s.key} data-step={s.key} data-status={s.status} className={cx('flex flex-col gap-1 rounded-md border p-3 text-sm', s.status === 'BROKEN' ? 'border-danger bg-danger-bg' : 'border-border bg-surface')}>
                <span className="flex items-center gap-2 font-semibold">
                  <StepIcon status={s.status} />
                  {s.title}
                </span>
                <span className="text-xs text-muted [overflow-wrap:anywhere]">{s.detail}</span>
                {s.href ? (
                  <Link href={s.href} className="text-xs font-semibold text-link">
                    Open
                  </Link>
                ) : null}
              </li>
            ))}
          </ol>
          {t.storefrontPath ? (
            <a href={`${storefront}${t.storefrontPath}`} target="_blank" rel="noreferrer" className="inline-flex items-center gap-1 text-sm font-semibold text-link">
              Open the product page <ArrowRight size={14} aria-hidden />
            </a>
          ) : null}
        </div>
      ) : null}
    </Card>
  );
}

/** Workflow: from purchase order to “Sold & shipped by AfroTransact”, with what is waiting at each step. */
export function FlowPanel() {
  const api = useHub();
  const flow = useQuery(inventoryHubQueries.flow(api));
  return (
    <div className="flex flex-col gap-4">
      <Card>
        <h2 className="m-0 text-xl font-black">From purchase order to “Sold &amp; shipped by AfroTransact”</h2>
        <p className="m-0 text-sm text-muted">
          Stock enters the platform only through receiving: a purchase order (AfroTransact Retail) or a seller’s inbound plan (FBA) announces what is coming and which batches are in it, the
          fulfilment centre counts it at the dock, every batch becomes a lot with its expiry, and available lots flow to the offer, the search index and the product page.
        </p>
      </Card>
      <Card title="Buying stock: where to start">
        <ol className="m-0 flex list-decimal flex-col gap-1 pl-5 text-sm">
          <li><Link className="font-semibold text-link" href="/retail?tab=suppliers">Register the supplier</Link> (AfroTransact Retail › Suppliers): name, country, currency, payment terms, lead time and contacts. Suppliers never sign in; they are records we buy from.</li>
          <li><Link className="font-semibold text-link" href="/retail?tab=items">Add the items</Link> you buy from them (SKU, supplier SKU, case pack, unit cost), then <Link className="font-semibold text-link" href="/retail?tab=pos">raise a purchase order</Link>: supplier, ship-to fulfilment centre, Incoterm, expected date and the lines.</li>
          <li>When the supplier confirms, book the inbound shipment on the PO (mode, carrier, tracking, dates, batch and expiry per line) and enter its costs: freight, insurance, duty and so on.</li>
          <li>The fulfilment centre <Link className="font-semibold text-link" href="/inventory?tab=receiving">receives it here</Link>: counted units become lots, with the costs spread over them as landed cost.</li>
        </ol>
      </Card>
      {flow.isError ? <ErrorState error={flow.error} onRetry={() => void flow.refetch()} /> : null}
      <ol className="m-0 grid list-none grid-cols-[repeat(auto-fill,minmax(min(100%,250px),1fr))] gap-3 p-0" aria-label="Inventory workflow">
        {(flow.data?.stages ?? []).map((s, i) => (
          <li key={s.key}>
            <Link href={s.href} className="flex h-full flex-col gap-2 rounded-md border border-border bg-surface p-4 text-inherit no-underline hover:border-border-strong" data-testid="flow-step">
              <span className="flex items-center gap-2">
                <span className="flex size-7 shrink-0 items-center justify-center rounded-full bg-ink-1000 text-sm font-bold text-gold-500">{i + 1}</span>
                <strong className="flex-1">{s.title}</strong>
              </span>
              <span className="text-xs text-muted">
                <strong className="text-text">Who:</strong> {s.who}
              </span>
              <span className="text-sm">{s.what}</span>
              <span className="text-xs text-muted">
                <strong className="text-text">System:</strong> {s.effect}
              </span>
              <span className="mt-auto border-t border-border pt-2 text-xs font-bold text-success">
                {s.count === null ? `Unavailable (${s.unavailable ?? 'service'})` : `${s.count} ${s.countLabel}`} →
              </span>
            </Link>
          </li>
        ))}
      </ol>
      {flow.isPending ? <Spinner /> : null}
      <TraceCard />
      <Card title="Status lifecycles">
        {LIFECYCLES.map((lc) => (
          <div key={lc.label} className="flex flex-col gap-1.5">
            <span className="text-xs font-bold text-muted">{lc.label}</span>
            <div className="flex flex-wrap items-center gap-1.5">
              {lc.states.map(([label, status], i) => (
                <span key={label} className="inline-flex items-center gap-1.5">
                  {i > 0 ? <ArrowRight size={12} className="text-muted" aria-hidden /> : null}
                  <StatusPill tone={toneOf(status)}>{label}</StatusPill>
                </span>
              ))}
            </div>
            <span className="text-xs text-muted">{lc.note}</span>
          </div>
        ))}
      </Card>
      <Card title="Where every batch field comes from">
        <div className="overflow-x-auto">
          <table className="at-table w-full text-sm" aria-label="Batch fields">
            <thead className="bg-surface-muted text-left text-xs uppercase text-muted">
              <tr>
                <th scope="col" className="px-3 py-2">Field</th>
                <th scope="col" className="px-3 py-2">Source</th>
                <th scope="col" className="px-3 py-2">Checks</th>
              </tr>
            </thead>
            <tbody>
              {FIELDS.map(([f, src, rule]) => (
                <tr key={f} className="border-t border-border align-top">
                  <td className="px-3 py-2 font-semibold">{f}</td>
                  <td className="px-3 py-2">{src}</td>
                  <td className="px-3 py-2 text-muted">{rule}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>
    </div>
  );
}
