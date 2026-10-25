/* Back office run economics and advertorials (S263, S265, ADR 0005): the staff BFF routes that mirror delivery-runs'
   economics settings (the driver cost model, staff-editable with a reason and an audit trail), the P&L of a run
   before and after it, the break-even shown while a pre-order window is created, a location's history (orders per
   run, subscriber growth) and the location-targeted advertorials. Money is in minor units, distances in miles. */
import { queryOptions } from '@tanstack/react-query';
import { z } from 'zod';

import { type ApiClientOptions, type Requester } from './http';

/* ── Shared ────────────────────────────────────────────── */

/** What the signed-in staff member may do (the pre-order module's permission bits). */
export const RunCanSchema = z.looseObject({ manage: z.boolean() });

/* ── Economics settings (the driver cost model) ────────── */

export const ECONOMICS_REASON_MAX = 500;

/** The numbers the cost of a run is calculated from (delivery-runs checks the same limits). */
export const EconomicsNumbersSchema = z.object({
  /** Loaded driver cost per hour (wage, insurance). */
  driverCostPerHourMinor: z.number().int(),
  /** Vehicle cost per mile (fuel, wear). */
  driverCostPerMileMinor: z.number().int(),
  /** Seller sweep and loading at the origin. */
  collectionMinutes: z.number(),
  /** Time per doorstep drop, local driving included. */
  perStopMinutes: z.number(),
  /** Time at a community pickup point. */
  pickupPointStopMinutes: z.number(),
  /** Time per order handed over at a pickup point. */
  handoverMinutesPerOrder: z.number(),
  /** Count the drive back to the origin. */
  roundTrip: z.boolean()
});
export type EconomicsNumbers = z.infer<typeof EconomicsNumbersSchema>;

export const EconomicsSettingsSchema = EconomicsNumbersSchema.extend({
  /** Optimistic-lock version; PUT sends it back as If-Match (0 while the environment defaults apply). */
  version: z.number().int(),
  /** CONFIG: nobody saved yet, the environment defaults apply. */
  source: z.enum(['CONFIG', 'STAFF']),
  updatedAt: z.string().nullable().default(null),
  updatedBy: z.string().nullable().default(null),
  reason: z.string().nullable().default(null),
  can: RunCanSchema
});
export type EconomicsSettings = z.infer<typeof EconomicsSettingsSchema>;

export const EconomicsChangeSchema = z.object({
  id: z.string().optional(),
  version: z.number().int(),
  before: EconomicsNumbersSchema,
  after: EconomicsNumbersSchema,
  actor: z.string(),
  reason: z.string(),
  at: z.string()
});
export type EconomicsChange = z.infer<typeof EconomicsChangeSchema>;
export const EconomicsChangesSchema = z.object({ items: z.array(EconomicsChangeSchema) });

/** Body of PUT /v1/staff/economics-settings. */
export interface EconomicsSettingsInput extends EconomicsNumbers {
  reason: string;
}

/** Limits of the cost model (delivery-runs checks them again). */
export const ECONOMICS_LIMITS = {
  driverCostPerHourMinor: { min: 0, max: 100_000 },
  driverCostPerMileMinor: { min: 0, max: 10_000 },
  collectionMinutes: { min: 0, max: 1440 },
  perStopMinutes: { min: 0, max: 1440 },
  pickupPointStopMinutes: { min: 0, max: 1440 },
  handoverMinutesPerOrder: { min: 0, max: 1440 }
} as const;

export type EconomicsField = keyof typeof ECONOMICS_LIMITS | 'roundTrip' | 'reason';

/** The problems of a cost model as it is sent (keyed by field), the same ones the BFF's 422 reports. */
export function economicsInputProblems(input: Partial<Record<keyof typeof ECONOMICS_LIMITS, number | null>> & { reason: string }): Partial<Record<EconomicsField, 'required' | 'range' | 'whole' | 'tooLong'>> {
  const out: Partial<Record<EconomicsField, 'required' | 'range' | 'whole' | 'tooLong'>> = {};
  (Object.keys(ECONOMICS_LIMITS) as (keyof typeof ECONOMICS_LIMITS)[]).forEach((k) => {
    const v = input[k];
    const { min, max } = ECONOMICS_LIMITS[k];
    if (v === null || v === undefined || Number.isNaN(v)) out[k] = 'required';
    else if (v < min || v > max) out[k] = 'range';
    else if ((k === 'driverCostPerHourMinor' || k === 'driverCostPerMileMinor') && !Number.isInteger(v)) out[k] = 'whole';
  });
  const reason = input.reason.trim();
  if (!reason) out.reason = 'required';
  else if (reason.length > ECONOMICS_REASON_MAX) out.reason = 'tooLong';
  return out;
}

/* ── P&L and break-even ────────────────────────────────── */

export const RunRouteSchema = z.object({ roadMiles: z.number(), driveMinutes: z.number(), method: z.string().nullish() });

/** cost(n) = fixedCostMinor + n x perOrderCostMinor. */
export const RunCostModelSchema = z.object({
  driveMiles: z.number(),
  fixedMinutes: z.number(),
  perOrderMinutes: z.number(),
  fixedCostMinor: z.number(),
  perOrderCostMinor: z.number()
});
export type RunCostModel = z.infer<typeof RunCostModelSchema>;

export const RunSnapshotSchema = z.object({
  orders: z.number().int(),
  feeRevenueMinor: z.number().int(),
  commissionShareMinor: z.number().int().nullable().default(null),
  revenueMinor: z.number().int(),
  estimatedCostMinor: z.number().int(),
  contributionMarginMinor: z.number().int()
});
export type RunSnapshot = z.infer<typeof RunSnapshotSchema>;

export const RunEconomicsSchema = z.object({
  route: RunRouteSchema,
  cost: RunCostModelSchema,
  /** Delivery fee per order. */
  feeMinor: z.number().int(),
  currency: z.string(),
  /** Fewest orders whose fees cover the cost; null when a fee never covers a stop. */
  breakEvenOrders: z.number().int().nullable().default(null),
  atMinimum: RunSnapshotSchema,
  atCapacity: RunSnapshotSchema.nullable().default(null),
  atOrders: RunSnapshotSchema
});
export type RunEconomics = z.infer<typeof RunEconomicsSchema>;

export const RUN_STATUSES = ['DRAFT', 'ANNOUNCED', 'OPEN', 'EXTENDED', 'CUT_OFF', 'CONFIRMED', 'COLLECTING', 'OUT_FOR_DELIVERY', 'COMPLETED', 'CANCELLED'] as const;
export const RunStatusSchema = z.enum(RUN_STATUSES);
export type RunStatus = z.infer<typeof RunStatusSchema>;

/** BEFORE the run is driven (the estimate), AFTER once completed, CANCELLED: nothing earned or spent. */
export const RunPhaseSchema = z.enum(['BEFORE', 'AFTER', 'CANCELLED']);
export type RunPhase = z.infer<typeof RunPhaseSchema>;

export const RunPnlSchema = z.object({
  economics: RunEconomicsSchema,
  status: RunStatusSchema,
  phase: RunPhaseSchema,
  cancelReason: z.string().nullable().default(null),
  /** Orders of a cancelled run, each refunded in full (the delivery fee included). */
  ordersRefunded: z.number().int(),
  refundedFeesMinor: z.number().int()
});
export type RunPnl = z.infer<typeof RunPnlSchema>;

export const RunDropModeSchema = z.enum(['DOORSTEP', 'PICKUP_POINT']);
export type RunDropMode = z.infer<typeof RunDropModeSchema>;

/** Body of POST /v1/staff/delivery-runs/economics-preview. */
export interface RunPlanInput {
  locationId: string;
  feeMinor?: number | null;
  minOrders?: number | null;
  capacityOrders?: number | null;
  dropMode?: RunDropMode | null;
  expectedOrders?: number | null;
  commissionShareMinor?: number | null;
}

/** Body of POST /v1/staff/preorders/economics-preview: what the window form holds so far. */
export interface WindowPlanInput {
  locationId?: string | null;
  targets?: string[];
  door?: boolean | null;
  pickup?: boolean | null;
  doorFeeMinor?: number | null;
  pickupFeeMinor?: number | null;
  minOrders?: number | null;
  expectedOrders?: number | null;
}

export const WindowPlanSchema = z.object({
  locationId: z.string().nullable().default(null),
  locationName: z.string().nullable().default(null),
  currency: z.string(),
  feeMinor: z.number().int(),
  dropMode: RunDropModeSchema,
  minOrders: z.number().int(),
  /** Null when no location serves the targets or the route cannot be measured. */
  economics: RunEconomicsSchema.nullable().default(null),
  /** Whether the minimum reaches break-even (null without a break-even or a minimum). */
  minimumCoversBreakEven: z.boolean().nullable().default(null),
  can: RunCanSchema
});
export type WindowPlan = z.infer<typeof WindowPlanSchema>;

export const WindowEconomicsSchema = z.object({
  windowId: z.string(),
  code: z.string().optional(),
  name: z.string().optional(),
  status: z.string(),
  cancelled: z.boolean(),
  locationId: z.string().nullable().default(null),
  locationName: z.string().nullable().default(null),
  currency: z.string(),
  minOrders: z.number().int(),
  /** Orders that count (paid and late adds); 0 for a cancelled window. */
  orders: z.number().int(),
  /** What customers paid, delivery fees included; 0 for a cancelled window. */
  revenueMinor: z.number().int(),
  deliveryFeesMinor: z.number().int(),
  ordersRefunded: z.number().int(),
  refundsSettled: z.number().int(),
  refundedMinor: z.number().int(),
  economics: RunEconomicsSchema.nullable().default(null),
  minimumCoversBreakEven: z.boolean().nullable().default(null),
  can: RunCanSchema
});
export type WindowEconomics = z.infer<typeof WindowEconomicsSchema>;

/* ── The calculation (delivery-runs' DefaultRunEconomicsCalculator; the mock BFF and live previews share it) ── */

const round2 = (v: number): number => Math.round(v * 100) / 100;

/** cost(n) = (fixed minutes / 60 x per hour + miles x per mile) + n x (per-order minutes / 60 x per hour). */
export function runCostModel(settings: EconomicsNumbers, route: { roadMiles: number; driveMinutes: number }, dropMode: RunDropMode): RunCostModel {
  const legs = settings.roundTrip ? 2 : 1;
  const miles = route.roadMiles * legs;
  let fixedMinutes = route.driveMinutes * legs + settings.collectionMinutes;
  let perOrderMinutes: number;
  if (dropMode === 'PICKUP_POINT') {
    fixedMinutes += settings.pickupPointStopMinutes;
    perOrderMinutes = settings.handoverMinutesPerOrder;
  } else perOrderMinutes = settings.perStopMinutes;
  const perMinute = settings.driverCostPerHourMinor / 60;
  return {
    driveMiles: round2(miles),
    fixedMinutes: round2(fixedMinutes),
    perOrderMinutes: round2(perOrderMinutes),
    fixedCostMinor: fixedMinutes * perMinute + miles * settings.driverCostPerMileMinor,
    perOrderCostMinor: perOrderMinutes * perMinute
  };
}

const costFor = (m: RunCostModel, orders: number): number => m.fixedCostMinor + orders * m.perOrderCostMinor;

/** The fewest orders whose delivery fees cover the cost; null when a fee never covers a stop. */
export function runBreakEven(feeMinor: number, m: RunCostModel): number | null {
  if (feeMinor <= m.perOrderCostMinor) return null;
  let n = Math.max(1, Math.ceil(m.fixedCostMinor / (feeMinor - m.perOrderCostMinor)));
  while (n > 1 && (n - 1) * feeMinor >= costFor(m, n - 1)) n--;
  while (n * feeMinor < costFor(m, n) && n < 100_000) n++;
  return n >= 100_000 ? null : n;
}

/** The P&L at a number of orders (commission is added to the revenue when reported). */
export function runSnapshot(orders: number, feeMinor: number, commissionShareMinor: number | null, m: RunCostModel): RunSnapshot {
  const feeRevenueMinor = orders * feeMinor;
  const revenueMinor = feeRevenueMinor + (commissionShareMinor ?? 0);
  const estimatedCostMinor = Math.round(costFor(m, orders));
  return { orders, feeRevenueMinor, commissionShareMinor, revenueMinor, estimatedCostMinor, contributionMarginMinor: revenueMinor - estimatedCostMinor };
}

/* ── Locations and runs ────────────────────────────────── */

export const StaffRunLocationSchema = z.looseObject({
  id: z.string(),
  slug: z.string(),
  name: z.string(),
  market: z.string().optional(),
  state: z.string().nullable().optional(),
  timeZone: z.string().optional(),
  currency: z.string().optional(),
  defaultFeeMinor: z.number().int().optional(),
  defaultMinOrders: z.number().int().optional(),
  dropMode: RunDropModeSchema.optional(),
  whatsappInviteUrl: z.string().nullable().optional(),
  active: z.boolean().optional()
});
export type StaffRunLocation = z.infer<typeof StaffRunLocationSchema>;
export const StaffRunLocationsSchema = z.object({ items: z.array(StaffRunLocationSchema), can: RunCanSchema });
export type StaffRunLocations = z.infer<typeof StaffRunLocationsSchema>;

export const StaffRunSchema = z.looseObject({
  id: z.string(),
  locationId: z.string(),
  status: z.string(),
  windowStart: z.string(),
  windowEnd: z.string().optional(),
  cutoffAt: z.string(),
  feeMinor: z.number().int(),
  currency: z.string(),
  minOrders: z.number().int(),
  capacityOrders: z.number().int().nullable().optional(),
  dropMode: z.string().optional(),
  reservedOrders: z.number().int(),
  minimumMet: z.boolean().optional(),
  cancelReason: z.string().nullable().optional()
});
export type StaffRun = z.infer<typeof StaffRunSchema>;
export const StaffRunsSchema = z.object({ items: z.array(StaffRunSchema) });

export const RunHistoryRowSchema = z.object({
  id: z.string(),
  status: z.string(),
  windowStart: z.string(),
  cutoffAt: z.string(),
  orders: z.number().int(),
  packages: z.number().int(),
  feeMinor: z.number().int(),
  /** Delivery fees earned; 0 for a cancelled run. */
  feeRevenueMinor: z.number().int(),
  /** Orders refunded in full (cancelled runs). */
  ordersRefunded: z.number().int(),
  minOrders: z.number().int(),
  minimumMet: z.boolean()
});
export type RunHistoryRow = z.infer<typeof RunHistoryRowSchema>;

export const SubscriberCountsSchema = z.object({
  locationId: z.string().optional(),
  total: z.number().int(),
  active: z.number().int(),
  pending: z.number().int(),
  /** Active subscriptions with consent per channel. */
  byChannel: z.record(z.string(), z.number().int())
});
export type SubscriberCounts = z.infer<typeof SubscriberCountsSchema>;

export const StaffRunLocationHistorySchema = z.object({
  location: StaffRunLocationSchema,
  runs: z.array(RunHistoryRowSchema),
  subscribers: SubscriberCountsSchema,
  subscriberGrowth: z.array(z.object({ month: z.string(), count: z.number().int() }))
});
export type StaffRunLocationHistory = z.infer<typeof StaffRunLocationHistorySchema>;

/* ── Advertorials ──────────────────────────────────────── */

export const ADVERTORIAL_SOURCES = ['WINDOW', 'RUN'] as const;
export const AdvertorialSourceSchema = z.enum(ADVERTORIAL_SOURCES);
export type AdvertorialSource = z.infer<typeof AdvertorialSourceSchema>;
export const ADVERTORIAL_STATUSES = ['DRAFT', 'LIVE', 'PAUSED', 'ENDED'] as const;
export const AdvertorialStatusSchema = z.enum(ADVERTORIAL_STATUSES);
export type AdvertorialStatus = z.infer<typeof AdvertorialStatusSchema>;
/** Why an advertorial ended: its window or run closed or was cancelled, its schedule, or staff. */
export const AdvertorialEndSchema = z.enum(['SOURCE_CLOSED', 'CANCELLED', 'SCHEDULE', 'STAFF']);
export type AdvertorialEnd = z.infer<typeof AdvertorialEndSchema>;

export const AdvertorialResultsSchema = z.object({
  views: z.number().int(),
  clicks: z.number().int(),
  ctrPct: z.number().nullable().default(null),
  /** Pre-orders whose tracking tag carries the advertorial; null for runs (their orders are not attributed). */
  orders: z.number().int().nullable().default(null)
});
export type AdvertorialResults = z.infer<typeof AdvertorialResultsSchema>;

export const StaffAdvertorialSchema = z.object({
  id: z.string(),
  source: AdvertorialSourceSchema,
  sourceId: z.string(),
  sourceName: z.string().nullable().default(null),
  sourceCode: z.string().nullable().default(null),
  sourceStatus: z.string().nullable().default(null),
  sourceCloses: z.string().nullable().default(null),
  locationId: z.string().nullable().default(null),
  locationName: z.string().nullable().default(null),
  variant: z.string(),
  weight: z.number().int(),
  priority: z.number().int(),
  /** The words follow the window or run until staff edit them. */
  generated: z.boolean(),
  headline: z.string(),
  body: z.string(),
  ctaLabel: z.string(),
  startsAt: z.string().nullable().default(null),
  endsAt: z.string().nullable().default(null),
  status: AdvertorialStatusSchema,
  ended: AdvertorialEndSchema.nullable().default(null),
  /** Visitors can see it right now. */
  serving: z.boolean(),
  results: AdvertorialResultsSchema,
  createdBy: z.string().optional(),
  createdAt: z.string().optional(),
  updatedAt: z.string().optional(),
  /** Optimistic-lock version; edits and actions send it back as If-Match. */
  version: z.number().int(),
  can: RunCanSchema.optional()
});
export type StaffAdvertorial = z.infer<typeof StaffAdvertorialSchema>;
export const StaffAdvertorialsSchema = z.object({ items: z.array(StaffAdvertorialSchema), can: RunCanSchema });
export type StaffAdvertorials = z.infer<typeof StaffAdvertorialsSchema>;

/** Body of POST /v1/staff/advertorials (a new variant) and PUT /v1/staff/advertorials/{id} (an edit). */
export interface AdvertorialInput {
  source?: AdvertorialSource;
  sourceId?: string;
  variant?: string | null;
  weight?: number | null;
  priority?: number | null;
  headline: string;
  body?: string | null;
  ctaLabel: string;
  startsAt?: string | null;
  endsAt?: string | null;
}

export const ADVERTORIAL_LIMITS = { headline: 140, body: 400, ctaLabel: 40, variant: 8, weightMax: 100, priorityMax: 1000 } as const;

export type AdvertorialAction = 'publish' | 'pause' | 'end';

/** What can staff do with an advertorial in this state. */
export const advertorialActions = (status: AdvertorialStatus): AdvertorialAction[] =>
  status === 'DRAFT' || status === 'PAUSED' ? ['publish', 'end'] : status === 'LIVE' ? ['pause', 'end'] : [];

/** The problems of an advertorial as it is sent (keyed by field), the ones the BFF's 422 reports. */
export function advertorialInputProblems(input: AdvertorialInput, creating: boolean): Record<string, 'required' | 'tooLong' | 'range' | 'order'> {
  const out: Record<string, 'required' | 'tooLong' | 'range' | 'order'> = {};
  const len = (s: string | null | undefined) => Array.from(s ?? '').length;
  if (!input.headline.trim()) out.headline = 'required';
  else if (len(input.headline.trim()) > ADVERTORIAL_LIMITS.headline) out.headline = 'tooLong';
  if (!input.ctaLabel.trim()) out.ctaLabel = 'required';
  else if (len(input.ctaLabel.trim()) > ADVERTORIAL_LIMITS.ctaLabel) out.ctaLabel = 'tooLong';
  if (len(input.body?.trim()) > ADVERTORIAL_LIMITS.body) out.body = 'tooLong';
  if (creating && !input.sourceId) out.sourceId = 'required';
  if (input.variant && Array.from(input.variant).length > ADVERTORIAL_LIMITS.variant) out.variant = 'tooLong';
  if (input.weight !== null && input.weight !== undefined && (input.weight < 1 || input.weight > ADVERTORIAL_LIMITS.weightMax)) out.weight = 'range';
  if (input.priority !== null && input.priority !== undefined && (input.priority < 0 || input.priority > ADVERTORIAL_LIMITS.priorityMax)) out.priority = 'range';
  if (input.startsAt && input.endsAt && Date.parse(input.endsAt) <= Date.parse(input.startsAt)) out.endsAt = 'order';
  return out;
}

/* ── Client ────────────────────────────────────────────── */

const enc = encodeURIComponent;
const ifMatch = (version: number): Record<string, string> => ({ 'If-Match': `"${version}"` });
const str = (n: number | undefined): string | undefined => (n === undefined ? undefined : String(n));

export interface RunListQuery {
  locationId?: string;
  /** One or more statuses. */
  status?: RunStatus[];
  limit?: number;
}

export interface AdvertorialListQuery {
  source?: AdvertorialSource;
  sourceId?: string;
  status?: AdvertorialStatus;
  limit?: number;
}

/** Run economics and advertorial routes of the staff BFF (mounted as `staffApi().runs`). */
export function createStaffRunsApi(request: Requester, _options: ApiClientOptions) {
  return {
    /* Economics settings */
    settings: (signal?: AbortSignal): Promise<EconomicsSettings> => request('/v1/staff/economics-settings', EconomicsSettingsSchema, { signal }),
    /** Sets the cost model (the reason is required and logged with who and when); 412 when someone saved in between. */
    updateSettings: (body: EconomicsSettingsInput, version: number): Promise<EconomicsSettings> =>
      request('/v1/staff/economics-settings', EconomicsSettingsSchema, { method: 'PUT', body, headers: ifMatch(version) }),
    /** The audit trail of the cost model, newest first. */
    settingChanges: (limit?: number, signal?: AbortSignal): Promise<EconomicsChange[]> =>
      request('/v1/staff/economics-settings/changes', EconomicsChangesSchema, { query: { limit: str(limit) }, signal }).then((r) => r.items),

    /* Break-even and P&L */
    previewWindow: (body: WindowPlanInput, signal?: AbortSignal): Promise<WindowPlan> =>
      request('/v1/staff/preorders/economics-preview', WindowPlanSchema, { method: 'POST', body, signal }),
    windowEconomics: (windowId: string, signal?: AbortSignal): Promise<WindowEconomics> =>
      request(`/v1/staff/preorders/${enc(windowId)}/economics`, WindowEconomicsSchema, { signal }),
    previewRun: (body: RunPlanInput, signal?: AbortSignal): Promise<RunEconomics> =>
      request('/v1/staff/delivery-runs/economics-preview', RunEconomicsSchema, { method: 'POST', body, signal }),
    runPnl: (runId: string, commissionShareMinor?: number, signal?: AbortSignal): Promise<RunPnl> =>
      request(`/v1/staff/delivery-runs/runs/${enc(runId)}/pnl`, RunPnlSchema, { query: { commissionShareMinor: str(commissionShareMinor) }, signal }),

    /* Locations and runs */
    locations: (signal?: AbortSignal): Promise<StaffRunLocations> => request('/v1/staff/delivery-runs/locations', StaffRunLocationsSchema, { signal }),
    locationHistory: (locationId: string, signal?: AbortSignal): Promise<StaffRunLocationHistory> =>
      request(`/v1/staff/delivery-runs/locations/${enc(locationId)}/history`, StaffRunLocationHistorySchema, { signal }),
    runs: (q: RunListQuery = {}, signal?: AbortSignal): Promise<StaffRun[]> =>
      request('/v1/staff/delivery-runs/runs', StaffRunsSchema, { query: { locationId: q.locationId, status: q.status?.join(','), limit: str(q.limit) }, signal }).then((r) => r.items),

    /* Advertorials */
    advertorials: (q: AdvertorialListQuery = {}, signal?: AbortSignal): Promise<StaffAdvertorials> =>
      request('/v1/staff/advertorials', StaffAdvertorialsSchema, { query: { source: q.source, sourceId: q.sourceId, status: q.status, limit: str(q.limit) }, signal }),
    advertorial: (id: string, signal?: AbortSignal): Promise<StaffAdvertorial> => request(`/v1/staff/advertorials/${enc(id)}`, StaffAdvertorialSchema, { signal }),
    /** A draft written from the window's or run's own details (re-calling returns the same generated draft). */
    generateAdvertorial: (source: AdvertorialSource, sourceId: string): Promise<StaffAdvertorial> =>
      request('/v1/staff/advertorials/generate', StaffAdvertorialSchema, { method: 'POST', body: { source, sourceId } }),
    createAdvertorial: (body: AdvertorialInput): Promise<StaffAdvertorial> => request('/v1/staff/advertorials', StaffAdvertorialSchema, { method: 'POST', body }),
    updateAdvertorial: (id: string, body: AdvertorialInput, version: number): Promise<StaffAdvertorial> =>
      request(`/v1/staff/advertorials/${enc(id)}`, StaffAdvertorialSchema, { method: 'PUT', body, headers: ifMatch(version) }),
    actAdvertorial: (id: string, action: AdvertorialAction, version: number): Promise<StaffAdvertorial> =>
      request(`/v1/staff/advertorials/${enc(id)}/${action}`, StaffAdvertorialSchema, { method: 'POST', headers: ifMatch(version) })
  };
}

export type StaffRunsApi = ReturnType<typeof createStaffRunsApi>;

/* ── Query keys & options ──────────────────────────────── */

export const staffRunKeys = {
  all: ['staff', 'runs'] as const,
  settings: () => [...staffRunKeys.all, 'settings'] as const,
  settingChanges: (limit?: number) => [...staffRunKeys.all, 'setting-changes', limit ?? 'all'] as const,
  windowPlan: (body: WindowPlanInput) => [...staffRunKeys.all, 'window-plan', body] as const,
  windowEconomics: (windowId: string) => [...staffRunKeys.all, 'window-economics', windowId] as const,
  runPnl: (runId: string, commissionShareMinor?: number) => [...staffRunKeys.all, 'pnl', runId, commissionShareMinor ?? null] as const,
  locations: () => [...staffRunKeys.all, 'locations'] as const,
  history: (locationId: string) => [...staffRunKeys.all, 'history', locationId] as const,
  runs: (q: RunListQuery) => [...staffRunKeys.all, 'list', q] as const,
  advertorials: (q: AdvertorialListQuery) => [...staffRunKeys.all, 'advertorials', q] as const,
  advertorial: (id: string) => [...staffRunKeys.all, 'advertorial', id] as const
};

export const staffRunQueries = {
  settings: (api: StaffRunsApi) => queryOptions({ queryKey: staffRunKeys.settings(), queryFn: ({ signal }) => api.settings(signal), staleTime: 15_000 }),
  settingChanges: (api: StaffRunsApi, limit?: number) => queryOptions({ queryKey: staffRunKeys.settingChanges(limit), queryFn: ({ signal }) => api.settingChanges(limit, signal), staleTime: 5_000 }),
  windowPlan: (api: StaffRunsApi, body: WindowPlanInput) => queryOptions({ queryKey: staffRunKeys.windowPlan(body), queryFn: ({ signal }) => api.previewWindow(body, signal), staleTime: 10_000 }),
  windowEconomics: (api: StaffRunsApi, windowId: string) => queryOptions({ queryKey: staffRunKeys.windowEconomics(windowId), queryFn: ({ signal }) => api.windowEconomics(windowId, signal), staleTime: 10_000 }),
  runPnl: (api: StaffRunsApi, runId: string, commissionShareMinor?: number) =>
    queryOptions({ queryKey: staffRunKeys.runPnl(runId, commissionShareMinor), queryFn: ({ signal }) => api.runPnl(runId, commissionShareMinor, signal), staleTime: 10_000 }),
  locations: (api: StaffRunsApi) => queryOptions({ queryKey: staffRunKeys.locations(), queryFn: ({ signal }) => api.locations(signal), staleTime: 30_000 }),
  history: (api: StaffRunsApi, locationId: string) => queryOptions({ queryKey: staffRunKeys.history(locationId), queryFn: ({ signal }) => api.locationHistory(locationId, signal), staleTime: 15_000 }),
  runs: (api: StaffRunsApi, q: RunListQuery = {}) => queryOptions({ queryKey: staffRunKeys.runs(q), queryFn: ({ signal }) => api.runs(q, signal), staleTime: 10_000 }),
  advertorials: (api: StaffRunsApi, q: AdvertorialListQuery = {}) => queryOptions({ queryKey: staffRunKeys.advertorials(q), queryFn: ({ signal }) => api.advertorials(q, signal), staleTime: 5_000 })
};
