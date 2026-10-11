/* Mock BFF for the Inventory hub, AfroTransact Retail (1P) and Seller Center FBA (S281): an in-memory inventory and
   retail back end behind /v1/staff/inventory/**, /v1/staff/retail/** and /v1/seller/fba/**, with the same rules the
   services apply (FEFO, whole cases, lots from receipts, cycle-count variances posted as ADJUST, over-receipt
   approval, three-way match). State is process-wide (the app server), so journeys run one at a time. */
import { http, HttpResponse, type JsonBodyType } from 'msw';

import {
  type CycleCount,
  type CycleCountLine,
  type GoodsReceipt,
  type InboundLine,
  type InboundShipment,
  type Lot,
  type Movement,
  OVER_RECEIPT_TOLERANCE,
  type PurchaseOrder,
  type RetailItem,
  type RetailShipment,
  type Supplier,
  type SupplierInvoice
} from '../inventory-hub';
import { bffProblemType } from '../problem-types';

/* ── State ── */

export const RETAIL_ORG = 'org_afrotransact_retail';

interface HubDb {
  seq: number;
  nodes: { id: string; name: string; kind: string; country: string; active: boolean }[];
  inbound: InboundShipment[];
  lots: Lot[];
  movements: Movement[];
  counts: CycleCount[];
  suppliers: Supplier[];
  items: RetailItem[];
  pos: PurchaseOrder[];
  shipments: RetailShipment[];
  receipts: GoodsReceipt[];
  invoices: SupplierInvoice[];
  /** offer inventory SKU per variant ATIN (the storefront link that "list on storefront" sets) */
  offers: Record<string, { inventorySku: string; status: 'ACTIVE' | 'PAUSED'; priceMinor: number }>;
}

const KEY = '__atInventoryHubDb';
type G = typeof globalThis & { [KEY]?: HubDb };

const iso = (d: Date) => d.toISOString().slice(0, 10);
const addDays = (d: Date, n: number) => new Date(d.getTime() + n * 86_400_000);
/** The seed's own rows (always present). */
function must<T>(v: T | undefined): T {
  if (v === undefined) throw new Error('mock seed row missing');
  return v;
}
const uuid = (n: number, prefix = '0') => `${prefix.padEnd(8, '0').slice(0, 8)}-0000-4000-8000-${String(n).padStart(12, '0')}`;

function seed(now: Date): HubDb {
  const db: HubDb = { seq: 100, nodes: [], inbound: [], lots: [], movements: [], counts: [], suppliers: [], items: [], pos: [], shipments: [], receipts: [], invoices: [], offers: {} };
  db.nodes = [
    { id: 'HOU-FC', name: 'Houston FC', kind: 'FULFILMENT_CENTRE', country: 'US', active: true },
    { id: 'EWR-FC', name: 'Edison FC', kind: 'FULFILMENT_CENTRE', country: 'US', active: true },
    { id: 'ATL-FC', name: 'Atlanta FC', kind: 'FULFILMENT_CENTRE', country: 'US', active: true },
    { id: 'LAX-FC', name: 'Los Angeles FC', kind: 'FULFILMENT_CENTRE', country: 'US', active: true }
  ];
  db.suppliers = [
    { id: uuid(1, 'aaaa0001'), code: 'SUP-ACC', name: 'Accra Spice Co.', country: 'GH', city: 'Tema', currency: 'GHS', paymentTerms: 'Net 30', leadTimeDays: 35, status: 'APPROVED', canReceivePurchaseOrders: true, scorecard: { receipts: 6, onTimePercent: 92, defectPercent: 0.8 }, version: 0 },
    { id: uuid(2, 'aaaa0001'), code: 'SUP-LAG', name: 'Lagos Pepper Works', country: 'NG', city: 'Lagos', currency: 'NGN', paymentTerms: 'Net 45 · 30% deposit', leadTimeDays: 42, status: 'APPROVED', canReceivePurchaseOrders: true, scorecard: { receipts: 3, onTimePercent: 88, defectPercent: 1.4 }, version: 0 }
  ];
  const item = (n: number, sku: string, gtin: string, name: string, atin: string | null, storage: string, shelf: number, priceMinor: number, listing: RetailItem['listingStatus'], casePack: number, sup: number): RetailItem => ({
    id: uuid(n, 'bbbb0001'), sku, gtin, gtin14: gtin.padStart(14, '0'), name, category: 'Groceries', atin, primarySupplierId: must(db.suppliers[sup]).id,
    supplierItems: [{ supplierId: must(db.suppliers[sup]).id, supplierSku: `${must(db.suppliers[sup]).code}-${n}`, casePack, minOrderQty: casePack, unitCostMinor: 1850 + n * 120, currency: must(db.suppliers[sup]).currency }],
    shelfLifeDays: shelf, storage, reorderPoint: 60, packSize: '8 oz', casePack, priceMinor, currency: 'USD', listingStatus: listing, version: 0
  });
  db.items = [
    item(1, 'AT-JOL-200061', '086001200069', 'AfroTransact Brand Jollof Seasoning, 8 oz', 'ATJOLLOF01', 'AMBIENT', 540, 649, 'LIVE', 24, 0),
    item(2, 'AT-SHI-200078', '086001200076', 'AfroTransact Brand Shito Pepper Sauce, 12 oz', 'ATSHITO001', 'AMBIENT', 365, 899, 'LIVE', 12, 0),
    item(3, 'AT-OKR-200085', '086001200083', 'AfroTransact Brand Chopped Okra, Frozen', null, 'FROZEN', 270, 499, 'DRAFT', 20, 1)
  ];
  db.offers = { ATJOLLOF01: { inventorySku: 'AT-JOL-200061', status: 'ACTIVE', priceMinor: 649 }, ATSHITO001: { inventorySku: 'AT-SHI-200078', status: 'ACTIVE', priceMinor: 899 } };
  const lot = (sku: string, lotNo: string, nodeId: string, qty: number, expIn: number | null, status: Lot['status'] = 'SELLABLE', reserved = 0, owner = RETAIL_ORG, bin = 'A-01-01'): Lot => {
    const exp = expIn === null ? null : iso(addDays(now, expIn));
    return {
      id: uuid(++db.seq, 'cccc0001'), sku, lotNo, nodeId, ownerOrg: owner, bin, qtyOnHand: qty, qtyReserved: reserved, qtyAvailable: status === 'SELLABLE' ? qty - reserved : 0,
      mfgDate: iso(addDays(now, -60)), expDate: exp, receivedAt: addDays(now, -40).toISOString(), unitLandedCostMinor: 312, currency: 'USD', status, reason: null,
      markdownFlag: expIn !== null && expIn <= 30 ? 'WITHIN_30' : expIn !== null && expIn <= 60 ? 'WITHIN_60' : 'NONE', receiptRef: 'GRN-SEED', version: 0
    };
  };
  db.lots = [
    lot('AT-JOL-200061', 'JOL-2604', 'HOU-FC', 48, 22, 'SELLABLE', 4, RETAIL_ORG, 'A-01-02'),
    lot('AT-JOL-200061', 'JOL-2607', 'HOU-FC', 120, 260, 'SELLABLE', 0, RETAIL_ORG, 'A-01-03'),
    lot('AT-JOL-200061', 'JOL-2609', 'EWR-FC', 72, 400, 'SELLABLE', 0, RETAIL_ORG, 'B-02-01'),
    lot('AT-SHI-200078', 'SHI-2511', 'HOU-FC', 30, 48, 'SELLABLE', 2, RETAIL_ORG, 'A-02-01'),
    lot('AT-SHI-200078', 'SHI-2509-DMG', 'HOU-FC', 3, 48, 'DAMAGED', 0, RETAIL_ORG, 'Q-01-01'),
    lot('AT-SHI-200078', 'SHI-2503', 'ATL-FC', 6, -3, 'EXPIRED', 0, RETAIL_ORG, 'A-04-02'),
    lot('ATF7K2M9QX', 'MT-0915', 'ATL-FC', 40, 180, 'SELLABLE', 0, 'org_mamatees_pantry', 'C-01-01')
  ];
  const shipLine = (lineNo: number, sku: string, atin: string | null, qty: number, casePack: number | null, lotNo: string | null, expIn: number | null): InboundLine => ({
    lineNo, sku, atin, unitLabel: atin ? `ATF${sku.slice(-7)}` : null, expectedQty: qty, receivedGood: 0, receivedDamaged: 0, shortQty: qty, overQty: 0, inboundOpen: qty,
    casePack, cases: casePack ? Math.ceil(qty / casePack) : 0, lotNo, mfgDate: lotNo ? iso(addDays(now, -20)) : null, expDate: expIn === null ? null : iso(addDays(now, expIn))
  });
  db.inbound = [
    {
      id: uuid(1, 'dddd0001'), number: 'FBA-IN-006601', source: 'FBA', externalRef: null, ownerOrg: 'org_mamatees_pantry', nodeId: 'ATL-FC', assignmentReason: 'Requested', name: 'October restock',
      status: 'SHIPPED', shipFrom: { name: "Mama Tee's Pantry", line1: '12 Bissonnet St', line2: null, city: 'Houston', region: 'TX', postalCode: '77005', country: 'US', phone: null },
      shipTo: { name: 'AfroTransact Atlanta FC', line1: '4500 Inbound Way', line2: null, city: 'Atlanta', region: 'GA', postalCode: '30349', country: 'US', phone: null },
      carrier: 'ups', trackingNumber: '1Z999AA10123456784', labelDocumentUrl: null, expectedArrival: iso(addDays(now, 1)), expectedUnits: 36, receivedUnits: 0, receiptCount: 0,
      discrepancy: false, discrepancies: [], lines: [shipLine(1, 'ATF7K2M9QX', 'ATMTPALM01', 36, 12, 'MT-1007', 300)],
      boxes: [{ boxNo: 1, lpn: 'LPN7Q4K2M9X', contents: [{ sku: 'ATF7K2M9QX', qty: 36 }], units: 36, weightLb: 22, receivedAt: null }],
      createdBy: 'seller.owner', createdAt: addDays(now, -3).toISOString(), shippedAt: addDays(now, -2).toISOString(), closedAt: null, updatedAt: addDays(now, -2).toISOString(), version: 2
    },
    {
      id: uuid(2, 'dddd0001'), number: 'ASN-SHP-2610-GH01', source: 'RETAIL_ASN', externalRef: 'SHP-2610-GH01', ownerOrg: RETAIL_ORG, nodeId: 'HOU-FC', assignmentReason: 'Purchase order destination', name: 'PO-1P-000322',
      status: 'SHIPPED', shipFrom: null, shipTo: null, carrier: 'MSC', trackingNumber: 'MSCU4829137', labelDocumentUrl: null, expectedArrival: iso(addDays(now, 9)), expectedUnits: 240, receivedUnits: 0,
      receiptCount: 0, discrepancy: false, discrepancies: [], lines: [shipLine(1, 'AT-JOL-200061', null, 240, 24, 'JOL-2610', 540)], boxes: [],
      createdBy: 'retail-service', createdAt: addDays(now, -12).toISOString(), shippedAt: addDays(now, -10).toISOString(), closedAt: null, updatedAt: addDays(now, -10).toISOString(), version: 1
    }
  ];
  const poLine = (lineNo: number, it: RetailItem, qty: number, received = 0) => ({
    lineNo, itemId: it.id, sku: it.sku, description: it.name, supplierSku: must(it.supplierItems[0]).supplierSku, qtyOrdered: qty, unitCostMinor: must(it.supplierItems[0]).unitCostMinor,
    amountMinor: qty * must(it.supplierItems[0]).unitCostMinor, qtyReceived: received, qtyDamaged: 0, qtyInvoiced: 0, openQty: qty - received
  });
  const po = (n: number, number: string, status: string, lines: ReturnType<typeof poLine>[], booked = false): PurchaseOrder => ({
    id: uuid(n, 'eeee0001'), number, supplierId: must(db.suppliers[0]).id, supplierCode: 'SUP-ACC', currency: 'GHS', fxRate: 15.42, baseCurrency: 'USD', destinationNodeId: 'HOU-FC', incoterm: 'FOB Tema',
    orderDate: iso(addDays(now, -20)), expectedDate: iso(addDays(now, 9)), status, approvalTier: 'BUYER', shipmentBooked: booked,
    totalMinor: lines.reduce((a, l) => a + l.amountMinor, 0), totalBaseMinor: Math.round(lines.reduce((a, l) => a + l.amountMinor, 0) / 15.42), lines,
    history: [{ status: 'DRAFT', at: addDays(now, -20).toISOString(), by: 'Kemi Adebayo', note: null }], createdBy: 'Kemi Adebayo', approvedBy: null, createdAt: addDays(now, -20).toISOString(), version: 1
  });
  db.pos = [po(1, 'PO-1P-000322', 'CONFIRMED', [poLine(1, must(db.items[0]), 240)], true), po(2, 'PO-1P-000323', 'DRAFT', [poLine(1, must(db.items[1]), 96)])];
  db.shipments = [{
    id: uuid(1, 'ffff0001'), reference: 'SHP-2610-GH01', poId: must(db.pos[0]).id, poNumber: 'PO-1P-000322', mode: 'OCEAN_FCL', carrier: 'MSC', trackingRef: 'MSCU4829137', destinationNodeId: 'HOU-FC',
    etd: iso(addDays(now, -10)), eta: iso(addDays(now, 9)), stage: 'DEPARTED', stageLabel: 'Departed origin', asnLocked: true,
    lines: [{ lineNo: 1, poLineNo: 1, itemId: must(db.items[0]).id, sku: 'AT-JOL-200061', qty: 240, lotNo: 'JOL-2610', mfgDate: iso(addDays(now, -20)), expDate: iso(addDays(now, 540)) }],
    cartons: [], documents: [{ name: 'Commercial invoice', status: 'RECEIVED', reference: 'CI-0322' }, { name: 'Bill of lading', status: 'RECEIVED', reference: 'MSCU4829137' }, { name: 'FDA prior notice', status: 'MISSING', reference: null }],
    milestones: [{ stage: 'BOOKED', at: addDays(now, -15).toISOString(), by: 'Kemi Adebayo' }, { stage: 'DEPARTED', at: addDays(now, -10).toISOString(), by: 'Kemi Adebayo' }], version: 3
  }];
  db.movements = db.lots.map((l, i) => ({ id: i + 1, occurredAt: l.receivedAt ?? now.toISOString(), type: 'RECEIVE', sku: l.sku, nodeId: l.nodeId, ownerOrg: l.ownerOrg, lotId: l.id, lotNo: l.lotNo, qty: l.qtyOnHand, reference: 'GRN-SEED', actor: 'Ama Owusu', reason: null }));
  return db;
}

export function getHubDb(): HubDb {
  const g = globalThis as G;
  g[KEY] ??= seed(new Date());
  return g[KEY];
}

export function resetHubDb(now: Date = new Date()): HubDb {
  const g = globalThis as G;
  g[KEY] = seed(now);
  return g[KEY];
}

/* ── Plumbing ── */

class HubProblem extends Error {
  constructor(readonly status: number, readonly code: string, readonly title: string, readonly detail: string, readonly errors?: Record<string, string>) {
    super(detail);
  }
}
const problemOf = (e: HubProblem) =>
  HttpResponse.json({ type: bffProblemType(e.code), title: e.title, status: e.status, detail: e.detail, code: e.code, ...(e.errors ? { errors: e.errors } : {}) } as JsonBodyType, {
    status: e.status,
    headers: { 'Content-Type': 'application/problem+json' }
  });
const notFound = (what: string) => new HubProblem(404, 'NOT_FOUND', 'Not found', `No such ${what}.`);
const invalid = (detail: string, errors: Record<string, string>) => new HubProblem(422, 'VALIDATION_FAILED', 'Validation failed', detail, errors);
const conflict = (detail: string) => new HubProblem(409, 'CONFLICT', 'Conflict', detail);

export type HubLevel = 'VIEW' | 'EDIT' | 'APPROVE';
/** The caller's access (throws a problem Response-able error) and, for sellers, the organisation lists are limited to. */
export type HubGate = (request: Request, area: 'inventory' | 'retail' | 'fba', level: HubLevel) => { ownerOrg: string | null; actor: string } | { status: number; code: string; title: string; detail: string };

type Ctx = { db: HubDb; ownerOrg: string | null; actor: string; request: Request; now: Date; url: URL };
type Params = { id: string; action: string };
type Fn = (ctx: Ctx, params: Params) => unknown;

function handler(gate: HubGate, area: 'inventory' | 'retail' | 'fba', level: HubLevel, fn: Fn, status = 200) {
  return async ({ request, params }: { request: Request; params: Record<string, string | readonly string[] | undefined> }) => {
    const flat: Record<string, string> = {};
    Object.entries(params).forEach(([k, v]) => {
      if (typeof v === 'string') flat[k] = v;
    });
    const g = gate(request, area, level);
    if ('status' in g) return problemOf(new HubProblem(g.status, g.code, g.title, g.detail));
    try {
      const out = await fn({ db: getHubDb(), ownerOrg: g.ownerOrg, actor: g.actor, request, now: new Date(), url: new URL(request.url) }, { id: flat.id ?? '', action: flat.action ?? '' });
      if (out instanceof Response) return out;
      const res = HttpResponse.json(out as JsonBodyType, { status });
      const version = (out as { version?: number } | null)?.version;
      if (typeof version === 'number') res.headers.set('ETag', `"${version}"`);
      return res;
    } catch (e) {
      if (e instanceof HubProblem) return problemOf(e);
      throw e;
    }
  };
}

async function bodyOf(request: Request): Promise<Record<string, unknown>> {
  try {
    const t = await request.text();
    return t ? (JSON.parse(t) as Record<string, unknown>) : {};
  } catch {
    throw new HubProblem(400, 'MALFORMED_JSON', 'Malformed request', 'The request body is not valid JSON.');
  }
}

function checkVersion(ctx: Ctx, current: number) {
  const raw = ctx.request.headers.get('if-match');
  if (!raw) return;
  const v = Number(raw.replace(/W\//, '').replace(/"/g, ''));
  if (v !== current) throw conflict(`This changed meanwhile (version ${current}); reload and retry.`);
}

const page = <T>(items: T[], url: URL, size = 25) => {
  const p = Math.max(0, Number(url.searchParams.get('page') ?? 0));
  const s = Math.min(200, Math.max(1, Number(url.searchParams.get('size') ?? size)));
  return { items: items.slice(p * s, p * s + s), total: items.length, page: p, size: s };
};

const today = (ctx: Ctx) => iso(ctx.now);

function syncLot(l: Lot): Lot {
  l.qtyAvailable = l.status === 'SELLABLE' ? Math.max(0, l.qtyOnHand - l.qtyReserved) : 0;
  l.version += 1;
  return l;
}

function move(ctx: Ctx, l: Lot, type: string, qty: number, reference: string, reason: string | null) {
  ctx.db.movements.unshift({ id: ctx.db.movements.length + 1, occurredAt: ctx.now.toISOString(), type, sku: l.sku, nodeId: l.nodeId, ownerOrg: l.ownerOrg, lotId: l.id, lotNo: l.lotNo, qty, reference, actor: ctx.actor, reason });
}

function visible<T extends { ownerOrg?: string | null }>(ctx: Ctx, rows: T[]): T[] {
  return ctx.ownerOrg ? rows.filter((r) => r.ownerOrg === ctx.ownerOrg) : rows;
}

function shipmentOf(ctx: Ctx, id: string): InboundShipment {
  const s = ctx.db.inbound.find((x) => x.id === id || x.number === id);
  if (!s || (ctx.ownerOrg && s.ownerOrg !== ctx.ownerOrg)) throw notFound('inbound shipment');
  return s;
}

function lotOf(ctx: Ctx, id: string): Lot {
  const l = ctx.db.lots.find((x) => x.id === id);
  if (!l || (ctx.ownerOrg && l.ownerOrg !== ctx.ownerOrg)) throw notFound('lot');
  return l;
}

function recompute(s: InboundShipment) {
  s.lines.forEach((l) => {
    l.shortQty = Math.max(0, l.expectedQty - l.receivedGood - l.receivedDamaged);
    l.overQty = Math.max(0, l.receivedGood + l.receivedDamaged - l.expectedQty);
    l.cases = l.casePack ? Math.ceil(l.expectedQty / l.casePack) : 0;
  });
  s.expectedUnits = s.lines.reduce((a, l) => a + l.expectedQty, 0);
  s.receivedUnits = s.lines.reduce((a, l) => a + l.receivedGood + l.receivedDamaged, 0);
  s.version += 1;
}

const LOT_RE = /^[A-Za-z0-9][A-Za-z0-9._/-]{0,58}$/;

function planLines(ctx: Ctx, raw: unknown): InboundLine[] {
  const lines = Array.isArray(raw) ? (raw as Record<string, unknown>[]) : [];
  const errors: Record<string, string> = {};
  if (lines.length === 0) errors.lines = '1 to 200 SKUs';
  const out = lines.map((l, i): InboundLine => {
    const p = `lines[${i}].`;
    const atin = String(l.atin ?? '').toUpperCase();
    if (!/^AT[0-9A-Z]{8}$/.test(atin)) errors[`${p}atin`] = 'A variant ATIN (AT + 8 letters or digits)';
    const qty = Number(l.qty);
    if (!Number.isInteger(qty) || qty < 1) errors[`${p}qty`] = '1 to 100000 units';
    const pack = l.casePack === undefined || l.casePack === null ? null : Number(l.casePack);
    if (pack !== null && qty % pack !== 0) errors[`${p}qty`] = `Send whole cases: a multiple of ${pack}`;
    const lotNo = l.lotNo ? String(l.lotNo).toUpperCase() : null;
    if (lotNo && !LOT_RE.test(lotNo)) errors[`${p}lotNo`] = 'Letters, digits, . _ / - (max 59)';
    const exp = l.expDate ? String(l.expDate) : null;
    const mfg = l.mfgDate ? String(l.mfgDate) : null;
    if (exp && exp <= today(ctx)) errors[`${p}expDate`] = 'This batch has already expired';
    if (exp && mfg && exp <= mfg) errors[`${p}expDate`] = 'Must be after the manufacture date';
    const sku = l.sku ? String(l.sku).toUpperCase() : `ATF${atin.slice(-7)}`;
    return { lineNo: i + 1, sku, atin, unitLabel: `ATF${atin.slice(-7)}`, expectedQty: qty, receivedGood: 0, receivedDamaged: 0, shortQty: qty, overQty: 0, inboundOpen: 0, casePack: pack, cases: pack ? qty / pack : 0, lotNo, mfgDate: mfg, expDate: exp };
  });
  if (Object.keys(errors).length) throw invalid('Invalid inbound plan', errors);
  return out;
}

function addLot(ctx: Ctx, sku: string, lotNo: string, nodeId: string, owner: string | null, qty: number, mfg: string | null, exp: string | null, bin: string | null, status: Lot['status'], ref: string, cost = 0): Lot {
  if (ctx.db.lots.some((l) => l.sku === sku && l.lotNo === lotNo && l.nodeId === nodeId)) throw conflict(`Lot ${lotNo} of ${sku} already exists at ${nodeId}.`);
  const l: Lot = {
    id: uuid(++ctx.db.seq, 'cccc0001'), sku, lotNo, nodeId, ownerOrg: owner, bin, qtyOnHand: qty, qtyReserved: 0, qtyAvailable: status === 'SELLABLE' ? qty : 0, mfgDate: mfg, expDate: exp,
    receivedAt: ctx.now.toISOString(), unitLandedCostMinor: cost, currency: 'USD', status, reason: status === 'SELLABLE' ? null : 'Received damaged', markdownFlag: 'NONE', receiptRef: ref, version: 0
  };
  ctx.db.lots.push(l);
  move(ctx, l, 'RECEIVE', qty, ref, null);
  return l;
}

/* ── Inventory routes (staff hub and seller FBA share them) ── */

function inventoryRoutes(W: string, gate: HubGate, area: 'inventory' | 'fba') {
  const h = (level: HubLevel, fn: Fn, status = 200) => handler(gate, area, level, fn, status);
  return [
    http.get(`${W}/nodes`, h('VIEW', (ctx) => ({ items: ctx.db.nodes }))),
    http.get(
      `${W}/inbound`,
      h('VIEW', (ctx) => {
        const st = ctx.url.searchParams.get('status');
        const sku = ctx.url.searchParams.get('sku');
        const rows = visible(ctx, ctx.db.inbound).filter((s) => (!st || s.status === st) && (!sku || s.lines.some((l) => l.sku === sku))).sort((a, b) => (b.createdAt ?? '').localeCompare(a.createdAt ?? ''));
        return page(rows, ctx.url);
      })
    ),
    http.post(
      `${W}/inbound`,
      h(
        'EDIT',
        async (ctx) => {
          const b = await bodyOf(ctx.request);
          const lines = planLines(ctx, b.lines);
          const owner = ctx.ownerOrg ?? (typeof b.ownerOrg === 'string' && b.ownerOrg ? b.ownerOrg : RETAIL_ORG);
          const n = ++ctx.db.seq;
          const s: InboundShipment = {
            id: uuid(n, 'dddd0001'), number: `FBA-IN-00${6600 + n}`, source: 'FBA', externalRef: null, ownerOrg: owner, nodeId: typeof b.nodeId === 'string' && b.nodeId ? b.nodeId : 'HOU-FC',
            assignmentReason: b.nodeId ? 'Requested' : 'Closest FC with space', name: typeof b.name === 'string' ? b.name : null, status: 'WORKING', shipFrom: null,
            shipTo: { name: 'AfroTransact FC', line1: null, line2: null, city: null, region: null, postalCode: null, country: 'US', phone: null }, carrier: null, trackingNumber: null, labelDocumentUrl: null,
            expectedArrival: typeof b.expectedArrival === 'string' ? b.expectedArrival : null, expectedUnits: 0, receivedUnits: 0, receiptCount: 0, discrepancy: false, discrepancies: [], lines,
            boxes: lines.map((l, i) => ({ boxNo: i + 1, lpn: `LPN${String(n * 10 + i).padStart(8, '0')}`, contents: [{ sku: l.sku, qty: l.expectedQty }], units: l.expectedQty, weightLb: null, receivedAt: null })),
            createdBy: ctx.actor, createdAt: ctx.now.toISOString(), shippedAt: null, closedAt: null, updatedAt: ctx.now.toISOString(), version: -1
          };
          recompute(s);
          ctx.db.inbound.unshift(s);
          return s;
        },
        201
      )
    ),
    http.get(`${W}/inbound/:id`, h('VIEW', (ctx, p) => shipmentOf(ctx, p.id))),
    http.put(
      `${W}/inbound/:id`,
      h('EDIT', async (ctx, p) => {
        const s = shipmentOf(ctx, p.id);
        checkVersion(ctx, s.version);
        if (s.status !== 'WORKING') throw conflict(`${s.number} is ${s.status}; only plans being prepared can change.`);
        s.lines = planLines(ctx, (await bodyOf(ctx.request)).lines);
        recompute(s);
        return s;
      })
    ),
    http.put(
      `${W}/inbound/:id/tracking`,
      h('EDIT', async (ctx, p) => {
        const s = shipmentOf(ctx, p.id);
        const b = await bodyOf(ctx.request);
        if (!b.carrier || !b.trackingNumber) throw invalid('Carrier and tracking number are required', { carrier: 'Required', trackingNumber: 'Required' });
        s.carrier = String(b.carrier);
        s.trackingNumber = String(b.trackingNumber);
        s.version += 1;
        return { shipment: s, trackingRegistered: true };
      })
    ),
    http.post(
      `${W}/inbound/:id/ship`,
      h('EDIT', (ctx, p) => {
        const s = shipmentOf(ctx, p.id);
        checkVersion(ctx, s.version);
        if (s.status !== 'WORKING') throw conflict(`${s.number} is already ${s.status.toLowerCase()}.`);
        s.status = 'SHIPPED';
        s.shippedAt = ctx.now.toISOString();
        s.lines.forEach((l) => (l.inboundOpen = l.expectedQty));
        s.version += 1;
        return s;
      })
    ),
    http.post(
      `${W}/inbound/:id/cancel`,
      h('EDIT', (ctx, p) => {
        const s = shipmentOf(ctx, p.id);
        if (s.status !== 'WORKING' && s.status !== 'SHIPPED') throw conflict(`${s.number} is ${s.status}; it can no longer be cancelled.`);
        s.status = 'CANCELLED';
        s.version += 1;
        return s;
      })
    ),
    http.get(`${W}/inbound/:id/labels`, h('VIEW', (ctx, p) => {
      const s = shipmentOf(ctx, p.id);
      const kind = ctx.url.searchParams.get('kind') ?? 'UNIT';
      return new HttpResponse(`%PDF-1.4\n% mock ${kind} labels of ${s.number}\n%%EOF`, { headers: { 'Content-Type': 'application/pdf', 'Content-Disposition': `inline; filename="${kind.toLowerCase()}-${s.number}.pdf"` } });
    })),
    http.get(
      `${W}/lots`,
      h('VIEW', (ctx) => {
        const q = ctx.url.searchParams;
        const rows = visible(ctx, ctx.db.lots)
          .filter((l) => (!q.get('sku') || l.sku.includes(q.get('sku') ?? '') || l.lotNo.includes(q.get('sku') ?? '')) && (!q.get('nodeId') || l.nodeId === q.get('nodeId')) && (!q.get('status') || l.status === q.get('status')))
          .sort((a, b) => a.sku.localeCompare(b.sku) || a.nodeId.localeCompare(b.nodeId) || (a.expDate ?? '9999').localeCompare(b.expDate ?? '9999'));
        return page(rows, ctx.url, 50);
      })
    ),
    http.get(`${W}/lots/markdown`, h('VIEW', (ctx) => ({ items: visible(ctx, ctx.db.lots).filter((l) => l.status === 'SELLABLE' && l.qtyOnHand > 0 && l.markdownFlag !== 'NONE').sort((a, b) => (a.expDate ?? '').localeCompare(b.expDate ?? '')) }))),
    http.get(`${W}/lots/:id`, h('VIEW', (ctx, p) => lotOf(ctx, p.id)))
  ];
}

/* ── Staff-only inventory routes ── */

function staffInventoryRoutes(W: string, gate: HubGate) {
  const h = (level: HubLevel, fn: Fn, status = 200) => handler(gate, 'inventory', level, fn, status);
  const reasonOf = async (ctx: Ctx) => {
    const b = await bodyOf(ctx.request);
    const reason = typeof b.reason === 'string' ? b.reason.trim() : '';
    if (!reason) throw invalid('Give a reason', { reason: 'Required' });
    return { b, reason };
  };
  return [
    http.get(`${W}/flow`, h('VIEW', (ctx) => flowOf(ctx))),
    http.get(`${W}/trace`, h('VIEW', (ctx) => traceOf(ctx))),
    http.get(`${W}/overview`, h('VIEW', (ctx) => overviewOf(ctx))),
    http.post(
      `${W}/inbound/:id/receipts`,
      h('EDIT', async (ctx, p) => {
        const s = shipmentOf(ctx, p.id);
        if (s.status !== 'SHIPPED' && s.status !== 'RECEIVING') throw conflict(`Shipment ${s.number} is ${s.status}; mark it shipped before receiving.`);
        const b = await bodyOf(ctx.request);
        const lines = Array.isArray(b.lines) ? (b.lines as Record<string, unknown>[]) : [];
        if (!lines.length) throw invalid('Count at least one SKU', { lines: 'Required' });
        const ref = `${s.number}-R${s.receiptCount + 1}`;
        const errors: Record<string, string> = {};
        const lots: Lot[] = [];
        lines.forEach((l, i) => {
          const sku = String(l.sku ?? '').toUpperCase() || s.lines.find((x) => x.unitLabel === String(l.unitLabel ?? '').toUpperCase())?.sku;
          const line = s.lines.find((x) => x.sku === sku);
          if (!sku) errors[`lines[${i}].sku`] = 'Scan the unit label or give the SKU';
          const exp = (l.expDate as string | undefined) || line?.expDate || null;
          if (exp && exp < today(ctx)) errors[`lines[${i}].expDate`] = `Lot expired on ${exp}`;
          if (Object.keys(errors).length || !sku) return;
          const good = Number(l.qtyGood ?? 0);
          const damaged = Number(l.qtyDamaged ?? 0);
          const lotNo = String(l.lotNo || line?.lotNo || ref).toUpperCase();
          const mfg = (l.mfgDate as string | undefined) || line?.mfgDate || null;
          const bin = (l.bin as string | undefined) || 'R-01-01';
          if (good > 0) lots.push(addLot(ctx, sku, lotNo, s.nodeId, s.ownerOrg ?? null, good, mfg, exp, bin, l.holdReason ? 'QUARANTINE' : 'SELLABLE', ref));
          if (damaged > 0) lots.push(addLot(ctx, sku, `${lotNo}-DMG`, s.nodeId, s.ownerOrg ?? null, damaged, mfg, exp, 'Q-01-01', 'DAMAGED', ref));
          if (line) {
            line.receivedGood += good;
            line.receivedDamaged += damaged;
            line.inboundOpen = Math.max(0, line.inboundOpen - good - damaged);
          } else s.lines.push({ lineNo: s.lines.length + 1, sku, atin: null, unitLabel: null, expectedQty: 0, receivedGood: good, receivedDamaged: damaged, shortQty: 0, overQty: good + damaged, inboundOpen: 0, casePack: null, cases: 0, lotNo, mfgDate: mfg, expDate: exp });
        });
        if (Object.keys(errors).length) throw invalid('Some lines cannot be received', errors);
        s.status = 'RECEIVING';
        s.receiptCount += 1;
        recompute(s);
        return { shipment: s, receiptRef: ref, lots, replayed: false };
      })
    ),
    http.post(
      `${W}/inbound/:id/close`,
      h('EDIT', (ctx, p) => {
        const s = shipmentOf(ctx, p.id);
        if (s.status !== 'RECEIVING' && s.status !== 'SHIPPED') throw conflict(`${s.number} is ${s.status}.`);
        s.status = 'CLOSED';
        s.closedAt = ctx.now.toISOString();
        s.lines.forEach((l) => (l.inboundOpen = 0));
        recompute(s);
        s.discrepancies = s.lines.filter((l) => l.shortQty > 0 || l.overQty > 0).map((l) => ({ type: l.shortQty > 0 ? 'SHORT' : 'OVER', sku: l.sku, expectedQty: l.expectedQty, receivedQty: l.receivedGood + l.receivedDamaged, qty: l.shortQty || l.overQty }));
        s.discrepancy = s.discrepancies.length > 0;
        return s;
      })
    ),
    http.post(`${W}/lots/expiry-runs`, h('EDIT', (ctx) => {
      let expired = 0;
      ctx.db.lots.forEach((l) => {
        if (l.expDate && l.expDate <= today(ctx) && ['SELLABLE', 'QUARANTINE', 'DAMAGED'].includes(l.status) && l.qtyReserved === 0) {
          l.status = 'EXPIRED';
          syncLot(l);
          move(ctx, l, 'EXPIRE', 0, 'expiry-run', 'Expired');
          expired++;
        }
      });
      return { expired, deferred: 0 };
    })),
    http.post(`${W}/lots/markdown-runs`, h('EDIT', (ctx) => {
      const changed: Lot[] = [];
      ctx.db.lots.forEach((l) => {
        const days = l.expDate ? Math.round((Date.parse(l.expDate) - Date.parse(today(ctx))) / 86_400_000) : null;
        const flag = l.status === 'SELLABLE' && days !== null ? (days <= 30 ? 'WITHIN_30' : days <= 60 ? 'WITHIN_60' : 'NONE') : 'NONE';
        if (flag !== l.markdownFlag) {
          l.markdownFlag = flag;
          changed.push(syncLot(l));
        }
      });
      return { items: changed };
    })),
    http.get(`${W}/lots/:id/trace`, h('VIEW', (ctx, p) => {
      const lot = lotOf(ctx, p.id);
      return { lot, movements: ctx.db.movements.filter((m) => m.lotId === lot.id), relatedLots: ctx.db.lots.filter((l) => l.id !== lot.id && l.sku === lot.sku && l.lotNo.replace(/-DMG$/, '') === lot.lotNo.replace(/-DMG$/, '')), orders: lot.qtyReserved ? [{ orderId: 'AT-2026-004812', qty: lot.qtyReserved, status: 'HELD', at: ctx.now.toISOString() }] : [] };
    })),
    http.post(`${W}/lots/:id/quarantine`, h('EDIT', async (ctx, p) => {
      const lot = lotOf(ctx, p.id);
      const { reason } = await reasonOf(ctx);
      if (lot.status !== 'SELLABLE') throw conflict(`Lot ${lot.lotNo} is ${lot.status}.`);
      lot.status = 'QUARANTINE';
      lot.reason = reason;
      move(ctx, lot, 'QUARANTINE', lot.qtyOnHand, lot.lotNo, reason);
      return syncLot(lot);
    })),
    http.post(`${W}/lots/:id/release`, h('EDIT', async (ctx, p) => {
      const lot = lotOf(ctx, p.id);
      const { reason } = await reasonOf(ctx);
      if (lot.status !== 'QUARANTINE' && lot.status !== 'DAMAGED') throw conflict(`Lot ${lot.lotNo} is ${lot.status}.`);
      lot.status = 'SELLABLE';
      lot.reason = reason;
      move(ctx, lot, 'RELEASE', lot.qtyOnHand, lot.lotNo, reason);
      return syncLot(lot);
    })),
    http.post(`${W}/lots/:id/adjust`, h('EDIT', async (ctx, p) => {
      const lot = lotOf(ctx, p.id);
      const { b, reason } = await reasonOf(ctx);
      const counted = Number(b.countedQty);
      if (counted < lot.qtyReserved) throw invalid('Below what is reserved', { countedQty: `At least ${lot.qtyReserved}` });
      move(ctx, lot, 'ADJUST', counted - lot.qtyOnHand, lot.lotNo, reason);
      lot.qtyOnHand = counted;
      return syncLot(lot);
    })),
    http.post(`${W}/lots/:id/write-off`, h('APPROVE', async (ctx, p) => {
      const lot = lotOf(ctx, p.id);
      const { b, reason } = await reasonOf(ctx);
      const qty = b.qty === undefined || b.qty === null ? lot.qtyOnHand - lot.qtyReserved : Number(b.qty);
      if (qty < 1 || qty > lot.qtyOnHand - lot.qtyReserved) throw invalid('Write off unreserved units only', { qty: `1 to ${lot.qtyOnHand - lot.qtyReserved}` });
      lot.qtyOnHand -= qty;
      if (lot.qtyOnHand === 0) lot.status = 'WRITTEN_OFF';
      move(ctx, lot, 'WRITE_OFF', -qty, lot.lotNo, reason);
      return syncLot(lot);
    })),
    http.post(`${W}/lots/:id/transfer`, h('EDIT', async (ctx, p) => {
      const lot = lotOf(ctx, p.id);
      const { b, reason } = await reasonOf(ctx);
      const qty = Number(b.qty);
      const to = String(b.toNodeId ?? '');
      if (!ctx.db.nodes.some((n) => n.id === to) || to === lot.nodeId) throw invalid('Pick another fulfilment centre', { toNodeId: 'Different, active node' });
      if (!(qty >= 1 && qty <= lot.qtyAvailable)) throw invalid('Transfer available units only', { qty: `1 to ${lot.qtyAvailable}` });
      lot.qtyOnHand -= qty;
      syncLot(lot);
      move(ctx, lot, 'TRANSFER_OUT', -qty, `TRF-${lot.lotNo}`, reason);
      const existing = ctx.db.lots.find((l) => l.sku === lot.sku && l.lotNo === lot.lotNo && l.nodeId === to);
      const dest = existing ?? addLot(ctx, lot.sku, lot.lotNo, to, lot.ownerOrg ?? null, 0, lot.mfgDate ?? null, lot.expDate ?? null, typeof b.toBin === 'string' ? b.toBin : null, 'SELLABLE', `TRF-${lot.lotNo}`, lot.unitLandedCostMinor);
      dest.qtyOnHand += qty;
      syncLot(dest);
      move(ctx, dest, 'TRANSFER_IN', qty, `TRF-${lot.lotNo}`, reason);
      return { reference: `TRF-${lot.lotNo}`, from: lot, to: dest };
    })),
    http.get(`${W}/movements`, h('VIEW', (ctx) => {
      const q = ctx.url.searchParams;
      return page(ctx.db.movements.filter((m) => (!q.get('sku') || m.sku === q.get('sku')) && (!q.get('lotId') || m.lotId === q.get('lotId')) && (!q.get('type') || m.type === q.get('type'))), ctx.url, 100);
    })),
    http.get(`${W}/cycle-counts`, h('VIEW', (ctx) => {
      const q = ctx.url.searchParams;
      return page(ctx.db.counts.filter((c) => (!q.get('status') || c.status === q.get('status')) && (!q.get('nodeId') || c.nodeId === q.get('nodeId'))), ctx.url);
    })),
    http.post(`${W}/cycle-counts`, h('EDIT', async (ctx) => {
      const b = await bodyOf(ctx.request);
      const nodeId = String(b.nodeId ?? '');
      if (!ctx.db.nodes.some((n) => n.id === nodeId)) throw invalid('Pick an active fulfilment centre', { nodeId: 'Unknown node' });
      const sku = b.sku ? String(b.sku).toUpperCase() : null;
      const bin = b.binPrefix ? String(b.binPrefix).toUpperCase() : null;
      const lots = ctx.db.lots.filter((l) => l.nodeId === nodeId && l.qtyOnHand > 0 && l.status !== 'WRITTEN_OFF' && (!sku || l.sku === sku) && (!bin || (l.bin ?? '').startsWith(bin)));
      if (!lots.length) throw invalid(`No stock to count at ${nodeId}${sku ? ` for ${sku}` : ''}`, { [sku ? 'sku' : 'nodeId']: 'Nothing on hand' });
      const n = ++ctx.db.seq;
      const c: CycleCount = {
        id: uuid(n, '99990001'), number: `CC-${String(ctx.db.counts.length + 1).padStart(6, '0')}`, nodeId, sku, binPrefix: bin, ownerOrg: null, blind: Boolean(b.blind), status: 'OPEN', note: typeof b.note === 'string' ? b.note : null,
        lineCount: lots.length, countedLines: 0, varianceLines: 0, netVariance: 0,
        lines: lots.map((l, i): CycleCountLine => ({ lineNo: i + 1, lotId: l.id, sku: l.sku, lotNo: l.lotNo, bin: l.bin, expDate: l.expDate, expectedQty: l.qtyOnHand, countedQty: null, variance: 0, reasonCode: null, note: null, countedBy: null, countedAt: null, postedDelta: null })),
        createdBy: ctx.actor, createdAt: ctx.now.toISOString(), postedBy: null, postedAt: null, version: 0
      };
      ctx.db.counts.unshift(c);
      return c;
    }, 201)),
    http.get(`${W}/cycle-counts/:id`, h('VIEW', (ctx, p) => countOf(ctx, p.id))),
    http.put(`${W}/cycle-counts/:id/counts`, h('EDIT', async (ctx, p) => {
      const c = countOf(ctx, p.id);
      checkVersion(ctx, c.version);
      if (c.status !== 'OPEN' && c.status !== 'COUNTED') throw conflict(`Cycle count ${c.number} is ${c.status}`);
      const entries = ((await bodyOf(ctx.request)).lines as Record<string, unknown>[] | undefined) ?? [];
      entries.forEach((e) => {
        const line = c.lines.find((l) => l.lineNo === Number(e.lineNo));
        if (!line) throw invalid('Invalid counts', { [`lines[${String(e.lineNo)}]`]: 'No such line' });
        line.countedQty = Number(e.countedQty);
        line.variance = line.countedQty - line.expectedQty;
        line.reasonCode = (e.reasonCode as CycleCountLine['reasonCode']) ?? null;
        line.note = (e.note as string | undefined) ?? null;
        line.countedBy = ctx.actor;
        line.countedAt = ctx.now.toISOString();
      });
      summarize(c);
      c.status = c.lines.every((l) => l.countedQty !== null && l.countedQty !== undefined) ? 'COUNTED' : 'OPEN';
      c.version += 1;
      return c;
    })),
    http.post(`${W}/cycle-counts/:id/post`, h('EDIT', (ctx, p) => {
      const c = countOf(ctx, p.id);
      checkVersion(ctx, c.version);
      const errors: Record<string, string> = {};
      c.lines.forEach((l) => {
        if (l.countedQty === null || l.countedQty === undefined) errors[`lines[${l.lineNo}].countedQty`] = `Count lot ${l.lotNo} first`;
        else if (l.variance !== 0 && !l.reasonCode) errors[`lines[${l.lineNo}].reasonCode`] = `Give a reason for the variance of ${l.variance}`;
      });
      if (Object.keys(errors).length) throw invalid('Count every line and give a reason for each variance before posting', errors);
      c.lines.forEach((l) => {
        l.postedDelta = l.variance;
        if (l.variance === 0) return;
        const lot = ctx.db.lots.find((x) => x.id === l.lotId);
        if (!lot) return;
        lot.qtyOnHand += l.variance;
        syncLot(lot);
        move(ctx, lot, 'ADJUST', l.variance, c.number, `${c.number} · ${(l.reasonCode ?? 'OTHER').toLowerCase().replace(/_/g, ' ')}${l.note ? ` · ${l.note}` : ''}`);
      });
      c.status = 'POSTED';
      c.postedBy = ctx.actor;
      c.postedAt = ctx.now.toISOString();
      c.version += 1;
      return c;
    })),
    http.post(`${W}/cycle-counts/:id/cancel`, h('EDIT', (ctx, p) => {
      const c = countOf(ctx, p.id);
      if (c.status === 'POSTED' || c.status === 'CANCELLED') throw conflict(`Cycle count ${c.number} is ${c.status}`);
      c.status = 'CANCELLED';
      c.version += 1;
      return c;
    }))
  ];
}

function countOf(ctx: Ctx, id: string): CycleCount {
  const c = ctx.db.counts.find((x) => x.id === id);
  if (!c) throw notFound('cycle count');
  return c;
}

function summarize(c: CycleCount) {
  const counted = c.lines.filter((l) => l.countedQty !== null && l.countedQty !== undefined);
  c.countedLines = counted.length;
  c.varianceLines = counted.filter((l) => l.variance !== 0).length;
  c.netVariance = counted.reduce((a, l) => a + l.variance, 0);
}

function overviewOf(ctx: Ctx) {
  const t = today(ctx);
  const days = (l: Lot) => (l.expDate ? Math.round((Date.parse(l.expDate) - Date.parse(t)) / 86_400_000) : null);
  const live = ctx.db.lots.filter((l) => l.qtyOnHand > 0 && l.status !== 'WRITTEN_OFF');
  const statuses = ['SELLABLE', 'QUARANTINE', 'DAMAGED', 'EXPIRED'];
  const buckets: [string, (d: number | null) => boolean][] = [
    ['EXPIRED', (d) => d !== null && d < 0], ['D0_30', (d) => d !== null && d >= 0 && d <= 30], ['D31_60', (d) => d !== null && d > 30 && d <= 60],
    ['D61_90', (d) => d !== null && d > 60 && d <= 90], ['D91_180', (d) => d !== null && d > 90 && d <= 180], ['D181_PLUS', (d) => d !== null && d > 180], ['NO_EXPIRY', (d) => d === null]
  ];
  return {
    byStatus: statuses.map((s) => {
      const ls = live.filter((l) => l.status === s);
      return { status: s, currency: 'USD', units: ls.reduce((a, l) => a + l.qtyOnHand, 0), lots: ls.length, valueMinor: ls.reduce((a, l) => a + l.qtyOnHand * l.unitLandedCostMinor, 0) };
    }),
    expiry: buckets.map(([bucket, f]) => {
      const ls = live.filter((l) => f(days(l)));
      return { bucket, units: ls.reduce((a, l) => a + l.qtyOnHand, 0), lots: ls.length };
    }),
    nodes: ctx.db.nodes.map((n) => {
      const ls = live.filter((l) => l.nodeId === n.id);
      return { nodeId: n.id, name: n.name, lots: ls.length, units: ls.reduce((a, l) => a + l.qtyOnHand, 0), sellableUnits: ls.filter((l) => l.status === 'SELLABLE').reduce((a, l) => a + l.qtyOnHand, 0), blockedUnits: ls.filter((l) => l.status !== 'SELLABLE').reduce((a, l) => a + l.qtyOnHand, 0), valueMinor: ls.reduce((a, l) => a + l.qtyOnHand * l.unitLandedCostMinor, 0), currency: 'USD', reserved: ls.reduce((a, l) => a + l.qtyReserved, 0), inbound: ctx.db.inbound.filter((s) => s.nodeId === n.id && s.status === 'SHIPPED').reduce((a, s) => a + s.expectedUnits, 0) };
    }),
    inbound: { openShipments: ctx.db.inbound.filter((s) => s.status === 'SHIPPED' || s.status === 'RECEIVING').length, expectedUnits: ctx.db.inbound.reduce((a, s) => a + s.expectedUnits, 0), receivedUnits: ctx.db.inbound.reduce((a, s) => a + s.receivedUnits, 0), withDiscrepancies: ctx.db.inbound.filter((s) => s.discrepancy).length },
    reservations: { held: 2, heldUnits: live.reduce((a, l) => a + l.qtyReserved, 0) },
    alerts: live
      .filter((l) => l.status === 'EXPIRED' || (l.status === 'SELLABLE' && (days(l) ?? 999) <= 30))
      .map((l) => ({ type: l.status === 'EXPIRED' ? 'LOT_EXPIRED' : 'LOT_EXPIRING', severity: l.status === 'EXPIRED' ? 'HIGH' : 'MEDIUM', sku: l.sku, nodeId: l.nodeId, lotId: l.id, lotNo: l.lotNo, shipmentId: null, qty: l.qtyOnHand, date: l.expDate, message: l.status === 'EXPIRED' ? `Lot ${l.lotNo} of ${l.sku} expired on ${l.expDate ?? ''}: write it off` : `Lot ${l.lotNo} of ${l.sku} expires on ${l.expDate ?? ''}: mark it down` }))
  };
}

function flowOf(ctx: Ctx) {
  const n = (x: number) => x;
  const po = (s: string[]) => ctx.db.pos.filter((p) => s.includes(p.status)).length;
  const stage = (key: string, title: string, who: string, what: string, effect: string, count: number, countLabel: string, href: string) => ({ key, title, who, what, effect, count, countLabel, href });
  return {
    asOf: ctx.now.toISOString(),
    stages: [
      stage('items', 'Create the product', 'Category manager', 'Retail item master: name, supplier, shelf life, storage, price. SKU and GTIN barcode are issued; link the catalog ATIN.', 'retail.item.created · listing starts as Draft', n(ctx.db.items.length), 'retail items', '/retail?tab=items'),
      stage('po', 'Raise a purchase order', 'Buyer', 'Pick the supplier, the destination fulfilment centre and the Incoterm; add lines from retail items in the supplier’s currency.', 'Draft PO · base-currency value at the day’s FX rate', po(['DRAFT']), 'draft POs', '/retail?tab=pos'),
      stage('approve', 'Approve & send', 'Finance (Approve) → Buyer', 'Approval above the buyer’s limit, then send the PO PDF and EDI 850; the supplier confirms quantity, price and ready date.', 'Pending approval → Approved → Sent → Confirmed', po(['PENDING_APPROVAL', 'APPROVED', 'SENT', 'CONFIRMED']), 'awaiting the next step', '/retail?tab=pos'),
      stage('inbound', 'Announce the inbound shipment', 'Buyer / seller', 'The ASN (packing list): lines with quantity, case pack, batch, made and expiry dates; box and pallet labels with GS1-128 barcodes.', 'inventory.inbound.created · units count as inbound once shipped', ctx.db.inbound.filter((s) => s.status === 'WORKING').length, 'inbound plans being prepared', '/inventory?tab=inbound'),
      stage('transit', 'Track in transit', 'Logistics', 'Carrier and tracking number; expected arrival at the fulfilment centre. The storefront can show “Coming soon”.', 'inventory.inbound.shipped · inbound units at the FC', ctx.db.inbound.filter((s) => s.status === 'SHIPPED').length, 'in transit', '/inventory?tab=inbound'),
      stage('receive', 'Receive & count at the dock', 'Warehouse receiver', 'Scan each case’s GS1-128 label, count good and damaged units, confirm batch, made and expiry, choose the bin; over-receipts need approval.', 'Validates dates, minimum remaining life, duplicate lots and over / short', ctx.db.inbound.filter((s) => s.status === 'RECEIVING').length + ctx.db.receipts.filter((r) => r.status === 'OPEN').length, 'at the dock', '/inventory?tab=receiving'),
      stage('lots', 'Lots go into stock', 'System', 'Each batch becomes its own lot with landed cost per unit. Damaged, short-dated and QC-held units go to quarantine, not to sale.', 'inventory.lot.created · stock movements logged · positions updated', ctx.db.lots.filter((l) => l.status === 'SELLABLE').length, 'sellable lots', '/inventory?tab=stock'),
      stage('storefront', 'Sell on the storefront', 'System + category manager', 'Live listings show “Sold & shipped by AfroTransact”. Available = sellable lots − reserved; orders reserve the earliest expiry first (FEFO).', 'inventory.availability.changed → offers → search index → product page', ctx.db.items.filter((i) => i.listingStatus === 'LIVE').length, 'live listings', '/inventory?tab=flow'),
      stage('invoice', 'Match the invoice & pay', 'Finance', 'Three-way match: PO price × received quantity = supplier invoice. Exceptions go to the buyer; closing the PO releases payment.', 'PO closed · accounts payable cleared', ctx.db.invoices.filter((i) => i.status === 'EXCEPTION').length, 'invoice exceptions', '/retail?tab=invoices')
    ]
  };
}

function traceOf(ctx: Ctx) {
  const q = ctx.url.searchParams;
  let atin = (q.get('atin') ?? '').toUpperCase();
  let sku = (q.get('sku') ?? '').toUpperCase();
  if (!atin && !sku) throw invalid('Give an ATIN or an inventory SKU', { atin: 'Give an ATIN or an inventory SKU' });
  const item = ctx.db.items.find((i) => (atin && i.atin === atin) || (sku && i.sku === sku));
  if (item) {
    sku ||= item.sku;
    atin ||= item.atin ?? '';
  }
  const offer = atin ? ctx.db.offers[atin] : undefined;
  if (!sku && offer) sku = offer.inventorySku;
  const steps: { key: string; title: string; status: 'DONE' | 'WAITING' | 'BROKEN'; detail: string; href?: string }[] = [];
  steps.push(item ? { key: 'item', title: 'Retail item & listing', status: item.listingStatus === 'LIVE' && item.atin ? 'DONE' : 'WAITING', detail: `${item.sku} · ${item.atin ? `ATIN ${item.atin}` : 'not linked to a catalog ATIN'} · listing ${item.listingStatus.toLowerCase()}`, href: `/retail?tab=items&q=${item.sku}` } : { key: 'item', title: 'Retail item & listing', status: 'WAITING', detail: 'No AfroTransact Retail item: a seller’s product (FBA) or not set up yet.' });
  const ships = ctx.db.inbound.filter((s) => s.lines.some((l) => l.sku === sku));
  const last = ships[0];
  steps.push(last ? { key: 'inbound', title: 'Inbound shipment (ASN)', status: last.status === 'RECEIVING' || last.status === 'CLOSED' ? 'DONE' : 'WAITING', detail: `${last.number} · ${last.status.toLowerCase()} · ${last.receivedUnits} of ${last.expectedUnits} units received (${ships.length} shipments)`, href: `/inventory?tab=inbound&id=${last.id}` } : { key: 'inbound', title: 'Inbound shipment (ASN)', status: 'WAITING', detail: `No inbound shipment of ${sku} yet.` });
  const sell = ctx.db.lots.filter((l) => l.sku === sku && l.status === 'SELLABLE' && l.qtyAvailable > 0).sort((a, b) => (a.expDate ?? '9999').localeCompare(b.expDate ?? '9999'));
  const units = sell.reduce((a, l) => a + l.qtyAvailable, 0);
  steps.push(sell.length ? { key: 'lots', title: 'Lots (batches)', status: 'DONE', detail: `${sell.length} sellable lots · ${units} units · picks next: ${must(sell[0]).lotNo} (exp ${must(sell[0]).expDate ?? 'none'}) (FEFO)`, href: `/inventory?tab=stock&q=${sku}` } : { key: 'lots', title: 'Lots (batches)', status: 'WAITING', detail: `No sellable lot of ${sku}.` });
  steps.push({ key: 'positions', title: 'Available to sell', status: units > 0 ? 'DONE' : 'WAITING', detail: `${units} available (on hand − reserved) over every fulfilment centre` });
  if (!atin) steps.push({ key: 'offer', title: 'Offer (Fulfilled by AfroTransact)', status: 'WAITING', detail: 'Link the retail item to a catalog ATIN to sell it.' });
  else if (!offer) steps.push({ key: 'offer', title: 'Offer (Fulfilled by AfroTransact)', status: 'BROKEN', detail: `No open Fulfilled-by-AfroTransact offer on ${atin}.` });
  else if (offer.inventorySku !== sku) steps.push({ key: 'offer', title: 'Offer (Fulfilled by AfroTransact)', status: 'BROKEN', detail: `The offer sells inventory SKU ${offer.inventorySku}, not ${sku}: list the retail item to point it here.`, href: '/retail?tab=items' });
  else steps.push({ key: 'offer', title: 'Offer (Fulfilled by AfroTransact)', status: offer.status === 'ACTIVE' ? 'DONE' : 'WAITING', detail: `${offer.status.toLowerCase()} · inventory SKU ${offer.inventorySku} · USD ${(offer.priceMinor / 100).toFixed(2)}` });
  const live = Boolean(offer && offer.status === 'ACTIVE' && offer.inventorySku === sku && units > 0);
  steps.push({ key: 'pdp', title: 'Product page', status: live ? 'DONE' : 'WAITING', detail: live ? `Buy box: AfroTransact Retail · afrotransact · ${units} available · delivery in 2 days · FULFILLED_BY_AFROTRANSACT` : 'Currently unavailable: no eligible offer (no stock or the listing is paused).' });
  steps.push({ key: 'search', title: 'Search index', status: live ? 'DONE' : 'WAITING', detail: live ? `Indexed · in stock · ${units} available` : 'Indexed as currently unavailable' });
  return { atin, sku, title: item?.name ?? sku, steps, availableUnits: units, storefrontPath: atin ? `/p/${atin}` : undefined, badges: live ? ['FULFILLED_BY_AFROTRANSACT', 'SOLD_BY_AFROTRANSACT'] : [], deliveryDays: live ? 2 : null };
}

/* ── Retail (1P) ── */

function retailRoutes(W: string, gate: HubGate) {
  const h = (level: HubLevel, fn: Fn, status = 200) => handler(gate, 'retail', level, fn, status);
  const poOf = (ctx: Ctx, id: string) => {
    const p = ctx.db.pos.find((x) => x.id === id);
    if (!p) throw notFound('purchase order');
    return p;
  };
  const receiptOf = (ctx: Ctx, id: string) => {
    const r = ctx.db.receipts.find((x) => x.id === id);
    if (!r) throw notFound('goods receipt');
    return r;
  };
  const itemOf = (ctx: Ctx, id: string) => {
    const i = ctx.db.items.find((x) => x.id === id);
    if (!i) throw notFound('item');
    return i;
  };
  const transition = (ctx: Ctx, po: PurchaseOrder, from: string[], to: string, note?: string) => {
    checkVersion(ctx, po.version);
    if (!from.includes(po.status)) throw conflict(`${po.number} is ${po.status.toLowerCase().replace(/_/g, ' ')}.`);
    po.status = to;
    po.history.push({ status: to, at: ctx.now.toISOString(), by: ctx.actor, note: note ?? null });
    if (to === 'APPROVED') po.approvedBy = ctx.actor;
    po.version += 1;
    return po;
  };
  const overBlocked = (r: GoodsReceipt) => {
    const byPo = new Map<number, { expected: number; received: number }>();
    r.lines.forEach((l) => {
      const e = byPo.get(l.poLineNo) ?? { expected: 0, received: 0 };
      e.expected = Math.max(e.expected, l.expectedQty);
      e.received += l.qtyGood + l.qtyDamaged;
      byPo.set(l.poLineNo, e);
    });
    return [...byPo.entries()].filter(([, e]) => e.received - e.expected > Math.floor(e.expected * OVER_RECEIPT_TOLERANCE)).map(([k]) => k);
  };
  const validation = (ctx: Ctx, r: GoodsReceipt) => {
    const blocked = r.overApprovedBy ? [] : overBlocked(r);
    const lines = r.lines.map((l) => {
      const errors: string[] = [];
      const warnings: string[] = [];
      const item = ctx.db.items.find((i) => i.id === l.itemId);
      if (!l.lotNo) errors.push('Batch / lot number is required');
      if (item && item.shelfLifeDays > 0 && !l.expDate) errors.push('Expiry date is required');
      if (l.expDate && l.expDate <= today(ctx)) errors.push(`Lot expired on ${l.expDate}`);
      if (l.qtyDamaged > 0) warnings.push(`${l.qtyDamaged} damaged units go to a -DMG quarantine lot`);
      if (l.qa === 'FAIL') warnings.push('QA failed: the batch is quarantined');
      return { lineNo: l.lineNo, errors, warnings, disposition: l.qa === 'FAIL' ? 'QUARANTINE' : 'SELLABLE', remainingDays: l.expDate ? Math.round((Date.parse(l.expDate) - Date.parse(today(ctx))) / 86_400_000) : null };
    });
    const po = poOf(ctx, r.poId);
    const variances = po.lines.map((pl) => {
      const ls = r.lines.filter((l) => l.poLineNo === pl.lineNo);
      return { poLineNo: pl.lineNo, sku: pl.sku, expected: ls[0]?.expectedQty ?? pl.openQty, received: ls.reduce((a, l) => a + l.qtyGood + l.qtyDamaged, 0), damaged: ls.reduce((a, l) => a + l.qtyDamaged, 0), overBlocked: blocked.includes(pl.lineNo) };
    });
    const receiptErrors = blocked.length ? [`More than ${OVER_RECEIPT_TOLERANCE * 100} % over on PO line ${blocked.join(', ')}: purchasing must approve the over-receipt`] : [];
    return { ok: lines.every((l) => l.errors.length === 0) && receiptErrors.length === 0, lines, variances, receiptErrors, fieldErrors: {} };
  };
  return [
    http.get(`${W}/suppliers`, h('VIEW', (ctx) => page(ctx.db.suppliers, ctx.url, 200))),
    http.get(`${W}/items`, h('VIEW', (ctx) => {
      const q = (ctx.url.searchParams.get('q') ?? '').toLowerCase();
      const listing = ctx.url.searchParams.get('listing');
      return page(ctx.db.items.filter((i) => (!q || `${i.sku} ${i.name} ${i.atin ?? ''} ${i.gtin}`.toLowerCase().includes(q)) && (!listing || i.listingStatus === listing)), ctx.url);
    })),
    http.get(`${W}/items/:id`, h('VIEW', (ctx, p) => itemOf(ctx, p.id))),
    http.put(`${W}/items/:id/atin`, h('EDIT', async (ctx, p) => {
      const it = itemOf(ctx, p.id);
      checkVersion(ctx, it.version);
      const atin = String((await bodyOf(ctx.request)).atin ?? '').toUpperCase();
      if (!/^AT[0-9A-Z]{8}$/.test(atin)) throw invalid('Not an ATIN', { atin: 'AT + 8 letters or digits' });
      it.atin = atin;
      ctx.db.offers[atin] ??= { inventorySku: atin, status: 'ACTIVE', priceMinor: it.priceMinor };
      it.version += 1;
      return it;
    })),
    http.put(`${W}/items/:id/listing`, h('EDIT', async (ctx, p) => {
      const it = itemOf(ctx, p.id);
      checkVersion(ctx, it.version);
      const status = String((await bodyOf(ctx.request)).status ?? '') as RetailItem['listingStatus'];
      if (!['DRAFT', 'LIVE', 'PAUSED'].includes(status)) throw invalid('Pick a listing status', { status: 'DRAFT, LIVE or PAUSED' });
      if (status === 'LIVE' && !it.atin) throw invalid('Link the catalog ATIN before listing', { atin: 'Required to list' });
      it.listingStatus = status;
      // offers-service follows retail.item.listing-changed: LIVE points the 1P offer at the item SKU.
      if (it.atin && status !== 'DRAFT') ctx.db.offers[it.atin] = { inventorySku: status === 'LIVE' ? it.sku : (ctx.db.offers[it.atin]?.inventorySku ?? it.sku), status: status === 'LIVE' ? 'ACTIVE' : 'PAUSED', priceMinor: it.priceMinor };
      it.version += 1;
      return it;
    })),
    http.get(`${W}/purchase-orders`, h('VIEW', (ctx) => {
      const st = ctx.url.searchParams.get('status');
      return page(ctx.db.pos.filter((p) => !st || p.status === st), ctx.url);
    })),
    http.post(`${W}/purchase-orders`, h('EDIT', async (ctx) => {
      const b = await bodyOf(ctx.request);
      const sup = ctx.db.suppliers.find((s) => s.id === b.supplierId);
      const errors: Record<string, string> = {};
      if (!sup) errors.supplierId = 'Pick an approved supplier';
      if (!b.destinationNodeId) errors.destinationNodeId = 'Pick the fulfilment centre';
      const raw = Array.isArray(b.lines) ? (b.lines as Record<string, unknown>[]) : [];
      if (!raw.length) errors.lines = 'Add at least one line';
      const lines = raw.map((l, i) => {
        const it = ctx.db.items.find((x) => x.id === l.itemId);
        const qty = Number(l.qty);
        if (!it) errors[`lines[${i}].itemId`] = 'Unknown item';
        if (!(qty > 0)) errors[`lines[${i}].qty`] = 'Must be positive';
        const si = it?.supplierItems.find((x) => x.supplierId === sup?.id) ?? it?.supplierItems[0];
        if (it && si && qty % si.casePack !== 0) errors[`lines[${i}].qty`] = `Order whole cases of ${si.casePack}`;
        const cost = Number(l.unitCostMinor ?? si?.unitCostMinor ?? 0);
        return { lineNo: i + 1, itemId: String(l.itemId), sku: it?.sku ?? '', description: it?.name ?? '', supplierSku: si?.supplierSku ?? null, qtyOrdered: qty, unitCostMinor: cost, amountMinor: qty * cost, qtyReceived: 0, qtyDamaged: 0, qtyInvoiced: 0, openQty: qty };
      });
      if (Object.keys(errors).length) throw invalid('Invalid purchase order', errors);
      const n = ++ctx.db.seq;
      const total = lines.reduce((a, l) => a + l.amountMinor, 0);
      const po: PurchaseOrder = {
        id: uuid(n, 'eeee0001'), number: `PO-1P-000${300 + n}`, supplierId: sup!.id, supplierCode: sup!.code, currency: sup!.currency, fxRate: 15.42, baseCurrency: 'USD', destinationNodeId: String(b.destinationNodeId),
        incoterm: String(b.incoterm ?? 'FOB'), orderDate: today(ctx), expectedDate: typeof b.expectedDate === 'string' ? b.expectedDate : null, status: 'DRAFT', approvalTier: total / 15.42 > 1_000_000 ? 'FINANCE' : 'BUYER', shipmentBooked: false,
        totalMinor: total, totalBaseMinor: Math.round(total / 15.42), lines, history: [{ status: 'DRAFT', at: ctx.now.toISOString(), by: ctx.actor, note: null }], createdBy: ctx.actor, approvedBy: null, createdAt: ctx.now.toISOString(), version: 0
      };
      ctx.db.pos.unshift(po);
      return po;
    }, 201)),
    http.get(`${W}/purchase-orders/:id`, h('VIEW', (ctx, p) => poOf(ctx, p.id))),
    http.post(`${W}/purchase-orders/:id/submit`, h('EDIT', (ctx, p) => transition(ctx, poOf(ctx, p.id), ['DRAFT'], 'PENDING_APPROVAL'))),
    http.post(`${W}/purchase-orders/:id/approve`, h('APPROVE', async (ctx, p) => transition(ctx, poOf(ctx, p.id), ['PENDING_APPROVAL'], 'APPROVED', ((await bodyOf(ctx.request)).note as string | undefined) ?? undefined))),
    http.post(`${W}/purchase-orders/:id/send-back`, h('APPROVE', async (ctx, p) => transition(ctx, poOf(ctx, p.id), ['PENDING_APPROVAL'], 'DRAFT', ((await bodyOf(ctx.request)).note as string | undefined) ?? undefined))),
    http.post(`${W}/purchase-orders/:id/send`, h('EDIT', (ctx, p) => transition(ctx, poOf(ctx, p.id), ['APPROVED'], 'SENT', 'PDF emailed and EDI 850 sent'))),
    http.post(`${W}/purchase-orders/:id/confirm`, h('EDIT', (ctx, p) => transition(ctx, poOf(ctx, p.id), ['SENT'], 'CONFIRMED', 'Supplier confirmed quantity, price and ready date'))),
    http.post(`${W}/purchase-orders/:id/close`, h('EDIT', (ctx, p) => transition(ctx, poOf(ctx, p.id), ['RECEIVED', 'PARTIALLY_RECEIVED'], 'CLOSED'))),
    http.post(`${W}/purchase-orders/:id/cancel`, h('EDIT', (ctx, p) => {
      const po = poOf(ctx, p.id);
      if (po.shipmentBooked) throw conflict('A shipment is booked; cancel is no longer allowed.');
      return transition(ctx, po, ['DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'SENT', 'CONFIRMED'], 'CANCELLED');
    })),
    http.get(`${W}/purchase-orders/:id/dispatches`, h('VIEW', (ctx, p) => {
      const po = poOf(ctx, p.id);
      return { items: ['SENT', 'CONFIRMED', 'PARTIALLY_RECEIVED', 'RECEIVED', 'CLOSED'].includes(po.status) ? [{ channel: 'EMAIL_PDF', status: 'DELIVERED', attempts: 1, lastError: null, sentAt: ctx.now.toISOString() }, { channel: 'EDI_850', status: 'ACKNOWLEDGED', attempts: 1, lastError: null, sentAt: ctx.now.toISOString() }] : [] };
    })),
    http.get(`${W}/purchase-orders/:id/pdf`, h('VIEW', (ctx, p) => new HttpResponse(`%PDF-1.4\n% ${poOf(ctx, p.id).number}\n%%EOF`, { headers: { 'Content-Type': 'application/pdf' } }))),
    http.get(`${W}/purchase-orders/:id/edi850`, h('VIEW', (ctx, p) => new HttpResponse(`ISA*00*          *00*          *ZZ*AFROTRANSACT   *ZZ*SUPACC         *261011*1200*U*00401*000000322*0*P*>~\nBEG*00*SA*${poOf(ctx, p.id).number}**20261011~\nSE*3*0001~`, { headers: { 'Content-Type': 'text/plain' } }))),
    http.get(`${W}/shipments`, h('VIEW', (ctx) => page(ctx.db.shipments.filter((s) => !ctx.url.searchParams.get('poId') || s.poId === ctx.url.searchParams.get('poId')), ctx.url))),
    http.post(`${W}/shipments`, h('EDIT', async (ctx) => {
      const b = await bodyOf(ctx.request);
      const po = poOf(ctx, String(b.poId ?? ''));
      if (!['CONFIRMED', 'SENT', 'APPROVED'].includes(po.status)) throw conflict(`${po.number} must be confirmed by the supplier first.`);
      const raw: Record<string, unknown>[] = Array.isArray(b.lines) && b.lines.length ? (b.lines as Record<string, unknown>[]) : po.lines.map((l) => ({ poLineNo: l.lineNo, qty: l.openQty }));
      const n = ++ctx.db.seq;
      const s: RetailShipment = {
        id: uuid(n, 'ffff0001'), reference: `SHP-${n}`, poId: po.id, poNumber: po.number, mode: String(b.mode ?? 'OCEAN_FCL'), carrier: (b.carrier as string | undefined) ?? null, trackingRef: (b.trackingRef as string | undefined) ?? null, destinationNodeId: po.destinationNodeId,
        etd: (b.etd as string | undefined) ?? null, eta: (b.eta as string | undefined) ?? null, stage: 'BOOKED', stageLabel: 'PO confirmed', asnLocked: false,
        lines: raw.map((l, i) => {
          const pl = must(po.lines.find((x) => x.lineNo === Number(l.poLineNo)) ?? po.lines[0]);
          return { lineNo: i + 1, poLineNo: pl.lineNo, itemId: pl.itemId, sku: pl.sku, qty: Number(l.qty), lotNo: (l.lotNo as string | undefined) ?? null, mfgDate: (l.mfgDate as string | undefined) ?? null, expDate: (l.expDate as string | undefined) ?? null };
        }),
        cartons: [], documents: [{ name: 'Commercial invoice', status: 'MISSING', reference: null }, { name: 'Packing list', status: 'RECEIVED', reference: null }], milestones: [{ stage: 'BOOKED', at: ctx.now.toISOString(), by: ctx.actor }], version: 0
      };
      po.shipmentBooked = true;
      ctx.db.shipments.unshift(s);
      // retail.shipment.stage-changed → inventory: a RETAIL_ASN inbound notice (units inbound at the FC).
      ctx.db.inbound.unshift({
        id: uuid(n, 'dddd0001'), number: `ASN-${s.reference}`, source: 'RETAIL_ASN', externalRef: s.reference, ownerOrg: RETAIL_ORG, nodeId: po.destinationNodeId, assignmentReason: 'Purchase order destination', name: po.number, status: 'SHIPPED',
        shipFrom: null, shipTo: null, carrier: s.carrier, trackingNumber: s.trackingRef, labelDocumentUrl: null, expectedArrival: s.eta, expectedUnits: 0, receivedUnits: 0, receiptCount: 0, discrepancy: false, discrepancies: [],
        lines: s.lines.map((l, i) => ({ lineNo: i + 1, sku: l.sku, atin: null, unitLabel: null, expectedQty: l.qty, receivedGood: 0, receivedDamaged: 0, shortQty: l.qty, overQty: 0, inboundOpen: l.qty, casePack: ctx.db.items.find((x) => x.id === l.itemId)?.casePack ?? null, cases: 0, lotNo: l.lotNo, mfgDate: l.mfgDate, expDate: l.expDate })),
        boxes: [], createdBy: 'retail-service', createdAt: ctx.now.toISOString(), shippedAt: ctx.now.toISOString(), closedAt: null, updatedAt: ctx.now.toISOString(), version: 0
      });
      recompute(must(ctx.db.inbound[0]));
      return s;
    }, 201)),
    http.post(`${W}/shipments/:id/advance`, h('EDIT', (ctx, p) => {
      const s = ctx.db.shipments.find((x) => x.id === p.id);
      if (!s) throw notFound('shipment');
      const order = ['BOOKED', 'CARGO_READY', 'PICKED_UP', 'EXPORT_CLEARED', 'DEPARTED', 'ARRIVED_PORT', 'CUSTOMS_CLEARED', 'DELIVERED_TO_FC', 'RECEIVED'];
      const labels = ['PO confirmed', 'Cargo ready', 'Picked up at origin', 'Export cleared', 'Departed origin', 'Arrived US port', 'US customs cleared', 'Delivered to FC', 'Received & put away'];
      const i = order.indexOf(s.stage);
      if (i >= order.length - 2) throw conflict('Receive the shipment at the fulfilment centre to finish it.');
      s.stage = order[i + 1] ?? s.stage;
      s.stageLabel = labels[i + 1] ?? s.stageLabel;
      s.asnLocked = i + 1 >= 4;
      s.milestones.push({ stage: s.stage, at: ctx.now.toISOString(), by: ctx.actor });
      s.version += 1;
      return s;
    })),
    http.get(`${W}/receipts`, h('VIEW', (ctx) => {
      const q = ctx.url.searchParams;
      return page(ctx.db.receipts.filter((r) => (!q.get('status') || r.status === q.get('status')) && (!q.get('poId') || r.poId === q.get('poId'))), ctx.url);
    })),
    http.post(`${W}/receipts`, h('EDIT', async (ctx) => {
      const b = await bodyOf(ctx.request);
      const ship = b.shipmentId ? ctx.db.shipments.find((s) => s.id === b.shipmentId) : undefined;
      const po = poOf(ctx, String(b.poId ?? ship?.poId ?? ''));
      const existing = ctx.db.receipts.find((r) => r.poId === po.id && r.status === 'OPEN');
      if (existing) return existing;
      const n = ++ctx.db.seq;
      const lines = ship
        ? ship.lines.map((l) => ({ lineNo: l.lineNo, poLineNo: l.poLineNo, asnLineNo: l.lineNo, itemId: l.itemId, sku: l.sku, expectedQty: l.qty, asnLotNo: l.lotNo, lotNo: l.lotNo, mfgDate: l.mfgDate, expDate: l.expDate, qtyGood: 0, qtyDamaged: 0, bin: null, qa: 'PENDING' as const, scanned: false, split: false }))
        : po.lines.map((l) => ({ lineNo: l.lineNo, poLineNo: l.lineNo, asnLineNo: null, itemId: l.itemId, sku: l.sku, expectedQty: l.openQty, asnLotNo: null, lotNo: null, mfgDate: null, expDate: null, qtyGood: 0, qtyDamaged: 0, bin: null, qa: 'PENDING' as const, scanned: false, split: false }));
      const r: GoodsReceipt = { id: uuid(n, '77770001'), number: `GRN-${String(n).padStart(6, '0')}`, poId: po.id, poNumber: po.number, shipmentId: ship?.id ?? null, shipmentRef: ship?.reference ?? null, nodeId: po.destinationNodeId, dock: (b.dock as string | undefined) ?? 'Dock 1', status: 'OPEN', lines, overApprovedBy: null, discrepancies: [], postedLots: [], openedBy: ctx.actor, openedAt: ctx.now.toISOString(), completedAt: null, version: 0 };
      ctx.db.receipts.unshift(r);
      return r;
    }, 201)),
    http.get(`${W}/receipts/:id`, h('VIEW', (ctx, p) => receiptOf(ctx, p.id))),
    http.put(`${W}/receipts/:id/lines`, h('EDIT', async (ctx, p) => {
      const r = receiptOf(ctx, p.id);
      checkVersion(ctx, r.version);
      if (r.status !== 'OPEN') throw conflict(`${r.number} is ${r.status.toLowerCase()}.`);
      const lines = ((await bodyOf(ctx.request)).lines as Record<string, unknown>[] | undefined) ?? [];
      lines.forEach((l) => {
        const existing = r.lines.find((x) => x.lineNo === Number(l.lineNo));
        const base = existing ?? r.lines.find((x) => x.poLineNo === Number(l.poLineNo));
        if (!base) throw invalid('Unknown PO line', { [`lines[${String(l.lineNo)}].poLineNo`]: 'Not on this PO' });
        const next = { ...base, lineNo: Number(l.lineNo), poLineNo: Number(l.poLineNo), lotNo: (l.lotNo as string | undefined) ?? base.lotNo, mfgDate: (l.mfgDate as string | undefined) ?? base.mfgDate, expDate: (l.expDate as string | undefined) ?? base.expDate, qtyGood: Number(l.qtyGood ?? 0), qtyDamaged: Number(l.qtyDamaged ?? 0), bin: (l.bin as string | undefined) ?? base.bin, qa: (l.qa as 'PENDING' | 'PASS' | 'FAIL' | undefined) ?? base.qa, scanned: Boolean(l.scanned), split: !existing };
        if (existing) Object.assign(existing, next);
        else r.lines.push(next);
      });
      r.version += 1;
      return r;
    })),
    http.post(`${W}/receipts/:id/validate`, h('VIEW', (ctx, p) => validation(ctx, receiptOf(ctx, p.id)))),
    http.post(`${W}/receipts/:id/approve-over-receipt`, h('APPROVE', (ctx, p) => {
      const r = receiptOf(ctx, p.id);
      if (!overBlocked(r).length) throw conflict('Nothing is over the tolerance.');
      r.overApprovedBy = ctx.actor;
      r.version += 1;
      return r;
    })),
    http.post(`${W}/receipts/:id/complete`, h('EDIT', (ctx, p) => {
      const r = receiptOf(ctx, p.id);
      if (r.status === 'COMPLETED') return r;
      const v = validation(ctx, r);
      if (!v.ok) throw invalid([...v.receiptErrors, ...v.lines.flatMap((l) => l.errors.map((e) => `Line ${l.lineNo}: ${e}`))].join('; '), Object.fromEntries(v.lines.filter((l) => l.errors.length).map((l) => [`lines[${l.lineNo}]`, l.errors.join('; ')])));
      const po = poOf(ctx, r.poId);
      r.lines.filter((l) => l.qtyGood + l.qtyDamaged > 0).forEach((l) => {
        const lotNo = (l.lotNo ?? r.number).toUpperCase();
        if (l.qtyGood > 0) {
          const lot = addLot(ctx, l.sku, lotNo, r.nodeId, RETAIL_ORG, l.qtyGood, l.mfgDate ?? null, l.expDate ?? null, l.bin ?? 'A-05-01', l.qa === 'FAIL' ? 'QUARANTINE' : 'SELLABLE', r.number, 298);
          r.postedLots.push({ lotId: lot.id, sku: l.sku, lotNo, qty: l.qtyGood, status: lot.status, unitLandedCostMinor: 298 });
        }
        if (l.qtyDamaged > 0) {
          const dmg = addLot(ctx, l.sku, `${lotNo}-DMG`, r.nodeId, RETAIL_ORG, l.qtyDamaged, l.mfgDate ?? null, l.expDate ?? null, 'Q-01-01', 'DAMAGED', r.number, 298);
          r.postedLots.push({ lotId: dmg.id, sku: l.sku, lotNo: dmg.lotNo, qty: l.qtyDamaged, status: 'DAMAGED', unitLandedCostMinor: 298 });
        }
        const pl = po.lines.find((x) => x.lineNo === l.poLineNo);
        if (pl) {
          pl.qtyReceived += l.qtyGood;
          pl.qtyDamaged += l.qtyDamaged;
          pl.openQty = Math.max(0, pl.qtyOrdered - pl.qtyReceived - pl.qtyDamaged);
        }
      });
      po.lines.forEach((pl) => {
        if (pl.openQty > 0) r.discrepancies.push({ claimRef: `CLM-${r.number}-${pl.lineNo}`, type: 'SHORT', poLineNo: pl.lineNo, sku: pl.sku, expectedQty: pl.qtyOrdered, receivedQty: pl.qtyReceived, qty: pl.openQty, status: 'OPEN', note: null });
      });
      po.status = po.lines.every((pl) => pl.openQty === 0) ? 'RECEIVED' : 'PARTIALLY_RECEIVED';
      po.history.push({ status: po.status, at: ctx.now.toISOString(), by: ctx.actor, note: r.number });
      po.version += 1;
      const ship = ctx.db.shipments.find((s) => s.id === r.shipmentId);
      if (ship) {
        ship.stage = 'RECEIVED';
        ship.stageLabel = 'Received & put away';
      }
      const asn = ctx.db.inbound.find((s) => s.externalRef && s.externalRef === r.shipmentRef);
      if (asn) {
        asn.lines.forEach((al) => {
          const got = r.lines.filter((l) => l.sku === al.sku);
          al.receivedGood = got.reduce((a, l) => a + l.qtyGood, 0);
          al.receivedDamaged = got.reduce((a, l) => a + l.qtyDamaged, 0);
          al.inboundOpen = 0;
        });
        asn.status = 'CLOSED';
        asn.receiptCount += 1;
        recompute(asn);
      }
      r.status = 'COMPLETED';
      r.completedAt = ctx.now.toISOString();
      r.version += 1;
      return r;
    })),
    http.get(`${W}/invoices`, h('VIEW', (ctx) => page(ctx.db.invoices.filter((i) => !ctx.url.searchParams.get('poId') || i.poId === ctx.url.searchParams.get('poId')), ctx.url))),
    http.post(`${W}/invoices`, h('EDIT', async (ctx) => {
      const b = await bodyOf(ctx.request);
      const po = poOf(ctx, String(b.poId ?? ''));
      const lines = ((b.lines as Record<string, unknown>[] | undefined) ?? []).map((l) => ({ poLineNo: Number(l.poLineNo), qty: Number(l.qty), unitPriceMinor: Number(l.unitPriceMinor) }));
      if (!b.invoiceNumber || !lines.length) throw invalid('Invoice number and lines are required', { invoiceNumber: 'Required' });
      const expected = lines.reduce((a, l) => a + (po.lines.find((p) => p.lineNo === l.poLineNo)?.unitCostMinor ?? 0) * Math.min(l.qty, po.lines.find((p) => p.lineNo === l.poLineNo)?.qtyReceived ?? 0), 0);
      const invoiced = lines.reduce((a, l) => a + l.qty * l.unitPriceMinor, 0);
      const pct = expected ? ((invoiced - expected) / expected) * 100 : 100;
      const reasons: string[] = [];
      lines.forEach((l) => {
        const pl = po.lines.find((p) => p.lineNo === l.poLineNo);
        if (!pl) reasons.push(`Line ${l.poLineNo} is not on ${po.number}`);
        else {
          if (l.qty > pl.qtyReceived) reasons.push(`Line ${l.poLineNo}: invoiced ${l.qty}, received ${pl.qtyReceived}`);
          if (l.unitPriceMinor !== pl.unitCostMinor) reasons.push(`Line ${l.poLineNo}: price ${l.unitPriceMinor} vs PO ${pl.unitCostMinor}`);
        }
      });
      const n = ++ctx.db.seq;
      const inv: SupplierInvoice = { id: uuid(n, '66660001'), poId: po.id, poNumber: po.number, invoiceNumber: String(b.invoiceNumber), invoiceDate: String(b.invoiceDate ?? today(ctx)), currency: po.currency, totalMinor: invoiced, lines, status: reasons.length ? 'EXCEPTION' : 'MATCHED', match: { outcome: reasons.length ? 'EXCEPTION' : 'MATCHED', expectedTotalMinor: expected, invoicedTotalMinor: invoiced, totalVariancePercent: Math.round(pct * 100) / 100, reasons, lines: [] }, resolutionNote: null, version: 0 };
      ctx.db.invoices.unshift(inv);
      return inv;
    }, 201)),
    http.post(`${W}/invoices/:id/:action`, h('APPROVE', async (ctx, p) => {
      const inv = ctx.db.invoices.find((x) => x.id === p.id);
      if (!inv) throw notFound('invoice');
      const note = ((await bodyOf(ctx.request)).note as string | undefined) ?? null;
      if (p.action === 'approve') inv.status = 'APPROVED';
      else if (p.action === 'reject') inv.status = 'REJECTED';
      else if (p.action === 'rematch') inv.status = inv.match?.reasons.length ? 'EXCEPTION' : 'MATCHED';
      else throw notFound('invoice action');
      inv.resolutionNote = note;
      inv.version += 1;
      return inv;
    }))
  ];
}

/** Staff console routes: `W` is the staff prefix (e.g. "*\/v1/staff"). */
export function inventoryHubHandlers(W: string, gate: HubGate) {
  return [...staffInventoryRoutes(`${W}/inventory`, gate), ...inventoryRoutes(`${W}/inventory`, gate, 'inventory'), ...retailRoutes(`${W}/retail`, gate)];
}

/** Seller Center routes: `W` is the seller prefix (e.g. "*\/v1/seller"); the gate names the active organisation. */
export function sellerFbaHandlers(W: string, gate: HubGate) {
  const h = (fn: Fn) => handler(gate, 'fba', 'VIEW', fn);
  return [
    ...inventoryRoutes(`${W}/fba`, gate, 'fba'),
    http.get(`${W}/fba/inventory`, h((ctx) => {
      const lots = visible(ctx, ctx.db.lots);
      const keys = [...new Set(lots.map((l) => `${l.sku}|${l.nodeId}`))];
      const items = keys.map((k) => {
        const [sku, nodeId] = k.split('|');
        const ls = lots.filter((l) => l.sku === sku && l.nodeId === nodeId);
        const sell = ls.filter((l) => l.status === 'SELLABLE');
        return { sku, atin: null, nodeId, onHand: sell.reduce((a, l) => a + l.qtyOnHand, 0), reserved: sell.reduce((a, l) => a + l.qtyReserved, 0), available: sell.reduce((a, l) => a + l.qtyAvailable, 0), inbound: visible(ctx, ctx.db.inbound).filter((s) => s.status === 'SHIPPED' && s.nodeId === nodeId).reduce((a, s) => a + s.lines.filter((x) => x.sku === sku).reduce((b, x) => b + x.inboundOpen, 0), 0), unfulfillable: ls.filter((l) => l.status !== 'SELLABLE').reduce((a, l) => a + l.qtyOnHand, 0), stranded: false };
      });
      return { ownerOrg: ctx.ownerOrg, items, total: items.length, page: 0, size: items.length };
    }))
  ];
}
