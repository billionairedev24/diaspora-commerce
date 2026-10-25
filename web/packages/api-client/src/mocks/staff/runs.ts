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
  runBreakEven,
  runCostModel,
  type RunDropMode,
  type RunEconomics,
  type RunHistoryRow,
  type RunPnl,
  runSnapshot,
  type RunStatus,
  type StaffAdvertorial,
  type StaffRun,
  type StaffRunLocation,
  type WindowEconomics,
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
  perStopMinutes: 6.5,
  pickupPointStopMinutes: 30,
  handoverMinutesPerOrder: 1,
  roundTrip: true
};

interface Place {
  id: string;
  slug: string;
  name: string;
  key: string;
  roadMiles: number;
  driveMinutes: number;
  growth: [string, number][];
  channels: Record<string, number>;
}

/* One location per seeded pre-order city: an origin hub sweeps the sellers and drives out. */
const PLACES: Place[] = [
  { id: '4b2f9d3a-0000-4000-8000-00000000000a', slug: 'lubbock-tx', name: 'Lubbock', key: 'US/TX/Lubbock', roadMiles: 148, driveMinutes: 165, growth: [['2026-05', 12], ['2026-06', 29], ['2026-07', 51], ['2026-08', 88], ['2026-09', 131], ['2026-10', 164]], channels: { EMAIL: 118, SMS: 61, PUSH: 24, WHATSAPP: 33 } },
  { id: '4b2f9d3a-0000-4000-8000-00000000000b', slug: 'amarillo-tx', name: 'Amarillo', key: 'US/TX/Amarillo', roadMiles: 241, driveMinutes: 262, growth: [['2026-07', 6], ['2026-08', 17], ['2026-09', 38], ['2026-10', 55]], channels: { EMAIL: 41, SMS: 18, PUSH: 7, WHATSAPP: 9 } },
  { id: '4b2f9d3a-0000-4000-8000-00000000000c', slug: 'midland-tx', name: 'Midland', key: 'US/TX/Midland', roadMiles: 112, driveMinutes: 128, growth: [['2026-08', 9], ['2026-09', 21], ['2026-10', 34]], channels: { EMAIL: 26, SMS: 12, PUSH: 4, WHATSAPP: 5 } }
];

interface MockRun extends StaffRun {
  orders: number;
  packages: number;
  completed: boolean;
}

interface RunsState {
  settings: EconomicsSettings;
  changes: EconomicsChange[];
  runs: MockRun[];
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
    ...extra
  });
  const [lbk, ama, mid] = PLACES as [Place, Place, Place];
  const runs = [
    run(1, lbk, 'COMPLETED', -42, 31),
    run(2, lbk, 'COMPLETED', -28, 38),
    run(3, lbk, 'CANCELLED', -14, 7, 12, 799, { cancelReason: 'Below the minimum: not enough orders to cover the drive' }),
    run(4, lbk, 'OPEN', 6, 19),
    run(5, ama, 'COMPLETED', -21, 14, 12),
    run(6, ama, 'OPEN', 9, 9, 12),
    run(7, mid, 'COMPLETED', -10, 16, 10),
    run(8, mid, 'ANNOUNCED', 15, 0, 10)
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
    status: 'LIVE',
    ended: null,
    serving: true,
    results: { views: 1840, clicks: 212, ctrPct: 11.5, orders: 37 },
    createdBy: 'system',
    createdAt: at(-9),
    updatedAt: at(-9),
    version: 2,
    ...extra
  });
  const advertorials = [
    adv(1, {}),
    adv(2, { variant: 'B', generated: false, headline: 'Last days: Lubbock jollof and spice box', body: 'Pre-order closes Thursday. Free pickup, door delivery from the run fee.', ctaLabel: 'Reserve yours', results: { views: 1792, clicks: 274, ctrPct: 15.3, orders: 52 } }),
    adv(3, { source: 'RUN', sourceId: run(4, lbk, 'OPEN', 6, 19).id, sourceName: 'Lubbock run', sourceCode: null, sourceStatus: 'OPEN', variant: 'A', headline: 'Our Lubbock delivery run is open', ctaLabel: 'See the run', results: { views: 420, clicks: 38, ctrPct: 9, orders: null } })
  ];
  return { settings: { ...DEFAULT_ECONOMICS, version: 0, source: 'CONFIG', updatedAt: null, updatedBy: null, reason: null, can: { manage: true } }, changes: [], runs, advertorials, seq: 100 };
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
  expectedOrders: z.number().int().nullish()
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
  endsAt: z.string().nullish()
});
const GenerateBody = z.object({ source: z.enum(['WINDOW', 'RUN']), sourceId: z.string() });

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
  const st = (ctx: PreorderCtx) => stateOf(ctx.db);
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

  const placeOf = (id: string | null | undefined) => PLACES.find((p) => p.id === id);
  const placeOfTargets = (targets: string[] | undefined) => {
    const first = targets?.[0];
    if (!first) return undefined;
    const a = areaFromKey(first);
    return PLACES.find((p) => areaFromKey(p.key).city?.toLowerCase() === a.city?.toLowerCase() && !!a.city);
  };
  const locationRow = (p: Place): StaffRunLocation => ({
    id: p.id, slug: p.slug, name: p.name, market: 'US', state: 'TX', timeZone: 'America/Chicago', currency: 'USD', defaultFeeMinor: 799, defaultMinOrders: 12, dropMode: 'DOORSTEP',
    whatsappInviteUrl: `https://chat.example/join/${p.slug}`, active: true
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
      const p = placeOfTargets(w.targets);
      return { name: w.name, code: w.code, status: preorderStatus(w, clockOf(ctx.db)) as string, closes: w.closesAt, location: p, place: areaFromKey(w.targets[0] ?? '').city ?? w.name };
    }
    const r = st(ctx).runs.find((x) => x.id === sourceId);
    if (!r) throw validation({ sourceId: 'No such delivery run' });
    const p = placeOf(r.locationId);
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
      const { reason, ...numbers } = b;
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
      const p = placeOf(b.locationId) ?? placeOfTargets(b.targets);
      const door = b.door !== false;
      const dropMode: RunDropMode = door ? 'DOORSTEP' : 'PICKUP_POINT';
      const fee = (door ? b.doorFeeMinor : b.pickupFeeMinor) ?? 799;
      const minOrders = b.minOrders ?? 12;
      const economics = p ? evaluate(ctx, p, dropMode, fee, minOrders, null, b.expectedOrders ?? minOrders, null) : null;
      return {
        locationId: p?.id ?? null,
        locationName: p?.name ?? null,
        currency: 'USD',
        feeMinor: fee,
        dropMode,
        minOrders,
        economics,
        minimumCoversBreakEven: economics?.breakEvenOrders == null || minOrders < 1 ? null : minOrders >= economics.breakEvenOrders,
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
      const p = placeOfTargets(w.targets);
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
        can: can(ctx)
      };
    })),
    http.post(`${W}/delivery-runs/economics-preview`, route(async (ctx): Promise<RunEconomics> => {
      view(ctx);
      const b = await json(ctx.request, RunPlanBody);
      const p = placeOf(b.locationId);
      if (!p) throw notFound('No such location.');
      const min = b.minOrders ?? 12;
      return evaluate(ctx, p, b.dropMode ?? 'DOORSTEP', b.feeMinor ?? 799, min, b.capacityOrders ?? null, b.expectedOrders ?? min, b.commissionShareMinor ?? null);
    })),
    http.get(`${W}/delivery-runs/runs/:id/pnl`, route((ctx, params): RunPnl => {
      view(ctx);
      const r = runOf(ctx, params.id);
      const p = placeOf(r.locationId);
      if (!p) throw notFound('No such location.');
      const commission = new URL(ctx.request.url).searchParams.get('commissionShareMinor');
      const cancelled = r.status === 'CANCELLED';
      const economics = evaluate(ctx, p, (r.dropMode as RunDropMode | undefined) ?? 'DOORSTEP', r.feeMinor, r.minOrders, r.capacityOrders ?? null, cancelled ? 0 : r.orders, commission === null ? null : Number(commission));
      return {
        economics,
        status: r.status as RunStatus,
        phase: cancelled ? 'CANCELLED' : r.completed ? 'AFTER' : 'BEFORE',
        cancelReason: r.cancelReason ?? null,
        ordersRefunded: cancelled ? r.orders : 0,
        refundedFeesMinor: cancelled ? r.orders * r.feeMinor : 0
      };
    })),

    /* ── locations and runs ── */
    http.get(`${W}/delivery-runs/locations`, route((ctx) => {
      view(ctx);
      return { items: PLACES.map(locationRow), can: can(ctx) };
    })),
    http.get(`${W}/delivery-runs/locations/:id/history`, route((ctx, params) => {
      view(ctx);
      const p = placeOf(params.id);
      if (!p) throw notFound('No such location.');
      const runs = st(ctx).runs.filter((r) => r.locationId === p.id).sort((a, b) => b.windowStart.localeCompare(a.windowStart));
      const rows: RunHistoryRow[] = runs.map((r) => ({
        id: r.id, status: r.status, windowStart: r.windowStart, cutoffAt: r.cutoffAt, orders: r.orders, packages: r.packages, feeMinor: r.feeMinor,
        feeRevenueMinor: r.status === 'CANCELLED' ? 0 : r.orders * r.feeMinor, ordersRefunded: r.status === 'CANCELLED' ? r.orders : 0, minOrders: r.minOrders, minimumMet: r.minimumMet ?? r.orders >= r.minOrders
      }));
      const last = p.growth[p.growth.length - 1]?.[1] ?? 0;
      return {
        location: locationRow(p),
        runs: rows,
        subscribers: { locationId: p.id, total: last + 9, active: last, pending: 9, byChannel: p.channels },
        subscriberGrowth: p.growth.map(([month, count]) => ({ month, count }))
      };
    })),
    http.get(`${W}/delivery-runs/runs`, route((ctx) => {
      view(ctx);
      const q = new URL(ctx.request.url).searchParams;
      const status = q.get('status')?.split(',') ?? null;
      const limit = Math.min(Math.max(Number(q.get('limit') ?? 100) || 100, 1), 500);
      return { items: st(ctx).runs.filter((r) => (!q.get('locationId') || r.locationId === q.get('locationId')) && (!status || status.includes(r.status))).slice(0, limit) };
    })),

    /* ── advertorials ── */
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
      const existing = s.advertorials.find((a) => a.source === b.source && a.sourceId === b.sourceId && a.generated && a.status === 'DRAFT');
      if (existing) return withEtag(decorate(ctx, existing));
      const info = sourceInfo(ctx, b.source, b.sourceId);
      s.seq += 1;
      const a: StaffAdvertorial = {
        id: `5c3a0e4b-0000-4000-8000-${String(s.seq).padStart(12, '0')}`, source: b.source, sourceId: b.sourceId, sourceName: info.name, sourceCode: info.code, sourceStatus: info.status,
        sourceCloses: info.closes, locationId: info.location?.id ?? null, locationName: info.location?.name ?? null, variant: nextVariant(s.advertorials, b.source, b.sourceId), weight: 1, priority: 0,
        generated: true, ...draftWords(info), startsAt: null, endsAt: null, status: 'DRAFT', ended: null, serving: false, results: { views: 0, clicks: 0, ctrPct: null, orders: b.source === 'WINDOW' ? 0 : null },
        createdBy: ctx.action.actor, createdAt: ctx.now.toISOString(), updatedAt: ctx.now.toISOString(), version: 0
      };
      s.advertorials.unshift(a);
      audit(ctx, 'advertorial.generated', a.id, `Drafted an advertorial for ${info.name}`);
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
        results: { views: 0, clicks: 0, ctrPct: null, orders: b.source === 'WINDOW' ? 0 : null }, createdBy: ctx.action.actor, createdAt: ctx.now.toISOString(), updatedAt: ctx.now.toISOString(), version: 0
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
        startsAt: b.startsAt ?? null, endsAt: b.endsAt ?? null, generated: false
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
    roundTrip: n.roundTrip
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
