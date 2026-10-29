/* Mock of the staff run economics and advertorial routes (/v1/staff/economics-settings, /v1/staff/preorders/economics-preview,
   /v1/staff/preorders/{id}/economics, /v1/staff/delivery-runs/**, /v1/staff/advertorials/**; S263, S265), mirroring
   delivery-runs through the BFF. The cost model, break-even and P&L use the same calculation as delivery-runs; locations
   and runs are seeded around the mock pre-order windows (Lubbock, Amarillo, Midland). */
import { http, HttpResponse } from 'msw';
import { z } from 'zod';

import { areaFromKey, preorderStatus } from '../../staff-preorders';
import {
  type AdvertorialAction,
  advertorialActions,
  type AdvertorialInput,
  advertorialInputProblems,
  type AdvertorialSource,
  type EconomicsChange,
  economicsInputProblems,
  type EconomicsNumbers,
  type EconomicsSettings,
  type RunAllowed,
  type RunArea,
  runBreakEven,
  runCostModel,
  type RunCutoffPolicy,
  type RunActuals,
  type RunDropMode,
  type RunEconomics,
  type RunHistoryRow,
  type RunLocationInput,
  runLocationProblems,
  type RunLogEntry,
  type RunFeeLine,
  type RunMilestones,
  type PlatformFees,
  type RunOrderStatus,
  type RunPnl,
  type RunScheduleInput,
  runScheduleProblems,
  runSnapshot,
  type RunStatus,
  type StaffAdvertorial,
  type StaffRun,
  type StaffRunDetail,
  type StaffRunLocation,
  type StaffRunOrder,
  type WindowEconomics,
  type WindowHistoryRow,
  type WindowPlan
} from '../../staff-runs';
import { getStaffDb, type MockStaffDb } from './db';
import { allows, levelOf } from './model';
import { ordersOf, type PreorderCtx, type PreorderHandlerDeps } from './preorders';

const DAY = 86_400_000;

/** The defaults the environment applies until staff save the cost model (delivery-runs' application.yml). */
export const DEFAULT_ECONOMICS: EconomicsNumbers = {
  driverCostPerHourMinor: 2500,
  driverCostPerMileMinor: 70,
  collectionMinutes: 45,
  perStopMinutes: 6,
  pickupPointStopMinutes: 30,
  handoverMinutesPerOrder: 1,
  roundTrip: true,
  platformFeePerOrderMinor: 0
};

interface Place {
  id: string;
  slug: string;
  name: string;
  key: string;
  /** Where the location delivers: a radius around the town, or a polygon registered as a zone in geo. */
  area: RunArea;
  roadMiles: number;
  driveMinutes: number;
  growth: [string, number][];
  channels: Record<string, number>;
  /** What staff entered for a town they added or changed (the seeded towns use the defaults below). */
  details?: {
    market?: string;
    state?: string | null;
    timeZone?: string;
    currency?: string;
    defaultFeeMinor?: number;
    defaultMinOrders?: number;
    defaultCapacityOrders?: number | null;
    defaultCapacityPackages?: number | null;
    defaultExtendDays?: number;
    defaultCutoffPolicy?: RunCutoffPolicy;
    dropMode?: RunDropMode;
    whatsappInviteUrl?: string | null;
  };
  active?: boolean;
}

/* One location per seeded pre-order city: an origin hub sweeps the sellers and drives out. */
const PLACES: Place[] = [
  { id: '4b2f9d3a-0000-4000-8000-00000000000a', slug: 'lubbock-tx', name: 'Lubbock', key: 'US/TX/Lubbock', area: { kind: 'RADIUS', geoZoneId: null, centerLat: 33.5779, centerLng: -101.8552, radiusMiles: 15 }, roadMiles: 148, driveMinutes: 165, growth: [['2026-05', 12], ['2026-06', 29], ['2026-07', 51], ['2026-08', 88], ['2026-09', 131], ['2026-10', 164]], channels: { EMAIL: 118, SMS: 61, PUSH: 24, WHATSAPP: 33 } },
  { id: '4b2f9d3a-0000-4000-8000-00000000000b', slug: 'amarillo-tx', name: 'Amarillo', key: 'US/TX/Amarillo', area: { kind: 'POLYGON', geoZoneId: 'zone-amarillo-3f9a', centerLat: 35.222, centerLng: -101.8313, radiusMiles: null }, roadMiles: 241, driveMinutes: 262, growth: [['2026-07', 6], ['2026-08', 17], ['2026-09', 38], ['2026-10', 55]], channels: { EMAIL: 41, SMS: 18, PUSH: 7, WHATSAPP: 9 } },
  { id: '4b2f9d3a-0000-4000-8000-00000000000c', slug: 'midland-tx', name: 'Midland', key: 'US/TX/Midland', area: { kind: 'RADIUS', geoZoneId: null, centerLat: 31.9973, centerLng: -102.0779, radiusMiles: 12 }, roadMiles: 112, driveMinutes: 128, growth: [['2026-08', 9], ['2026-09', 21], ['2026-10', 34]], channels: { EMAIL: 26, SMS: 12, PUSH: 4, WHATSAPP: 5 } }
];

interface MockRun extends StaffRun {
  orders: number;
  packages: number;
  completed: boolean;
  notes: string | null;
  extensions: number;
  opensAt: string | null;
  sellerPickupAt: string | null;
  cutoffPolicy: RunCutoffPolicy;
  extendDays: number;
  capacityPackages: number | null;
  originalCutoffAt: string;
  milestones: RunMilestones;
  version: number;
}

interface RunsState {
  settings: EconomicsSettings;
  changes: EconomicsChange[];
  runs: MockRun[];
  /** The towns: the seeded ones, those staff added and the changes they made. */
  places: Place[];
  /** What was decided about each run (extend, confirm, cancel), oldest first. */
  logs: Map<string, RunLogEntry[]>;
  advertorials: StaffAdvertorial[];
  seq: number;
}

const STATES = new WeakMap<MockStaffDb, RunsState>();

function seed(db: MockStaffDb): RunsState {
  const base = db.preorders.base;
  const at = (days: number, hour = 15) => new Date(Math.floor(base / DAY) * DAY + days * DAY + hour * 3_600_000).toISOString();
  const run = (n: number, place: Place, status: RunStatus, days: number, orders: number, min = 12, fee = 799, extra: Partial<MockRun> = {}): MockRun => ({
    id: `7e5c2a6d-0000-4000-8000-${String(n).padStart(12, '0')}`,
    locationId: place.id,
    status,
    windowStart: at(days),
    windowEnd: at(days, 19),
    cutoffAt: at(days - 3, 23),
    feeMinor: fee,
    currency: 'USD',
    minOrders: min,
    capacityOrders: 40,
    dropMode: 'DOORSTEP',
    reservedOrders: orders,
    minimumMet: orders >= min,
    cancelReason: null,
    orders,
    packages: Math.round(orders * 1.4),
    completed: status === 'COMPLETED',
    notes: null,
    extensions: 0,
    opensAt: null,
    sellerPickupAt: at(days - 2, 13),
    cutoffPolicy: 'EXTEND',
    extendDays: 7,
    capacityPackages: null,
    originalCutoffAt: at(days - 3, 23),
    milestones: { openedAt: status === 'DRAFT' ? null : at(days - 12), cancelledAt: status === 'CANCELLED' ? at(days - 3, 23) : null, completedAt: status === 'COMPLETED' ? at(days, 19) : null },
    version: 1,
    ...extra
  });
  const [lbk, ama, mid] = PLACES as [Place, Place, Place];
  const runs = [
    run(1, lbk, 'COMPLETED', -42, 31),
    run(2, lbk, 'COMPLETED', -28, 38),
    run(3, lbk, 'CANCELLED', -14, 7, 12, 799, { cancelReason: 'MINIMUM_NOT_MET', extensions: 1 }),
    run(4, lbk, 'OPEN', 6, 19),
    run(5, ama, 'COMPLETED', -21, 14, 12),
    run(6, ama, 'OPEN', 9, 9, 12),
    run(7, mid, 'COMPLETED', -10, 16, 10),
    run(8, mid, 'ANNOUNCED', 15, 0, 10),
    /* Short of its minimum with the cutoff about a day away: it needs a decision before the job makes one. */
    run(9, ama, 'OPEN', 3, 5, 12, 799, { cutoffAt: at(1, 5), originalCutoffAt: at(1, 5), notes: 'Two sellers deliver to the hub on Thursday evening' }),
    run(10, mid, 'DRAFT', 20, 0, 10)
  ];
  const adv = (n: number, extra: Partial<StaffAdvertorial>): StaffAdvertorial => ({
    id: `5c3a0e4b-0000-4000-8000-${String(n).padStart(12, '0')}`,
    source: 'WINDOW',
    sourceId: 'lbk-7',
    sourceName: 'Lubbock Pre-order #7',
    sourceCode: 'LBK-7',
    sourceStatus: 'OPEN',
    sourceCloses: db.preorders.windows.find((w) => w.id === 'lbk-7')?.closesAt ?? null,
    locationId: lbk.id,
    locationName: 'Lubbock',
    variant: 'A',
    weight: 1,
    priority: 0,
    generated: true,
    headline: 'Lubbock pre-order is open',
    body: 'African groceries, delivered to Lubbock. Order before the close.',
    ctaLabel: 'Order now',
    startsAt: null,
    endsAt: null,
    featuredProducts: [],
    status: 'LIVE',
    ended: null,
    serving: true,
    results: { views: 1840, clicks: 212, ctrPct: 11.5, orders: 37, cancelledOrders: 3 },
    createdBy: 'system',
    createdAt: at(-9),
    updatedAt: at(-9),
    version: 2,
    ...extra
  });
  const advertorials = [
    adv(1, { featuredProducts: [{ atin: 'ATSHEA0001', title: 'Shea butter, raw' }, { atin: 'ATGARI0001', title: 'Garri, white' }] }),
    adv(2, { variant: 'B', generated: false, headline: 'Last days: Lubbock jollof and spice box', body: 'Pre-order closes Thursday. Free pickup, door delivery from the run fee.', ctaLabel: 'Reserve yours', results: { views: 1792, clicks: 274, ctrPct: 15.3, orders: 52, cancelledOrders: 5 } }),
    adv(3, { source: 'RUN', sourceId: run(4, lbk, 'OPEN', 6, 19).id, sourceName: 'Lubbock run', sourceCode: null, sourceStatus: 'OPEN', variant: 'A', headline: 'Our Lubbock delivery run is open', ctaLabel: 'See the run', results: { views: 420, clicks: 38, ctrPct: 9, orders: 6, cancelledOrders: 0 } })
  ];
  const logs = new Map<string, RunLogEntry[]>([
    [
      runs[2]?.id ?? '',
      [
        { id: 'rl-1', kind: 'EXTEND', reason: 'MINIMUM_NOT_MET', actor: 'System', bySystem: true, fromStatus: 'OPEN', toStatus: 'EXTENDED', orders: 5, minimum: 12, fromCutoff: at(-24, 23), toCutoff: at(-17, 23), at: at(-24, 23) },
        { id: 'rl-2', kind: 'CANCEL', reason: 'MINIMUM_NOT_MET', actor: 'System', bySystem: true, fromStatus: 'EXTENDED', toStatus: 'CANCELLED', orders: 7, minimum: 12, fromCutoff: null, toCutoff: null, at: at(-17, 23) }
      ]
    ]
  ]);
  return { settings: { ...DEFAULT_ECONOMICS, version: 0, source: 'CONFIG', updatedAt: null, updatedBy: null, reason: null, can: { manage: true } }, changes: [], runs, places: PLACES.map((p) => ({ ...p })), logs, advertorials, seq: 100 };
}

/** The mock database's clock (seeded "now" plus elapsed real time), like the pre-order mock's. */
const clockOf = (db: MockStaffDb): number => db.preorders.base + (Date.now() - db.preorders.seededAt);

const stateOf = (db: MockStaffDb): RunsState => {
  let s = STATES.get(db);
  if (!s) {
    s = seed(db);
    STATES.set(db, s);
  }
  return s;
};

const NumbersBody = z.object({
  driverCostPerHourMinor: z.number(),
  driverCostPerMileMinor: z.number(),
  collectionMinutes: z.number(),
  perStopMinutes: z.number(),
  pickupPointStopMinutes: z.number(),
  handoverMinutesPerOrder: z.number(),
  roundTrip: z.boolean(),
  platformFeePerOrderMinor: z.number().int().optional(),
  reason: z.string()
});
const WindowPlanBody = z.object({
  locationId: z.string().nullish(),
  targets: z.array(z.string()).optional(),
  door: z.boolean().nullish(),
  pickup: z.boolean().nullish(),
  doorFeeMinor: z.number().int().nullish(),
  pickupFeeMinor: z.number().int().nullish(),
  minOrders: z.number().int().nullish(),
  expectedOrders: z.number().int().nullish(),
  capacityOrders: z.number().int().nullish()
});
const RunPlanBody = z.object({
  locationId: z.string(),
  feeMinor: z.number().int().nullish(),
  minOrders: z.number().int().nullish(),
  capacityOrders: z.number().int().nullish(),
  dropMode: z.enum(['DOORSTEP', 'PICKUP_POINT']).nullish(),
  expectedOrders: z.number().int().nullish(),
  commissionShareMinor: z.number().int().nullish()
});
const FeaturedBody = z.object({ atin: z.string(), title: z.string(), imageUrl: z.string().nullish() });
const AdvertorialBody = z.object({
  source: z.enum(['WINDOW', 'RUN']).optional(),
  sourceId: z.string().optional(),
  variant: z.string().nullish(),
  weight: z.number().int().nullish(),
  priority: z.number().int().nullish(),
  headline: z.string(),
  body: z.string().nullish(),
  ctaLabel: z.string(),
  startsAt: z.string().nullish(),
  endsAt: z.string().nullish(),
  featuredProducts: z.array(FeaturedBody).optional()
});
const GenerateBody = z.object({ source: z.enum(['WINDOW', 'RUN']), sourceId: z.string() });
const RunScheduleBody = z.object({
  locationId: z.string().optional(),
  windowStart: z.string().optional(),
  windowEnd: z.string().optional(),
  cutoffAt: z.string().optional(),
  opensAt: z.string().nullish(),
  sellerPickupAt: z.string().nullish(),
  feeMinor: z.number().nullish(),
  minOrders: z.number().nullish(),
  capacityOrders: z.number().nullish(),
  capacityPackages: z.number().nullish(),
  dropMode: z.enum(['DOORSTEP', 'PICKUP_POINT']).nullish(),
  cutoffPolicy: z.enum(['EXTEND', 'CANCEL']).nullish(),
  extendDays: z.number().nullish(),
  notes: z.string().nullish()
});
const RunExtendBody = z.object({
  cutoffAt: z.string().optional(),
  windowStart: z.string().nullish(),
  windowEnd: z.string().nullish(),
  sellerPickupAt: z.string().nullish(),
  reason: z.string().nullish()
});
const ReasonBody = z.object({ reason: z.string().nullish() });
const LocationBody = z.object({
  name: z.string().optional(),
  slug: z.string().nullish(),
  market: z.string().optional(),
  state: z.string().nullish(),
  timeZone: z.string().nullish(),
  areaKind: z.enum(['POLYGON', 'RADIUS']).optional(),
  radiusMiles: z.number().nullish(),
  geoZoneId: z.string().nullish(),
  polygonGeoJson: z.string().nullish(),
  centerLat: z.number().optional(),
  centerLng: z.number().optional(),
  defaultFeeMinor: z.number().nullish(),
  currency: z.string().nullish(),
  defaultMinOrders: z.number().nullish(),
  defaultCapacityOrders: z.number().nullish(),
  defaultCapacityPackages: z.number().nullish(),
  dropMode: z.enum(['DOORSTEP', 'PICKUP_POINT']).nullish(),
  defaultCutoffPolicy: z.enum(['EXTEND', 'CANCEL']).nullish(),
  defaultExtendDays: z.number().nullish(),
  whatsappInviteUrl: z.string().nullish(),
  active: z.boolean().optional()
});
/** Run statuses in which a location cannot be switched off (the run is announced, takes orders or is on the road). */
const LIVE_RUN_STATUSES: string[] = ['ANNOUNCED', 'OPEN', 'EXTENDED', 'CUT_OFF', 'CONFIRMED', 'COLLECTING', 'OUT_FOR_DELIVERY'];
const slugOf = (name: string, state?: string | null) =>
  `${name} ${state ?? ''}`
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
const locationInput = (b: z.infer<typeof LocationBody>): RunLocationInput => ({
  name: b.name ?? '',
  market: b.market ?? '',
  state: b.state ?? null,
  timeZone: b.timeZone ?? 'America/Chicago',
  areaKind: b.areaKind ?? ('' as never),
  radiusMiles: b.radiusMiles ?? null,
  geoZoneId: b.geoZoneId ?? null,
  polygonGeoJson: b.polygonGeoJson ?? null,
  centerLat: b.centerLat ?? Number.NaN,
  centerLng: b.centerLng ?? Number.NaN,
  defaultFeeMinor: b.defaultFeeMinor ?? null,
  currency: b.currency ?? 'USD',
  defaultMinOrders: b.defaultMinOrders ?? null,
  defaultCapacityOrders: b.defaultCapacityOrders ?? null,
  defaultCapacityPackages: b.defaultCapacityPackages ?? null,
  dropMode: b.dropMode ?? null,
  defaultCutoffPolicy: b.defaultCutoffPolicy ?? null,
  defaultExtendDays: b.defaultExtendDays ?? null,
  whatsappInviteUrl: b.whatsappInviteUrl ?? null
});
const LOCATION_WORDS = { required: 'This is required', range: 'Outside the allowed range', whole: 'Enter a whole number', order: 'Capacity cannot be below the minimum orders', tooLong: 'Too long', link: 'Paste the full link, starting with https://', zone: 'Choose the area the location delivers to, or draw its outline' } as const;
const locationErrors = (input: RunLocationInput): Record<string, string> => {
  const errors = Object.fromEntries(Object.entries(runLocationProblems(input)).map(([k, v]) => [k, LOCATION_WORDS[v]])) as Record<string, string>;
  if (input.timeZone) {
    try {
      new Intl.DateTimeFormat('en-US', { timeZone: input.timeZone });
    } catch {
      errors.timeZone = 'Use an IANA time zone such as America/Chicago';
    }
  }
  return errors;
};
const areaOf = (input: RunLocationInput): RunArea =>
  input.areaKind === 'RADIUS'
    ? { kind: 'RADIUS', geoZoneId: null, centerLat: input.centerLat, centerLng: input.centerLng, radiusMiles: input.radiusMiles ?? null }
    : { kind: 'POLYGON', geoZoneId: input.geoZoneId?.trim() || `zone-${slugOf(input.name)}`, centerLat: input.centerLat, centerLng: input.centerLng, radiusMiles: null };
const detailsOf = (input: RunLocationInput): NonNullable<Place['details']> => ({
  market: input.market.trim().toUpperCase(),
  state: input.state?.trim() ? input.state.trim().toUpperCase() : null,
  timeZone: input.timeZone,
  currency: input.currency,
  defaultFeeMinor: input.defaultFeeMinor ?? 799,
  defaultMinOrders: input.defaultMinOrders ?? 12,
  defaultCapacityOrders: input.defaultCapacityOrders ?? 40,
  defaultCapacityPackages: input.defaultCapacityPackages ?? null,
  defaultExtendDays: input.defaultExtendDays ?? 7,
  defaultCutoffPolicy: input.defaultCutoffPolicy ?? 'EXTEND',
  dropMode: input.dropMode ?? 'DOORSTEP',
  whatsappInviteUrl: input.whatsappInviteUrl?.trim() ? input.whatsappInviteUrl.trim() : null
});

const RANK = { VIEW: 1, EDIT: 2, APPROVE: 3, FULL: 4 } as const;

export function runHandlers(W: string, deps: PreorderHandlerDeps) {
  const { route, json, problem } = deps;
  const notFound = (detail: string) => problem(404, 'NOT_FOUND', 'Not found', detail);
  const view = (ctx: PreorderCtx) => {
    if (!allows(ctx.roles, 'preorders')) throw problem(403, 'FORBIDDEN', 'Forbidden', 'Your roles can’t open pre-orders.');
  };
  const canManage = (ctx: PreorderCtx) => {
    const lv = levelOf(ctx.roles, 'preorders');
    return ctx.permissions.includes('preorders:manage') && !!lv && RANK[lv] >= RANK.EDIT;
  };
  const manage = (ctx: PreorderCtx) => {
    view(ctx);
    if (!canManage(ctx)) throw problem(403, 'FORBIDDEN', 'Forbidden', 'Only pre-order managers can change the cost model and advertorials.');
  };
  const can = (ctx: PreorderCtx) => ({ manage: canManage(ctx) });
  const st = (ctx: PreorderCtx) => {
    const state = stateOf(ctx.db);
    settle(ctx.db, state);
    return state;
  };
  const audit = (ctx: PreorderCtx, action: string, entityId: string, summary: string) => ctx.db.audit.unshift({ at: ctx.now.toISOString(), actor: ctx.action.actor, action, summary, entityId });
  const ifMatch = (ctx: PreorderCtx, version: number) => {
    const raw = ctx.request.headers.get('if-match');
    if (!raw) throw problem(428, 'PRECONDITION_REQUIRED', 'If-Match required', 'Send the version you edited as If-Match.');
    if (raw.replace(/^W\//, '').replace(/"/g, '') !== String(version)) {
      throw problem(412, 'PRECONDITION_FAILED', 'Changed by someone else', 'Someone saved this in the meantime. Reload to see their changes, then edit again.');
    }
  };
  const withEtag = <T extends { version: number }>(body: T, status = 200) => HttpResponse.json(body as never, { status, headers: { ETag: `"${body.version}"` } });
  const validation = (errors: Record<string, string>) => problem(422, 'VALIDATION_FAILED', 'Validation failed', 'One or more fields are invalid.', errors);

  const placeOf = (ctx: PreorderCtx, id: string | null | undefined) => st(ctx).places.find((p) => p.id === id);
  const placeOfTargets = (ctx: PreorderCtx, targets: string[] | undefined) => {
    const first = targets?.[0];
    if (!first) return undefined;
    const a = areaFromKey(first);
    return st(ctx).places.find((p) => areaFromKey(p.key).city?.toLowerCase() === a.city?.toLowerCase() && !!a.city);
  };
  const locationRow = (p: Place): StaffRunLocation => ({
    id: p.id,
    slug: p.slug,
    name: p.name,
    market: p.details?.market ?? 'US',
    state: p.details?.state === undefined ? 'TX' : p.details.state,
    timeZone: p.details?.timeZone ?? 'America/Chicago',
    currency: p.details?.currency ?? 'USD',
    defaultFeeMinor: p.details?.defaultFeeMinor ?? 799,
    defaultMinOrders: p.details?.defaultMinOrders ?? 12,
    defaultCapacityOrders: p.details?.defaultCapacityOrders === undefined ? 40 : p.details.defaultCapacityOrders,
    defaultCapacityPackages: p.details?.defaultCapacityPackages ?? null,
    defaultCutoffPolicy: p.details?.defaultCutoffPolicy ?? 'EXTEND',
    defaultExtendDays: p.details?.defaultExtendDays ?? 7,
    dropMode: p.details?.dropMode ?? 'DOORSTEP',
    whatsappInviteUrl: p.details?.whatsappInviteUrl === undefined ? `https://chat.example/join/${p.slug}` : p.details.whatsappInviteUrl,
    area: p.area,
    active: p.active !== false
  });
  const evaluate = (ctx: PreorderCtx, p: Place, dropMode: RunDropMode, feeMinor: number, minOrders: number, capacity: number | null, orders: number, commission: number | null): RunEconomics => {
    const cost = runCostModel(st(ctx).settings, p, dropMode);
    return {
      route: { roadMiles: p.roadMiles, driveMinutes: p.driveMinutes, method: 'ROAD' },
      cost,
      feeMinor,
      currency: 'USD',
      breakEvenOrders: runBreakEven(feeMinor, cost),
      atMinimum: runSnapshot(minOrders, feeMinor, null, cost),
      atCapacity: capacity === null ? null : runSnapshot(capacity, feeMinor, null, cost),
      atOrders: runSnapshot(orders, feeMinor, commission, cost)
    };
  };

  /* A run's orders as the mock tells them: a fifth of them are collected at a pickup point (free), a few carrier hand-offs
     cost more, a tenth were given back after reserving, and a long completed run has one refunded order. */
  interface MockTally {
    delivered: number;
    cancelled: number;
    refunded: number;
    mix: RunFeeLine[];
    fees: number;
    refundedFees: number;
    refunds: number;
  }
  const tallyOf = (r: MockRun): MockTally => {
    if (r.status === 'CANCELLED') return { delivered: 0, cancelled: 0, refunded: r.orders, mix: [], fees: 0, refundedFees: r.orders * r.feeMinor, refunds: 0 };
    const delivered = r.orders;
    const pickup = Math.floor(delivered * 0.2);
    const carrier = delivered >= 30 ? 2 : 0;
    const door = delivered - pickup - carrier;
    const mix: RunFeeLine[] = [
      { kind: 'DOOR' as const, orders: door, feeMinor: door * r.feeMinor },
      { kind: 'PICKUP' as const, orders: pickup, feeMinor: 0 },
      { kind: 'CARRIER' as const, orders: carrier, feeMinor: carrier * (r.feeMinor + 500) }
    ].filter((m) => m.orders > 0);
    const refunded = r.completed && delivered >= 20 ? 1 : 0;
    return {
      delivered,
      cancelled: Math.floor(r.orders / 10),
      refunded,
      mix,
      fees: mix.reduce((a, m) => a + m.feeMinor, 0),
      refundedFees: refunded * r.feeMinor,
      refunds: refunded * (r.feeMinor + 1500)
    };
  };
  const platformFeesOf = (ctx: PreorderCtx, r: MockRun, t: MockTally, manual: number | null): PlatformFees | null => {
    if (r.status === 'CANCELLED') return null;
    const perOrder = st(ctx).settings.platformFeePerOrderMinor;
    if (manual !== null) return { source: 'MANUAL', commissionMinor: manual, orders: t.delivered, ordersReported: 0, perOrderEstimateMinor: 0 };
    // Payments reported the commission of a completed run's orders; for the others it is the configured estimate.
    if (r.completed) return { source: 'PAYMENTS', commissionMinor: t.delivered * 180, orders: t.delivered, ordersReported: t.delivered, perOrderEstimateMinor: perOrder };
    if (perOrder === 0) return { source: 'NONE', commissionMinor: null, orders: t.delivered, ordersReported: 0, perOrderEstimateMinor: 0 };
    return { source: 'ESTIMATE', commissionMinor: t.delivered * perOrder, orders: t.delivered, ordersReported: 0, perOrderEstimateMinor: perOrder };
  };
  /** The run's delivery P&L from its (mock) orders, the same formulas as delivery-runs. */
  const pnlOf = (ctx: PreorderCtx, r: MockRun, p: Place, manual: number | null): RunPnl => {
    const dropMode = (r.dropMode as RunDropMode | undefined) ?? 'DOORSTEP';
    const t = tallyOf(r);
    const cancelled = r.status === 'CANCELLED';
    const base = evaluate(ctx, p, dropMode, r.feeMinor, r.minOrders, r.capacityOrders ?? null, cancelled ? 0 : t.delivered, null);
    if (cancelled) {
      // Nobody drove it: no fees kept, no cost.
      const none = { orders: 0, feeRevenueMinor: 0, commissionShareMinor: null, revenueMinor: 0, estimatedCostMinor: 0, contributionMarginMinor: 0 };
      return { economics: { ...base, atOrders: none }, status: r.status as RunStatus, phase: 'CANCELLED', cancelReason: r.cancelReason ?? null, ordersRefunded: t.refunded, refundedFeesMinor: t.refundedFees, refundedFeesEstimated: false, actuals: null, platformFees: null };
    }
    const net = t.fees - t.refundedFees;
    const cost = base.cost;
    const estimated = Math.round(cost.fixedCostMinor + t.delivered * cost.perOrderCostMinor);
    const average = t.delivered === 0 ? null : net / t.delivered;
    const breakEven = average === null ? null : runBreakEven(Math.round(average), cost);
    const actuals: RunActuals = {
      ordersCarried: t.delivered,
      ordersDelivered: t.delivered,
      ordersFailed: 0,
      ordersCancelled: t.cancelled,
      ordersRefunded: t.refunded,
      feeMix: t.mix,
      deliveryFeesMinor: t.fees,
      refundedFeesMinor: t.refundedFees,
      netDeliveryFeesMinor: net,
      feesEstimated: false,
      customersPaidMinor: t.delivered * 2800,
      totalsComplete: true,
      refundsMinor: t.refunds,
      averageFeeMinor: average,
      costBasis: 'ESTIMATE',
      actualMinutes: null,
      estimatedCostMinor: estimated,
      contributionMarginMinor: net - estimated,
      breakEvenOrders: breakEven,
      breakEvenCovered: breakEven === null ? null : t.delivered >= breakEven
    };
    const atOrders = { orders: t.delivered, feeRevenueMinor: net, commissionShareMinor: null, revenueMinor: net, estimatedCostMinor: estimated, contributionMarginMinor: net - estimated };
    return {
      economics: { ...base, atOrders },
      status: r.status as RunStatus,
      phase: r.completed ? 'AFTER' : 'BEFORE',
      cancelReason: null,
      ordersRefunded: t.refunded,
      refundedFeesMinor: t.refundedFees,
      refundedFeesEstimated: false,
      actuals,
      platformFees: platformFeesOf(ctx, r, t, manual)
    };
  };
  const runOf = (ctx: PreorderCtx, id: string | undefined) => {
    const r = st(ctx).runs.find((x) => x.id === id);
    if (!r) throw notFound('No such run.');
    return r;
  };
  const advertorialOf = (ctx: PreorderCtx, id: string | undefined) => {
    const a = st(ctx).advertorials.find((x) => x.id === id);
    if (!a) throw notFound('No such advertorial.');
    return a;
  };
  const sourceInfo = (ctx: PreorderCtx, source: AdvertorialSource, sourceId: string) => {
    if (source === 'WINDOW') {
      const w = ctx.db.preorders.windows.find((x) => x.id === sourceId);
      if (!w) throw validation({ sourceId: 'No such pre-order window' });
      const p = placeOfTargets(ctx, w.targets);
      return { name: w.name, code: w.code, status: preorderStatus(w, clockOf(ctx.db)) as string, closes: w.closesAt, location: p, place: areaFromKey(w.targets[0] ?? '').city ?? w.name };
    }
    const r = st(ctx).runs.find((x) => x.id === sourceId);
    if (!r) throw validation({ sourceId: 'No such delivery run' });
    const p = placeOf(ctx, r.locationId);
    return { name: `${p?.name ?? 'Delivery'} run`, code: null, status: r.status as string, closes: r.cutoffAt, location: p, place: p?.name ?? '' };
  };
  const decorate = (ctx: PreorderCtx, a: StaffAdvertorial): StaffAdvertorial => {
    const now = clockOf(ctx.db);
    const info = (() => {
      try {
        return sourceInfo(ctx, a.source, a.sourceId);
      } catch {
        return null;
      }
    })();
    const sourceOpen = info ? info.status === 'OPEN' || info.status === 'EXTENDED' : false;
    const scheduled = (!a.startsAt || Date.parse(a.startsAt) <= now) && (!a.endsAt || Date.parse(a.endsAt) > now);
    const serving = a.status === 'LIVE' && sourceOpen && scheduled;
    return { ...a, sourceStatus: info?.status ?? a.sourceStatus, sourceCloses: info?.closes ?? a.sourceCloses, serving, can: can(ctx) };
  };
  const draftWords = (info: { name: string; place: string }) => ({
    headline: `${info.place} pre-order is open`,
    body: `Order African groceries for ${info.place} before ordering closes. ${info.name}.`,
    ctaLabel: 'Order now'
  });
  const save = (ctx: PreorderCtx, a: StaffAdvertorial, patch: Partial<StaffAdvertorial>) => {
    Object.assign(a, patch, { version: a.version + 1, updatedAt: ctx.now.toISOString() });
    return decorate(ctx, a);
  };

  /* ── run lifecycle (S260) ── */
  const RUN_FEE_RULE_MINOR = 799;
  const allowedFor = (status: string): RunAllowed => ({
    edit: ['DRAFT', 'ANNOUNCED', 'OPEN', 'EXTENDED'].includes(status),
    delete: status === 'DRAFT',
    announce: status === 'DRAFT',
    open: status === 'DRAFT' || status === 'ANNOUNCED',
    extend: ['OPEN', 'EXTENDED', 'CUT_OFF'].includes(status),
    confirm: ['OPEN', 'EXTENDED', 'CUT_OFF'].includes(status),
    cancel: ['DRAFT', 'ANNOUNCED', 'OPEN', 'EXTENDED', 'CUT_OFF', 'CONFIRMED'].includes(status)
  });
  const logOf = (s: RunsState, runId: string): RunLogEntry[] => {
    let l = s.logs.get(runId);
    if (!l) {
      l = [];
      s.logs.set(runId, l);
    }
    return l;
  };
  const record = (s: RunsState, r: MockRun, kind: RunLogEntry['kind'], actor: string, bySystem: boolean, reason: string | null, from: string, at: string, cutoffs?: [string, string]) => {
    s.seq += 1;
    logOf(s, r.id).push({
      id: `rl-${s.seq}`, runId: r.id, kind, reason, actor, bySystem, fromStatus: from, toStatus: r.status, orders: r.reservedOrders, minimum: r.minOrders,
      fromCutoff: cutoffs?.[0] ?? null, toCutoff: cutoffs?.[1] ?? null, at
    });
  };
  const place = (ctx: PreorderCtx, r: MockRun): Place | undefined => placeOf(ctx, r.locationId);
  /** The cutoff job (every minute in delivery-runs): confirms a run at its minimum, extends a short one once, else cancels it. */
  const settle = (db: MockStaffDb, s: RunsState) => {
    const now = clockOf(db);
    for (const r of s.runs) {
      if ((r.status !== 'OPEN' && r.status !== 'EXTENDED') || Date.parse(r.cutoffAt) > now) continue;
      const from = r.status;
      const at = new Date(Date.parse(r.cutoffAt)).toISOString();
      if (r.reservedOrders >= r.minOrders) {
        r.status = 'CONFIRMED';
        r.milestones = { ...r.milestones, cutOffAt: at, confirmedAt: at };
        record(s, r, 'CONFIRM', 'System', true, null, from, at);
      } else if (r.cutoffPolicy === 'EXTEND' && r.extensions === 0) {
        const before = r.cutoffAt;
        const by = r.extendDays * DAY;
        Object.assign(r, { status: 'EXTENDED', extensions: 1, cutoffAt: new Date(Date.parse(r.cutoffAt) + by).toISOString(), windowStart: new Date(Date.parse(r.windowStart) + by).toISOString(), windowEnd: new Date(Date.parse(r.windowEnd ?? r.windowStart) + by).toISOString() });
        record(s, r, 'EXTEND', 'System', true, 'MINIMUM_NOT_MET', from, at, [before, r.cutoffAt]);
      } else {
        r.status = 'CANCELLED';
        r.cancelReason = 'MINIMUM_NOT_MET';
        r.milestones = { ...r.milestones, cutOffAt: at, cancelledAt: at };
        record(s, r, 'CANCEL', 'System', true, 'MINIMUM_NOT_MET', from, at);
      }
      r.minimumMet = r.reservedOrders >= r.minOrders;
      r.version += 1;
    }
  };
  const INTERNAL_RUN_FIELDS = new Set(['orders', 'packages', 'completed']);
  const detail = (ctx: PreorderCtx, r: MockRun): StaffRunDetail => {
    const p = place(ctx, r);
    const dropMode = (r.dropMode as RunDropMode | undefined) ?? 'DOORSTEP';
    const economics = p ? evaluate(ctx, p, dropMode, r.feeMinor, r.minOrders, r.capacityOrders ?? null, r.reservedOrders, null) : null;
    const base = Object.fromEntries(Object.entries(r).filter(([k]) => !INTERNAL_RUN_FIELDS.has(k))) as StaffRun;
    return {
      ...base,
      windowEnd: r.windowEnd ?? r.windowStart,
      minimumMet: r.reservedOrders >= r.minOrders,
      reservedPackages: r.packages,
      ordersLeft: r.capacityOrders == null ? null : Math.max(0, r.capacityOrders - r.reservedOrders),
      breakEvenOrders: economics?.breakEvenOrders ?? null,
      locationName: p?.name ?? null,
      locationTimeZone: p ? 'America/Chicago' : null,
      location: p ? locationRow(p) : null,
      allowed: allowedFor(r.status),
      can: can(ctx)
    };
  };
  const NAMES = ['Amara Nwosu', 'Tunde Bello', 'Efe Okoro', 'Grace Adjei', 'Musa Bello', 'Ifeoma Okafor', 'Sara Tesfaye', 'Kofi Mensah', 'Zainab Yusuf', 'Chidi Eze', 'Ngozi Obi', 'Yaw Boateng'];
  const orderRows = (r: MockRun, status?: RunOrderStatus | null): StaffRunOrder[] => {
    const slotStatus: RunOrderStatus = r.status === 'CANCELLED' ? 'CANCELLED' : ['CONFIRMED', 'COLLECTING', 'OUT_FOR_DELIVERY', 'COMPLETED'].includes(r.status) ? 'CONFIRMED' : 'RESERVED';
    const rows = Array.from({ length: Math.min(r.orders, 80) }, (_, i): StaffRunOrder => ({
      id: `${r.id.slice(0, 24)}${String(i + 1).padStart(12, '0')}`,
      orderId: `ord-${r.id.slice(-4)}-${i + 1}`,
      orderNumber: `AT-${String(200000 + (r.id.charCodeAt(r.id.length - 1) % 9) * 1000 + i)}`,
      customerName: NAMES[i % NAMES.length] ?? 'Customer',
      packages: i % 3 === 0 ? 2 : 1,
      status: slotStatus,
      stage: r.status === 'COMPLETED' ? 'DELIVERED' : 'PENDING',
      reservedAt: r.milestones.openedAt ?? r.windowStart,
      deliveredAt: r.status === 'COMPLETED' ? (r.milestones.completedAt ?? null) : null
    }));
    return status ? rows.filter((o) => o.status === status) : rows;
  };
  const runWords = { required: 'This is required', order: 'Check the dates and numbers', tooLong: 'Too long', range: 'Outside the allowed range', whole: 'Enter a whole number' } as const;
  const fieldWords: Record<string, string> = {
    locationId: 'Choose the location the run goes to',
    windowStart: 'Choose a date and time',
    windowEnd: 'The delivery window has to end after it starts, within 24 hours',
    cutoffAt: 'Orders have to close before the delivery window starts',
    opensAt: 'The run has to open before its cutoff',
    sellerPickupAt: 'Sellers are collected between the cutoff and the end of the delivery window',
    capacityOrders: 'Capacity cannot be below the minimum orders',
    feeMinor: 'Enter an amount between 0 and 100.00 (0 is free)',
    notes: 'At most 500 characters'
  };
  const scheduleErrors = (b: Partial<RunScheduleInput>, creating: boolean): Record<string, string> =>
    Object.fromEntries(Object.entries(runScheduleProblems(b, creating)).map(([k, v]) => [k, fieldWords[k] ?? runWords[v]]));
  const reasonOf = (body: { reason?: string | null }, missing: string): string => {
    const reason = (body.reason ?? '').trim();
    if (!reason) throw validation({ reason: missing });
    if (reason.length > 500) throw validation({ reason: 'At most 500 characters' });
    return reason;
  };
  const invalidMove = (r: MockRun, to: string) => problem(409, 'delivery-runs.invalid-transition', 'Not possible now', `Cannot move from ${r.status} to ${to}`);
  const bump = (ctx: PreorderCtx, r: MockRun) => {
    r.version += 1;
    r.minimumMet = r.reservedOrders >= r.minOrders;
    r.milestones = { ...r.milestones };
    return detail(ctx, r);
  };

  return [
    /* ── economics settings (the driver cost model) ── */
    http.get(`${W}/economics-settings`, route((ctx) => {
      view(ctx);
      const s = st(ctx).settings;
      return withEtag({ ...s, can: can(ctx) });
    })),
    http.put(`${W}/economics-settings`, route(async (ctx) => {
      manage(ctx);
      const b = await json(ctx.request, NumbersBody);
      const problems = economicsInputProblems(b);
      const words = { required: 'Enter a number', range: 'Outside the allowed range', whole: 'Enter whole minor units', tooLong: 'At most 500 characters' } as const;
      const errors: Record<string, string> = {};
      Object.entries(problems).forEach(([k, v]) => {
        errors[k] = k === 'reason' && v === 'required' ? 'Say why: the reason is logged with your name' : words[v as keyof typeof words];
      });
      if (Object.keys(errors).length) throw validation(errors);
      const s = st(ctx);
      ifMatch(ctx, s.settings.version);
      const { reason, platformFeePerOrderMinor, ...rest } = b;
      const numbers: EconomicsNumbers = { ...rest, platformFeePerOrderMinor: platformFeePerOrderMinor ?? 0 };
      const before: EconomicsNumbers = { ...s.settings };
      const same = (Object.keys(numbers) as (keyof EconomicsNumbers)[]).every((k) => numbers[k] === before[k]);
      if (same) return withEtag({ ...s.settings, can: can(ctx) });
      s.settings = { ...numbers, version: s.settings.version + 1, source: 'STAFF', updatedAt: ctx.now.toISOString(), updatedBy: ctx.action.actor, reason: reason.trim(), can: can(ctx) };
      s.changes.unshift({ id: `ec-${s.settings.version}`, version: s.settings.version, before: pickNumbers(before), after: pickNumbers(numbers), actor: ctx.action.actor, reason: reason.trim(), at: ctx.now.toISOString() });
      audit(ctx, 'economics.changed', 'economics-settings', `Set the run cost model (version ${s.settings.version}): ${reason.trim()}`);
      return withEtag(s.settings);
    })),
    http.get(`${W}/economics-settings/changes`, route((ctx) => {
      view(ctx);
      const limit = Math.min(Math.max(Number(new URL(ctx.request.url).searchParams.get('limit') ?? 50) || 50, 1), 200);
      return { items: st(ctx).changes.slice(0, limit) };
    })),

    /* ── break-even and P&L ── */
    http.post(`${W}/preorders/economics-preview`, route(async (ctx): Promise<WindowPlan> => {
      view(ctx);
      const b = await json(ctx.request, WindowPlanBody);
      if (!b.locationId && !b.targets?.length) throw validation({ targets: 'Choose where the pre-order delivers' });
      const p = placeOf(ctx, b.locationId) ?? placeOfTargets(ctx, b.targets);
      const door = b.door !== false;
      const dropMode: RunDropMode = door ? 'DOORSTEP' : 'PICKUP_POINT';
      const fee = (door ? b.doorFeeMinor : b.pickupFeeMinor) ?? 799;
      const minOrders = b.minOrders ?? 12;
      // The orders the run can carry: asked for, else the location's default capacity (null = not limited).
      const capacity = b.capacityOrders ?? (p ? (p.details?.defaultCapacityOrders === undefined ? 40 : p.details.defaultCapacityOrders) : null);
      const economics = p ? evaluate(ctx, p, dropMode, fee, minOrders, capacity, b.expectedOrders ?? minOrders, null) : null;
      return {
        locationId: p?.id ?? null,
        locationName: p?.name ?? null,
        currency: 'USD',
        feeMinor: fee,
        dropMode,
        minOrders,
        economics,
        minimumCoversBreakEven: economics?.breakEvenOrders == null || minOrders < 1 ? null : minOrders >= economics.breakEvenOrders,
        capacityOrders: economics ? capacity : null,
        can: can(ctx)
      };
    })),
    http.get(`${W}/preorders/:id/economics`, route((ctx, params): WindowEconomics => {
      view(ctx);
      const pre = ctx.db.preorders;
      const w = pre.windows.find((x) => x.id === params.id);
      if (!w) throw notFound('No such pre-order.');
      const cancelled = w.outcome === 'CANCELLED';
      const all = ordersOf(pre, w);
      const counted = cancelled ? [] : all.filter((o) => o.status !== 'CANCELLED');
      const refunded = cancelled ? all.filter((o) => o.refundStatus !== null) : [];
      const p = placeOfTargets(ctx, w.targets);
      const door = counted.filter((o) => o.fulfilment === 'DOOR').length;
      const fee = w.doorFeeOverride ?? 799;
      const economics = p && !cancelled ? evaluate(ctx, p, w.door ? 'DOORSTEP' : 'PICKUP_POINT', fee, w.minOrders, null, counted.length, null) : null;
      return {
        windowId: w.id,
        code: w.code,
        name: w.name,
        status: preorderStatus(w, clockOf(ctx.db)),
        cancelled,
        locationId: p?.id ?? null,
        locationName: p?.name ?? null,
        currency: 'USD',
        minOrders: w.minOrders,
        orders: counted.length,
        revenueMinor: counted.reduce((a, o) => a + o.total, 0),
        deliveryFeesMinor: door * fee,
        ordersRefunded: refunded.length,
        refundsSettled: refunded.filter((o) => o.refundStatus === 'REFUNDED' || o.refundStatus === 'VOIDED').length,
        refundedMinor: refunded.reduce((a, o) => a + o.total, 0),
        economics,
        minimumCoversBreakEven: economics?.breakEvenOrders == null ? null : w.minOrders >= economics.breakEvenOrders,
        platformFees: cancelled
          ? null
          : st(ctx).settings.platformFeePerOrderMinor === 0
            ? { source: 'NONE', commissionMinor: null, orders: counted.length, ordersReported: 0, perOrderEstimateMinor: 0 }
            : { source: 'ESTIMATE', commissionMinor: counted.length * st(ctx).settings.platformFeePerOrderMinor, orders: counted.length, ordersReported: 0, perOrderEstimateMinor: st(ctx).settings.platformFeePerOrderMinor },
        can: can(ctx)
      };
    })),
    http.post(`${W}/delivery-runs/economics-preview`, route(async (ctx): Promise<RunEconomics> => {
      view(ctx);
      const b = await json(ctx.request, RunPlanBody);
      const p = placeOf(ctx, b.locationId);
      if (!p) throw notFound('No such location.');
      const min = b.minOrders ?? 12;
      return evaluate(ctx, p, b.dropMode ?? 'DOORSTEP', b.feeMinor ?? 799, min, b.capacityOrders ?? null, b.expectedOrders ?? min, b.commissionShareMinor ?? null);
    })),
    http.get(`${W}/delivery-runs/runs/:id/pnl`, route((ctx, params): RunPnl => {
      view(ctx);
      const r = runOf(ctx, params.id);
      const p = placeOf(ctx, r.locationId);
      if (!p) throw notFound('No such location.');
      const commission = new URL(ctx.request.url).searchParams.get('commissionShareMinor');
      return pnlOf(ctx, r, p, commission === null ? null : Number(commission));
    })),

    /* ── locations and runs ── */
    http.get(`${W}/delivery-runs/locations`, route((ctx) => {
      view(ctx);
      return { items: st(ctx).places.map(locationRow), can: can(ctx) };
    })),
    http.post(`${W}/delivery-runs/locations`, route(async (ctx) => {
      manage(ctx);
      const b = await json(ctx.request, LocationBody);
      const s = st(ctx);
      const input = locationInput(b);
      const errors = locationErrors(input);
      const slug = (b.slug?.trim() || slugOf(input.name, input.state)).toLowerCase();
      if (!/^[a-z0-9]+(-[a-z0-9]+)*$/.test(slug)) errors.slug = 'Use lower-case letters, digits and hyphens, such as bastrop-tx';
      if (Object.keys(errors).length) throw validation(errors);
      if (s.places.some((p) => p.slug === slug)) throw problem(409, 'delivery-runs.slug-taken', 'Already exists', 'A location with this web address already exists');
      s.seq += 1;
      const place: Place = {
        id: `4b2f9d3a-0000-4000-8000-${String(s.seq).padStart(12, '0')}`,
        slug,
        name: input.name.trim(),
        key: `${input.market.trim().toUpperCase()}/${(input.state ?? '').trim().toUpperCase()}/${input.name.trim()}`,
        area: areaOf(input),
        roadMiles: 60,
        driveMinutes: 75,
        growth: [],
        channels: { EMAIL: 0, SMS: 0, PUSH: 0, WHATSAPP: 0 },
        details: detailsOf(input),
        active: b.active !== false
      };
      s.places.push(place);
      audit(ctx, 'location.created', place.id, `Added the delivery location ${place.name}`);
      return { ...locationRow(place), can: can(ctx) };
    }, 201)),
    http.put(`${W}/delivery-runs/locations/:id`, route(async (ctx, params) => {
      manage(ctx);
      const place = placeOf(ctx, params.id);
      if (!place) throw notFound('No such location.');
      const b = await json(ctx.request, LocationBody);
      const input = locationInput(b);
      const errors = locationErrors(input);
      if (Object.keys(errors).length) throw validation(errors);
      Object.assign(place, { name: input.name.trim(), area: areaOf(input), details: detailsOf(input) });
      audit(ctx, 'location.updated', place.id, `Changed the delivery location ${place.name}`);
      return { ...locationRow(place), can: can(ctx) };
    })),
    http.post(`${W}/delivery-runs/locations/:id/deactivate`, route(async (ctx, params) => {
      manage(ctx);
      const place = placeOf(ctx, params.id);
      if (!place) throw notFound('No such location.');
      const reason = reasonOf(await json(ctx.request, ReasonBody), 'Say why: the reason is logged with your name');
      if (place.active === false) return { ...locationRow(place), can: can(ctx) };
      if (st(ctx).runs.some((r) => r.locationId === place.id && LIVE_RUN_STATUSES.includes(r.status))) {
        throw problem(409, 'delivery-runs.location-has-live-runs', 'Not possible now', 'A run of this location is announced, takes orders or is on the road. Cancel or finish it first.');
      }
      place.active = false;
      audit(ctx, 'location.deactivated', place.id, `Switched off the delivery location ${place.name}: ${reason}`);
      return { ...locationRow(place), can: can(ctx) };
    })),
    http.post(`${W}/delivery-runs/locations/:id/activate`, route(async (ctx, params) => {
      manage(ctx);
      const place = placeOf(ctx, params.id);
      if (!place) throw notFound('No such location.');
      const body = await json(ctx.request, ReasonBody);
      const reason = (body.reason ?? '').trim();
      if (reason.length > 500) throw validation({ reason: 'At most 500 characters' });
      if (place.active === false) {
        place.active = true;
        audit(ctx, 'location.activated', place.id, `Switched on the delivery location ${place.name}${reason ? `: ${reason}` : ''}`);
      }
      return { ...locationRow(place), can: can(ctx) };
    })),
    http.get(`${W}/delivery-runs/locations/:id/history`, route((ctx, params) => {
      view(ctx);
      const p = placeOf(ctx, params.id);
      if (!p) throw notFound('No such location.');
      const runs = st(ctx).runs.filter((r) => r.locationId === p.id).sort((a, b) => b.windowStart.localeCompare(a.windowStart));
      const rows: RunHistoryRow[] = runs.map((r) => {
        const pnl = pnlOf(ctx, r, p, null);
        const at = pnl.economics.atOrders;
        return {
          id: r.id, status: r.status, windowStart: r.windowStart, cutoffAt: r.cutoffAt, orders: r.orders, packages: r.packages, feeMinor: r.feeMinor,
          feeRevenueMinor: at.feeRevenueMinor, ordersRefunded: pnl.ordersRefunded, minOrders: r.minOrders, minimumMet: r.minimumMet ?? r.orders >= r.minOrders,
          ordersDelivered: pnl.actuals?.ordersDelivered ?? 0, ordersCancelled: pnl.actuals?.ordersCancelled ?? 0, refundedFeesMinor: pnl.refundedFeesMinor, feesEstimated: pnl.refundedFeesEstimated,
          phase: pnl.phase, costMinor: at.estimatedCostMinor, marginMinor: at.contributionMarginMinor, breakEvenOrders: pnl.economics.breakEvenOrders,
          minimumCoveredBreakEven: pnl.economics.breakEvenOrders === null ? null : r.minOrders >= pnl.economics.breakEvenOrders
        };
      });
      const windows: WindowHistoryRow[] = ctx.db.preorders.windows
        .filter((w) => placeOfTargets(ctx, w.targets)?.id === p.id && preorderStatus(w, clockOf(ctx.db)) !== 'DRAFT')
        .map((w) => {
          const cancelled = w.outcome === 'CANCELLED';
          const all = ordersOf(ctx.db.preorders, w);
          const counted = cancelled ? [] : all.filter((o) => o.status !== 'CANCELLED');
          const refunded = cancelled ? all.filter((o) => o.refundStatus !== null) : [];
          const fee = w.doorFeeOverride ?? 799;
          return {
            id: w.id, code: w.code, name: w.name, status: preorderStatus(w, clockOf(ctx.db)), deliversOn: w.deliversOn, currency: 'USD', orders: counted.length,
            deliveryFeesMinor: counted.filter((o) => o.fulfilment === 'DOOR').length * fee, revenueMinor: counted.reduce((a, o) => a + o.total, 0), ordersRefunded: refunded.length,
            refundedMinor: refunded.reduce((a, o) => a + o.total, 0), minOrders: w.minOrders, minimumMet: w.minOrders < 1 ? null : (cancelled ? all.length : counted.length) >= w.minOrders
          };
        });
      const last = p.growth[p.growth.length - 1]?.[1] ?? 0;
      return {
        location: locationRow(p),
        runs: rows,
        windows,
        subscribers: { locationId: p.id, total: last + 9, active: last, pending: 9, byChannel: p.channels },
        subscriberGrowth: p.growth.map(([month, count]) => ({ month, count })),
        // A tenth of each month's sign-ups leave in the same month (rounded down): net = sign-ups - unsubscribes.
        subscriberNetGrowth: p.growth.map(([month, count]) => ({ month, signUps: count, unsubscribes: Math.floor(count / 10), net: count - Math.floor(count / 10) }))
      };
    })),
    http.get(`${W}/delivery-runs/runs`, route((ctx) => {
      view(ctx);
      const q = new URL(ctx.request.url).searchParams;
      const status = q.get('status')?.split(',') ?? null;
      const limit = Math.min(Math.max(Number(q.get('limit') ?? 100) || 100, 1), 500);
      const items = st(ctx).runs
        .filter((r) => (!q.get('locationId') || r.locationId === q.get('locationId')) && (!status || status.includes(r.status)))
        .slice(0, limit)
        .map((r) => ({ ...detail(ctx, r), location: undefined, allowed: undefined, can: undefined }));
      return { items };
    })),
    /* ── run lifecycle (S260) ── */
    http.post(`${W}/delivery-runs/runs`, route(async (ctx) => {
      manage(ctx);
      const b = await json(ctx.request, RunScheduleBody);
      const errors = scheduleErrors(b, true);
      const p = placeOf(ctx, b.locationId);
      if (b.locationId && !p) errors.locationId = 'Choose one of the locations';
      if (p && p.active === false) throw problem(422, 'delivery-runs.location-inactive', 'Not possible', 'The location is not active');
      if (!errors.cutoffAt && b.cutoffAt && Date.parse(b.cutoffAt) <= clockOf(ctx.db)) errors.cutoffAt = 'The cutoff must be in the future';
      if (Object.keys(errors).length || !p) throw validation(errors);
      const s = st(ctx);
      s.seq += 1;
      const orders = 0;
      const run: MockRun = {
        id: `7e5c2a6d-0000-4000-8000-${String(s.seq).padStart(12, '0')}`,
        locationId: p.id,
        status: 'DRAFT',
        windowStart: new Date(b.windowStart as string).toISOString(),
        windowEnd: new Date(b.windowEnd as string).toISOString(),
        cutoffAt: new Date(b.cutoffAt as string).toISOString(),
        originalCutoffAt: new Date(b.cutoffAt as string).toISOString(),
        opensAt: b.opensAt ? new Date(b.opensAt).toISOString() : null,
        sellerPickupAt: b.sellerPickupAt ? new Date(b.sellerPickupAt).toISOString() : null,
        feeMinor: b.feeMinor ?? RUN_FEE_RULE_MINOR,
        currency: 'USD',
        minOrders: b.minOrders ?? 12,
        capacityOrders: b.capacityOrders ?? 40,
        capacityPackages: b.capacityPackages ?? null,
        dropMode: b.dropMode ?? 'DOORSTEP',
        cutoffPolicy: b.cutoffPolicy ?? 'EXTEND',
        extendDays: b.extendDays ?? 7,
        notes: b.notes?.trim() ? b.notes.trim() : null,
        extensions: 0,
        reservedOrders: orders,
        minimumMet: false,
        cancelReason: null,
        orders,
        packages: 0,
        completed: false,
        milestones: {},
        version: 0
      };
      s.runs.unshift(run);
      audit(ctx, 'run.created', run.id, `Scheduled a run for ${p.name}`);
      return detail(ctx, run);
    }, 201)),
    http.get(`${W}/delivery-runs/runs/:id`, route((ctx, params) => {
      view(ctx);
      return detail(ctx, runOf(ctx, params.id));
    })),
    http.put(`${W}/delivery-runs/runs/:id`, route(async (ctx, params) => {
      manage(ctx);
      const r = runOf(ctx, params.id);
      const b = await json(ctx.request, RunScheduleBody);
      const errors = scheduleErrors(b, false);
      if (b.locationId && b.locationId !== r.locationId) errors.locationId = 'A run cannot move to another location';
      if (!['DRAFT', 'ANNOUNCED', 'OPEN', 'EXTENDED'].includes(r.status)) throw problem(422, 'delivery-runs.run-not-editable', 'Not editable', `A ${r.status} run cannot be edited`);
      if (Object.keys(errors).length) throw validation(errors);
      const open = r.status === 'OPEN' || r.status === 'EXTENDED';
      const cutoff = new Date(b.cutoffAt as string).toISOString();
      const fee = b.feeMinor ?? RUN_FEE_RULE_MINOR;
      if (open && (fee !== r.feeMinor || cutoff !== r.cutoffAt || (b.dropMode ?? r.dropMode) !== r.dropMode)) {
        throw problem(422, 'delivery-runs.run-not-editable', 'Not editable', 'Fee, currency, cutoff and drop mode are fixed once a run is open (extend to move the cutoff)');
      }
      const capacity = b.capacityOrders ?? r.capacityOrders ?? null;
      if (capacity !== null && capacity < r.reservedOrders) throw validation({ capacityOrders: 'Capacity cannot drop below the orders already on the run' });
      Object.assign(r, {
        windowStart: new Date(b.windowStart as string).toISOString(),
        windowEnd: new Date(b.windowEnd as string).toISOString(),
        cutoffAt: cutoff,
        originalCutoffAt: open ? r.originalCutoffAt : cutoff,
        opensAt: b.opensAt ? new Date(b.opensAt).toISOString() : null,
        sellerPickupAt: b.sellerPickupAt ? new Date(b.sellerPickupAt).toISOString() : null,
        feeMinor: fee,
        minOrders: b.minOrders ?? r.minOrders,
        capacityOrders: capacity,
        capacityPackages: b.capacityPackages ?? r.capacityPackages,
        dropMode: b.dropMode ?? r.dropMode,
        cutoffPolicy: b.cutoffPolicy ?? r.cutoffPolicy,
        extendDays: b.extendDays ?? r.extendDays,
        notes: b.notes?.trim() ? b.notes.trim() : null
      });
      audit(ctx, 'run.updated', r.id, `Changed the run of ${place(ctx, r)?.name ?? 'a location'}`);
      return bump(ctx, r);
    })),
    http.delete(`${W}/delivery-runs/runs/:id`, route((ctx, params) => {
      manage(ctx);
      const r = runOf(ctx, params.id);
      if (r.status !== 'DRAFT') throw problem(422, 'delivery-runs.run-not-draft', 'Not a draft', 'Only draft runs can be deleted; cancel instead');
      const s = st(ctx);
      s.runs = s.runs.filter((x) => x.id !== r.id);
      audit(ctx, 'run.deleted', r.id, `Deleted a draft run of ${place(ctx, r)?.name ?? 'a location'}`);
      return new HttpResponse(null, { status: 204 });
    })),
    http.post(`${W}/delivery-runs/runs/:id/announce`, route((ctx, params) => {
      manage(ctx);
      const r = runOf(ctx, params.id);
      if (r.status !== 'DRAFT') throw invalidMove(r, 'ANNOUNCED');
      r.status = 'ANNOUNCED';
      r.milestones = { ...r.milestones, announcedAt: ctx.now.toISOString() };
      audit(ctx, 'run.announced', r.id, `Announced the run of ${place(ctx, r)?.name ?? 'a location'}`);
      return bump(ctx, r);
    })),
    http.post(`${W}/delivery-runs/runs/:id/open`, route((ctx, params) => {
      manage(ctx);
      const r = runOf(ctx, params.id);
      if (r.status !== 'DRAFT' && r.status !== 'ANNOUNCED') throw invalidMove(r, 'OPEN');
      if (Date.parse(r.cutoffAt) <= clockOf(ctx.db)) throw problem(422, 'delivery-runs.cutoff-passed', 'Cutoff passed', 'The cutoff has passed; extend the run instead');
      r.status = 'OPEN';
      r.milestones = { ...r.milestones, announcedAt: r.milestones.announcedAt ?? ctx.now.toISOString(), openedAt: ctx.now.toISOString() };
      audit(ctx, 'run.opened', r.id, `Opened the run of ${place(ctx, r)?.name ?? 'a location'} for orders`);
      return bump(ctx, r);
    })),
    http.post(`${W}/delivery-runs/runs/:id/extend`, route(async (ctx, params) => {
      manage(ctx);
      const r = runOf(ctx, params.id);
      const b = await json(ctx.request, RunExtendBody);
      const errors: Record<string, string> = {};
      const cutoff = b.cutoffAt ? Date.parse(b.cutoffAt) : Number.NaN;
      if (Number.isNaN(cutoff)) errors.cutoffAt = 'Choose a date and time';
      const reason = (b.reason ?? '').trim();
      if (!reason) errors.reason = 'Say why: the reason is logged with your name';
      else if (reason.length > 500) errors.reason = 'At most 500 characters';
      if (Object.keys(errors).length) throw validation(errors);
      if (!allowedFor(r.status).extend) throw invalidMove(r, 'EXTENDED');
      if (cutoff <= clockOf(ctx.db) || cutoff <= Date.parse(r.cutoffAt)) throw problem(422, 'delivery-runs.invalid-extension', 'Not possible', 'The new cutoff must be in the future and later than the current one');
      const by = cutoff - Date.parse(r.cutoffAt);
      const before = r.cutoffAt;
      const from = r.status;
      Object.assign(r, {
        status: 'EXTENDED',
        extensions: r.extensions + 1,
        cutoffAt: new Date(cutoff).toISOString(),
        windowStart: b.windowStart && b.windowEnd ? new Date(b.windowStart).toISOString() : new Date(Date.parse(r.windowStart) + by).toISOString(),
        windowEnd: b.windowStart && b.windowEnd ? new Date(b.windowEnd).toISOString() : new Date(Date.parse(r.windowEnd ?? r.windowStart) + by).toISOString(),
        sellerPickupAt: b.sellerPickupAt ? new Date(b.sellerPickupAt).toISOString() : r.sellerPickupAt ? new Date(Date.parse(r.sellerPickupAt) + by).toISOString() : null
      });
      record(st(ctx), r, 'EXTEND', ctx.action.actor, false, reason, from, ctx.now.toISOString(), [before, r.cutoffAt]);
      audit(ctx, 'run.extended', r.id, `Moved the cutoff of the run of ${place(ctx, r)?.name ?? 'a location'}: ${reason}`);
      return bump(ctx, r);
    })),
    http.post(`${W}/delivery-runs/runs/:id/confirm`, route(async (ctx, params) => {
      manage(ctx);
      const r = runOf(ctx, params.id);
      const b = await json(ctx.request, ReasonBody);
      if (!allowedFor(r.status).confirm) throw invalidMove(r, 'CONFIRMED');
      const below = r.reservedOrders < r.minOrders;
      const reason = (b.reason ?? '').trim();
      if (below && !reason) throw problem(422, 'delivery-runs.reason-required', 'A reason is required', 'Confirming a run below its minimum needs a reason');
      if (reason.length > 500) throw validation({ reason: 'At most 500 characters' });
      const from = r.status;
      r.status = 'CONFIRMED';
      r.milestones = { ...r.milestones, cutOffAt: ctx.now.toISOString(), confirmedAt: ctx.now.toISOString() };
      record(st(ctx), r, below ? 'CONFIRM_OVERRIDE' : 'CONFIRM', ctx.action.actor, false, reason || null, from, ctx.now.toISOString());
      audit(ctx, 'run.confirmed', r.id, `Confirmed the run of ${place(ctx, r)?.name ?? 'a location'}${below ? ` below its minimum: ${reason}` : ''}`);
      return bump(ctx, r);
    })),
    http.post(`${W}/delivery-runs/runs/:id/cancel`, route(async (ctx, params) => {
      manage(ctx);
      const r = runOf(ctx, params.id);
      const b = await json(ctx.request, ReasonBody);
      const reason = reasonOf(b, 'Say why: the reason is logged with your name and shown to the customers');
      if (!allowedFor(r.status).cancel) throw invalidMove(r, 'CANCELLED');
      const from = r.status;
      r.status = 'CANCELLED';
      r.cancelReason = reason;
      r.milestones = { ...r.milestones, cancelledAt: ctx.now.toISOString() };
      record(st(ctx), r, 'CANCEL', ctx.action.actor, false, reason, from, ctx.now.toISOString());
      audit(ctx, 'run.cancelled', r.id, `Cancelled the run of ${place(ctx, r)?.name ?? 'a location'} and refunded ${r.orders} orders: ${reason}`);
      return bump(ctx, r);
    })),
    http.get(`${W}/delivery-runs/runs/:id/log`, route((ctx, params) => {
      view(ctx);
      const r = runOf(ctx, params.id);
      return { items: logOf(st(ctx), r.id) };
    })),
    http.get(`${W}/delivery-runs/runs/:id/orders`, route((ctx, params) => {
      view(ctx);
      const r = runOf(ctx, params.id);
      const status = new URL(ctx.request.url).searchParams.get('status');
      if (status && !['RESERVED', 'CONFIRMED', 'RELEASED', 'CANCELLED'].includes(status)) throw validation({ status: 'Use RESERVED, CONFIRMED, RELEASED or CANCELLED' });
      return { items: orderRows(r, status as RunOrderStatus | null) };
    })),


    /* ── advertorials ── */
    http.get(`${W}/featured-products/search`, route((ctx) => {
      view(ctx);
      const q = (new URL(ctx.request.url).searchParams.get('q') ?? '').trim();
      if (q.length < 2 || q.length > 120) throw validation({ q: 'Type 2 to 120 characters' });
      const needle = q.toLowerCase();
      return { items: FEATURE_CATALOGUE.filter((p) => p.title.toLowerCase().includes(needle) || (p.sellerName ?? '').toLowerCase().includes(needle)).slice(0, 8) };
    })),
    http.get(`${W}/delivery-runs/locations/:id/attribution`, route((ctx, params) => {
      view(ctx);
      const p = placeOf(ctx, params.id);
      if (!p) throw notFound('No such location.');
      const q = new URL(ctx.request.url).searchParams;
      const runId = q.get('runId');
      const days = q.get('days');
      if (runId && !st(ctx).runs.some((r) => r.id === runId && r.locationId === p.id)) throw validation({ runId: 'Choose one of the runs' });
      if (days !== null && !(Number.isInteger(Number(days)) && Number(days) >= 1 && Number(days) <= 366)) throw validation({ days: 'Between 1 and 366' });
      const rows = attributionRows(p, runId);
      return {
        locationId: p.id,
        runId,
        since: new Date(clockOf(ctx.db) - Number(days ?? 90) * DAY).toISOString().slice(0, 10),
        visits: rows.reduce((n, r) => n + r.visits, 0),
        signups: rows.reduce((n, r) => n + r.signups, 0),
        rows
      };
    })),
    http.get(`${W}/advertorials`, route((ctx) => {
      view(ctx);
      const q = new URL(ctx.request.url).searchParams;
      const items = st(ctx).advertorials
        .filter((a) => (!q.get('source') || a.source === q.get('source')) && (!q.get('sourceId') || a.sourceId === q.get('sourceId')) && (!q.get('status') || a.status === q.get('status')))
        .map((a) => decorate(ctx, a));
      return { items, can: can(ctx) };
    })),
    http.post(`${W}/advertorials/generate`, route(async (ctx) => {
      manage(ctx);
      const b = await json(ctx.request, GenerateBody);
      const s = st(ctx);
      const info = sourceInfo(ctx, b.source, b.sourceId);
      if (info.status === 'CANCELLED') throw problem(409, 'delivery-runs.invalid-transition', 'Not possible now', 'A cancelled pre-order or run has no advertorial.');
      const existing = s.advertorials.find((a) => a.source === b.source && a.sourceId === b.sourceId && a.generated);
      if (existing) {
        const words = draftWords(info);
        return withEtag(existing.status === 'ENDED' ? decorate(ctx, existing) : save(ctx, existing, { ...words, generated: true }));
      }
      s.seq += 1;
      const a: StaffAdvertorial = {
        id: `5c3a0e4b-0000-4000-8000-${String(s.seq).padStart(12, '0')}`, source: b.source, sourceId: b.sourceId, sourceName: info.name, sourceCode: info.code, sourceStatus: info.status,
        sourceCloses: info.closes, locationId: info.location?.id ?? null, locationName: info.location?.name ?? null, variant: nextVariant(s.advertorials, b.source, b.sourceId), weight: 1, priority: 0,
        generated: true, ...draftWords(info), startsAt: null, endsAt: null, status: 'LIVE', ended: null, serving: false, results: { views: 0, clicks: 0, ctrPct: null, orders: 0, cancelledOrders: 0 }, featuredProducts: [],
        createdBy: ctx.action.actor, createdAt: ctx.now.toISOString(), updatedAt: ctx.now.toISOString(), version: 0
      };
      s.advertorials.unshift(a);
      audit(ctx, 'advertorial.generated', a.id, `Generated the advertorial of ${info.name}`);
      return withEtag(decorate(ctx, a));
    })),
    http.post(`${W}/advertorials`, route(async (ctx) => {
      manage(ctx);
      const b = await json(ctx.request, AdvertorialBody);
      const errors = problemWords(advertorialInputProblems(b as AdvertorialInput, true));
      if (!b.source) errors.source = 'Choose a window or a run';
      if (Object.keys(errors).length) throw validation(errors);
      const s = st(ctx);
      const info = sourceInfo(ctx, b.source as AdvertorialSource, b.sourceId as string);
      s.seq += 1;
      const a: StaffAdvertorial = {
        id: `5c3a0e4b-0000-4000-8000-${String(s.seq).padStart(12, '0')}`, source: b.source as AdvertorialSource, sourceId: b.sourceId as string, sourceName: info.name, sourceCode: info.code,
        sourceStatus: info.status, sourceCloses: info.closes, locationId: info.location?.id ?? null, locationName: info.location?.name ?? null,
        variant: b.variant?.trim() || nextVariant(s.advertorials, b.source as AdvertorialSource, b.sourceId as string), weight: b.weight ?? 1, priority: b.priority ?? 0, generated: false,
        headline: b.headline.trim(), body: (b.body ?? '').trim(), ctaLabel: b.ctaLabel.trim(), startsAt: b.startsAt ?? null, endsAt: b.endsAt ?? null, status: 'DRAFT', ended: null, serving: false,
        results: { views: 0, clicks: 0, ctrPct: null, orders: 0, cancelledOrders: 0 }, featuredProducts: featured(b.featuredProducts), createdBy: ctx.action.actor, createdAt: ctx.now.toISOString(), updatedAt: ctx.now.toISOString(), version: 0
      };
      s.advertorials.unshift(a);
      audit(ctx, 'advertorial.created', a.id, `Wrote variant ${a.variant} for ${info.name}`);
      return withEtag(decorate(ctx, a), 201);
    })),
    http.get(`${W}/advertorials/:id`, route((ctx, params) => {
      view(ctx);
      return withEtag(decorate(ctx, advertorialOf(ctx, params.id)));
    })),
    http.put(`${W}/advertorials/:id`, route(async (ctx, params) => {
      manage(ctx);
      const a = advertorialOf(ctx, params.id);
      const b = await json(ctx.request, AdvertorialBody);
      const errors = problemWords(advertorialInputProblems(b as AdvertorialInput, false));
      if (Object.keys(errors).length) throw validation(errors);
      if (a.status === 'ENDED') throw problem(422, 'delivery-runs.invalid-transition', 'Already ended', 'An ended advertorial cannot be changed.');
      ifMatch(ctx, a.version);
      audit(ctx, 'advertorial.updated', a.id, `Edited variant ${a.variant} of ${a.sourceName ?? a.sourceId}`);
      return withEtag(save(ctx, a, {
        headline: b.headline.trim(), body: (b.body ?? '').trim(), ctaLabel: b.ctaLabel.trim(), variant: b.variant?.trim() || a.variant, weight: b.weight ?? a.weight, priority: b.priority ?? a.priority,
        startsAt: b.startsAt ?? null, endsAt: b.endsAt ?? null, generated: false,
        ...(b.featuredProducts ? { featuredProducts: featured(b.featuredProducts) } : {})
      }));
    })),
    http.post(`${W}/advertorials/:id/:action`, route((ctx, params) => {
      manage(ctx);
      const action = String(params.action) as AdvertorialAction;
      if (!['publish', 'pause', 'end'].includes(action)) throw notFound('No such action.');
      const a = advertorialOf(ctx, params.id);
      ifMatch(ctx, a.version);
      if (!advertorialActions(a.status).includes(action)) throw problem(422, 'delivery-runs.invalid-transition', 'Not possible now', `An advertorial that is ${a.status.toLowerCase()} cannot be ${action === 'end' ? 'ended' : `${action}ed`}.`);
      audit(ctx, `advertorial.${action}`, a.id, `${action} variant ${a.variant} of ${a.sourceName ?? a.sourceId}`);
      return withEtag(save(ctx, a, action === 'publish' ? { status: 'LIVE', ended: null } : action === 'pause' ? { status: 'PAUSED' } : { status: 'ENDED', ended: 'STAFF' }));
    }))
  ];
}

function pickNumbers(n: EconomicsNumbers): EconomicsNumbers {
  return {
    driverCostPerHourMinor: n.driverCostPerHourMinor,
    driverCostPerMileMinor: n.driverCostPerMileMinor,
    collectionMinutes: n.collectionMinutes,
    perStopMinutes: n.perStopMinutes,
    pickupPointStopMinutes: n.pickupPointStopMinutes,
    handoverMinutesPerOrder: n.handoverMinutesPerOrder,
    roundTrip: n.roundTrip,
    platformFeePerOrderMinor: n.platformFeePerOrderMinor
  };
}

const nextVariant = (list: StaffAdvertorial[], source: string, sourceId: string): string => {
  const used = new Set(list.filter((a) => a.source === source && a.sourceId === sourceId).map((a) => a.variant));
  return 'ABCDEFGH'.split('').find((v) => !used.has(v)) ?? 'Z';
};

function problemWords(p: Record<string, string>): Record<string, string> {
  const words: Record<string, string> = { required: 'This is required', tooLong: 'Too long', range: 'Outside the allowed range', order: 'The end must be after the start' };
  return Object.fromEntries(Object.entries(p).map(([k, v]) => [k, words[v] ?? v]));
}

/** Test hook: the mock database's runs state (the same object the handlers use). */
export const mockRunsState = (): RunsState => stateOf(getStaffDb());

/** Products the editor can find (the search index in production). */
const FEATURE_CATALOGUE: { atin: string; title: string; imageUrl?: string; sellerName?: string }[] = [
  { atin: 'ATSHEA0001', title: 'Shea butter, raw', sellerName: 'Accra Naturals' },
  { atin: 'ATGARI0001', title: 'Garri, white', sellerName: 'AfroTransact Retail' },
  { atin: 'ATEGUS0001', title: 'Egusi, ground', sellerName: 'Mama Tee Foods' },
  { atin: 'ATPALM0001', title: 'Palm oil, 1 litre', sellerName: 'Mama Tee Foods' },
  { atin: 'ATJOLL0001', title: 'Jollof spice box', sellerName: 'AfroTransact Retail' },
  { atin: 'ATPLAN0001', title: 'Plantain chips', sellerName: 'Lagos Snacks' }
];

const featured = (list: { atin: string; title: string; imageUrl?: string | null }[] | undefined) =>
  (list ?? []).map((p) => ({ atin: p.atin.trim(), title: p.title.trim(), ...(p.imageUrl ? { imageUrl: p.imageUrl } : {}) }));

/** Where visits and sign-ups of a town came from (seeded; a run's link carries a share of them). */
function attributionRows(p: Place, runId: string | null) {
  const base = [
    { utmSource: 'whatsapp', utmMedium: 'social', utmCampaign: `delivery-run-${p.slug}-20261024`, visits: 140, signups: 23 },
    { utmSource: 'instagram', utmMedium: 'social', utmCampaign: '', visits: 64, signups: 7 },
    { utmSource: 'email', utmMedium: 'newsletter', utmCampaign: 'weekly-run', visits: 41, signups: 12 }
  ];
  return runId ? base.slice(0, 2).map((r) => ({ ...r, visits: Math.round(r.visits / 2), signups: Math.round(r.signups / 2) })) : base;
}
