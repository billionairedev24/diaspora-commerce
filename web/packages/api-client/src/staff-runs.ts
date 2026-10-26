/* Back office delivery runs, run economics and advertorials (S260, S263, S265, ADR 0005): the staff BFF routes that mirror delivery-runs'
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

/** Where a location delivers: a polygon registered as a zone in geo-service, or a radius around the town. */
export const RunAreaSchema = z.looseObject({
  kind: z.enum(['POLYGON', 'RADIUS']),
  geoZoneId: z.string().nullish(),
  centerLat: z.number().optional(),
  centerLng: z.number().optional(),
  radiusMiles: z.number().nullish()
});
export type RunArea = z.infer<typeof RunAreaSchema>;

export const RunCutoffPolicySchema = z.enum(['EXTEND', 'CANCEL']);
export type RunCutoffPolicy = z.infer<typeof RunCutoffPolicySchema>;

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
  defaultCapacityOrders: z.number().int().nullish(),
  defaultCapacityPackages: z.number().int().nullish(),
  defaultCutoffPolicy: RunCutoffPolicySchema.optional(),
  defaultExtendDays: z.number().int().optional(),
  dropMode: RunDropModeSchema.optional(),
  pickupPoint: z.looseObject({ name: z.string().optional(), line1: z.string().optional(), city: z.string().optional() }).nullish(),
  whatsappInviteUrl: z.string().nullable().optional(),
  area: RunAreaSchema.optional(),
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
  cancelReason: z.string().nullable().optional(),
  /** The town (resolved by the BFF; never show the location id). */
  locationName: z.string().nullish(),
  notes: z.string().nullish(),
  extensions: z.number().int().optional()
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

/* ── Run lifecycle (S260) ──────────────────────────────── */

/** What a run's status permits. The staff member's own access is `can.manage`: both must hold to show an action. */
export const RunAllowedSchema = z.object({
  edit: z.boolean(),
  delete: z.boolean(),
  announce: z.boolean(),
  open: z.boolean(),
  extend: z.boolean(),
  confirm: z.boolean(),
  cancel: z.boolean()
});
export type RunAllowed = z.infer<typeof RunAllowedSchema>;

/** When each step of the run's life happened (null until it did). */
export const RunMilestonesSchema = z.looseObject({
  announcedAt: z.string().nullish(),
  openedAt: z.string().nullish(),
  cutOffAt: z.string().nullish(),
  confirmedAt: z.string().nullish(),
  collectingAt: z.string().nullish(),
  outForDeliveryAt: z.string().nullish(),
  completedAt: z.string().nullish(),
  cancelledAt: z.string().nullish()
});
export type RunMilestones = z.infer<typeof RunMilestonesSchema>;

/** One run with its location (name and area), the break-even and what staff may do with it. */
export const StaffRunDetailSchema = StaffRunSchema.extend({
  windowEnd: z.string(),
  minimumMet: z.boolean(),
  originalCutoffAt: z.string().optional(),
  opensAt: z.string().nullish(),
  sellerPickupAt: z.string().nullish(),
  capacityPackages: z.number().int().nullish(),
  pickupPoint: z.looseObject({ name: z.string().optional(), line1: z.string().optional(), city: z.string().optional() }).nullish(),
  cutoffPolicy: RunCutoffPolicySchema.optional(),
  extendDays: z.number().int().optional(),
  reservedPackages: z.number().int().optional(),
  ordersLeft: z.number().int().nullish(),
  /** Fewest orders covering the estimated cost; null when the fee never covers a stop or the route cannot be measured. */
  breakEvenOrders: z.number().int().nullish(),
  milestones: RunMilestonesSchema.optional(),
  version: z.number().int().optional(),
  location: StaffRunLocationSchema.nullish(),
  allowed: RunAllowedSchema,
  can: RunCanSchema
});
export type StaffRunDetail = z.infer<typeof StaffRunDetailSchema>;

export const RUN_ACTION_KINDS = ['EXTEND', 'CONFIRM', 'CONFIRM_OVERRIDE', 'CANCEL'] as const;
export type RunActionKind = (typeof RUN_ACTION_KINDS)[number];

/** One decision about a run: who, when and why. The cutoff job is "System". */
export const RunLogEntrySchema = z.looseObject({
  id: z.string(),
  runId: z.string().optional(),
  kind: z.enum(RUN_ACTION_KINDS),
  reason: z.string().nullish(),
  /** The staff member's name (never their id), or "System". */
  actor: z.string(),
  bySystem: z.boolean().default(false),
  fromStatus: z.string(),
  toStatus: z.string(),
  /** Orders on the run when it was decided. */
  orders: z.number().int(),
  minimum: z.number().int(),
  fromCutoff: z.string().nullish(),
  toCutoff: z.string().nullish(),
  at: z.string()
});
export type RunLogEntry = z.infer<typeof RunLogEntrySchema>;
export const RunLogSchema = z.object({ items: z.array(RunLogEntrySchema) });

export const RUN_ORDER_STATUSES = ['RESERVED', 'CONFIRMED', 'RELEASED', 'CANCELLED'] as const;
export type RunOrderStatus = (typeof RUN_ORDER_STATUSES)[number];

/** An order on a run: its number and the customer's name. */
export const StaffRunOrderSchema = z.looseObject({
  id: z.string(),
  orderId: z.string(),
  orderNumber: z.string().nullish(),
  customerName: z.string().nullish(),
  packages: z.number().int().optional(),
  status: z.enum(RUN_ORDER_STATUSES),
  stage: z.string().optional(),
  reservedAt: z.string().optional(),
  deliveredAt: z.string().nullish()
});
export type StaffRunOrder = z.infer<typeof StaffRunOrderSchema>;
export const StaffRunOrdersSchema = z.object({ items: z.array(StaffRunOrderSchema) });

/** Body of POST /v1/staff/delivery-runs/runs and PUT /v1/staff/delivery-runs/runs/{id}. Times are instants (ISO 8601). */
export interface RunScheduleInput {
  locationId: string;
  windowStart: string;
  windowEnd: string;
  cutoffAt: string;
  /** An announced run opens for orders by itself at this time. */
  opensAt?: string | null;
  sellerPickupAt?: string | null;
  /** null: the delivery fee rule for the location's distance prices the run; a number is a custom fee. */
  feeMinor?: number | null;
  minOrders?: number | null;
  capacityOrders?: number | null;
  capacityPackages?: number | null;
  dropMode?: RunDropMode | null;
  cutoffPolicy?: RunCutoffPolicy | null;
  extendDays?: number | null;
  notes?: string | null;
}

/** Body of POST /v1/staff/delivery-runs/runs/{id}/extend. */
export interface RunExtendInput {
  cutoffAt: string;
  windowStart?: string | null;
  windowEnd?: string | null;
  sellerPickupAt?: string | null;
  reason: string;
}

export const RUN_LIMITS = { orders: 10_000, packages: 100_000, notes: 500, reason: 500, feeMinor: 10_000, extendDays: 30, windowHours: 24 } as const;

export type RunScheduleProblem = 'required' | 'order' | 'tooLong' | 'range' | 'whole';

/**
 * The problems of a run as it is sent (keyed by field), the same ones the BFF's 422 reports: the window ends after it
 * starts and lasts at most 24 hours, orders close before it starts, the run opens before its cutoff, sellers are collected
 * between cutoff and end, capacity is not below the minimum.
 */
export function runScheduleProblems(input: Partial<RunScheduleInput>, creating: boolean): Partial<Record<keyof RunScheduleInput, RunScheduleProblem>> {
  const out: Partial<Record<keyof RunScheduleInput, RunScheduleProblem>> = {};
  const ms = (v: string | null | undefined): number => (v ? Date.parse(v) : Number.NaN);
  if (creating && !input.locationId) out.locationId = 'required';
  (['windowStart', 'windowEnd', 'cutoffAt'] as const).forEach((k) => {
    if (!input[k] || Number.isNaN(ms(input[k]))) out[k] = 'required';
  });
  const [start, end, cutoff] = [ms(input.windowStart), ms(input.windowEnd), ms(input.cutoffAt)];
  if (!Number.isNaN(start) && !Number.isNaN(end) && (end <= start || end - start > RUN_LIMITS.windowHours * 3_600_000)) out.windowEnd = 'order';
  if (!Number.isNaN(start) && !Number.isNaN(cutoff) && cutoff >= start) out.cutoffAt = 'order';
  if (input.opensAt && !Number.isNaN(cutoff) && ms(input.opensAt) >= cutoff) out.opensAt = 'order';
  if (input.sellerPickupAt && !Number.isNaN(cutoff) && !Number.isNaN(end) && (ms(input.sellerPickupAt) < cutoff || ms(input.sellerPickupAt) > end)) out.sellerPickupAt = 'order';
  const whole = (k: 'minOrders' | 'capacityOrders' | 'capacityPackages', hi: number) => {
    const v = input[k];
    if (v === null || v === undefined) return;
    if (!Number.isInteger(v)) out[k] = 'whole';
    else if (v < 1 || v > hi) out[k] = 'range';
  };
  whole('minOrders', RUN_LIMITS.orders);
  whole('capacityOrders', RUN_LIMITS.orders);
  whole('capacityPackages', RUN_LIMITS.packages);
  if (!out.minOrders && !out.capacityOrders && input.minOrders && input.capacityOrders && input.capacityOrders < input.minOrders) out.capacityOrders = 'order';
  if (input.feeMinor !== null && input.feeMinor !== undefined && (!Number.isInteger(input.feeMinor) || input.feeMinor < 0 || input.feeMinor > RUN_LIMITS.feeMinor)) out.feeMinor = 'range';
  if (Array.from(input.notes ?? '').length > RUN_LIMITS.notes) out.notes = 'tooLong';
  return out;
}

/** What the cutoff job will do with an open run, as delivery-runs decides it. */
export type RunCutoffOutcome = 'CONFIRM' | 'EXTEND' | 'CANCEL';

/**
 * What happens at the cutoff: at or above the minimum the run is confirmed; below it, a run whose policy is EXTEND and that
 * was not extended yet moves its cutoff once, otherwise it is cancelled and every order refunded.
 */
export function runCutoffOutcome(run: { minimumMet: boolean; cutoffPolicy?: RunCutoffPolicy; extensions?: number }): RunCutoffOutcome {
  if (run.minimumMet) return 'CONFIRM';
  return run.cutoffPolicy === 'EXTEND' && (run.extensions ?? 0) === 0 ? 'EXTEND' : 'CANCEL';
}

/**
 * Where an order-taking run stands, for the banner staff read first. `DECIDING`: the cutoff has passed and the job is
 * deciding right now (it runs every minute). `AT_RISK`: below the minimum and the cutoff is within two days: the run needs a
 * decision, because at the cutoff it will extend once or cancel and refund everyone. `ON_TRACK`: the minimum is met.
 * `FILLING`: below the minimum with time to go. Runs that do not take orders have no outlook.
 */
export type RunOutlookKind = 'DECIDING' | 'AT_RISK' | 'FILLING' | 'ON_TRACK';

export const RUN_ATTENTION_WINDOW_MS = 2 * 86_400_000;

export interface RunOutlook {
  kind: RunOutlookKind;
  /** Orders still missing to reach the minimum (0 when met). */
  short: number;
  /** Milliseconds to the cutoff (negative once passed). */
  msToCutoff: number;
  /** What the job will do at the cutoff. */
  outcome: RunCutoffOutcome;
  /** Room left, null without a capacity limit. */
  ordersLeft: number | null;
}

type OutlookRun = Pick<StaffRunDetail, 'status' | 'cutoffAt' | 'minOrders' | 'reservedOrders' | 'cutoffPolicy' | 'extensions'> & { minimumMet?: boolean; ordersLeft?: number | null };

export function runOutlook(run: OutlookRun, nowMs: number): RunOutlook | null {
  if (run.status !== 'OPEN' && run.status !== 'EXTENDED') return null;
  const msToCutoff = Date.parse(run.cutoffAt) - nowMs;
  const minimumMet = run.minimumMet ?? run.reservedOrders >= run.minOrders;
  const outcome = runCutoffOutcome({ minimumMet, cutoffPolicy: run.cutoffPolicy, extensions: run.extensions });
  const kind: RunOutlookKind = msToCutoff <= 0 ? 'DECIDING' : minimumMet ? 'ON_TRACK' : msToCutoff <= RUN_ATTENTION_WINDOW_MS ? 'AT_RISK' : 'FILLING';
  return { kind, short: Math.max(0, run.minOrders - run.reservedOrders), msToCutoff, outcome, ordersLeft: run.ordersLeft ?? null };
}

/** Runs the list shows under "Needs attention": order-taking, below the minimum, cutoff within two days or just passed. */
export const runNeedsAttention = (run: OutlookRun, nowMs: number): boolean => {
  const o = runOutlook(run, nowMs);
  return o !== null && o.short > 0 && (o.kind === 'AT_RISK' || o.kind === 'DECIDING');
};

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

    /* Run lifecycle (S260) */
    run: (runId: string, signal?: AbortSignal): Promise<StaffRunDetail> => request(`/v1/staff/delivery-runs/runs/${enc(runId)}`, StaffRunDetailSchema, { signal }),
    /** Schedules a draft run for a location; its area is the location's. */
    createRun: (body: RunScheduleInput): Promise<StaffRunDetail> => request('/v1/staff/delivery-runs/runs', StaffRunDetailSchema, { method: 'POST', body }),
    /** Changes a run before it opens (an open run keeps its fee, cutoff and drop mode). */
    updateRun: (runId: string, body: RunScheduleInput): Promise<StaffRunDetail> => request(`/v1/staff/delivery-runs/runs/${enc(runId)}`, StaffRunDetailSchema, { method: 'PUT', body }),
    deleteRun: (runId: string): Promise<void> => request(`/v1/staff/delivery-runs/runs/${enc(runId)}`, z.undefined(), { method: 'DELETE' }),
    announceRun: (runId: string): Promise<StaffRunDetail> => request(`/v1/staff/delivery-runs/runs/${enc(runId)}/announce`, StaffRunDetailSchema, { method: 'POST' }),
    openRun: (runId: string): Promise<StaffRunDetail> => request(`/v1/staff/delivery-runs/runs/${enc(runId)}/open`, StaffRunDetailSchema, { method: 'POST' }),
    /** Moves the cutoff later; the reason is required and logged with who and when. */
    extendRun: (runId: string, body: RunExtendInput): Promise<StaffRunDetail> => request(`/v1/staff/delivery-runs/runs/${enc(runId)}/extend`, StaffRunDetailSchema, { method: 'POST', body }),
    /** Confirms now; a run below its minimum needs a reason (the override is logged). */
    confirmRun: (runId: string, reason?: string): Promise<StaffRunDetail> =>
      request(`/v1/staff/delivery-runs/runs/${enc(runId)}/confirm`, StaffRunDetailSchema, { method: 'POST', body: reason ? { reason } : {} }),
    /** Cancels and refunds every order in full; the reason is required, logged and shown to the customers. */
    cancelRun: (runId: string, reason: string): Promise<StaffRunDetail> =>
      request(`/v1/staff/delivery-runs/runs/${enc(runId)}/cancel`, StaffRunDetailSchema, { method: 'POST', body: { reason } }),
    runLog: (runId: string, signal?: AbortSignal): Promise<RunLogEntry[]> =>
      request(`/v1/staff/delivery-runs/runs/${enc(runId)}/log`, RunLogSchema, { signal }).then((r) => r.items),
    runOrders: (runId: string, status?: RunOrderStatus, signal?: AbortSignal): Promise<StaffRunOrder[]> =>
      request(`/v1/staff/delivery-runs/runs/${enc(runId)}/orders`, StaffRunOrdersSchema, { query: { status }, signal }).then((r) => r.items),

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
  run: (runId: string) => [...staffRunKeys.all, 'run', runId] as const,
  runLog: (runId: string) => [...staffRunKeys.all, 'run-log', runId] as const,
  runOrders: (runId: string, status?: RunOrderStatus) => [...staffRunKeys.all, 'run-orders', runId, status ?? 'all'] as const,
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
  run: (api: StaffRunsApi, runId: string) => queryOptions({ queryKey: staffRunKeys.run(runId), queryFn: ({ signal }) => api.run(runId, signal), staleTime: 5_000 }),
  runLog: (api: StaffRunsApi, runId: string) => queryOptions({ queryKey: staffRunKeys.runLog(runId), queryFn: ({ signal }) => api.runLog(runId, signal), staleTime: 5_000 }),
  runOrders: (api: StaffRunsApi, runId: string, status?: RunOrderStatus) =>
    queryOptions({ queryKey: staffRunKeys.runOrders(runId, status), queryFn: ({ signal }) => api.runOrders(runId, status, signal), staleTime: 10_000 }),
  advertorials: (api: StaffRunsApi, q: AdvertorialListQuery = {}) => queryOptions({ queryKey: staffRunKeys.advertorials(q), queryFn: ({ signal }) => api.advertorials(q, signal), staleTime: 5_000 })
};
