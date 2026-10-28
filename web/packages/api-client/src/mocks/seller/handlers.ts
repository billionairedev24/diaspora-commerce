/* MSW handlers implementing contracts/openapi/gateway-bff-seller.yaml for the Seller Center & Provider Hub in
   development and tests. Paths use a wildcard prefix (any base URL ending in /v1/seller/...). The cookie
   at_mock_role overrides the member's role in the active organisation (e.g. seller.fulfilment) to try role-aware
   navigation without a real identity service. */
import { http, HttpResponse, type JsonBodyType } from 'msw';
import { z } from 'zod';

import { type BookingTakeHome, type SellerRateCard } from '../../fee-rates';
import { applyBps } from '../../fees';
import { bffProblemType } from '../../problem-types';
import { ExportColumnKindSchema, ExportFormatSchema, type ExportJob } from '../../schemas/exports';
import { REVIEW_REASON_CODES, SELLER_REPLY_MAX } from '../../schemas/reviews';
import {
  AvailabilityFormSchema,
  type BookingAction,
  BookingActionSchema,
  type BookingCard,
  type BookingStatus,
  type BusinessPageSettings,
  CadenceSchema,
  CancellationPolicyInputSchema,
  CarrierSchema,
  CategoryRequestInputSchema,
  type ChartSpec,
  CreateSellerCouponSchema,
  type Dashboard,
  type EarningsRow,
  FulfilmentKindSchema,
  type ListingRow,
  type ModuleKey,
  type OnboardingStatus,
  type OnboardingStripeSession,
  type OrgSummary,
  ParcelSchema,
  PickupWindowSchema,
  type PlanFeeCollectionView,
  ProductDraftSchema,
  SellerAddressSchema,
  type SellerCoupon,
  type SellerFeesView,
  type SellerFeeVersion,
  type SellerOrderDetail,
  type SellerOrderRow,
  type SellerProductDetail,
  type SellerSession,
  SellerStatusActionSchema,
  ServiceInputSchema,
  type ShipFromAddress,
  type ShipmentStatus,
  StockAdjustmentRequestSchema,
  TEAM_ERROR_MESSAGES,
  type TeamMember,
  UploadRequestSchema,
  validatePickupHours
} from '../../schemas/seller';
import { mockAddressSuggestions, mockResolveAddress } from '../addresses';
import { exportFileOf } from '../export-file';
import { type HubGate, sellerFbaHandlers } from '../inventory-hub';
import { parseSort, queryRows, type SortKey, type TableColumnLike } from '../table';
import { capabilitiesOf, CATEGORY_TREE, getSellerDb, type MockOrg, type MockSellerDb, plansFor, PRODUCT_TYPES, resetSellerDb, SELLER_ORG } from './db';
import {
  advanceImport,
  categoryLeaves,
  detailOf,
  draftView,
  IMPORT_LIMITS,
  importSummary,
  importView,
  listingProblems,
  type MockImport,
  parseCsv,
  productDetail,
  reportCsv,
  SAMPLE_UPLOAD,
  templateCsv,
  validateSheet
} from './listing-form';
import { allowedBookingActions, allows, BOOKING_LABELS, describePolicy, navigation, presetPolicy, roleCards, roleLabel, SHIPMENT_LABELS, takeHome } from './model';
import {
  accountForm,
  applicationOf,
  type FieldErrors,
  type MockApplication,
  onboardingPlans,
  onboardingStatus,
  saveCompliance,
  saveDetails,
  saveOwners,
  savePlan,
  submit,
  verifyStripe
} from './onboarding';
import { sellerSettingsHandlers } from './settings';

export const MOCK_ROLE_COOKIE = 'at_mock_role';

/** Mock image library (S310): file names SKU.MAIN / SKU.PT01…PT08 / SKU.SWCH match listings by SKU. */
interface MockLibraryImage {
  assetId: string;
  orgId: string;
  fileName: string;
  sku: string | null;
  slot: 'MAIN' | 'PT01' | 'PT02' | 'PT03' | 'PT04' | 'PT05' | 'PT06' | 'PT07' | 'PT08' | 'SWCH' | null;
  status: 'READY' | 'FLAGGED' | 'REJECTED';
  url: string | null;
  width: number;
  height: number;
  bytes: number;
  flags: string[];
  createdAt: string;
}
const MOCK_LIBRARY: MockLibraryImage[] = [];
const MOCK_PENDING_LIBRARY = new Map<string, { orgId: string; fileName: string; bytes: number }>();
const LIBRARY_NAME = /^(.+?)[._\- ](MAIN|PT0[1-8]|SWCH)\.(?:jpe?g|png|webp)$/i;

class SellerProblem extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    readonly title: string,
    detail: string,
    readonly errors?: Record<string, string>
  ) {
    super(detail);
  }
}

const problemResponse = (p: SellerProblem) =>
  HttpResponse.json(
    { type: bffProblemType(p.code), title: p.title, status: p.status, detail: p.message, code: p.code, ...(p.errors ? { errors: p.errors } : {}) } as JsonBodyType,
    { status: p.status, headers: { 'Content-Type': 'application/problem+json' } }
  );

const invalid = (errors: Record<string, string>) => new SellerProblem(422, 'VALIDATION_FAILED', 'Validation failed', 'One or more fields are invalid.', errors);
const forbidden = (detail: string) => new SellerProblem(403, 'FORBIDDEN', 'Forbidden', detail);
const notFound = (detail = 'We couldn’t find that.') => new SellerProblem(404, 'NOT_FOUND', 'Not found', detail);
const conflict = (detail: string) => new SellerProblem(409, 'CONFLICT', 'Conflict', detail);

function readCookie(request: Request, name: string): string | null {
  for (const part of (request.headers.get('cookie') ?? '').split(';')) {
    const [k, ...v] = part.trim().split('=');
    if (k === name) return decodeURIComponent(v.join('='));
  }
  return null;
}

interface Ctx {
  db: MockSellerDb;
  org: MockOrg;
  role: string;
  request: Request;
  now: Date;
}

function context(request: Request): Ctx {
  const db = getSellerDb();
  const org = db.orgs.find((o) => o.id === db.activeOrgId) ?? db.orgs[0];
  if (!org) throw new SellerProblem(403, 'NO_ORGANISATION', 'No organisation', 'You don’t belong to a seller or service-provider organisation yet.');
  const override = readCookie(request, MOCK_ROLE_COOKIE);
  const prefix = org.kind === 'PROVIDER' ? 'provider.' : 'seller.';
  const role = override && override.startsWith(prefix) ? override : org.role;
  return { db, org, role, request, now: new Date() };
}

/* ── Tables: one search over every column, sort by any column (as the BFF: SellerOrderColumns, SellerListingColumns) ── */

type SellerColumn = TableColumnLike & { label: string };
const ORDER_COLUMNS: SellerColumn[] = [
  { key: 'orderNumber', label: 'Order', kind: 'mono' }, { key: 'placedAt', label: 'Placed', kind: 'datetime' }, { key: 'buyerName', label: 'Buyer', kind: 'strong' },
  { key: 'shipTo', label: 'Ship to', kind: 'text' }, { key: 'itemsSummary', label: 'Items', kind: 'text' }, { key: 'itemCount', label: 'Units', kind: 'number' },
  { key: 'totalMinor', label: 'Total', kind: 'money' }, { key: 'fulfilment', label: 'Fulfilment', kind: 'text' }, { key: 'status', label: 'Status', kind: 'status' },
  { key: 'trackingNumber', label: 'Tracking', kind: 'mono' }
];
const LISTING_COLUMNS: SellerColumn[] = [
  { key: 'title', label: 'Product', kind: 'strong' }, { key: 'atin', label: 'ATIN', kind: 'mono' }, { key: 'sku', label: 'SKU', kind: 'mono' },
  { key: 'variantCount', label: 'Variants', kind: 'number' }, { key: 'priceMinor', label: 'Price', kind: 'money' }, { key: 'stock', label: 'Stock', kind: 'number' },
  { key: 'fulfilment', label: 'Fulfilment', kind: 'text' }, { key: 'status', label: 'Status', kind: 'status' }, { key: 'updatedAt', label: 'Updated', kind: 'datetime' }
];
const FULFILMENT_LABEL: Record<string, string> = { SELLER: 'You ship', AFROTRANSACT: 'AfroTransact', AFROTRANSACT_DELIVERY: 'AfroTransact pickup' };

function sortFor(cols: readonly SellerColumn[], values: string[]): SortKey[] {
  const sort = parseSort(values, (k) => cols.some((c) => c.key === k));
  if (!sort) throw invalid({ sort: 'This table can’t be sorted that way.' });
  return sort;
}

const orderCells = (r: SellerOrderRow) => ({
  id: r.shipmentId,
  row: r,
  cells: { orderNumber: r.orderNumber, placedAt: r.placedAt, buyerName: r.buyerName, shipTo: r.shipTo, itemsSummary: r.itemsSummary, itemCount: r.itemCount,
    totalMinor: r.totalMinor, fulfilment: FULFILMENT_LABEL[r.fulfilment] ?? r.fulfilment, status: r.statusLabel, trackingNumber: r.trackingNumber }
});
const listingCells = (r: ListingRow) => ({
  id: r.productId,
  row: r,
  cells: { title: r.title, atin: r.atin, sku: r.sku, variantCount: r.variantCount, priceMinor: r.priceMinor, stock: r.stock,
    fulfilment: FULFILMENT_LABEL[r.fulfilment] ?? r.fulfilment, status: r.statusLabel, updatedAt: r.updatedAt }
});

function sellerOrderRows(ctx: Ctx, status: string | null, q: string | null, sort: SortKey[], ids?: string[]): SellerOrderRow[] {
  const rows = ctx.db.orders.map(orderRow).filter((r): r is SellerOrderRow => r !== null && (!status || r.status === status));
  return queryRows(rows.map(orderCells), ORDER_COLUMNS, q, sort, ids).map((r) => r.row);
}

function sellerListingRows(ctx: Ctx, status: string | null, q: string | null, sort: SortKey[], ids?: string[]): ListingRow[] {
  const rows: ListingRow[] = ctx.db.listings.filter((l) => !status || l.status === status).map(({ variants: _v, ...row }) => row);
  return queryRows(rows.map(listingCells), LISTING_COLUMNS, q, sort, ids).map((r) => r.row);
}

const exportView = ({ owner: _o, orgId: _g, body: _b, ...job }: MockSellerDb['exports'][number]): ExportJob => job;

const ExportBody = z.object({
  table: z.enum(['orders', 'listings']).optional(),
  format: ExportFormatSchema,
  q: z.string().max(120).optional(),
  status: z.string().max(60).optional(),
  sort: z.array(z.string().max(90)).max(3).optional(),
  ids: z.array(z.string().max(200)).max(1000).optional(),
  columns: z.array(z.string().max(80)).max(40).optional(),
  dataset: z
    .object({
      table: z.string().regex(/^[a-z0-9-]{1,60}$/),
      title: z.string().max(120).optional(),
      scope: z.string().max(200).optional(),
      columns: z.array(z.object({ key: z.string().max(80), label: z.string().min(1).max(80), kind: ExportColumnKindSchema })).min(1).max(40),
      rows: z.array(z.array(z.union([z.string(), z.number(), z.boolean(), z.null()]))).max(5000)
    })
    .optional()
});

/** Approval, organisation kind and module access — the same checks the BFF makes. */
function require(ctx: Ctx, module: ModuleKey | null, level: 'VIEW' | 'EDIT', kind?: 'SELLER' | 'PROVIDER') {
  if (ctx.org.status !== 'APPROVED') throw new SellerProblem(403, 'NOT_APPROVED', 'Not approved yet', 'This part of the back office unlocks once your application is approved.');
  if (kind && ctx.org.kind !== kind) throw forbidden(`Switch to an organisation that uses ${kind === 'PROVIDER' ? 'Provider Hub' : 'Seller Center'}.`);
  if (module && !allows(ctx.org.kind, ctx.role, module, level)) throw forbidden(`Your role (${roleLabel(ctx.role)}) can’t ${level === 'VIEW' ? 'view' : 'change'} this.`);
}

type Resolver = (ctx: Ctx, params: Record<string, string>) => Promise<unknown> | unknown;

function route(fn: Resolver, status: number | ((out: unknown) => number) = 200) {
  return async ({ request, params }: { request: Request; params: Record<string, string | readonly string[] | undefined> }) => {
    const flat: Record<string, string> = {};
    Object.entries(params).forEach(([k, v]) => {
      if (typeof v === 'string') flat[k] = v;
    });
    try {
      const out = await fn(context(request), flat);
      const code = typeof status === 'function' ? status(out) : status;
      if (code === 204) return new HttpResponse(null, { status: code });
      return HttpResponse.json(out as JsonBodyType, { status: code });
    } catch (e) {
      if (e instanceof SellerProblem) return problemResponse(e);
      throw e;
    }
  };
}

async function body<S extends z.ZodType>(request: Request, schema: S): Promise<z.infer<S>> {
  let json: unknown = {};
  try {
    const text = await request.text();
    json = text ? JSON.parse(text) : {};
  } catch {
    throw new SellerProblem(400, 'MALFORMED_JSON', 'Malformed request', 'The request body is not valid JSON.');
  }
  const parsed = schema.safeParse(json);
  if (!parsed.success) {
    const errors: Record<string, string> = {};
    parsed.error.issues.forEach((i) => {
      errors[i.path.join('.') || '_'] = i.message;
    });
    throw invalid(errors);
  }
  return parsed.data;
}

const orgSummary = (o: MockOrg, role = o.role): OrgSummary => ({ id: o.id, kind: o.kind, name: o.name, status: o.status, approved: o.status === 'APPROVED', roleKey: role, roleLabel: roleLabel(role) });

const toShip = (db: MockSellerDb) => db.orders.filter((o) => o.shipments.some((s) => s.fulfilment === 'SELLER' && ['PLACED', 'CONFIRMED', 'PACKED', 'READY'].includes(s.status))).length;
const requested = (db: MockSellerDb) => db.bookings.filter((b) => b.status === 'REQUESTED' || b.status === 'RESCHEDULE_REQUESTED');

function session(ctx: Ctx): SellerSession {
  const { db, org, role } = ctx;
  const approved = org.status === 'APPROVED';
  const badges: Partial<Record<ModuleKey, number>> = { 'account-health': org.kind === 'SELLER' ? 2 : 0 };
  if (org.kind === 'SELLER') badges.orders = toShip(db);
  else badges.requests = requested(db).length;
  const capabilities = capabilitiesOf(db.subscription[org.kind].planCode);
  const allGroups = navigation(org.kind, role, approved, badges);
  const navGroups = capabilities.includes('coupons') ? allGroups : allGroups.map((g) => ({ ...g, modules: g.modules.filter((m) => m.key !== 'coupons') })).filter((g) => g.modules.length > 0);
  return {
    user: { ...db.user, initials: (db.user.firstName[0] ?? '') + (db.user.lastName[0] ?? '') },
    orgs: db.orgs.map((o) => orgSummary(o, o.id === org.id ? role : o.role)),
    activeOrg: orgSummary(org, role),
    portal: org.kind === 'PROVIDER' ? 'PROVIDER_HUB' : 'SELLER_CENTER',
    approved,
    navGroups,
    landing: navGroups[0]?.modules[0]?.key ?? 'home',
    accessNote: approved ? 'Least-privilege by role · enforced server-side' : 'Token scope: onboarding only',
    capabilities: approved ? capabilities : []
  };
}

/* ── orders ── */

function orderRow(o: SellerOrderDetail): SellerOrderRow | null {
  const s = o.shipments[0];
  if (!s) return null;
  return {
    orderNumber: o.orderNumber, shipmentId: s.shipmentId, placedAt: o.placedAt, buyerName: o.buyerName,
    shipTo: o.shippingAddress ? `${o.shippingAddress.city}, ${o.shippingAddress.state}` : '', itemsSummary: s.lines.map((l) => `${l.title} ×${l.quantity}`).join(', '),
    itemCount: s.lines.reduce((a, l) => a + l.quantity, 0), totalMinor: s.totalMinor, currency: o.currency, fulfilment: s.fulfilment, status: s.status,
    statusLabel: s.statusLabel, carrier: s.carrier, trackingNumber: s.trackingNumber, nextActions: s.nextActions, canBuyLabel: s.canBuyLabel
  };
}

const NEXT: Partial<Record<ShipmentStatus, ShipmentStatus>> = { PLACED: 'CONFIRMED', CONFIRMED: 'PACKED', PACKED: 'READY' };
/** Seller-shipped carrier parcels: "Shipped" once the label exists. */
const SHIPPED_FROM: ShipmentStatus = 'READY';

function findOrder(db: MockSellerDb, orderNumber: string) {
  const o = db.orders.find((x) => x.orderNumber === orderNumber);
  if (!o) throw notFound('We couldn’t find that order.');
  return o;
}

function refreshShipment(o: SellerOrderDetail, shipmentId: string) {
  const s = o.shipments.find((x) => x.shipmentId === shipmentId);
  if (!s) throw notFound('That shipment isn’t part of this order.');
  s.statusLabel = SHIPMENT_LABELS[s.status];
  const next = NEXT[s.status];
  // Sellers pack AfroTransact Delivery shipments too (a driver collects them), but buy no label for them.
  s.nextActions = s.fulfilment !== 'AFROTRANSACT' && next && SellerStatusActionSchema.safeParse(next).success ? [next as 'CONFIRMED' | 'PACKED' | 'READY'] : [];
  if (s.fulfilment === 'SELLER' && s.status === SHIPPED_FROM && s.trackingNumber) s.nextActions = ['PICKED_UP'];
  s.hasLabel = s.fulfilment === 'SELLER' && Boolean(s.trackingNumber);
  s.canBuyLabel = s.fulfilment === 'SELLER' && !s.trackingNumber && ['CONFIRMED', 'PACKED', 'READY'].includes(s.status);
  return s;
}

/* ── dashboard ── */

const daysBack = (n: number, now: Date) =>
  Array.from({ length: n }, (_, i) => new Date(now.getTime() - (n - 1 - i) * 86_400_000).toLocaleDateString('en-US', { month: 'short', day: 'numeric' }));
const wave = (base: number, amp: number, i: number, seed: number) => Math.max(0, Math.round(base + amp * Math.sin((i + seed) / 3.2) + amp * 0.6 * Math.cos((i * seed) / 5) + i * base * 0.012));
const chart = (c: Omit<ChartSpec, 'available'>): ChartSpec => ({ ...c, available: true });

function dashboard(ctx: Ctx, rangeDays: number): Dashboard {
  const { db, org, role, now } = ctx;
  const days = daysBack(rangeDays, now);
  const money = allows(org.kind, role, 'payouts', 'VIEW');
  const health = allows(org.kind, role, 'account-health', 'VIEW');
  if (org.kind === 'PROVIDER') {
    const reqs = requested(db).length;
    const rows = days.map((d, i) => ({ category: d, values: { bookings: wave(6, 3, i, 2), deposits: wave(420, 160, i, 5) } }));
    return {
      portal: 'PROVIDER_HUB', rangeDays,
      kpis: [
        { label: `Bookings, ${rangeDays} days`, value: String(rows.reduce((a, r) => a + (r.values.bookings ?? 0), 0)), detail: 'Deposits held until completion', tone: 'positive' },
        { label: 'Requests', value: String(reqs), detail: 'Expire after 4 hours', tone: reqs > 0 ? 'attention' : 'positive' },
        ...(money ? [{ label: 'Available to request', value: '$612.80', detail: '$420.00 still settling', tone: 'positive' as const }] : []),
        ...(health ? [{ label: 'Account health', value: 'Good', detail: 'Nothing outstanding', tone: 'positive' as const }] : [])
      ],
      charts: [
        chart({ id: 'bookings', title: `Bookings & deposits · last ${rangeDays} days`, kind: 'trend', categoryLabel: 'Day', valueFormat: 'count',
          series: [{ key: 'bookings', label: 'Bookings', tone: 'ink', format: 'count' }, { key: 'deposits', label: 'Deposits ($)', tone: 'gold', format: 'money' }], rows }),
        chart({ id: 'by-service', title: 'Bookings by service', kind: 'hbar', categoryLabel: 'Service', valueFormat: 'count', series: [{ key: 'bookings', label: 'Bookings', tone: 'ink', format: 'count' }],
          rows: [['Box braids', 64], ['Knotless', 51], ['Locs retwist', 38], ['Cornrows', 29], ['Kids styles', 22]].map(([c, v]) => ({ category: String(c), values: { bookings: Number(v) } })) }),
        chart({ id: 'status', title: 'Booking status', kind: 'donut', categoryLabel: 'Status', valueFormat: 'count', series: [{ key: 'count', label: 'Bookings', tone: 'ink', format: 'count' }],
          rows: [['Completed', 168], ['Upcoming', 24], ['Cancelled', 9], ['No-show', 4]].map(([c, v]) => ({ category: String(c), values: { count: Number(v) } })) }),
        chart({ id: 'earnings', title: 'Earnings by week', kind: 'stack', categoryLabel: 'Week', valueFormat: 'money',
          series: [{ key: 'paidOut', label: 'Paid out', tone: 'green', format: 'money' }, { key: 'pending', label: 'Pending', tone: 'gold', format: 'money' }],
          rows: ['W1', 'W2', 'W3', 'W4', 'W5', 'W6'].map((w, i) => ({ category: w, values: { paidOut: wave(2400, 500, i, 2), pending: wave(600, 200, i, 3) } })) })
      ],
      todo: [
        ...(reqs > 0 ? [{ label: `${reqs} booking requests to confirm within 4 h`, module: 'requests' as const, tone: 'info' as const }] : []),
        { label: 'Set holiday hours for Thanksgiving week', module: 'hours', tone: 'info' }
      ]
    };
  }
  const ship = toShip(db);
  const rows = days.map((d, i) => ({ category: d, values: { sales: wave(1900, 450, i, 3), units: wave(64, 16, i, 1) } }));
  const sales = rows.reduce((a, r) => a + (r.values.sales ?? 0), 0);
  return {
    portal: 'SELLER_CENTER', rangeDays,
    kpis: [
      { label: 'To ship', value: String(ship), detail: 'Buy labels and mark ready', tone: ship > 0 ? 'attention' : 'positive' },
      { label: `Sales, ${rangeDays} days`, value: `$${sales.toLocaleString('en-US')}.00`, detail: `${rows.reduce((a, r) => a + (r.values.units ?? 0), 0)} units`, tone: 'positive' },
      ...(money ? [{ label: 'Available to request', value: `$${(db.availableMinor / 100).toLocaleString('en-US', { minimumFractionDigits: 2 })}`, detail: '$527.25 still settling', tone: 'positive' as const }] : []),
      ...(health ? [{ label: 'Account health', value: 'Action needed', detail: '2 item(s) open', tone: 'attention' as const }] : [])
    ],
    charts: [
      chart({ id: 'sales', title: `Sales · last ${rangeDays} days`, kind: 'trend', categoryLabel: 'Day', valueFormat: 'money',
        series: [{ key: 'sales', label: 'Sales', tone: 'ink', format: 'money' }, { key: 'units', label: 'Units', tone: 'gold', format: 'count' }], rows }),
      chart({ id: 'top-products', title: 'Top products by revenue', kind: 'hbar', categoryLabel: 'Product', valueFormat: 'money', series: [{ key: 'revenue', label: 'Revenue', tone: 'ink', format: 'money' }],
        rows: [['Ofada rice 5 lb', 6240], ['Suya spice', 4180], ['Palm oil 1 L', 3920], ['Ankara dress', 3110], ['Shea butter', 2350]].map(([c, v]) => ({ category: String(c), values: { revenue: Number(v) } })) }),
      chart({ id: 'fulfilment', title: 'Orders by fulfilment', kind: 'donut', categoryLabel: 'Fulfilment', valueFormat: 'count', series: [{ key: 'orders', label: 'Orders', tone: 'ink', format: 'count' }],
        rows: [['Fulfilled by AfroTransact', 412], ['Ship yourself', 268], ['Local driver', 97]].map(([c, v]) => ({ category: String(c), values: { orders: Number(v) } })) }),
      chart({ id: 'stock', title: 'Stock by warehouse', kind: 'stack', categoryLabel: 'Warehouse', valueFormat: 'count',
        series: [{ key: 'available', label: 'Available', tone: 'green' }, { key: 'reserved', label: 'Reserved', tone: 'blue' }, { key: 'inbound', label: 'Inbound', tone: 'gold' }],
        rows: [['HOU1', 820, 64, 40], ['ATL1', 540, 41, 0], ['NJ1', 610, 38, 120], ['DFW1', 330, 25, 12]].map(([c, a, r, n]) => ({ category: String(c), values: { available: Number(a), reserved: Number(r), inbound: Number(n) } })) })
    ],
    todo: [
      ...(ship > 0 ? [{ label: `${ship} orders to ship — buy labels`, module: 'orders' as const, tone: 'info' as const }] : []),
      { label: '2 listings suppressed — missing ingredients', module: 'listings', tone: 'warning' },
      ...(health ? [{ label: 'Stripe needs more information — open Account health', module: 'account-health' as const, tone: 'warning' as const }] : [])
    ]
  };
}

/* ── bookings ── */

/** Provider Studio (8 %) with hair & beauty negotiated at 7 % until the end of the year, as payments' rate card says. */
const MOCK_NEGOTIATED_UNTIL = '2026-12-31T23:59:59Z';
export function mockConnectionFees(): SellerRateCard {
  return {
    kind: 'SERVICE', version: 2, effectiveFrom: '2026-08-01T05:00:00Z', planCode: 'provider_studio', planName: 'Provider Studio',
    other: { category: '', label: 'Other services', bps: 800, source: 'PLAN', planCode: 'provider_studio', planName: 'Provider Studio', negotiatedUntil: null },
    categories: [{ category: 'hair & beauty', label: 'Hair & beauty', bps: 700, source: 'OVERRIDE', planCode: 'provider_studio', planName: 'Provider Studio', negotiatedUntil: MOCK_NEGOTIATED_UNTIL }],
    negotiated: [{ type: 'CONNECTION_FEE', category: 'hair & beauty', label: 'Hair & beauty', rateBps: 700, amountMinor: null, startsAt: '2026-10-01T00:00:00Z', endsAt: MOCK_NEGOTIATED_UNTIL, status: 'ACTIVE' }]
  };
}

/** What the provider keeps at the booking's price after the connection fee and card fees (bookings' formula). */
function bookingTakeHome(b: BookingCard): BookingTakeHome {
  const card = mockConnectionFees();
  const rate = card.categories.find((c) => c.category === (b.serviceCategory ?? 'services')) ?? card.other;
  const cardFee = (charge: number) => (charge > 0 ? applyBps(charge, 290) + 30 : 0);
  const fee = applyBps(b.priceMinor, rate.bps);
  const cards = cardFee(b.depositMinor) + cardFee(b.priceMinor - b.depositMinor);
  return { connectionFeeBps: rate.bps, connectionFeeMinor: fee, cardFeesMinor: cards, keepMinor: b.priceMinor - fee - cards, source: rate.source, currency: b.currency };
}

function bookingCard(b: BookingCard, now: Date): BookingCard {
  return { ...b, statusLabel: BOOKING_LABELS[b.status], allowedActions: allowedBookingActions(b.status, b.startAt, now) };
}

const ACTION_STATUS: Record<BookingAction, BookingStatus> = {
  CONFIRM: 'CONFIRMED', DECLINE: 'DECLINED', PROPOSE: 'RESCHEDULE_PROPOSED', COMPLETE: 'COMPLETED', NO_SHOW: 'NO_SHOW', CANCEL: 'CANCELLED_PROVIDER'
};

function earningsRows(db: MockSellerDb, now: Date): EarningsRow[] {
  return db.bookings
    .filter((b) => new Date(b.startAt) <= now || b.status === 'CANCELLED_PROVIDER')
    .map((b) => {
      const collected = b.status === 'COMPLETED' ? b.priceMinor : b.status === 'NO_SHOW' ? b.depositMinor : 0;
      const commission = Math.round(collected * 0.08);
      const card = collected ? Math.round(collected * 0.029) + 30 : 0;
      const late = b.status === 'CANCELLED_PROVIDER' ? 1500 : 0;
      return {
        bookingId: b.id, reference: b.reference, customerName: b.customerName, serviceName: b.serviceName, startAt: b.startAt, status: b.status,
        statusLabel: BOOKING_LABELS[b.status], collectedMinor: collected, commissionMinor: commission, cardFeesMinor: card, lateCancelFeeMinor: late,
        netMinor: collected - commission - card - late
      };
    })
    .sort((a, b) => b.startAt.localeCompare(a.startAt));
}

/** Provider fees (late provider cancellations), as payments GET /v1/payouts/provider-fees lists them. */
function providerFees(db: MockSellerDb) {
  return db.bookings
    .filter((b) => b.status === 'CANCELLED_PROVIDER')
    .map((b, i) => ({
      id: `fee-${b.id}`, bookingReference: b.reference, amountMinor: 1500, currency: 'USD', reason: 'Late cancellation by provider',
      status: i === 0 ? ('OUTSTANDING' as const) : ('COLLECTED' as const), nextAttemptAt: i === 0 ? new Date(Date.now() + 86_400_000).toISOString() : null, createdAt: b.startAt
    }));
}

/** Fees as payments' effective-fee reads give them: v2 in force (fashion 11.50 %, the org's own override), v4 scheduled
    in three weeks (fashion 13.00 %, FBA $3.50). */
function sellerFees(kind: string, now: Date): SellerFeesView {
  const upcomingAt = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() + 21, 5, 0, 0)).toISOString();
  const launch: Record<string, number> = { beauty: 1000, books: 1000, fashion: 1150, groceries: 800, health: 1000, 'home & art': 1200, party: 1000, services: 1000 };
  const override: [string, number] = kind === 'PROVIDER' ? ['services', 900] : ['groceries', 700];
  const label = (k: string) => k.charAt(0).toUpperCase() + k.slice(1);
  const version = (v: number, at: string, rates: Record<string, number>, fba: number): SellerFeeVersion => ({
    version: v,
    effectiveFrom: at,
    defaultCommissionBps: 1000,
    categories: Object.keys(rates)
      .sort()
      .map((k) =>
        k === override[0]
          ? { category: k, label: label(k), commissionBps: override[1], source: 'OVERRIDE' as const, planCode: 'seller_growth', planName: 'Seller Growth', negotiatedUntil: '2026-12-31T23:59:59Z' }
          : { category: k, label: label(k), commissionBps: rates[k] ?? 1000, source: 'PLAN' as const, planCode: 'seller_growth', planName: 'Seller Growth', negotiatedUntil: null }
      ),
    cardPctBps: 290,
    cardFixedMinor: 30,
    fbaPerUnitMinor: fba,
    reverseCommissionOnRefund: true,
    deliveryFee:
      kind === 'PROVIDER'
        ? null
        : {
            enabled: true,
            maxRadiusMiles: 25,
            source: 'DEFAULT',
            tariff: { mode: 'FLAT', flatPerPackageMinor: 200, baseMinor: null, perMileMinor: null, bands: [], minMinor: null, maxMinor: null },
            summary: '$2.00 per package'
          }
  });
  const current = version(2, new Date(now.getTime() - 60 * 86_400_000).toISOString(), launch, 325);
  const upcoming = version(4, upcomingAt, { ...launch, fashion: 1300 }, 350);
  return {
    currency: 'USD',
    retail: false,
    note: null,
    current,
    upcoming,
    changes: [
      { field: 'category:fashion', label: 'Fashion commission', from: '11.50%', to: '13.00%' },
      { field: 'fbaPerUnitMinor', label: 'FBA fee per unit', from: '$3.25', to: '$3.50' }
    ],
    planCode: kind === 'PROVIDER' ? 'provider_studio' : 'seller_growth',
    planName: kind === 'PROVIDER' ? 'Provider Studio' : 'Seller Growth',
    negotiated:
      kind === 'PROVIDER'
        ? []
        : [{ type: 'COMMISSION', category: 'groceries', label: 'Groceries', rateBps: 700, amountMinor: null, startsAt: '2026-10-01T00:00:00Z', endsAt: '2026-12-31T23:59:59Z', status: 'ACTIVE' }, { type: 'FBA_FEE', category: '*', label: 'All categories', rateBps: 0, amountMinor: 250, startsAt: '2026-10-01T00:00:00Z', endsAt: null, status: 'ACTIVE' }],
    connectionFees: kind === 'PROVIDER' ? mockConnectionFees() : null
  };
}

const paused = (ctx: Ctx) => ctx.db.payoutsPaused && ctx.org.kind === 'SELLER';

const outstandingFees = (db: MockSellerDb) => providerFees(db).filter((f) => f.status === 'OUTSTANDING').reduce((a, f) => a + f.amountMinor, 0);

/** GS1 mod-10 check digit for GTIN-8/12/13/14 (ISBN-13 uses the same rule). */
export function gtinValid(code: string): boolean {
  if (!/^\d{8}$|^\d{12,14}$/.test(code)) return false;
  const digits = code.split('').map(Number);
  const check = digits.pop() ?? -1;
  const sum = digits.reverse().reduce((a, d, i) => a + d * (i % 2 === 0 ? 3 : 1), 0);
  return (10 - (sum % 10)) % 10 === check;
}


/* Small request bodies validated like the BFF does. */
const ActiveOrgBody = z.object({ orgId: z.string().min(1, 'Choose an organisation') });
const StatusBody = z.object({ status: z.string() });
const RatesBody = z.object({ carrier: CarrierSchema.optional(), parcel: ParcelSchema, shipFrom: SellerAddressSchema });
const LabelBody = RatesBody.extend({ carrier: CarrierSchema, service: z.string().min(1, 'Choose a rate') });
const ActiveBody = z.object({ active: z.boolean() });
const DraftBody = z.object({
  productId: z.string().max(80).optional(),
  title: z.string().max(200).optional(),
  categoryPath: z.string().max(300).optional(),
  payload: z.record(z.string(), z.unknown()),
  version: z.number().int().optional()
});
const WriterBody = z.object({
  productType: z.string().min(1).max(40),
  title: z.string().min(3).max(200),
  attributes: z.record(z.string(), z.string()).optional(),
  tone: z.enum(['warm', 'concise', 'premium']).optional()
});
/** Plans whose features include the AI listing writer (gateway-bff AIListingWriterPlans). */
const AI_PLANS = ['seller_growth', 'seller_pro'];

const SPREADSHEET_TYPES: Record<string, string> = { csv: 'text/csv; charset=utf-8', xlsx: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' };
/** A generated file (the mock writes CSV text even for .xlsx names: it isn't parsed back except as a download). */
const fileResponse = (text: string, fileName: string, format: string) =>
  new HttpResponse(text, { status: 200, headers: { 'Content-Type': SPREADSHEET_TYPES[format] ?? 'text/csv', 'Content-Disposition': `attachment; filename="${fileName}"` } });

/** The import job creates (or, in Update mode, finds) the listing of one product's rows. */
function importedListing(ctx: Ctx, imp: MockImport, rows: MockImport['rows']): string {
  const col = (r: MockImport['rows'][number], label: string) => r.cells[imp.header.findIndex((h) => h.replace(/\s*\*$/, '').trim() === label)] ?? '';
  if (imp.mode === 'UPDATE') {
    const l = ctx.db.listings.find((x) => x.variants.some((v) => v.sku.toUpperCase() === rows[0]?.sku));
    return l?.productId ?? '';
  }
  const first = rows[0];
  const productId = nextId(ctx.db, 'prod');
  const variants = rows.map((r) => ({ sku: r.sku, quantity: Number(col(r, 'Quantity')) || 0 }));
  ctx.db.listings.unshift({
    productId, atin: null, title: first ? col(first, 'Title') : 'Imported product', sku: variants[0]?.sku ?? '', variantCount: variants.length,
    priceMinor: Math.round(Math.min(...rows.map((r) => Number(col(r, 'Price (USD)')) || 0)) * 100), currency: 'USD',
    stock: variants.reduce((a, v) => a + v.quantity, 0), fulfilment: 'SELLER', status: 'IN_REVIEW', statusLabel: 'In review', moderationNote: null,
    imageUrl: null, updatedAt: ctx.now.toISOString(), variants
  });
  return productId;
}
const PayoutBody = z.object({ amountMinor: z.number().int().optional(), all: z.boolean().optional() });
const PlanBody = z.object({ planCode: z.string().min(1, 'Choose a plan'), cadence: CadenceSchema });
const PlanFeeBody = z.object({ mode: z.enum(['EARNINGS_FIRST', 'CARD'], 'Choose EARNINGS_FIRST or CARD') });

/** How the plan is paid (S298); owner and finance may change it while the platform offers it. */
function planFeeView(ctx: { db: MockSellerDb; org: MockOrg; role: string; now: Date }): PlanFeeCollectionView {
  const mode = ctx.db.planFeeCollection[ctx.org.kind];
  const sub = ctx.db.subscription[ctx.org.kind];
  const at = sub.trialEndsAt ?? sub.currentPeriodEnd;
  const day = at ? new Date(at).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric', timeZone: 'UTC' }) : null;
  const available = ctx.db.planFeeCollectionAvailable;
  return { available, mode, effective: available ? mode : 'CARD', source: 'SELLER', platformDefault: 'CARD', appliesFromInvoiceAt: at,
    appliesFromLabel: day ? `Applies from your next invoice, on ${day}` : 'Applies from your first paid invoice', inProgressInvoiceId: null,
    changedAt: null, canChange: available && (ctx.role.endsWith('.owner') || ctx.role.endsWith('.finance')) };
}
const AddPaymentMethodBody = z.object({
  stripePaymentMethodId: z.string().regex(/^pm_/, 'Expected a Stripe payment method id (pm_…)'), kind: z.enum(['CARD', 'US_BANK_ACCOUNT']), brand: z.string().optional(),
  last4: z.string().regex(/^[0-9]{4}$/, 'Four digits'), expMonth: z.number().int().optional(), expYear: z.number().int().optional(), bankName: z.string().optional(),
  accountType: z.string().optional(), makeDefault: z.boolean().optional()
});
const InviteBody = z.object({ email: z.email('Enter a valid email address'), roleKey: z.string().min(1) });
const RoleBody = z.object({ roleKey: z.string().min(1) });
const ActionBody = z.object({ action: BookingActionSchema, reason: z.string().max(500).optional(), proposedStartAt: z.string().optional() });

const ShipFromBody = z.object({
  label: z.string().trim().max(60).optional(),
  name: z.string().trim().min(2, 'Enter the sender name'),
  company: z.string().trim().max(120).optional(),
  phone: z.string().trim().max(32).optional(),
  line1: z.string().trim().min(3, 'Enter the street address'),
  line2: z.string().trim().max(120).optional(),
  city: z.string().trim().min(2, 'Enter the city'),
  state: z.string().trim().regex(/^[A-Za-z]{2}$/, 'Use the 2-letter state code'),
  postalCode: z.string().trim().regex(/^\d{5}(-\d{4})?$/, 'Enter a 5-digit ZIP code'),
  country: z.string().optional(),
  makeDefault: z.boolean().optional(),
  version: z.number().int().nullable().optional()
});

const CustomerPickupBody = z.object({ enabled: z.boolean(), hours: z.array(PickupWindowSchema).max(14, 'At most 14 pickup windows'), readyHours: z.number().int().min(0).max(168).optional() });
const PickupBody = z.object({ enabled: z.boolean(), hours: z.array(PickupWindowSchema).max(14, 'At most 14 pickup windows'), version: z.number().int().nullable().optional() });
const OfferFulfilmentBody = z.object({ fulfilment: FulfilmentKindSchema, shipFromLocationId: z.string().max(64).nullable().optional() });

const TokenBody = z.object({ token: z.string().trim().min(1, 'This invitation link is incomplete. Open it again from the email.').max(200) });
/** Invitation tokens the mock knows (Seller Center accept page). */
const MOCK_INVITES: Record<string, 'PENDING' | 'EXPIRED' | 'REVOKED'> = { 'demo-invite': 'PENDING', 'expired-invite': 'EXPIRED', 'revoked-invite': 'REVOKED' };
const TEAM_TITLES: Record<string, string> = {
  LAST_OWNER: 'Last owner', INVALID_ROLE: 'Invalid role', OWNER_ONLY: 'Owner only', OWN_ROLE: 'Your own role', ALREADY_MEMBER: 'Already a member',
  INVITATION_EXPIRED: 'Invitation expired', INVITATION_USED: 'Invitation already used', INVITATION_REVOKED: 'Invitation withdrawn'
};
const teamProblem = (status: number, code: string) => new SellerProblem(status, code, TEAM_TITLES[code] ?? code, TEAM_ERROR_MESSAGES[code] ?? code);

const W = '*/v1/seller';
/** Reviews this mock member has already reported (one report per member and review). */
const reported = new WeakMap<MockSellerDb, Set<string>>();
const query = (ctx: Ctx) => new URL(ctx.request.url).searchParams;
const nextId = (db: MockSellerDb, prefix: string) => `${prefix}-${++db.seq}`;

const BusinessPageBody = z.object({ description: z.string().max(2000).default(''), brandColor: z.string().max(7).default(''), logoUrl: z.string().max(2048).default('') });

function businessPage(ctx: Ctx): BusinessPageSettings {
  const p = ctx.db.businessPage[ctx.org.id];
  const slug = ctx.org.name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
  return { name: ctx.org.name, slug: slug || null, description: p?.description || null, brandColor: p?.brandColor || null, logoUrl: p?.logoUrl || null,
    contactEmail: ctx.db.user.email, canEdit: allows(ctx.org.kind, ctx.role, 'team', 'EDIT') };
}

function shipFromList(ctx: Ctx): ShipFromAddress[] {
  return (ctx.db.shipFrom[ctx.org.id] ??= []);
}

function saveShipFrom(ctx: Ctx, input: z.infer<typeof ShipFromBody>, existing?: ShipFromAddress): ShipFromAddress {
  const list = shipFromList(ctx);
  if (existing && input.version != null && input.version !== existing.version) throw conflict('Someone else changed this address. Reload and try again.');
  if (!existing && list.length >= 20) throw conflict('You can save up to 20 ship-from addresses.');
  const makeDefault = input.makeDefault === true || list.length === 0 || (existing?.isDefault ?? false);
  if (makeDefault) list.forEach((a) => (a.isDefault = false));
  const next: ShipFromAddress = {
    id: existing?.id ?? nextId(ctx.db, 'sfa'),
    label: input.label || input.city,
    name: input.name,
    company: input.company ?? '',
    phone: input.phone ?? '',
    line1: input.line1,
    line2: input.line2 ?? '',
    city: input.city,
    state: input.state.toUpperCase(),
    postalCode: input.postalCode,
    country: 'US',
    isDefault: makeDefault,
    version: existing ? existing.version + 1 : 0,
    // geo-service geocodes every change (S255); the mock can't place ZIP 00000 at all and 99999 only by its ZIP.
    position:
      input.postalCode === '00000'
        ? null
        : { lat: 29.76 + (Number(input.postalCode.slice(-2)) || 0) / 1000, lng: -95.37, precision: input.postalCode === '99999' ? 'ZIP' : 'ADDRESS', geocodedAt: ctx.now.toISOString() },
    afrotransactPickup: existing?.afrotransactPickup ?? { enabled: false, hours: [], zoneName: null, enabledAt: null },
    customerPickup: existing?.customerPickup ?? { enabled: false, hours: [], readyHours: 0 }
  };
  // Moving an enabled pickup location out of the delivery area turns it off (seller re-checks every move).
  if (next.afrotransactPickup.enabled && !next.postalCode.startsWith('77')) next.afrotransactPickup = { ...next.afrotransactPickup, enabled: false, enabledAt: null };
  if (existing) list.splice(list.indexOf(existing), 1, next);
  else list.push(next);
  return next;
}

function findShipFrom(ctx: Ctx, id: string | undefined): ShipFromAddress {
  const found = shipFromList(ctx).find((a) => a.id === id);
  if (!found) throw notFound('No such ship-from address.');
  return found;
}

function teamView(ctx: Ctx) {
  const canManage = allows(ctx.org.kind, ctx.role, 'team', 'EDIT');
  const members = (ctx.db.team[ctx.org.id] ?? []).filter((m) => canManage || m.status !== 'INVITED');
  return { members, roles: roleCards(ctx.org.kind), yourRoleKey: ctx.role, canManage };
}

function checkRole(ctx: Ctx, roleKey: string) {
  if (!roleCards(ctx.org.kind).some((r) => r.key === roleKey)) throw new SellerProblem(422, 'INVALID_ROLE', 'Invalid role', TEAM_ERROR_MESSAGES.INVALID_ROLE ?? '', { roleKey: 'Choose a role of this organisation' });
  if (roleKey.endsWith('.owner') && !ctx.role.endsWith('.owner')) throw teamProblem(403, 'OWNER_ONLY');
}

function activeMember(ctx: Ctx, userId: string | undefined): TeamMember {
  const m = (ctx.db.team[ctx.org.id] ?? []).find((x) => x.userId === userId && x.status !== 'INVITED');
  if (!m) throw notFound();
  return m;
}

const owners = (ctx: Ctx) => (ctx.db.team[ctx.org.id] ?? []).filter((m) => m.status !== 'INVITED' && m.roleKey.endsWith('.owner')).length;

/** Seller Center FBA routes (S281): approved seller organisations, Inventory module, lists limited to the active org. */
const fbaGate: HubGate = (request, _area, level) => {
  try {
    const ctx = context(request);
    require(ctx, 'inventory', level === 'VIEW' ? 'VIEW' : 'EDIT', 'SELLER');
    return { ownerOrg: ctx.org.id, actor: ctx.role };
  } catch (e) {
    if (e instanceof SellerProblem) return { status: e.status, code: e.code, title: e.title, detail: e.message };
    throw e;
  }
};

/* ── The application wizard (S286, S289): see ./onboarding ── */

const StartBody = z.object({ kind: z.enum(['SELLER', 'PROVIDER']) });
const BusinessBody = z.object({
  offering: z.enum(['PRODUCTS', 'SERVICES', 'BOTH']).optional(),
  entityType: z.enum(['SOLE_PROPRIETOR', 'SINGLE_MEMBER_LLC', 'MULTI_MEMBER_LLC', 'CORPORATION', 'PARTNERSHIP', 'NONPROFIT']).optional(),
  country: z.string().optional(),
  storeName: z.string().optional(),
  contactEmail: z.string().optional(),
  phone: z.string().optional(),
  description: z.string().optional(),
  brandColor: z.string().optional(),
  logoUrl: z.string().optional()
});
const AddressBody = z.object({ line1: z.string(), line2: z.string().optional(), city: z.string(), state: z.string(), postalCode: z.string(), country: z.string().optional() });
const DetailsBody = z.object({
  legalFirstName: z.string().optional(),
  legalLastName: z.string().optional(),
  legalName: z.string().optional(),
  dba: z.string().optional(),
  taxId: z.string().optional(),
  dateOfBirth: z.string().optional(),
  stateOfFormation: z.string().optional(),
  dateOfFormation: z.string().optional(),
  address: AddressBody.optional(),
  formationDocumentAssetId: z.string().optional(),
  irsDeterminationLetterAssetId: z.string().optional()
});
const OwnersBody = z.object({
  owners: z.array(z.object({
    firstName: z.string().optional(), lastName: z.string().optional(), title: z.string().optional(), email: z.string().optional(),
    ownershipPercent: z.number().min(0).max(100).optional(), significantControl: z.boolean().optional(), dateOfBirth: z.string().optional(), address: AddressBody.optional()
  })).max(10)
});
const OnboardingPlanBody = z.object({
  planCode: z.string(), cadence: z.enum(['MONTHLY', 'QUARTERLY', 'ANNUAL']), paymentMethodKind: z.enum(['CARD']).optional(), stripePaymentMethodId: z.string().optional(),
  cardBrand: z.string().optional(), cardLast4: z.string().optional(), cardExpMonth: z.number().int().optional(), cardExpYear: z.number().int().optional(),
  promoCode: z.string().optional(),
  planFeeCollection: z.enum(['EARNINGS_FIRST', 'CARD'], 'Choose EARNINGS_FIRST or CARD').optional()
});
const ComplianceBody = z.object({
  categories: z.array(z.string()).optional(),
  foodPermit: z.object({ permitType: z.enum(['FOOD_ESTABLISHMENT', 'COTTAGE_FOOD', 'FDA_FACILITY', 'FOOD_HANDLER']).optional(), permitNumber: z.string().optional(),
    issuingAuthority: z.string().optional(), expiresOn: z.string().optional(), documentAssetId: z.string().optional() }).optional(),
  beautyLabellingConfirmed: z.boolean().optional(),
  services: z.array(z.object({ serviceName: z.string(), regulated: z.boolean().optional(), licenceNumber: z.string().optional(), issuingState: z.string().optional(),
    expiresOn: z.string().optional(), documentAssetId: z.string().optional() })).optional(),
  insuranceDocumentAssetId: z.string().optional(),
  agreementsAccepted: z.boolean().optional(),
  w9Certified: z.boolean().optional(),
  w9SignerName: z.string().optional()
});
const UploadBody = z.object({ fileName: z.string().min(1), contentType: z.string(), size: z.number().int() });
const blankToNull = (v: string | undefined): string | null => (v && v.trim() ? v.trim() : null);

/** The applicant's DRAFT organisation (or the active one), for the wizard's autosave. */
function applicantOrg(db: MockSellerDb): MockOrg {
  const org = db.orgs.find((o) => o.id === db.activeOrgId) ?? db.orgs[0];
  if (!org) throw new SellerProblem(404, 'NOT_FOUND', 'Not found', 'You haven’t started an application yet.');
  if (org.status !== 'DRAFT' && org.status !== 'INFO_REQUESTED') throw new SellerProblem(409, 'CONFLICT', 'Conflict', 'This application can’t be edited.');
  return org;
}

/** Saves one step with fn (null = saved, else field errors → 422) and answers the application's status. */
function saveStep<S extends z.ZodType>(schema: S, fn: (app: MockApplication, input: z.infer<S>, org: MockOrg) => FieldErrors | null) {
  return plain(async (db, request) => {
    const input = await body(request, schema);
    const org = applicantOrg(db);
    const errors = fn(applicationOf(db, org), input, org);
    if (errors) throw new SellerProblem(422, 'VALIDATION_FAILED', 'Validation failed', 'Some fields are invalid.', errors);
    return onboardingStatus(db, org);
  });
}

function plain(fn: (db: MockSellerDb, request: Request) => unknown | Promise<unknown>, status = 200) {
  return async ({ request }: { request: Request }) => {
    try {
      return HttpResponse.json((await fn(getSellerDb(), request)) as JsonBodyType, { status });
    } catch (e) {
      if (e instanceof SellerProblem) return problemResponse(e);
      throw e;
    }
  };
}

export const sellerHandlers = [
  /* ── Shell ── */
  http.get(
    `${W}/session`,
    plain((db, request): SellerSession => {
      if (db.orgs.length > 0) return session(context(request));
      // A customer who has not applied: no organisation, no navigation (Seller Center starts the application).
      return {
        user: { ...db.user, initials: (db.user.firstName[0] ?? '') + (db.user.lastName[0] ?? '') },
        orgs: [], activeOrg: null, portal: null, approved: false, navGroups: [], landing: 'onboarding', accessNote: 'Least-privilege by role · enforced server-side', capabilities: []
      };
    })
  ),
  http.post(
    `${W}/onboarding`,
    async ({ request }: { request: Request }) => {
      try {
        const db = getSellerDb();
        const { kind } = await body(request, StartBody);
        const approved = db.orgs.find((o) => o.kind === kind && o.status === 'APPROVED');
        if (approved) throw new SellerProblem(409, 'ALREADY_APPROVED', 'Already approved', `${approved.name} is already approved. Open ${kind === 'PROVIDER' ? 'Provider Hub' : 'Seller Center'} instead.`);
        const existing = db.orgs.find((o) => o.status !== 'APPROVED' && o.status !== 'REJECTED');
        if (existing && existing.kind !== kind) throw new SellerProblem(409, 'APPLICATION_EXISTS', 'Application in progress', `You already have a ${existing.kind.toLowerCase()} application; continue that one.`);
        if (existing) {
          db.activeOrgId = existing.id;
          return HttpResponse.json(onboardingStatus(db, existing) as JsonBodyType, { status: 200 });
        }
        const org: MockOrg = { id: 'org_new', kind, name: kind === 'PROVIDER' ? 'Your provider application' : 'Your seller application', status: 'DRAFT', role: kind === 'PROVIDER' ? 'provider.owner' : 'seller.owner' };
        db.orgs.push(org);
        db.activeOrgId = org.id;
        db.onboarding[org.id] = { form: accountForm(db, kind), stripe: { status: 'NOT_STARTED', detailsSubmitted: false, chargesEnabled: false, payoutsEnabled: false, identityStatus: null, taxIdStatus: null, currentlyDue: [] }, submittedAt: null, timeline: [] };
        return HttpResponse.json(onboardingStatus(db, org) as JsonBodyType, { status: 201 });
      } catch (e) {
        if (e instanceof SellerProblem) return problemResponse(e);
        throw e;
      }
    }
  ),
  http.put(
    `${W}/onboarding/business`,
    saveStep(BusinessBody, (app, b) => {
      if (b.storeName !== undefined && b.storeName.trim().length === 1) return { storeName: '2 to 80 characters' } as FieldErrors;
      if (b.brandColor && !/^#[0-9a-fA-F]{6}$/.test(b.brandColor)) return { brandColor: 'Use a colour like #1a7f4b' } as FieldErrors;
      if (b.description && b.description.length > 500) return { description: 'At most 500 characters' } as FieldErrors;
      const next = {
        ...app.form,
        business: { offering: b.offering ?? null, entityType: b.entityType ?? null, country: blankToNull(b.country)?.toUpperCase() ?? null, storeName: blankToNull(b.storeName),
          contactEmail: blankToNull(b.contactEmail), phone: blankToNull(b.phone), description: blankToNull(b.description), brandColor: blankToNull(b.brandColor)?.toLowerCase() ?? null, logoUrl: blankToNull(b.logoUrl) }
      };
      app.form = { ...next, owners: b.entityType === 'SOLE_PROPRIETOR' ? [] : next.owners, prefilled: app.form.prefilled.filter((k) => !k.startsWith('business.') ||
        JSON.stringify((app.form.business as Record<string, unknown>)[k.slice(9)] ?? null) === JSON.stringify((next.business as Record<string, unknown>)[k.slice(9)] ?? null)) };
      return null;
    })
  ),
  http.put(`${W}/onboarding/business-details`, saveStep(DetailsBody, (app, d) => saveDetails(app, d))),
  http.put(`${W}/onboarding/owners`, saveStep(OwnersBody, (app, o) => saveOwners(app, o.owners))),
  http.put(`${W}/onboarding/plan`, saveStep(OnboardingPlanBody, (app, p, org) => savePlan(app, org.kind, p))),
  http.put(`${W}/onboarding/catalog-compliance`, saveStep(ComplianceBody, (app, c) => saveCompliance(app, c))),
  http.get(
    `${W}/onboarding/plans`,
    plain((db) => onboardingPlans((db.orgs.find((o) => o.id === db.activeOrgId) ?? db.orgs[0])?.kind ?? 'SELLER'))
  ),
  http.post(
    `${W}/onboarding/stripe/session`,
    plain((db): OnboardingStripeSession => {
      const app = applicationOf(db, applicantOrg(db));
      if (app.stripe.status === 'NOT_STARTED') app.stripe = { ...app.stripe, status: 'IN_PROGRESS', identityStatus: 'unverified', taxIdStatus: 'pending' };
      // The mock BFF has no Stripe: like the seller service's local stand-in, the session is flagged localTest.
      return { accountId: 'acct_local_mock', clientSecret: 'local_test_session_mock', publishableKey: '', expiresAt: null, localTest: true };
    })
  ),
  http.post(
    `${W}/onboarding/stripe/refresh`,
    plain((db) => {
      const org = applicantOrg(db);
      const app = applicationOf(db, org);
      if (app.stripe.status === 'NOT_STARTED') throw new SellerProblem(409, 'CONFLICT', 'Conflict', 'Start Stripe verification first.');
      verifyStripe(app);
      return onboardingStatus(db, org);
    })
  ),
  http.post(
    `${W}/onboarding/submit`,
    plain((db) => {
      const org = applicantOrg(db);
      const errors = submit(db, org);
      if (errors) throw new SellerProblem(422, 'VALIDATION_FAILED', 'Some steps are incomplete', 'Some steps are incomplete or invalid; fix the highlighted fields and submit again.', errors);
      return onboardingStatus(db, org);
    })
  ),
  http.post(
    `${W}/onboarding/uploads`,
    plain(async (db, request) => {
      const u = await body(request, UploadBody);
      if (!['application/pdf', 'image/jpeg', 'image/png', 'image/webp'].includes(u.contentType)) throw invalid({ contentType: 'Use a PDF, JPG, PNG or WebP' });
      if (u.size > 15 * 1024 * 1024) throw invalid({ size: 'Documents must be 15 MB or smaller' });
      db.seq += 1;
      const assetId = `asset-doc-${db.seq}`;
      return { assetId, method: 'PUT', url: `/api/bff/mock-uploads/${assetId}`, headers: { 'Content-Type': u.contentType }, publicUrl: null, expiresAt: null };
    }, 201)
  ),
  http.put('*/mock-uploads/:assetId', () => new HttpResponse(null, { status: 200 })),
  http.post(
    `${W}/onboarding/uploads/:assetId/complete`,
    plain((_db, request) => {
      const assetId = new URL(request.url).pathname.split('/').at(-2) ?? '';
      return { id: assetId, url: '', width: null, height: null, bytes: 120_000, contentType: 'application/pdf', status: 'READY' };
    })
  ),
  /* Mock control for tests (not part of the contract): reseed the shared mock state; `applicant: true` leaves a
     customer who belongs to no organisation, ready for "Start selling". */
  http.post(
    `${W}/__mock/reset`,
    plain(async (_db, request) => {
      const { applicant } = await body(request, z.object({ applicant: z.boolean().optional() }));
      const db = resetSellerDb(new Date());
      if (applicant) db.orgs = [];
      return { orgs: db.orgs.length };
    })
  ),
  http.get(
    '*/v1/geo/address-suggestions',
    plain((_db, request) => {
      const p = new URL(request.url).searchParams;
      return mockAddressSuggestions(p.get('q') ?? '', p.get('sessionToken'));
    })
  ),
  http.get(
    '*/v1/geo/address-suggestions/:id',
    plain((_db, request) => {
      const id = decodeURIComponent(new URL(request.url).pathname.split('/').pop() ?? '');
      return mockResolveAddress(id) ?? { line1: '', line2: '', city: '', state: '', zip: '', country: 'US', lat: null, lon: null };
    })
  ),
  http.put(
    `${W}/session/active-org`,
    route(async (ctx) => {
      const { orgId } = await body(ctx.request, ActiveOrgBody);
      const org = ctx.db.orgs.find((o) => o.id === orgId);
      if (!org) throw forbidden('You’re not a member of that organisation.');
      ctx.db.activeOrgId = org.id;
      return session(context(ctx.request));
    })
  ),
  http.get(
    `${W}/onboarding`,
    route((ctx): OnboardingStatus => onboardingStatus(ctx.db, ctx.org))
  ),
  http.get(
    `${W}/compliance`,
    route((ctx) => {
      require(ctx, null, 'VIEW');
      if (ctx.org.kind === 'PROVIDER') {
        return { status: 'COMPLIANT', showBanner: false, tone: 'none', title: 'Account in good standing.', message: 'Nothing to do.', openItems: 0, deadline: null,
          payoutsPaused: false, chargesEnabled: true, payoutsEnabled: true, listingsHidden: false, payoutBank: { bankName: 'Chase', last4: '9921', status: 'verified' }, items: [] };
      }
      const deadline = new Date(ctx.now.getTime() + 14 * 86_400_000);
      return {
        status: 'ACTION_NEEDED', showBanner: true, tone: 'warning', title: 'We need a little more information.',
        message: `2 item(s) due ${deadline.toLocaleDateString('en-US', { month: 'short', day: 'numeric' })}. Open Account health to resolve.`, openItems: 2,
        deadline: deadline.toISOString(), payoutsPaused: false, chargesEnabled: true, payoutsEnabled: true, listingsHidden: false,
        payoutBank: { bankName: 'STRIPE TEST BANK', last4: '6789', status: 'errored' },
        items: [
          { requirement: 'company.tax_id', label: 'Company tax ID (EIN)', bucket: 'currently_due', component: 'account_onboarding' },
          { requirement: 'external_account', label: 'Payout bank account', bucket: 'past_due', component: 'account_management' }
        ]
      };
    })
  ),
  http.post(
    `${W}/stripe/session`,
    route((ctx): OnboardingStripeSession => {
      require(ctx, null, 'VIEW');
      // Account health (owner / finance). No Stripe in the mock BFF: the session is the labelled local stand-in.
      return { accountId: 'acct_local_mock', clientSecret: 'local_test_session_mock', publishableKey: '', expiresAt: null, localTest: true };
    })
  ),
  http.get(
    `${W}/dashboard`,
    route((ctx) => {
      const days = Number(query(ctx).get('rangeDays') ?? '30');
      if (![7, 30, 90].includes(days)) throw invalid({ rangeDays: 'Choose 7, 30 or 90 days' });
      require(ctx, 'home', 'VIEW');
      return dashboard(ctx, days);
    })
  ),

  /* ── Exports (the mock writes CSV text for every format) ── */
  http.get(
    `${W}/exports`,
    route((ctx) => {
      require(ctx, null, 'VIEW');
      return { items: ctx.db.exports.filter((j) => j.owner === ctx.db.user.id && j.orgId === ctx.org.id).map(exportView) };
    })
  ),
  http.post(
    `${W}/exports`,
    route(async (ctx) => {
      const b = await body(ctx.request, ExportBody);
      let key: string;
      let title: string;
      let file: { body: string; rows: number };
      if (b.dataset) {
        require(ctx, null, 'VIEW');
        const ds = b.dataset;
        if (ds.rows.some((r) => r.length !== ds.columns.length)) throw invalid({ 'dataset.rows': 'Each row needs one cell per column' });
        key = ds.table;
        title = ds.title ?? ds.table;
        file = exportFileOf(ds.columns, ds.rows);
      } else if (b.table) {
        const orders = b.table === 'orders';
        require(ctx, orders ? 'orders' : 'listings', 'VIEW', 'SELLER');
        const all = orders ? ORDER_COLUMNS : LISTING_COLUMNS;
        const unknown = (b.columns ?? []).find((k) => !all.some((c) => c.key === k));
        if (unknown) throw invalid({ columns: `Unknown column ${unknown}` });
        const cols = b.columns?.length ? all.filter((c) => b.columns?.includes(c.key)) : all;
        const sort = sortFor(all, b.sort ?? []);
        const rows = orders
          ? sellerOrderRows(ctx, b.status ?? null, b.q ?? null, sort, b.ids).map((r) => orderCells(r).cells as Record<string, string | number | null>)
          : sellerListingRows(ctx, b.status ?? null, b.q ?? null, sort, b.ids).map((r) => listingCells(r).cells as Record<string, string | number | null>);
        key = b.table;
        title = orders ? 'Orders' : 'Listings';
        file = exportFileOf(cols, rows.map((r) => cols.map((c) => (c.kind === 'money' && typeof r[c.key] === 'number' ? Number(r[c.key]) / 100 : (r[c.key] ?? null)))));
      } else {
        throw invalid({ table: 'Choose a table to export' });
      }
      const id = `exp-${ctx.db.seq++}`;
      const job = {
        id, resource: key, title, format: b.format, status: 'READY' as const, rows: file.rows, async: false,
        fileName: `${key}-${ctx.now.toISOString().slice(0, 10)}.${b.format}`, createdAt: ctx.now.toISOString(), finishedAt: ctx.now.toISOString(),
        expiresAt: new Date(ctx.now.getTime() + 86_400_000).toISOString(), downloadUrl: `/api/bff/v1/seller/exports/${id}/download`, error: null,
        owner: ctx.db.user.id, orgId: ctx.org.id, body: file.body
      };
      ctx.db.exports.unshift(job);
      return exportView(job);
    }, 201)
  ),
  http.get(
    `${W}/exports/:id`,
    route((ctx, p) => {
      const job = ctx.db.exports.find((j) => j.id === p.id && j.owner === ctx.db.user.id && j.orgId === ctx.org.id);
      if (!job) throw notFound();
      return exportView(job);
    })
  ),
  http.get(`${W}/exports/:id/download`, ({ request, params }) => {
    const ctx = context(request);
    const job = ctx.db.exports.find((j) => j.id === params.id && j.owner === ctx.db.user.id && j.orgId === ctx.org.id);
    if (!job) return problemResponse(notFound());
    return new HttpResponse(job.body, {
      headers: { 'Content-Type': 'text/csv; charset=utf-8', 'Content-Disposition': `attachment; filename="${job.fileName}"`, 'Cache-Control': 'no-store' }
    });
  }),

  /* ── Orders ── */
  http.get(
    `${W}/orders`,
    route((ctx) => {
      require(ctx, 'orders', 'VIEW', 'SELLER');
      const q = query(ctx);
      const status = q.get('status');
      const limit = Math.min(100, Math.max(1, Number(q.get('limit') ?? '20')));
      const start = Number(q.get('cursor') ?? '0') || 0;
      const sort = sortFor(ORDER_COLUMNS, q.getAll('sort'));
      const rows = sellerOrderRows(ctx, status, q.get('q'), sort);
      return {
        items: rows.slice(start, start + limit),
        nextCursor: start + limit < rows.length ? String(start + limit) : null,
        total: rows.length,
        sort: sort.map((k) => `${k.key},${k.desc ? 'desc' : 'asc'}`),
        partial: false
      };
    })
  ),
  http.get(
    `${W}/orders/:orderNumber`,
    route((ctx, p) => {
      require(ctx, 'orders', 'VIEW', 'SELLER');
      return findOrder(ctx.db, p.orderNumber ?? '');
    })
  ),
  http.post(
    `${W}/orders/:orderNumber/shipments/:shipmentId/status`,
    route(async (ctx, p) => {
      const { status } = await body(ctx.request, StatusBody);
      if (!SellerStatusActionSchema.safeParse(status).success) throw invalid({ status: 'Sellers can confirm, pack and mark ready only' });
      require(ctx, 'orders', 'EDIT', 'SELLER');
      const o = findOrder(ctx.db, p.orderNumber ?? '');
      const s = refreshShipment(o, p.shipmentId ?? '');
      if (s.fulfilment === 'AFROTRANSACT') throw forbidden('AfroTransact handles every step of this shipment.');
      if (NEXT[s.status] !== status && !(status === 'PICKED_UP' && s.status === SHIPPED_FROM && s.trackingNumber)) throw conflict(`This order is ${s.statusLabel.toLowerCase()}; it can’t move to ${SHIPMENT_LABELS[status as ShipmentStatus].toLowerCase()}.`);
      s.status = status as ShipmentStatus;
      s.timeline.push({ status, at: ctx.now.toISOString(), actor: 'seller' });
      refreshShipment(o, s.shipmentId);
      return o;
    })
  ),
  http.get(`${W}/orders/:orderNumber/shipments/:shipmentId/label`, () =>
    new HttpResponse('%PDF-1.4\n%mock label\n', { status: 200, headers: { 'Content-Type': 'application/pdf', 'Content-Disposition': 'attachment; filename="label-MOCK.pdf"' } })
  ),
  http.post(
    `${W}/orders/:orderNumber/shipments/:shipmentId/rates`,
    route(async (ctx, p) => {
      const { carrier, parcel } = await body(ctx.request, RatesBody);
      require(ctx, 'orders', 'EDIT', 'SELLER');
      const s = refreshShipment(findOrder(ctx.db, p.orderNumber ?? ''), p.shipmentId ?? '');
      if (!s.canBuyLabel) throw conflict('Labels can be bought for confirmed seller-shipped orders without tracking.');
      const w = Math.ceil(parcel.weightLb);
      const day = (n: number) => new Date(ctx.now.getTime() + n * 86_400_000).toISOString();
      const allQuotes = [
        { carrier: 'usps' as const, service: 'USPS_GROUND_ADVANTAGE', serviceName: 'USPS Ground Advantage', amountMinor: 545 + w * 85, currency: 'USD', transitDays: 4, estimatedDelivery: day(4) },
        { carrier: 'usps' as const, service: 'PRIORITY_MAIL', serviceName: 'USPS Priority Mail', amountMinor: 895 + w * 120, currency: 'USD', transitDays: 2, estimatedDelivery: day(2) },
        { carrier: 'usps' as const, service: 'PRIORITY_MAIL_EXPRESS', serviceName: 'USPS Priority Mail Express', amountMinor: 2895 + w * 210, currency: 'USD', transitDays: 1, estimatedDelivery: day(1) },
        { carrier: 'ups' as const, service: '03', serviceName: 'UPS Ground', amountMinor: 1095 + w * 110, currency: 'USD', transitDays: 4, estimatedDelivery: day(4) },
        { carrier: 'ups' as const, service: '02', serviceName: 'UPS 2nd Day Air', amountMinor: 2195 + w * 180, currency: 'USD', transitDays: 2, estimatedDelivery: day(2) },
        { carrier: 'ups' as const, service: '01', serviceName: 'UPS Next Day Air', amountMinor: 3895 + w * 260, currency: 'USD', transitDays: 1, estimatedDelivery: day(1) },
        { carrier: 'fedex' as const, service: 'FEDEX_GROUND', serviceName: 'FedEx Ground', amountMinor: 1045 + w * 105, currency: 'USD', transitDays: 4, estimatedDelivery: day(4) },
        { carrier: 'fedex' as const, service: 'FEDEX_2_DAY', serviceName: 'FedEx 2Day', amountMinor: 2095 + w * 175, currency: 'USD', transitDays: 2, estimatedDelivery: day(2) },
        { carrier: 'fedex' as const, service: 'PRIORITY_OVERNIGHT', serviceName: 'FedEx Priority Overnight', amountMinor: 4095 + w * 270, currency: 'USD', transitDays: 1, estimatedDelivery: day(1) },
        { carrier: 'dhl' as const, service: 'P', serviceName: 'DHL Express Worldwide', amountMinor: 3495 + w * 300, currency: 'USD', transitDays: 3, estimatedDelivery: day(3) }
      ];
      const quotes = carrier ? allQuotes.filter((q) => q.carrier === carrier) : allQuotes;
      return {
        partial: false,
        quotes: quotes.sort((a, b) => a.amountMinor - b.amountMinor)
      };
    })
  ),
  http.post(
    `${W}/orders/:orderNumber/shipments/:shipmentId/labels`,
    route(async (ctx, p) => {
      const key = ctx.request.headers.get('idempotency-key') ?? '';
      const b = await body(ctx.request, LabelBody);
      if (key.length < 8) throw invalid({ 'Idempotency-Key': 'Send an 8–128 character Idempotency-Key header' });
      require(ctx, 'orders', 'EDIT', 'SELLER');
      const o = findOrder(ctx.db, p.orderNumber ?? '');
      const s = refreshShipment(o, p.shipmentId ?? '');
      const replay = ctx.db.labels[key];
      if (!replay && !s.canBuyLabel) throw conflict('Labels can be bought for confirmed seller-shipped orders without tracking.');
      const tracking = replay ?? `9400 1118 9922 ${String(Date.now()).slice(-10)}`;
      ctx.db.labels[key] = tracking;
      s.carrier = b.carrier;
      s.trackingNumber = tracking;
      refreshShipment(o, s.shipmentId);
      const cost = b.service === 'USPS_GROUND_ADVANTAGE' ? 845 : 1189;
      return { carrier: b.carrier, service: b.service, trackingNumber: tracking, costMinor: cost, currency: 'USD', documentUrl: `https://labels.afrotransact.dev/${s.shipmentId}.pdf`, order: o };
    }, 201)
  ),

  /* ── Listings & builder ── */
  http.get(
    `${W}/listings`,
    route((ctx) => {
      require(ctx, 'listings', 'VIEW', 'SELLER');
      const q = query(ctx);
      const status = q.get('status');
      const page = Number(q.get('page') ?? '0');
      const size = Number(q.get('size') ?? '25');
      if (size < 1 || size > 100 || page < 0) throw invalid({ size: 'Between 1 and 100' });
      const sort = sortFor(LISTING_COLUMNS, q.getAll('sort'));
      const rows = sellerListingRows(ctx, status, q.get('q'), sort);
      return { items: rows.slice(page * size, page * size + size), total: rows.length, page, size, sort: sort.map((k) => `${k.key},${k.desc ? 'desc' : 'asc'}`), partial: false };
    })
  ),
  http.post(
    `${W}/listings/:productId/status`,
    route(async (ctx, p) => {
      const { active } = await body(ctx.request, ActiveBody);
      require(ctx, 'listings', 'EDIT', 'SELLER');
      const l = ctx.db.listings.find((x) => x.productId === p.productId);
      if (!l) throw notFound();
      if (l.status !== 'LIVE' && l.status !== 'PAUSED') throw conflict('Only live or paused listings can be switched.');
      l.status = active ? 'LIVE' : 'PAUSED';
      l.statusLabel = active ? 'Live' : 'Paused';
      const { variants: _v, ...row } = l;
      return row;
    })
  ),
  http.get(
    `${W}/listings/:productId`,
    route((ctx, p) => {
      require(ctx, 'listings', 'VIEW', 'SELLER');
      const l = ctx.db.listings.find((x) => x.productId === p.productId);
      if (!l) throw notFound('No such listing.');
      return productDetail(ctx.db, l);
    })
  ),
  http.post(
    `${W}/products`,
    route(async (ctx) => {
      const d = await body(ctx.request, ProductDraftSchema);
      if (!d.categoryId === !d.categoryRequestId) throw invalid({ categoryId: 'Choose a category, or request a new one.' });
      const skus = d.variants.map((v) => v.sellerSku.toUpperCase());
      if (new Set(skus).size !== skus.length) throw invalid({ variants: 'SKUs must be unique.' });
      const problems = listingProblems(d);
      if (Object.keys(problems).length) throw invalid(problems);
      require(ctx, 'listings', 'EDIT', 'SELLER');
      const taken = skus.find((s) => ctx.db.listings.some((l) => l.variants.some((v) => v.sku.toUpperCase() === s)));
      if (taken) throw invalid({ variants: `You already have a listing with SKU ${taken}.` });
      const status = d.categoryRequestId ? ('PENDING_CATEGORY_APPROVAL' as const) : ('IN_REVIEW' as const);
      const leaf = categoryLeaves().find((c) => c.id === d.categoryId);
      const row: ListingRow = {
        productId: nextId(ctx.db, 'prod'), atin: null, title: d.title, sku: skus[0] ?? '', variantCount: d.variants.length,
        priceMinor: Math.min(...d.variants.map((v) => v.priceMinor)), currency: 'USD', stock: d.variants.reduce((a, v) => a + v.quantity, 0),
        fulfilment: d.listing?.fulfilment === 'AFROTRANSACT' ? 'AFROTRANSACT' : 'SELLER',
        status, statusLabel: status === 'IN_REVIEW' ? 'In review' : 'Waiting for category approval', moderationNote: null, imageUrl: d.images[0]?.url ?? null,
        updatedAt: ctx.now.toISOString()
      };
      ctx.db.listings.unshift({ ...row, variants: d.variants.map((v) => ({ sku: v.sellerSku.toUpperCase(), quantity: v.quantity })) });
      ctx.db.productDetails[row.productId] = detailOf(row.productId, d, row, 1, leaf?.path ?? 'Requested category', leaf?.productType ?? 'custom');
      if (d.draftId) ctx.db.drafts = ctx.db.drafts.filter((x) => x.id !== d.draftId);
      return row;
    }, 201)
  ),
  http.put(
    `${W}/products/:productId`,
    route(async (ctx, p) => {
      const d = await body(ctx.request, ProductDraftSchema.extend({ version: z.number().int().optional() }));
      const skus = d.variants.map((v) => v.sellerSku.toUpperCase());
      if (new Set(skus).size !== skus.length) throw invalid({ variants: 'SKUs must be unique.' });
      const problems = listingProblems(d);
      if (Object.keys(problems).length) throw invalid(problems);
      require(ctx, 'listings', 'EDIT', 'SELLER');
      const l = ctx.db.listings.find((x) => x.productId === p.productId);
      if (!l) throw notFound('No such listing.');
      if (l.status === 'TAKEN_DOWN') throw conflict('This listing was taken down and can’t be edited.');
      const current = productDetail(ctx.db, l);
      if (d.version !== undefined && d.version !== current.version) throw conflict('This listing changed since you opened it. Reload to see the latest version.');
      const live = l.status === 'LIVE' || l.status === 'PAUSED';
      if (live && current.variants.some((v) => !skus.includes(v.sellerSku.toUpperCase()))) throw invalid({ variants: 'A live listing keeps its variants; set the stock to 0 instead of removing one.' });
      if (live && d.categoryId && d.categoryId !== current.categoryId) throw invalid({ category: 'A live listing can’t move to another category.' });
      if (l.status === 'REJECTED') Object.assign(l, { status: 'IN_REVIEW', statusLabel: 'In review', moderationNote: null });
      Object.assign(l, {
        title: d.title, sku: skus[0] ?? l.sku, variantCount: d.variants.length, priceMinor: Math.min(...d.variants.map((v) => v.priceMinor)),
        stock: d.variants.reduce((a, v) => a + v.quantity, 0), imageUrl: d.images[0]?.url ?? l.imageUrl, updatedAt: ctx.now.toISOString(),
        fulfilment: d.listing?.fulfilment ?? l.fulfilment,
        variants: d.variants.map((v) => ({ sku: v.sellerSku.toUpperCase(), quantity: v.quantity }))
      });
      const { variants: _v, ...row } = l;
      ctx.db.productDetails[l.productId] = { ...detailOf(l.productId, { ...d, categoryId: d.categoryId ?? current.categoryId ?? undefined }, row, current.version + 1, current.categoryPath, current.productType),
        variants: d.variants.map((v, i) => ({ ...(detailOf(l.productId, d, row, 0, '', '').variants[i] as SellerProductDetail['variants'][number]),
          atin: current.variants.find((c) => c.sellerSku.toUpperCase() === v.sellerSku.toUpperCase())?.atin ?? '' })) };
      if (d.draftId) ctx.db.drafts = ctx.db.drafts.filter((x) => x.id !== d.draftId);
      return row;
    })
  ),
  http.get(
    `${W}/product-drafts`,
    route((ctx) => {
      require(ctx, 'listings', 'EDIT', 'SELLER');
      return { items: ctx.db.drafts.filter((x) => x.orgId === ctx.org.id).map(({ orgId: _o, payload: _p, ...s }) => s) };
    })
  ),
  http.post(
    `${W}/product-drafts`,
    route(async (ctx) => {
      const b = await body(ctx.request, DraftBody);
      require(ctx, 'listings', 'EDIT', 'SELLER');
      const existing = b.productId ? ctx.db.drafts.find((x) => x.orgId === ctx.org.id && x.productId === b.productId) : undefined;
      if (existing) {
        Object.assign(existing, { title: b.title ?? existing.title, categoryPath: b.categoryPath ?? existing.categoryPath, payload: b.payload, version: existing.version + 1, updatedAt: ctx.now.toISOString() });
        return draftView(existing);
      }
      const d = { id: nextId(ctx.db, 'draft'), orgId: ctx.org.id, productId: b.productId ?? null, title: b.title ?? '', categoryPath: b.categoryPath ?? null,
        payload: b.payload, version: 0, updatedAt: ctx.now.toISOString() };
      ctx.db.drafts.unshift(d);
      return draftView(d);
    }, 201)
  ),
  http.get(
    `${W}/product-drafts/:id`,
    route((ctx, p) => {
      require(ctx, 'listings', 'EDIT', 'SELLER');
      const d = ctx.db.drafts.find((x) => x.id === p.id && x.orgId === ctx.org.id);
      if (!d) throw notFound('No such draft.');
      return draftView(d);
    })
  ),
  http.put(
    `${W}/product-drafts/:id`,
    route(async (ctx, p) => {
      const b = await body(ctx.request, DraftBody);
      if (b.version === undefined) throw invalid({ version: 'Required' });
      require(ctx, 'listings', 'EDIT', 'SELLER');
      const d = ctx.db.drafts.find((x) => x.id === p.id && x.orgId === ctx.org.id);
      if (!d) throw notFound('No such draft.');
      if (d.version !== b.version) throw conflict('This draft was saved somewhere else. Reload it to continue.');
      Object.assign(d, { title: b.title ?? d.title, categoryPath: b.categoryPath ?? d.categoryPath, payload: b.payload, version: d.version + 1, updatedAt: ctx.now.toISOString() });
      return draftView(d);
    })
  ),
  http.delete(
    `${W}/product-drafts/:id`,
    route((ctx, p) => {
      require(ctx, 'listings', 'EDIT', 'SELLER');
      ctx.db.drafts = ctx.db.drafts.filter((x) => !(x.id === p.id && x.orgId === ctx.org.id));
      return undefined;
    }, 204)
  ),
  http.get(
    `${W}/catalog/listing-context`,
    route((ctx) => {
      require(ctx, 'listings', 'VIEW', 'SELLER');
      const planOk = AI_PLANS.includes(ctx.db.subscription.SELLER.planCode);
      const roleOk = ctx.role === 'seller.owner' || ctx.role === 'seller.admin' || ctx.role === 'seller.catalog';
      return { aiListingWriter: { available: planOk && roleOk, reason: !roleOk ? 'ROLE' : !planOk ? 'PLAN' : null }, moderated: true, importLimits: IMPORT_LIMITS };
    })
  ),
  http.post(
    `${W}/catalog/listing-writer`,
    route(async (ctx) => {
      const b = await body(ctx.request, WriterBody);
      require(ctx, 'listings', 'EDIT', 'SELLER');
      if (!AI_PLANS.includes(ctx.db.subscription.SELLER.planCode)) throw new SellerProblem(403, 'PLAN_FEATURE_REQUIRED', 'Upgrade your plan', 'The AI listing writer is part of Seller Growth and Seller Pro.');
      const details = Object.entries(b.attributes ?? {}).filter(([k, v]) => v && k !== 'brand').map(([k, v]) => `${PRODUCT_TYPES[b.productType]?.attributes.find((a) => a.key === k)?.label ?? k}: ${v}`);
      return { title: b.title, description: `${b.title} — made for everyday cooking and gifting, packed with care and shipped quickly.${details.length ? ` ${details.slice(0, 3).join('. ')}.` : ''}`,
        bullets: details.slice(0, 5) };
    })
  ),

  /* ── Bulk upload (S308) ── */
  http.get(`${W}/imports/template`, async ({ request }) => {
    try {
      const ctx = context(request);
      require(ctx, 'listings', 'VIEW', 'SELLER');
      const q = query(ctx);
      const format = q.get('format') ?? 'xlsx';
      if (format !== 'xlsx' && format !== 'csv') throw invalid({ format: 'xlsx or csv' });
      const type = q.get('productType') ?? categoryLeaves().find((l) => l.id === q.get('categoryId'))?.productType ?? undefined;
      if (type && !PRODUCT_TYPES[type]) throw notFound('Unknown product type.');
      return fileResponse(templateCsv(type), `afrotransact-${type ?? 'products'}-template.${format}`, format);
    } catch (e) {
      if (e instanceof SellerProblem) return problemResponse(e);
      throw e;
    }
  }),
  http.get(
    `${W}/imports`,
    route((ctx) => {
      require(ctx, 'listings', 'VIEW', 'SELLER');
      const q = query(ctx);
      const page = Number(q.get('page') ?? '0');
      const size = Number(q.get('size') ?? '25');
      if (size < 1 || size > 100 || page < 0) throw invalid({ size: 'Between 1 and 100' });
      const mine = ctx.db.imports.filter((i) => i.orgId === ctx.org.id);
      return { items: mine.slice(page * size, page * size + size).map(importView), total: mine.length, page, size };
    })
  ),
  http.post(
    `${W}/imports`,
    route(async (ctx) => {
      let form: FormData;
      try {
        form = await ctx.request.formData();
      } catch {
        throw invalid({ file: 'Choose an Excel (.xlsx) or CSV (.csv) file' });
      }
      const file = form.get('file');
      const mode = String(form.get('mode') ?? 'CREATE').toUpperCase();
      if (mode !== 'CREATE' && mode !== 'UPDATE') throw invalid({ mode: 'CREATE or UPDATE' });
      // Duck-typed: test runners mix Blob implementations (jsdom's and Node's).
      if (typeof file === 'string' || file === null || file.size === 0) throw invalid({ file: 'Choose an Excel (.xlsx) or CSV (.csv) file' });
      const name = file.name || 'upload.csv';
      const ext = name.toLowerCase().split('.').pop();
      if (ext !== 'xlsx' && ext !== 'csv') throw invalid({ file: 'Upload an Excel (.xlsx) or CSV (.csv) file; save .xls workbooks as .xlsx' });
      if (file.size > IMPORT_LIMITS.maxFileBytes) throw invalid({ file: 'The file is larger than 10 MB; split it into several files' });
      require(ctx, 'listings', 'EDIT', 'SELLER');
      const sheet = ext === 'csv' ? parseCsv(await file.text()) : SAMPLE_UPLOAD;
      const existing = new Set(ctx.db.listings.flatMap((l) => l.variants.map((v) => v.sku.toUpperCase())));
      const v = validateSheet(sheet, mode, existing);
      if (v.fileProblem) throw invalid({ file: v.fileProblem });
      const id = nextId(ctx.db, 'imp');
      const imp: MockImport = {
        id, orgId: ctx.org.id, reference: `UPL-${String(1000 + ctx.db.seq)}`, fileName: name, format: ext === 'csv' ? 'CSV' : 'XLSX', mode,
        productType: String(form.get('productType') ?? '') || null, status: 'VALIDATED', validOnly: false, ...importSummary(v.rows),
        processedRows: 0, createdProducts: 0, updatedProducts: 0, failedRows: 0, header: v.header, fileWarnings: [],
        uploadedByName: `${ctx.db.user.firstName} ${ctx.db.user.lastName}`, createdAt: ctx.now.toISOString(), startedAt: null, finishedAt: null,
        reportUrl: `/api/bff/v1/seller/imports/${id}/report?format=xlsx`, rows: v.rows
      };
      ctx.db.imports.unshift(imp);
      return importView(imp);
    }, 201)
  ),
  http.get(
    `${W}/imports/:id`,
    route((ctx, p) => {
      require(ctx, 'listings', 'VIEW', 'SELLER');
      const imp = ctx.db.imports.find((i) => i.id === p.id && i.orgId === ctx.org.id);
      if (!imp) throw notFound('No such upload.');
      advanceImport(imp, ctx.now, (rows) => importedListing(ctx, imp, rows));
      return importView(imp);
    })
  ),
  http.get(
    `${W}/imports/:id/rows`,
    route((ctx, p) => {
      require(ctx, 'listings', 'VIEW', 'SELLER');
      const imp = ctx.db.imports.find((i) => i.id === p.id && i.orgId === ctx.org.id);
      if (!imp) throw notFound('No such upload.');
      const q = query(ctx);
      const status = q.get('status');
      const outcome = q.get('outcome');
      const page = Number(q.get('page') ?? '0');
      const size = Number(q.get('size') ?? '50');
      if (size < 1 || size > 200 || page < 0) throw invalid({ size: 'Between 1 and 200' });
      const rows = imp.rows.filter((r) => (!status || r.status === status) && (!outcome || r.outcome === outcome));
      return { items: rows.slice(page * size, page * size + size), total: rows.length, page, size };
    })
  ),
  http.get(`${W}/imports/:id/report`, ({ request, params }) => {
    try {
      const ctx = context(request);
      require(ctx, 'listings', 'VIEW', 'SELLER');
      const imp = ctx.db.imports.find((i) => i.id === params.id && i.orgId === ctx.org.id);
      if (!imp) throw notFound('No such upload.');
      const format = query(ctx).get('format') ?? 'xlsx';
      return fileResponse(reportCsv(imp), `${imp.fileName.replace(/\.[^.]+$/, '')}-report.${format === 'csv' ? 'csv' : 'xlsx'}`, format);
    } catch (e) {
      if (e instanceof SellerProblem) return problemResponse(e);
      throw e;
    }
  }),
  http.post(
    `${W}/imports/:id/start`,
    route(async (ctx, p) => {
      const b = await body(ctx.request, z.object({ validOnly: z.boolean().optional() }));
      require(ctx, 'listings', 'EDIT', 'SELLER');
      const imp = ctx.db.imports.find((i) => i.id === p.id && i.orgId === ctx.org.id);
      if (!imp) throw notFound('No such upload.');
      if (imp.status !== 'VALIDATED') return importView(imp);
      if (imp.errorRows > 0 && !b.validOnly) throw new SellerProblem(409, 'CONFLICT', 'The file has errors', `${imp.errorRows} rows have errors. Fix them and upload again, or import the valid rows only.`);
      if (imp.validRows + imp.warningRows === 0) throw new SellerProblem(409, 'CONFLICT', 'Nothing to import', 'No row of this file can be imported.');
      Object.assign(imp, { status: 'QUEUED', validOnly: b.validOnly ?? false, startedAt: ctx.now.toISOString() });
      return importView(imp);
    }, 202)
  ),
  http.get(`${W}/catalog/categories`, route(() => CATEGORY_TREE)),
  http.get(
    `${W}/catalog/product-types/:code`,
    route((_ctx, p) => {
      const t = PRODUCT_TYPES[p.code ?? ''];
      if (!t) throw notFound('Unknown product type.');
      return t;
    })
  ),
  http.get(
    `${W}/catalog/gtin/:code`,
    route((_ctx, p) => {
      const code = p.code ?? '';
      const valid = gtinValid(code);
      return { digits: code, valid, suggestion: null, existingAtin: valid && code === '4006381333931' ? 'ATCAT00042' : null,
        message: valid ? (code === '4006381333931' ? 'This barcode already has a product page — your listing joins it.' : 'Valid barcode') : 'The check digit doesn’t match. Check the number on the pack.' };
    })
  ),
  http.get(`${W}/category-requests`, route((ctx) => ctx.db.categoryRequests)),
  http.post(
    `${W}/category-requests`,
    route(async (ctx) => {
      const b = await body(ctx.request, CategoryRequestInputSchema);
      require(ctx, 'listings', 'EDIT', 'SELLER');
      const req = { id: nextId(ctx.db, 'cr'), reference: `REQ-${4820 + ctx.db.seq}`, path: `${b.parentPath} › ${b.name}`, status: 'PENDING_REVIEW' as const, decisionNote: null, createdAt: ctx.now.toISOString() };
      ctx.db.categoryRequests.unshift(req);
      return req;
    }, 201)
  ),
  http.get(
    `${W}/image-library`,
    route((ctx) => {
      require(ctx, 'listings', 'VIEW', 'SELLER');
      const q = query(ctx);
      const page = Number(q.get('page') ?? '0');
      const size = Number(q.get('size') ?? '50');
      const text = (q.get('q') ?? '').toLowerCase();
      const status = q.get('status');
      const matched = q.get('matched');
      const skus = new Set(ctx.db.listings.flatMap((l) => l.variants.map((v) => v.sku.toUpperCase())));
      const mine = MOCK_LIBRARY.filter((i) => i.orgId === ctx.org.id)
        .filter((i) => !text || i.fileName.toLowerCase().includes(text) || (i.sku ?? '').toLowerCase().includes(text))
        .filter((i) => !status || i.status === status)
        .filter((i) => matched === null || (matched === 'true') === (i.sku !== null));
      return {
        items: mine.slice(page * size, page * size + size).map(({ orgId: _org, ...i }) => ({ ...i, usedBy: [], skuKnown: i.sku !== null && skus.has(i.sku) })),
        total: mine.length, page, size
      };
    })
  ),
  http.delete(
    `${W}/image-library/:assetId`,
    route((ctx, p) => {
      require(ctx, 'listings', 'EDIT', 'SELLER');
      const at = MOCK_LIBRARY.findIndex((i) => i.assetId === p.assetId && i.orgId === ctx.org.id);
      if (at < 0) throw notFound('No such library image.');
      MOCK_LIBRARY.splice(at, 1);
      return undefined;
    }, 204)
  ),
  http.post(
    `${W}/image-library/attach`,
    route((ctx) => {
      require(ctx, 'listings', 'EDIT', 'SELLER');
      const skus = new Set(ctx.db.listings.flatMap((l) => l.variants.map((v) => v.sku.toUpperCase())));
      const found = MOCK_LIBRARY.filter((i) => i.orgId === ctx.org.id && i.status === 'READY' && i.sku !== null && skus.has(i.sku) && i.slot !== 'SWCH');
      const products = new Set(found.map((i) => i.sku));
      return { productsUpdated: products.size, imagesAttached: found.length, skipped: [] };
    })
  ),
  http.get(
    `${W}/catalog/brands`,
    route((ctx) => {
      require(ctx, 'listings', 'VIEW', 'SELLER');
      const q = (query(ctx).get('q') ?? '').toLowerCase();
      const counts = new Map<string, number>();
      for (const l of ctx.db.listings) {
        const brand = (l as { attributes?: Record<string, string> }).attributes?.brand;
        if (brand) counts.set(brand, (counts.get(brand) ?? 0) + 1);
      }
      for (const b of ['Ankara House', 'Mama Africa', 'Shea Gold', 'Lagos Kitchen']) if (!counts.has(b)) counts.set(b, 0);
      const brands = [...counts.entries()]
        .filter(([name]) => !q || name.toLowerCase().startsWith(q) || name.toLowerCase().includes(` ${q}`))
        .sort((x, y) => y[1] - x[1] || x[0].localeCompare(y[0]))
        .map(([name, products]) => ({ name, source: products > 0 ? 'OWN' : 'CATALOG', products }));
      return { brands };
    })
  ),
  http.post(
    `${W}/uploads`,
    route(async (ctx) => {
      const b = await body(ctx.request, UploadRequestSchema);
      const id = nextId(ctx.db, 'asset');
      if (b.library === true) MOCK_PENDING_LIBRARY.set(id, { orgId: ctx.org.id, fileName: b.fileName, bytes: b.size });
      return { assetId: id, method: 'PUT', url: `${new URL(ctx.request.url).origin}/api/bff/mock-uploads/${id}`, headers: { 'Content-Type': b.contentType },
        publicUrl: `https://img.afrotransact.dev/${id}.webp`, expiresAt: new Date(ctx.now.getTime() + 15 * 60_000).toISOString() };
    }, 201)
  ),
  http.put('*/mock-uploads/:id', () => new HttpResponse(null, { status: 200 })),
  http.post(
    `${W}/uploads/:assetId/complete`,
    route((ctx, p) => {
      const id = p.assetId ?? '';
      const pending = MOCK_PENDING_LIBRARY.get(id);
      if (pending) {
        MOCK_PENDING_LIBRARY.delete(id);
        const m = LIBRARY_NAME.exec(pending.fileName);
        MOCK_LIBRARY.unshift({ assetId: id, orgId: pending.orgId, fileName: pending.fileName, sku: m?.[1]?.toUpperCase() ?? null,
          slot: (m?.[2]?.toUpperCase() as MockLibraryImage['slot']) ?? null, status: 'READY', url: `https://img.afrotransact.dev/${id}.webp`,
          width: 1600, height: 1600, bytes: pending.bytes, flags: [], createdAt: ctx.now.toISOString() });
      }
      return { id, url: `https://img.afrotransact.dev/${id}.webp`, width: 1600, height: 1600, bytes: 182000, contentType: 'image/webp', status: 'READY' };
    })
  ),
  http.get(
    `${W}/inventory`,
    route((ctx) => {
      require(ctx, 'inventory', 'VIEW', 'SELLER');
      const low = query(ctx).get('lowStock') === 'true';
      const nodes = ['HOU1', 'ATL1', 'NJ1', 'DFW1'];
      const positions = ctx.db.listings
        .filter((l) => l.fulfilment === 'AFROTRANSACT')
        .flatMap((l, i) => l.variants.map((v, j) => {
          const onHand = v.quantity + 10;
          const reserved = (i + j) % 6;
          const available = Math.max(0, onHand - reserved);
          return { sku: v.sku, nodeId: nodes[(i + j) % nodes.length] ?? 'HOU1', onHand, reserved, available, inbound: (i * 13) % 50, lowStock: available <= 10 };
        }))
        .filter((p) => !low || p.lowStock);
      return {
        positions,
        totals: positions.reduce((t, p) => ({ available: t.available + p.available, reserved: t.reserved + p.reserved, inbound: t.inbound + p.inbound, lowStockSkus: t.lowStockSkus + (p.lowStock ? 1 : 0) }),
          { available: 0, reserved: 0, inbound: 0, lowStockSkus: 0 })
      };
    })
  ),
  http.post(
    `${W}/inventory/adjustments`,
    route(async (ctx) => {
      const b = await body(ctx.request, StockAdjustmentRequestSchema);
      require(ctx, 'inventory', 'EDIT', 'SELLER');
      const l = ctx.db.listings.find((x) => x.variants.some((v) => v.sku === b.sku));
      const v = l?.variants.find((x) => x.sku === b.sku);
      if (!l || !v) throw notFound('No listing uses that SKU.');
      v.quantity = b.quantity;
      l.stock = l.variants.reduce((a, x) => a + x.quantity, 0);
      return { sku: b.sku, quantity: b.quantity };
    })
  ),

  /* ── Money ── */
  http.get(
    `${W}/fees`,
    route((ctx): SellerFeesView => {
      require(ctx, 'fees', 'VIEW');
      return sellerFees(ctx.org.kind, ctx.now);
    })
  ),
  http.get(
    `${W}/tax/statement`,
    route((ctx) => {
      require(ctx, 'payouts', 'VIEW');
      const months = Array.from({ length: 24 }, (_, i) => new Date(Date.UTC(ctx.now.getUTCFullYear(), ctx.now.getUTCMonth() - i, 1)).toISOString().slice(0, 7));
      const month = query(ctx).get('month') || months[0] || '';
      if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(month)) throw new SellerProblem(422, 'VALIDATION_FAILED', 'Invalid month', 'Use the form 2026-09');
      const quiet = month !== months[0] && month !== months[1];
      const rows = quiet
        ? []
        : [
            { jurisdiction: 'US-TX', state: 'TX', orders: 3, grossSalesMinor: 30_000, taxableSalesMinor: 25_000, taxCollectedMinor: 2_063, taxRefundedMinor: 536, netTaxMinor: 1_527 },
            { jurisdiction: 'US-NY', state: 'NY', orders: 1, grossSalesMinor: 10_000, taxableSalesMinor: 10_000, taxCollectedMinor: 888, taxRefundedMinor: 0, netTaxMinor: 888 }
          ];
      const sum = (k: 'orders' | 'grossSalesMinor' | 'taxableSalesMinor' | 'taxCollectedMinor' | 'taxRefundedMinor' | 'netTaxMinor') => rows.reduce((a, r) => a + r[k], 0);
      return { month, currency: 'USD', rows, months, totals: { orders: sum('orders'), grossSalesMinor: sum('grossSalesMinor'), taxableSalesMinor: sum('taxableSalesMinor'), taxCollectedMinor: sum('taxCollectedMinor'), taxRefundedMinor: sum('taxRefundedMinor'), netTaxMinor: sum('netTaxMinor') } };
    })
  ),
  http.get(
    `${W}/payouts`,
    route((ctx) => {
      require(ctx, 'payouts', 'VIEW');
      return {
        balance: { currency: 'USD', availableMinor: ctx.db.availableMinor, onHoldMinor: 52725, requestedOrInTransitMinor: ctx.db.payouts.filter((p) => p.status === 'REQUESTED' || p.status === 'IN_TRANSIT').reduce((a, p) => a + p.amountMinor, 0),
          paidMinor: ctx.db.payouts.filter((p) => p.status === 'PAID').reduce((a, p) => a + p.amountMinor, 0), minimumPayoutMinor: 2500,
          providerFeesMinor: ctx.org.kind === 'PROVIDER' ? outstandingFees(ctx.db) : 0,
          canRequestToday: !paused(ctx) && !ctx.db.payouts.some((p) => p.requestedAt.slice(0, 10) === ctx.now.toISOString().slice(0, 10)),
          payoutsPaused: paused(ctx),
          payoutsPausedReason: paused(ctx) ? ('AUTO_PAST_DUE' as const) : null,
          payoutsPausedMessage: paused(ctx) ? 'Stripe needs overdue information. Payouts resume automatically once Account health is resolved.' : null,
          payoutAccountMissing: false,
          holds: [{ orderId: 'ord-400160', orderNumber: 'AT-2026-400160', amountMinor: 31225, releaseAt: new Date(ctx.now.getTime() + 3 * 86_400_000).toISOString().slice(0, 10) }, { orderId: 'ord-400197', orderNumber: 'AT-2026-400197', amountMinor: 21500, releaseAt: null }],
          planFeesMinor: ctx.db.planFeeDeductions.reduce((a, d) => a + d.outstandingMinor, 0) },
        payouts: ctx.db.payouts,
        historyAvailable: true,
        planFeeDeductions: ctx.org.kind === 'SELLER' ? ctx.db.planFeeDeductions : []
      };
    })
  ),
  http.post(
    `${W}/payouts`,
    route(async (ctx) => {
      const b = await body(ctx.request, PayoutBody);
      require(ctx, 'payouts', 'EDIT');
      if (paused(ctx)) throw invalid({ amountMinor: 'Payouts are paused until Account health is resolved.' });
      const amount = b.all ? ctx.db.availableMinor : (b.amountMinor ?? 0);
      if (amount < 2500) throw invalid({ amountMinor: 'The minimum payout is $25.00' });
      if (amount > ctx.db.availableMinor) throw invalid({ amountMinor: 'That’s more than is available' });
      if (ctx.db.payouts.some((p) => p.requestedAt.slice(0, 10) === ctx.now.toISOString().slice(0, 10))) throw conflict('You can request one payout a day.');
      ctx.db.availableMinor -= amount;
      const p = { id: nextId(ctx.db, 'po'), amountMinor: amount, currency: 'USD', status: 'REQUESTED' as const, requestedAt: ctx.now.toISOString(), holdReason: null, failureMessage: null };
      ctx.db.payouts.unshift(p);
      return p;
    }, 201)
  ),
  http.get(
    `${W}/billing`,
    route((ctx) => {
      require(ctx, 'billing', 'VIEW');
      const sub = ctx.db.subscription[ctx.org.kind];
      return { subscription: sub, plans: plansFor(ctx.org.kind, sub.planCode), paymentMethods: ctx.db.paymentMethods[ctx.org.kind], invoices: ctx.db.invoices,
        canManage: ctx.role.endsWith('.owner'), planFeeCollection: planFeeView(ctx) };
    })
  ),
  http.post(
    `${W}/billing/invoices/:invoiceId/retry`,
    route((ctx, p) => {
      if (!ctx.role.endsWith('.owner') && !ctx.role.endsWith('.finance')) throw forbidden('Only the owner or a finance member can retry an invoice.');
      const inv = ctx.db.invoices.find((i) => i.id === p.invoiceId);
      if (!inv) throw notFound('We couldn’t find that invoice.');
      if (!inv.canRetry) throw conflict('That invoice is not waiting for payment.');
      Object.assign(inv, { status: 'PAID', amountPaidMinor: inv.amountDueMinor, paidByCardMinor: inv.amountDueMinor, paidAt: ctx.now.toISOString(), lastError: null, nextAttemptAt: null, canRetry: false });
      return inv;
    })
  ),
  http.get(`${W}/billing/invoices/:invoiceId/document`, ({ params }) =>
    new HttpResponse(`<!doctype html><title>Invoice ${String(params.invoiceId)}</title><h1>Invoice</h1>`, { status: 200, headers: { 'Content-Type': 'text/html', 'Content-Disposition': 'attachment; filename="invoice.html"' } })
  ),
  http.put(
    `${W}/billing/plan-fee-collection`,
    route(async (ctx) => {
      const b = await body(ctx.request, PlanFeeBody);
      require(ctx, 'billing', 'VIEW');
      if (!ctx.role.endsWith('.owner') && !ctx.role.endsWith('.finance')) throw forbidden('Only the owner or a finance member can change how the plan is paid.');
      if (!ctx.db.planFeeCollectionAvailable) throw conflict('Paying plans from sales is switched off on this platform.');
      ctx.db.planFeeCollection[ctx.org.kind] = b.mode;
      return { ...planFeeView(ctx), source: 'SELLER' as const, changedAt: ctx.now.toISOString() };
    })
  ),
  http.post(
    `${W}/billing/plan`,
    route(async (ctx) => {
      const b = await body(ctx.request, PlanBody);
      require(ctx, 'billing', 'VIEW');
      if (!ctx.role.endsWith('.owner')) throw forbidden('Only the owner can change billing.');
      const plans = plansFor(ctx.org.kind, '');
      const plan = plans.find((p) => p.code === b.planCode);
      if (!plan) throw invalid({ planCode: 'Choose a plan' });
      const sub = ctx.db.subscription[ctx.org.kind];
      const cur = plans.find((p) => p.code === sub.planCode);
      const price = plan.prices.find((p) => p.cadence === b.cadence) ?? plan.prices[0];
      let applied: 'IMMEDIATE_UPGRADE' | 'AT_RENEWAL' | 'NO_CHANGE' = 'AT_RENEWAL';
      if (plan.code === sub.planCode && b.cadence === sub.cadence) applied = 'NO_CHANGE';
      else if (plan.monthlyMinor > (cur?.monthlyMinor ?? 0) && b.cadence === sub.cadence) {
        applied = 'IMMEDIATE_UPGRADE';
        Object.assign(sub, { planCode: plan.code, planName: plan.name, priceLabel: price?.perMonthLabel ?? '', billedLabel: price?.billedLabel ?? '', pendingChange: null });
      } else {
        sub.pendingChange = { planCode: plan.code, planName: plan.name, cadence: b.cadence, effectiveAt: sub.currentPeriodEnd ?? ctx.now.toISOString() };
      }
      return { applied, billing: { subscription: sub, plans: plansFor(ctx.org.kind, sub.planCode), paymentMethods: ctx.db.paymentMethods[ctx.org.kind], invoices: ctx.db.invoices,
        canManage: true, planFeeCollection: planFeeView(ctx) } };
    })
  ),
  http.get(
    `${W}/billing/rewards`,
    route((ctx) => {
      require(ctx, 'billing', 'VIEW');
      return ctx.db.rewards[ctx.org.kind];
    })
  ),
  http.get(
    `${W}/billing/plan/preview`,
    route((ctx) => {
      require(ctx, 'billing', 'VIEW');
      const q = query(ctx);
      const sub = ctx.db.subscription[ctx.org.kind];
      const plans = plansFor(ctx.org.kind, '');
      const plan = plans.find((p) => p.code === q.get('planCode'));
      if (!plan) throw invalid({ planCode: 'Choose a plan' });
      const cadence = CadenceSchema.catch(sub.cadence).parse(q.get('cadence') ?? sub.cadence);
      const cur = plans.find((p) => p.code === sub.planCode);
      const price = plan.prices.find((p) => p.cadence === cadence);
      const curPrice = cur?.prices.find((p) => p.cadence === sub.cadence);
      const trialing = sub.status === 'TRIALING';
      const same = plan.code === sub.planCode && cadence === sub.cadence;
      const upgrade = !same && plan.monthlyMinor > (cur?.monthlyMinor ?? 0) && cadence === sub.cadence;
      // Stripe prorates an upgrade for the rest of the period; the mock takes half the difference.
      const due = upgrade && !trialing ? Math.max(0, Math.round(((price?.totalMinor ?? 0) - (curPrice?.totalMinor ?? 0)) / 2)) : 0;
      return {
        change: same ? 'NO_CHANGE' : upgrade ? 'IMMEDIATE_UPGRADE' : 'AT_RENEWAL', planCode: plan.code, planName: plan.name, cadence,
        effectiveAt: upgrade ? ctx.now.toISOString() : (sub.currentPeriodEnd ?? null), amountDueNowMinor: due, nextChargeMinor: price?.totalMinor ?? 0,
        nextChargeAt: sub.currentPeriodEnd ?? null, trialing
      };
    })
  ),
  http.post(
    `${W}/billing/setup-intents`,
    route((ctx) => {
      require(ctx, 'billing', 'VIEW');
      if (!ctx.role.endsWith('.owner')) throw forbidden('Only the owner can change billing.');
      const id = nextId(ctx.db, 'seti');
      return { setupIntentId: id, clientSecret: `${id}_secret_local`, publishableKey: 'pk_test_local', localTest: true };
    }, 201)
  ),
  http.post(
    `${W}/billing/payment-methods`,
    route(async (ctx) => {
      const b = await body(ctx.request, AddPaymentMethodBody);
      require(ctx, 'billing', 'VIEW');
      if (!ctx.role.endsWith('.owner')) throw forbidden('Only the owner can change billing.');
      const list = ctx.db.paymentMethods[ctx.org.kind];
      const card = b.kind === 'CARD';
      const brand = b.brand?.toLowerCase() ?? null;
      const pm = {
        id: b.stripePaymentMethodId, kind: card ? ('card' as const) : ('bank' as const), brand, last4: b.last4, expMonth: b.expMonth ?? null, expYear: b.expYear ?? null,
        bankName: b.bankName ?? null, accountType: b.accountType ?? null, verified: true, receivesPayouts: false, isDefault: Boolean(b.makeDefault) || list.length === 0,
        label: card ? `${brand ? brand.charAt(0).toUpperCase() + brand.slice(1) : 'Card'} ending in ${b.last4}` : `${b.bankName ?? 'Bank'} ••${b.last4}`
      };
      if (pm.isDefault) list.forEach((m) => (m.isDefault = false));
      ctx.db.paymentMethods[ctx.org.kind] = [...list.filter((m) => m.id !== pm.id), pm];
      return pm;
    }, 201)
  ),
  http.post(
    `${W}/billing/payment-methods/:id/default`,
    route((ctx, p) => {
      require(ctx, 'billing', 'VIEW');
      if (!ctx.role.endsWith('.owner')) throw forbidden('Only the owner can change billing.');
      const list = ctx.db.paymentMethods[ctx.org.kind];
      if (!list.some((m) => m.id === p.id)) throw notFound();
      list.forEach((m) => (m.isDefault = m.id === p.id));
      return list;
    })
  ),
  http.delete(
    `${W}/billing/payment-methods/:id`,
    route((ctx, p) => {
      require(ctx, 'billing', 'VIEW');
      if (!ctx.role.endsWith('.owner')) throw forbidden('Only the owner can change billing.');
      const list = ctx.db.paymentMethods[ctx.org.kind];
      const m = list.find((x) => x.id === p.id);
      if (!m) throw notFound();
      if (m.isDefault) throw conflict('Make another method the default before removing this one.');
      ctx.db.paymentMethods[ctx.org.kind] = list.filter((x) => x.id !== p.id);
      return ctx.db.paymentMethods[ctx.org.kind];
    })
  ),

  /* ── Team (identity member directory + invitations, S236) ── */
  http.get(
    `${W}/team`,
    route((ctx) => {
      require(ctx, 'team', 'VIEW');
      return teamView(ctx);
    })
  ),
  http.post(
    `${W}/team/invitations`,
    route(async (ctx) => {
      const b = await body(ctx.request, InviteBody);
      require(ctx, 'team', 'EDIT');
      checkRole(ctx, b.roleKey);
      const email = b.email.trim().toLowerCase();
      const members = (ctx.db.team[ctx.org.id] ??= []);
      if (members.some((m) => m.email === email && m.status !== 'INVITED')) throw teamProblem(409, 'ALREADY_MEMBER');
      const open = members.findIndex((m) => m.email === email && m.status === 'INVITED');
      if (open >= 0) members.splice(open, 1);
      const invitationId = crypto.randomUUID();
      const m: TeamMember = { userId: `invite:${invitationId}`, name: email, email, roleKey: b.roleKey, roleLabel: roleLabel(b.roleKey), status: 'INVITED', isYou: false,
        invitationId, expiresAt: new Date(ctx.now.getTime() + 7 * 86_400_000).toISOString() };
      members.push(m);
      return m;
    }, 201)
  ),
  http.delete(
    `${W}/team/invitations/:invitationId`,
    route((ctx, p) => {
      require(ctx, 'team', 'EDIT');
      const members = ctx.db.team[ctx.org.id] ?? [];
      const i = members.findIndex((m) => m.invitationId === p.invitationId);
      if (i < 0) throw notFound('We couldn’t find that invitation.');
      members.splice(i, 1);
      return teamView(ctx);
    })
  ),
  http.put(
    `${W}/team/members/:userId`,
    route(async (ctx, p) => {
      const { roleKey } = await body(ctx.request, RoleBody);
      require(ctx, 'team', 'EDIT');
      checkRole(ctx, roleKey);
      const m = activeMember(ctx, p.userId);
      if (m.isYou) throw teamProblem(403, 'OWN_ROLE');
      if (m.roleKey.endsWith('.owner') && !roleKey.endsWith('.owner') && owners(ctx) === 1) throw teamProblem(422, 'LAST_OWNER');
      m.roleKey = roleKey;
      m.roleLabel = roleLabel(roleKey);
      return teamView(ctx);
    })
  ),
  http.delete(
    `${W}/team/members/:userId`,
    route((ctx, p) => {
      require(ctx, 'team', 'EDIT');
      const m = activeMember(ctx, p.userId);
      if (m.roleKey.endsWith('.owner') && !ctx.role.endsWith('.owner')) throw teamProblem(403, 'OWNER_ONLY');
      if (m.roleKey.endsWith('.owner') && owners(ctx) === 1) throw teamProblem(422, 'LAST_OWNER');
      const members = ctx.db.team[ctx.org.id] ?? [];
      members.splice(members.indexOf(m), 1);
      return teamView(ctx);
    })
  ),
  /* Accept page (public lookup; accept needs a session — the mock is always signed in). */
  http.post(
    `${W}/invitations/lookup`,
    route(async (ctx) => {
      const { token } = await body(ctx.request, TokenBody);
      const invite = MOCK_INVITES[token];
      if (!invite) throw notFound('This invitation link isn’t valid. Ask the organisation to send a new one.');
      return { orgName: 'Ade’s Kitchen', orgKind: 'SELLER', email: ctx.db.user.email, roleKey: 'seller.catalog', roleLabel: roleLabel('seller.catalog'),
        status: invite, expiresAt: new Date(ctx.now.getTime() + (invite === 'EXPIRED' ? -1 : 3) * 86_400_000).toISOString() };
    })
  ),
  http.post(
    `${W}/invitations/accept`,
    route(async (ctx) => {
      const { token } = await body(ctx.request, TokenBody);
      const invite = MOCK_INVITES[token];
      if (!invite) throw notFound('This invitation link isn’t valid.');
      if (invite === 'EXPIRED') throw teamProblem(422, 'INVITATION_EXPIRED');
      if (invite === 'REVOKED') throw teamProblem(422, 'INVITATION_REVOKED');
      const org = ctx.db.orgs.find((o) => o.id === SELLER_ORG);
      if (org) ctx.db.activeOrgId = org.id;
      return { orgId: SELLER_ORG, orgName: 'Ade’s Kitchen', orgKind: 'SELLER', roleKey: org?.role ?? 'seller.catalog', roleLabel: roleLabel(org?.role ?? 'seller.catalog'),
        alreadyMember: Boolean(org) };
    })
  ),

  /* ── Provider Hub ── */
  http.get(
    `${W}/bookings/calendar`,
    route((ctx) => {
      const q = query(ctx);
      const from = q.get('from') ?? '';
      const to = q.get('to') ?? '';
      if (!/^\d{4}-\d{2}-\d{2}$/.test(from) || !/^\d{4}-\d{2}-\d{2}$/.test(to) || to < from) throw invalid({ to: 'Choose a range of up to 42 days' });
      require(ctx, 'calendar', 'VIEW', 'PROVIDER');
      const start = new Date(`${from}T00:00:00`);
      const end = new Date(`${to}T23:59:59`);
      const bookings = ctx.db.bookings.filter((b) => new Date(b.startAt) >= start && new Date(b.startAt) <= end).map((b) => bookingCard(b, ctx.now));
      const days = [];
      for (let d = new Date(start); d <= end; d.setDate(d.getDate() + 1)) {
        const key = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
        const mine = bookings.filter((b) => {
          const s = new Date(b.startAt);
          return s.getFullYear() === d.getFullYear() && s.getMonth() === d.getMonth() && s.getDate() === d.getDate() && ['REQUESTED', 'CONFIRMED', 'RESCHEDULE_REQUESTED', 'RESCHEDULE_PROPOSED'].includes(b.status);
        });
        days.push({ date: key, appointments: mine.length, bookedMinutes: mine.reduce((a, b) => a + b.durationMinutes, 0), expectedRevenueMinor: mine.reduce((a, b) => a + b.priceMinor, 0),
          needsAction: mine.filter((b) => b.status === 'REQUESTED' || b.status === 'RESCHEDULE_REQUESTED').length });
      }
      return { timezone: ctx.db.availability.timezone, from, to, openHours: ctx.db.availability.weekly, bookings, days };
    })
  ),
  http.get(
    `${W}/bookings/requests`,
    route((ctx) => {
      require(ctx, 'requests', 'VIEW', 'PROVIDER');
      return requested(ctx.db).map((b) => ({ ...bookingCard(b, ctx.now), takeHome: bookingTakeHome(b) }));
    })
  ),
  http.post(
    `${W}/bookings/:bookingId/actions`,
    route(async (ctx, p) => {
      const b = await body(ctx.request, ActionBody);
      if ((b.action === 'DECLINE' || b.action === 'CANCEL') && !b.reason?.trim()) throw invalid({ reason: 'Tell the customer why' });
      if (b.action === 'PROPOSE' && (!b.proposedStartAt || new Date(b.proposedStartAt) <= ctx.now)) throw invalid({ proposedStartAt: 'Choose a future time' });
      require(ctx, 'requests', 'EDIT', 'PROVIDER');
      const bk = ctx.db.bookings.find((x) => x.id === p.bookingId);
      if (!bk) throw notFound('No such booking.');
      if (!allowedBookingActions(bk.status, bk.startAt, ctx.now).includes(b.action)) throw conflict(`This booking is ${BOOKING_LABELS[bk.status].toLowerCase()}.`);
      bk.status = ACTION_STATUS[b.action];
      if (b.action === 'PROPOSE') bk.proposedStartAt = b.proposedStartAt ?? null;
      if (b.action === 'CONFIRM') {
        bk.depositState = 'captured';
        bk.expiresAt = null;
      }
      if (b.action === 'DECLINE') bk.depositState = 'released';
      return bookingCard(bk, ctx.now);
    })
  ),
  http.get(`${W}/provider/hours`, route((ctx) => (require(ctx, 'hours', 'VIEW', 'PROVIDER'), ctx.db.availability))),
  http.put(
    `${W}/provider/hours`,
    route(async (ctx) => {
      const a = await body(ctx.request, AvailabilityFormSchema);
      require(ctx, 'hours', 'EDIT', 'PROVIDER');
      ctx.db.availability = a;
      return a;
    })
  ),
  http.get(`${W}/provider/services`, route((ctx) => (require(ctx, 'services', 'VIEW', 'PROVIDER'), ctx.db.services))),
  http.post(
    `${W}/provider/services`,
    route(async (ctx) => {
      const s = await body(ctx.request, ServiceInputSchema);
      require(ctx, 'services', 'EDIT', 'PROVIDER');
      const svc = { id: nextId(ctx.db, 'svc'), ...s, currency: 'USD', takeHome: takeHome(s.priceMinor) };
      ctx.db.services.push(svc);
      return svc;
    }, 201)
  ),
  http.patch(
    `${W}/provider/services/:serviceId`,
    route(async (ctx, p) => {
      const s = await body(ctx.request, ServiceInputSchema);
      require(ctx, 'services', 'EDIT', 'PROVIDER');
      const svc = ctx.db.services.find((x) => x.id === p.serviceId);
      if (!svc) throw notFound();
      Object.assign(svc, s, { takeHome: takeHome(s.priceMinor) });
      return svc;
    })
  ),
  http.get(`${W}/provider/cancellation-policy`, route((ctx) => (require(ctx, 'policy', 'VIEW', 'PROVIDER'), describePolicy(ctx.db.policy)))),
  http.put(
    `${W}/provider/cancellation-policy`,
    route(async (ctx) => {
      const input = await body(ctx.request, CancellationPolicyInputSchema);
      const full = presetPolicy(input.kind) ?? {
        kind: input.kind, freeHours: input.freeHours ?? 48, partialRefund: input.partialRefund ?? false, partialFromHours: input.partialFromHours ?? 0,
        partialPercent: input.partialPercent ?? 50, noShow: input.noShow ?? 'DEPOSIT', rescheduleHours: input.rescheduleHours ?? 24, maxReschedules: input.maxReschedules ?? 2
      };
      if (full.partialRefund && full.partialFromHours >= full.freeHours) throw invalid({ partialFromHours: 'Must be below the free-cancellation window' });
      require(ctx, 'policy', 'EDIT', 'PROVIDER');
      ctx.db.policy = full;
      return describePolicy(full);
    })
  ),
  http.get(
    `${W}/provider/earnings`,
    route((ctx) => {
      require(ctx, 'earnings', 'VIEW', 'PROVIDER');
      const rows = earningsRows(ctx.db, ctx.now);
      const totals = rows.reduce(
        (t, r) => ({ collectedMinor: t.collectedMinor + r.collectedMinor, commissionMinor: t.commissionMinor + r.commissionMinor, cardFeesMinor: t.cardFeesMinor + r.cardFeesMinor,
          lateCancelFeesMinor: t.lateCancelFeesMinor + r.lateCancelFeeMinor, netMinor: t.netMinor + r.netMinor, heldMinor: t.heldMinor }),
        { collectedMinor: 0, commissionMinor: 0, cardFeesMinor: 0, lateCancelFeesMinor: 0, netMinor: 0, heldMinor: ctx.db.bookings.filter((b) => b.depositState === 'held').reduce((a, b) => a + b.depositMinor, 0) }
      );
      const items = providerFees(ctx.db);
      return {
        currency: 'USD', commissionPercent: 8, payoutDay: 'Tuesday', totals, rows, connectionFee: mockConnectionFees(),
        providerFees: { available: true, outstandingMinor: outstandingFees(ctx.db), collectedMinor: items.filter((f) => f.status === 'COLLECTED').reduce((a, f) => a + f.amountMinor, 0), items }
      };
    })
  ),
  /* ── Business page settings ── */
  http.get(
    `${W}/business-page`,
    route((ctx) => {
      require(ctx, 'team', 'VIEW');
      return businessPage(ctx);
    })
  ),
  http.put(
    `${W}/business-page`,
    route(async (ctx) => {
      require(ctx, 'team', 'EDIT');
      const b = await body(ctx.request, BusinessPageBody);
      const errors: Record<string, string> = {};
      if (b.description.trim().length > 500) errors.description = 'At most 500 characters';
      if (b.brandColor.trim() && !/^#[0-9a-fA-F]{6}$/.test(b.brandColor.trim())) errors.brandColor = 'Use a colour like #1a7f4b';
      if (b.logoUrl.trim() && !/^https:\/\/\S+$/.test(b.logoUrl.trim())) errors.logoUrl = 'Upload your logo again';
      if (Object.keys(errors).length) throw invalid(errors);
      ctx.db.businessPage[ctx.org.id] = { description: b.description.trim(), brandColor: b.brandColor.trim().toLowerCase(), logoUrl: b.logoUrl.trim() };
      return businessPage(ctx);
    })
  ),
  /* ── Delivery run pick lists (S264) ── */
  http.get(
    `${W}/run-pickups`,
    route((ctx) => {
      require(ctx, 'orders', 'VIEW', 'SELLER');
      return { items: ctx.db.runPickups[ctx.org.id] ?? [] };
    })
  ),
  http.get(
    `${W}/run-pickups/:runId`,
    route((ctx, p) => {
      require(ctx, 'orders', 'VIEW', 'SELLER');
      const found = (ctx.db.runPickups[ctx.org.id] ?? []).find((r) => r.runId === p.runId);
      if (!found) throw notFound('No such run.');
      return found;
    })
  ),
  /* ── Ship-from addresses ── */
  http.get(
    `${W}/ship-from-addresses`,
    route((ctx) => {
      require(ctx, 'orders', 'VIEW', 'SELLER');
      return { items: shipFromList(ctx) };
    })
  ),
  http.post(
    `${W}/ship-from-addresses`,
    route(async (ctx) => {
      require(ctx, 'orders', 'EDIT', 'SELLER');
      return saveShipFrom(ctx, await body(ctx.request, ShipFromBody));
    }, 201)
  ),
  http.post(
    `${W}/ship-from-addresses/:id/default`,
    route((ctx, p) => {
      require(ctx, 'orders', 'EDIT', 'SELLER');
      const target = findShipFrom(ctx, p.id);
      shipFromList(ctx).forEach((a) => {
        if (a.isDefault !== (a === target)) {
          a.isDefault = a === target;
          a.version += 1;
        }
      });
      return { items: shipFromList(ctx) };
    })
  ),
  http.put(
    `${W}/ship-from-addresses/:id`,
    route(async (ctx, p) => {
      require(ctx, 'orders', 'EDIT', 'SELLER');
      return saveShipFrom(ctx, await body(ctx.request, ShipFromBody), findShipFrom(ctx, p.id));
    })
  ),
  http.delete(
    `${W}/ship-from-addresses/:id`,
    route((ctx, p) => {
      require(ctx, 'orders', 'EDIT', 'SELLER');
      const list = shipFromList(ctx);
      const target = findShipFrom(ctx, p.id);
      list.splice(list.indexOf(target), 1);
      const first = list[0];
      if (target.isDefault && first) first.isDefault = true;
      return { items: list };
    })
  ),
  http.put(
    `${W}/ship-from-addresses/:id/customer-pickup`,
    route(async (ctx, p) => {
      const b = await body(ctx.request, CustomerPickupBody);
      const hourErrors = validatePickupHours(b.enabled, b.hours);
      if (Object.keys(hourErrors).length)
        throw new SellerProblem(422, 'PICKUP_INVALID_HOURS', 'Check the pickup hours', 'Add 1 to 14 pickup windows that don’t overlap, each opening before it closes.', hourErrors);
      require(ctx, 'orders', 'EDIT', 'SELLER');
      const a = findShipFrom(ctx, p.id);
      a.customerPickup = { enabled: b.enabled, hours: b.hours, readyHours: b.readyHours ?? 0 };
      return a;
    })
  ),
  http.put(
    `${W}/ship-from-addresses/:id/afrotransact-pickup`,
    route(async (ctx, p) => {
      const b = await body(ctx.request, PickupBody);
      const hourErrors = validatePickupHours(b.enabled, b.hours);
      if (Object.keys(hourErrors).length)
        throw new SellerProblem(422, 'PICKUP_INVALID_HOURS', 'Check the pickup hours', 'Add 1 to 14 pickup windows that don’t overlap, each opening before it closes.', hourErrors);
      require(ctx, 'orders', 'EDIT', 'SELLER');
      const a = findShipFrom(ctx, p.id);
      if (b.version != null && b.version !== a.version) throw conflict('Someone else changed this address. Reload and try again.');
      if (b.enabled) {
        if (a.position === null || a.position.precision !== 'ADDRESS')
          throw new SellerProblem(422, 'PICKUP_NOT_GEOCODED', 'We couldn’t locate this address', 'Drivers need a street-level location, and we could only place this address by its ZIP code. Check the street address and save it again.', { enabled: 'Not located at street level' });
        if (!a.postalCode.startsWith('77'))
          throw new SellerProblem(422, 'PICKUP_OUTSIDE_DELIVERY_AREA', 'Outside the delivery area', `${a.city} is more than 25 miles from the Houston Metro delivery zone.`, { enabled: 'Outside the delivery area' });
      }
      a.afrotransactPickup = {
        enabled: b.enabled,
        hours: b.hours,
        zoneName: b.enabled ? 'Houston Metro' : a.afrotransactPickup.zoneName,
        enabledAt: b.enabled ? (a.afrotransactPickup.enabledAt ?? ctx.now.toISOString()) : null
      };
      a.version += 1;
      return a;
    })
  ),
  /* ── Offers: the fulfilment choice (S255) ── */
  http.get(
    `${W}/offers`,
    route((ctx) => {
      const productId = (query(ctx).get('productId') ?? '').trim();
      if (!productId) throw invalid({ productId: 'Name the listing' });
      require(ctx, 'listings', 'VIEW', 'SELLER');
      return { items: ctx.db.offers.filter((o) => o.productId === productId && o.status !== 'WITHDRAWN') };
    })
  ),
  http.put(
    `${W}/offers/:offerId/fulfilment`,
    route(async (ctx, p) => {
      const b = await body(ctx.request, OfferFulfilmentBody);
      if (b.fulfilment === 'AFROTRANSACT_DELIVERY' && !b.shipFromLocationId)
        throw invalid({ shipFromLocationId: 'Choose the pickup location (a ship-from address with AfroTransact pickup on)' });
      require(ctx, 'listings', 'EDIT', 'SELLER');
      const o = ctx.db.offers.find((x) => x.offerId === p.offerId);
      if (!o) throw notFound('No such offer.');
      let shipsFrom = o.shipsFrom;
      if (b.fulfilment === 'AFROTRANSACT_DELIVERY') {
        const loc = shipFromList(ctx).find((a) => a.id === b.shipFromLocationId);
        if (!loc || !loc.afrotransactPickup.enabled) throw invalid({ shipFromLocationId: 'Choose a ship-from address with AfroTransact pickup turned on' });
        shipsFrom = `${loc.city}, ${loc.state}`;
      }
      o.fulfilment = b.fulfilment;
      o.shipFromLocationId = b.fulfilment === 'AFROTRANSACT_DELIVERY' ? (b.shipFromLocationId ?? null) : null;
      o.shipsFrom = b.fulfilment === 'AFROTRANSACT' ? null : shipsFrom ?? 'Houston, TX';
      o.quantity = b.fulfilment === 'AFROTRANSACT' ? 0 : o.availableQty;
      o.version += 1;
      const listing = ctx.db.listings.find((l) => l.productId === o.productId);
      const activeOffers = ctx.db.offers.filter((x) => x.productId === o.productId && x.status !== 'WITHDRAWN');
      if (listing && activeOffers.length > 0 && activeOffers.every((x) => x.fulfilment === o.fulfilment)) {
        listing.fulfilment = o.fulfilment;
      }
      const detail = ctx.db.productDetails[o.productId];
      if (detail?.listing) {
        // The product form knows two kinds; AfroTransact Delivery collects from the seller's store, so it is "SELLER" there.
        detail.listing.fulfilment = o.fulfilment === 'AFROTRANSACT' ? 'AFROTRANSACT' : 'SELLER';
      }
      return o;
    })
  ),
  http.get(`${W}/returns`, route((ctx) => {
    require(ctx, 'returns', 'VIEW', 'SELLER');
    const status = new URL(ctx.request.url).searchParams.get('status');
    const items = ctx.db.returns.filter((r) => !status || r.status === status);
    return { items, total: items.length };
  })),
  http.get(`${W}/disputes`, route((ctx) => {
    require(ctx, 'disputes', 'VIEW', 'SELLER');
    const status = new URL(ctx.request.url).searchParams.get('status');
    const items = ctx.db.disputes.filter((d) => !status || d.status === status);
    return { items, total: items.length };
  })),
  http.get(`${W}/coupons`, route((ctx) => {
    require(ctx, 'coupons', 'VIEW', 'SELLER');
    return { items: ctx.db.coupons.filter((c) => c.funding === 'SELLER') };
  })),
  http.post(`${W}/coupons`, route(async (ctx) => {
    require(ctx, 'coupons', 'EDIT', 'SELLER');
    const b = CreateSellerCouponSchema.parse(await ctx.request.json());
    const row: SellerCoupon = {
      id: `cp-${ctx.db.seq++}`,
      code: b.code.toUpperCase(),
      description: b.description ?? '',
      type: b.type,
      value: b.value,
      currency: b.currency ?? 'USD',
      scope: b.scope,
      scopeRef: b.scopeRef,
      minSpendMinor: b.minSpendMinor ?? 0,
      status: 'ACTIVE',
      funding: 'SELLER',
      editable: true,
      version: 1
    };
    ctx.db.coupons.unshift(row);
    return HttpResponse.json(row, { status: 201 });
  })),
  http.post(`${W}/coupons/:couponId/status`, route(async (ctx, p) => {
    require(ctx, 'coupons', 'EDIT', 'SELLER');
    const status = z.object({ status: z.enum(['ACTIVE', 'PAUSED', 'EXPIRED']) }).parse(await ctx.request.json()).status;
    const row = ctx.db.coupons.find((c) => c.id === p.couponId);
    if (!row || !row.editable) throw notFound('No such coupon.');
    row.status = status;
    row.version += 1;
    return row;
  })),
  http.delete(`${W}/coupons/:couponId`, route((ctx, p) => {
    require(ctx, 'coupons', 'EDIT', 'SELLER');
    const idx = ctx.db.coupons.findIndex((c) => c.id === p.couponId);
    if (idx === -1) throw notFound('No such coupon.');
    ctx.db.coupons.splice(idx, 1);
    return new HttpResponse(null, { status: 204 });
  })),
  /* Reviews (S324): read, one public reply, report. There is deliberately no route to edit, hide or delete a review. */
  http.get(`${W}/reviews`, route((ctx) => {
    require(ctx, 'reviews', 'VIEW', 'SELLER');
    const q = query(ctx);
    const page = Math.max(0, Number(q.get('page') ?? 0) || 0);
    const size = Math.min(100, Math.max(1, Number(q.get('size') ?? 20) || 20));
    const all = ctx.db.reviews.filter((r) => q.get('unanswered') !== 'true' || r.reply === null).sort((a, b) => b.createdAt.localeCompare(a.createdAt));
    return { items: all.slice(page * size, page * size + size), total: all.length, page, size };
  })),
  http.post(`${W}/reviews/:reviewId/reply`, route(async (ctx, p) => {
    require(ctx, 'reviews', 'EDIT', 'SELLER');
    const { text } = await body(ctx.request, z.object({ text: z.string().trim().min(1, 'Write your reply').max(SELLER_REPLY_MAX, `At most ${SELLER_REPLY_MAX} characters`) }));
    const row = ctx.db.reviews.find((r) => r.id === p.reviewId);
    if (!row) throw notFound('No such review.');
    if (row.reply) throw new SellerProblem(409, 'ALREADY_DONE', 'Already replied', 'You have already replied to this review. A reply cannot be edited.');
    row.reply = { text, at: new Date().toISOString() };
    return row;
  })),
  http.post(`${W}/reviews/:reviewId/reports`, route(async (ctx, p) => {
    require(ctx, 'reviews', 'EDIT', 'SELLER');
    const input = await body(ctx.request, z.object({ reasonCode: z.enum(REVIEW_REASON_CODES), details: z.string().max(500).optional() }));
    if (input.reasonCode === 'OTHER' && !input.details?.trim()) throw invalid({ details: 'Tell us what is wrong' });
    const row = ctx.db.reviews.find((r) => r.id === p.reviewId);
    if (!row) throw notFound('No such review.');
    const seen = reported.get(ctx.db) ?? new Set<string>();
    reported.set(ctx.db, seen);
    const created = !seen.has(row.id);
    seen.add(row.id);
    if (created) row.reportCount += 1;
    return { created };
  }, (out) => ((out as { created: boolean }).created ? 201 : 200))),
  /* Send to AfroTransact (S281) */
  ...sellerFbaHandlers(W, fbaGate),
  /* Anything else under /v1/seller is not part of the contract. */
  http.all(`${W}/*`, () => problemResponse(notFound('No such seller route.'))),
  /* My account (profile, sign-in & security, alerts, preferences): /v1/account/… */
  ...sellerSettingsHandlers
];

