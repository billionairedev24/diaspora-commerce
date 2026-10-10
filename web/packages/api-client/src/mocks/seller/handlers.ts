/* MSW handlers implementing contracts/openapi/gateway-bff-seller.yaml for the Seller Center & Provider Hub in
   development and tests. Paths use a wildcard prefix (any base URL ending in /v1/seller/...). The cookie
   at_mock_role overrides the member's role in the active organisation (e.g. seller.fulfilment) to try role-aware
   navigation without a real identity service. */
import { http, HttpResponse, type JsonBodyType } from 'msw';
import { z } from 'zod';

import { bffProblemType } from '../../problem-types';
import {
  AvailabilityFormSchema,
  type BookingAction,
  BookingActionSchema,
  type BookingCard,
  type BookingStatus,
  CadenceSchema,
  CancellationPolicyInputSchema,
  CarrierSchema,
  CategoryRequestInputSchema,
  type ChartSpec,
  type Dashboard,
  type EarningsRow,
  type ListingRow,
  type ModuleKey,
  type OnboardingStatus,
  type OrgSummary,
  ParcelSchema,
  ProductDraftSchema,
  PublicPageInputSchema,
  SellerAddressSchema,
  type SellerFeesView,
  type SellerFeeVersion,
  type SellerOrderDetail,
  type SellerOrderRow,
  type SellerSession,
  SellerStatusActionSchema,
  ServiceInputSchema,
  type ShipFromAddress,
  type ShipmentStatus,
  StockAdjustmentRequestSchema,
  TEAM_ERROR_MESSAGES,
  type TeamMember,
  UploadRequestSchema
} from '../../schemas/seller';
import { CATEGORY_TREE, getSellerDb, type MockOrg, type MockSellerDb, plansFor, PRODUCT_TYPES, SELLER_ORG } from './db';
import { allowedBookingActions, allows, BOOKING_LABELS, describePolicy, navigation, presetPolicy, roleCards, roleLabel, SHIPMENT_LABELS, takeHome } from './model';

export const MOCK_ROLE_COOKIE = 'at_mock_role';

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

/** Approval, organisation kind and module access — the same checks the BFF makes. */
function require(ctx: Ctx, module: ModuleKey | null, level: 'VIEW' | 'EDIT', kind?: 'SELLER' | 'PROVIDER') {
  if (ctx.org.status !== 'APPROVED') throw new SellerProblem(403, 'NOT_APPROVED', 'Not approved yet', 'This part of the back office unlocks once your application is approved.');
  if (kind && ctx.org.kind !== kind) throw forbidden(`Switch to an organisation that uses ${kind === 'PROVIDER' ? 'Provider Hub' : 'Seller Center'}.`);
  if (module && !allows(ctx.org.kind, ctx.role, module, level)) throw forbidden(`Your role (${roleLabel(ctx.role)}) can’t ${level === 'VIEW' ? 'view' : 'change'} this.`);
}

type Resolver = (ctx: Ctx, params: Record<string, string>) => Promise<unknown> | unknown;

function route(fn: Resolver, status = 200) {
  return async ({ request, params }: { request: Request; params: Record<string, string | readonly string[] | undefined> }) => {
    const flat: Record<string, string> = {};
    Object.entries(params).forEach(([k, v]) => {
      if (typeof v === 'string') flat[k] = v;
    });
    try {
      const out = await fn(context(request), flat);
      return HttpResponse.json(out as JsonBodyType, { status });
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
  const navGroups = navigation(org.kind, role, approved, badges);
  return {
    user: { ...db.user, initials: (db.user.firstName[0] ?? '') + (db.user.lastName[0] ?? '') },
    orgs: db.orgs.map((o) => orgSummary(o, o.id === org.id ? role : o.role)),
    activeOrg: orgSummary(org, role),
    portal: org.kind === 'PROVIDER' ? 'PROVIDER_HUB' : 'SELLER_CENTER',
    approved,
    navGroups,
    landing: navGroups[0]?.modules[0]?.key ?? 'home',
    accessNote: approved ? 'Least-privilege by role · enforced server-side' : 'Token scope: onboarding only'
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
  s.nextActions = s.fulfilment === 'SELLER' && next && SellerStatusActionSchema.safeParse(next).success ? [next as 'CONFIRMED' | 'PACKED' | 'READY'] : [];
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
      const commission = Math.round(collected * 0.1);
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
      .map((k) => (k === override[0] ? { category: k, label: label(k), commissionBps: override[1], source: 'OVERRIDE' as const } : { category: k, label: label(k), commissionBps: rates[k] ?? 1000, source: 'CATEGORY' as const })),
    cardPctBps: 290,
    cardFixedMinor: 30,
    fbaPerUnitMinor: fba,
    reverseCommissionOnRefund: true
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
    ]
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
const RatesBody = z.object({ parcel: ParcelSchema, shipFrom: SellerAddressSchema });
const LabelBody = RatesBody.extend({ carrier: CarrierSchema, service: z.string().min(1, 'Choose a rate') });
const ActiveBody = z.object({ active: z.boolean() });
const PayoutBody = z.object({ amountMinor: z.number().int().optional(), all: z.boolean().optional() });
const PlanBody = z.object({ planCode: z.string().min(1, 'Choose a plan'), cadence: CadenceSchema });
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

const TokenBody = z.object({ token: z.string().trim().min(1, 'This invitation link is incomplete. Open it again from the email.').max(200) });
/** Invitation tokens the mock knows (Seller Center accept page). */
const MOCK_INVITES: Record<string, 'PENDING' | 'EXPIRED' | 'REVOKED'> = { 'demo-invite': 'PENDING', 'expired-invite': 'EXPIRED', 'revoked-invite': 'REVOKED' };
const TEAM_TITLES: Record<string, string> = {
  LAST_OWNER: 'Last owner', INVALID_ROLE: 'Invalid role', OWNER_ONLY: 'Owner only', OWN_ROLE: 'Your own role', ALREADY_MEMBER: 'Already a member',
  INVITATION_EXPIRED: 'Invitation expired', INVITATION_USED: 'Invitation already used', INVITATION_REVOKED: 'Invitation withdrawn'
};
const teamProblem = (status: number, code: string) => new SellerProblem(status, code, TEAM_TITLES[code] ?? code, TEAM_ERROR_MESSAGES[code] ?? code);

const W = '*/v1/seller';
const query = (ctx: Ctx) => new URL(ctx.request.url).searchParams;
const nextId = (db: MockSellerDb, prefix: string) => `${prefix}-${++db.seq}`;

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
    version: existing ? existing.version + 1 : 0
  };
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

function publicPage(ctx: Ctx) {
  const page = ctx.db.pages[ctx.org.id];
  if (!page) throw notFound('No storefront yet.');
  return page;
}

export const sellerHandlers = [
  /* ── Shell ── */
  http.get(`${W}/session`, route((ctx) => session(ctx))),
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
    route((ctx): OnboardingStatus => {
      const org = ctx.org;
      const steps: [string, string][] = [
        ['ACCOUNT', 'Account & email verified'], ['BUSINESS', 'Business details & TIN'], ['OWNERS', 'Owners'], ['STRIPE', 'Stripe verification'],
        ['PAYOUTS', 'Payouts'], ['PLAN', 'Plan'], ['COMPLIANCE', 'Catalog, permits & licences']
      ];
      const info = org.status === 'INFO_REQUESTED';
      return {
        orgId: org.id, kind: org.kind, status: org.status, reference: 'APP-30131',
        steps: [
          ...steps.map(([key, label]) => ({ key, label, state: key === 'COMPLIANCE' && info ? ('ACTION' as const) : ('DONE' as const) })),
          { key: 'REVIEW', label: 'AfroTransact staff review', state: info ? 'ACTION' : org.status === 'APPROVED' ? 'DONE' : 'WAITING' }
        ],
        requestedItems: info ? ['Harris County food establishment permit (current)'] : [],
        canResume: info || org.status === 'DRAFT'
      };
    })
  ),
  http.get(
    `${W}/compliance`,
    route((ctx) => {
      require(ctx, null, 'VIEW');
      if (ctx.org.kind === 'PROVIDER') {
        return { status: 'COMPLIANT', showBanner: false, tone: 'none', title: 'Account in good standing.', message: 'Nothing to do.', openItems: 0, deadline: null,
          payoutsPaused: false, chargesEnabled: true, payoutsEnabled: true, listingsHidden: false, items: [] };
      }
      const deadline = new Date(ctx.now.getTime() + 14 * 86_400_000);
      return {
        status: 'ACTION_NEEDED', showBanner: true, tone: 'warning', title: 'We need a little more information.',
        message: `2 item(s) due ${deadline.toLocaleDateString('en-US', { month: 'short', day: 'numeric' })}. Open Account health to resolve.`, openItems: 2,
        deadline: deadline.toISOString(), payoutsPaused: false, chargesEnabled: true, payoutsEnabled: true, listingsHidden: false,
        items: [{ requirement: 'company.tax_id', label: 'Company tax ID (EIN)', bucket: 'currently_due' }, { requirement: 'external_account', label: 'Payout bank account', bucket: 'past_due' }]
      };
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

  /* ── Orders ── */
  http.get(
    `${W}/orders`,
    route((ctx) => {
      require(ctx, 'orders', 'VIEW', 'SELLER');
      const q = query(ctx);
      const status = q.get('status');
      const limit = Math.min(100, Math.max(1, Number(q.get('limit') ?? '20')));
      const start = Number(q.get('cursor') ?? '0') || 0;
      const rows = ctx.db.orders.map(orderRow).filter((r): r is SellerOrderRow => r !== null && (!status || r.status === status));
      return { items: rows.slice(start, start + limit), nextCursor: start + limit < rows.length ? String(start + limit) : null, total: rows.length };
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
      if (s.fulfilment !== 'SELLER') throw forbidden('AfroTransact handles every step of this shipment.');
      if (NEXT[s.status] !== status) throw conflict(`This order is ${s.statusLabel.toLowerCase()}; it can’t move to ${SHIPMENT_LABELS[status as ShipmentStatus].toLowerCase()}.`);
      s.status = status as ShipmentStatus;
      s.timeline.push({ status, at: ctx.now.toISOString(), actor: 'seller' });
      refreshShipment(o, s.shipmentId);
      return o;
    })
  ),
  http.post(
    `${W}/orders/:orderNumber/shipments/:shipmentId/rates`,
    route(async (ctx, p) => {
      const { parcel } = await body(ctx.request, RatesBody);
      require(ctx, 'orders', 'EDIT', 'SELLER');
      const s = refreshShipment(findOrder(ctx.db, p.orderNumber ?? ''), p.shipmentId ?? '');
      if (!s.canBuyLabel) throw conflict('Labels can be bought for confirmed seller-shipped orders without tracking.');
      const w = Math.ceil(parcel.weightLb);
      const day = (n: number) => new Date(ctx.now.getTime() + n * 86_400_000).toISOString();
      return {
        partial: false,
        quotes: [
          { carrier: 'usps', service: 'USPS_GROUND_ADVANTAGE', serviceName: 'USPS Ground Advantage', amountMinor: 545 + w * 60, currency: 'USD', transitDays: 4, estimatedDelivery: day(4) },
          { carrier: 'ups', service: '03', serviceName: 'UPS Ground', amountMinor: 889 + w * 75, currency: 'USD', transitDays: 3, estimatedDelivery: day(3) },
          { carrier: 'usps', service: 'USPS_PRIORITY', serviceName: 'USPS Priority Mail', amountMinor: 995 + w * 90, currency: 'USD', transitDays: 2, estimatedDelivery: day(2) },
          { carrier: 'fedex', service: 'FEDEX_2_DAY', serviceName: 'FedEx 2Day', amountMinor: 1890 + w * 120, currency: 'USD', transitDays: 2, estimatedDelivery: day(2) }
        ]
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
      const text = (q.get('q') ?? '').trim().toLowerCase();
      const page = Number(q.get('page') ?? '0');
      const size = Number(q.get('size') ?? '25');
      if (size < 1 || size > 100 || page < 0) throw invalid({ size: 'Between 1 and 100' });
      const rows = ctx.db.listings.filter((l) => (!status || l.status === status) && (!text || l.title.toLowerCase().includes(text) || l.sku.toLowerCase().includes(text)));
      const items: ListingRow[] = rows.slice(page * size, page * size + size).map(({ variants: _v, ...row }) => row);
      return { items, total: rows.length, page, size };
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
  http.post(
    `${W}/products`,
    route(async (ctx) => {
      const d = await body(ctx.request, ProductDraftSchema);
      if (!d.categoryId === !d.categoryRequestId) throw invalid({ categoryId: 'Choose a category, or request a new one.' });
      const skus = d.variants.map((v) => v.sellerSku);
      if (new Set(skus).size !== skus.length) throw invalid({ variants: 'SKUs must be unique.' });
      require(ctx, 'listings', 'EDIT', 'SELLER');
      const status = d.categoryRequestId ? ('PENDING_CATEGORY_APPROVAL' as const) : ('IN_REVIEW' as const);
      const row = {
        productId: nextId(ctx.db, 'prod'), atin: null, title: d.title, sku: d.variants[0]?.sellerSku ?? '', variantCount: d.variants.length,
        priceMinor: Math.min(...d.variants.map((v) => v.priceMinor)), currency: 'USD', stock: d.variants.reduce((a, v) => a + v.quantity, 0), fulfilment: 'SELLER' as const,
        status, statusLabel: status === 'IN_REVIEW' ? 'In review' : 'Waiting for category approval', moderationNote: null, imageUrl: d.images[0]?.url ?? null,
        updatedAt: ctx.now.toISOString()
      };
      ctx.db.listings.unshift({ ...row, variants: d.variants.map((v) => ({ sku: v.sellerSku, quantity: v.quantity })) });
      return row;
    }, 201)
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
  http.post(
    `${W}/uploads`,
    route(async (ctx) => {
      const b = await body(ctx.request, UploadRequestSchema);
      const id = nextId(ctx.db, 'asset');
      return { assetId: id, method: 'PUT', url: `${new URL(ctx.request.url).origin}/api/bff/mock-uploads/${id}`, headers: { 'Content-Type': b.contentType },
        publicUrl: `https://img.afrotransact.dev/${id}.webp`, expiresAt: new Date(ctx.now.getTime() + 15 * 60_000).toISOString() };
    }, 201)
  ),
  http.put('*/mock-uploads/:id', () => new HttpResponse(null, { status: 200 })),
  http.post(
    `${W}/uploads/:assetId/complete`,
    route((_ctx, p) => ({ id: p.assetId ?? '', url: `https://img.afrotransact.dev/${p.assetId ?? ''}.webp`, width: 1600, height: 1600, bytes: 182000, contentType: 'image/webp', status: 'READY' }))
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
          holds: [{ orderId: 'AT-2026-400160', amountMinor: 31225, releaseAt: new Date(ctx.now.getTime() + 3 * 86_400_000).toISOString().slice(0, 10) }, { orderId: 'AT-2026-400197', amountMinor: 21500, releaseAt: null }] },
        payouts: ctx.db.payouts,
        historyAvailable: true
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
      return { subscription: sub, plans: plansFor(ctx.org.kind, sub.planCode), paymentMethods: ctx.db.paymentMethods[ctx.org.kind], invoices: ctx.db.invoices, canManage: ctx.role.endsWith('.owner') };
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
      return { applied, billing: { subscription: sub, plans: plansFor(ctx.org.kind, sub.planCode), paymentMethods: ctx.db.paymentMethods[ctx.org.kind], invoices: ctx.db.invoices, canManage: true } };
    })
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
      return requested(ctx.db).map((b) => bookingCard(b, ctx.now));
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
        currency: 'USD', commissionPercent: 10, payoutDay: 'Tuesday', totals, rows,
        providerFees: { available: true, outstandingMinor: outstandingFees(ctx.db), collectedMinor: items.filter((f) => f.status === 'COLLECTED').reduce((a, f) => a + f.amountMinor, 0), items }
      };
    })
  ),
  http.get(
    `${W}/storefront`,
    route((ctx) => {
      require(ctx, ctx.org.kind === 'PROVIDER' ? 'public-page' : 'storefront', 'VIEW');
      return publicPage(ctx);
    })
  ),
  http.put(
    `${W}/storefront`,
    route(async (ctx) => {
      const input = await body(ctx.request, PublicPageInputSchema);
      const hosts: Record<string, string[]> = { instagram: ['instagram.com'], tiktok: ['tiktok.com'], facebook: ['facebook.com', 'fb.com'], youtube: ['youtube.com', 'youtu.be'],
        whatsapp: ['wa.me', 'whatsapp.com'], x: ['x.com', 'twitter.com'], linkedin: ['linkedin.com'] };
      const errors: Record<string, string> = {};
      (input.social ?? []).forEach((s) => {
        let ok = s.url.startsWith('https://');
        if (ok && s.network !== 'website') {
          const host = new URL(s.url).hostname.replace(/^www\./, '');
          ok = (hosts[s.network] ?? []).includes(host);
        }
        if (!ok) errors[`social.${s.network}`] = `Use a https link on ${s.network}`;
      });
      if (Object.keys(errors).length) throw invalid(errors);
      require(ctx, ctx.org.kind === 'PROVIDER' ? 'public-page' : 'storefront', 'EDIT');
      const page = publicPage(ctx);
      const { social, featured, ...rest } = input;
      Object.assign(page, Object.fromEntries(Object.entries(rest).filter(([, v]) => v !== undefined)));
      if (featured) page.featured = featured.map((f) => ({ ...f, serviceId: f.serviceId ?? null }));
      if (social) {
        page.social = social.map((s) => {
          const prev = page.social.find((x) => x.network === s.network);
          return { network: s.network, url: s.url, status: prev && prev.url === s.url ? prev.status : 'PENDING' };
        });
      }
      page.status = 'DRAFT';
      return page;
    })
  ),
  http.post(
    `${W}/storefront/publish`,
    route((ctx) => {
      require(ctx, ctx.org.kind === 'PROVIDER' ? 'public-page' : 'storefront', 'EDIT');
      const page = publicPage(ctx);
      page.status = 'PUBLISHED';
      page.publishedAt = ctx.now.toISOString();
      return page;
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
  /* Anything else under /v1/seller is not part of the contract. */
  http.all(`${W}/*`, () => problemResponse(notFound('No such seller route.')))
];

