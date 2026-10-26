/* Plans & billing (contracts/openapi/gateway-bff-staff.yaml, tag Plans & billing; S292–S297): AfroTransact's plan
   catalogue (versions, publish, migrations, invites, entitlements), trial campaigns, reward rules with a preview, the
   grants log and the per-seller billing panel with audited adjustments. AfroTransact issues and collects its own
   invoices; Stripe only moves card money.
   Amounts are cents (USD), as the seller service sends them. */
import { keepPreviousData, queryOptions } from '@tanstack/react-query';
import { z } from 'zod';

import { type Requester } from './http';

const nullableString = z.string().nullable();
const nullableInt = z.number().int().nullable();

export const PlanCadenceSchema = z.enum(['MONTHLY', 'QUARTERLY', 'ANNUAL']);
export type PlanCadence = z.infer<typeof PlanCadenceSchema>;
export const PlanVisibilitySchema = z.enum(['PUBLIC', 'HIDDEN', 'INVITE_ONLY']);
export type PlanVisibility = z.infer<typeof PlanVisibilitySchema>;
export const PlanStatusSchema = z.enum(['DRAFT', 'ACTIVE', 'RETIRED']);
export type PlanStatus = z.infer<typeof PlanStatusSchema>;
export const RewardMetricSchema = z.enum(['GROSS_SALES', 'ACTIVE_LISTINGS', 'COMPLETED_ORDERS', 'COMPLETED_BOOKINGS', 'DAYS_SINCE_APPROVAL']);
export type RewardMetric = z.infer<typeof RewardMetricSchema>;
export const RewardTypeSchema = z.enum(['FREE_DAYS', 'CREDIT', 'PERCENT_OFF']);
export type RewardType = z.infer<typeof RewardTypeSchema>;
/** S299: reach the threshold (AT_LEAST) or stay under it (LESS_THAN: low-sales relief, decided at window end). */
export const RewardComparisonSchema = z.enum(['AT_LEAST', 'LESS_THAN']);
export type RewardComparison = z.infer<typeof RewardComparisonSchema>;
/** Where "the first N days" start: approval or the subscription's start (its trial's first day). */
export const RewardWindowAnchorSchema = z.enum(['APPROVAL', 'SUBSCRIPTION_START']);
export type RewardWindowAnchor = z.infer<typeof RewardWindowAnchorSchema>;
export const GrantTypeSchema = z.enum(['FREE_DAYS', 'FREE_MONTHS', 'CREDIT', 'PERCENT_OFF', 'WAIVE_INVOICE', 'TRIAL_END', 'CHARGE_GATE']);
export type GrantType = z.infer<typeof GrantTypeSchema>;

export const StaffPlanVersionPriceSchema = z.object({
  cadence: PlanCadenceSchema,
  amountCents: z.number().int()
});
export type StaffPlanVersionPrice = z.infer<typeof StaffPlanVersionPriceSchema>;

/** What a plan lets an organisation do (read from `limits`): the listing cap and the capabilities it includes. */
export const StaffPlanEntitlementsSchema = z.object({
  listings: nullableInt,
  listingsUnlimited: z.boolean(),
  aiListingWriter: z.boolean(),
  lolaTier: z.enum(['NONE', 'ESSENTIALS', 'GROWTH', 'PRO']),
  bulkUpload: z.boolean().default(false),
  imageLibrary: z.boolean().default(false),
  coupons: z.boolean().default(false)
});
export type StaffPlanEntitlements = z.infer<typeof StaffPlanEntitlementsSchema>;

/**
 * What a plan includes, in plain words: the plan's feature list is derived from its capabilities (never typed), and only
 * what the plan allows is listed. Mirrors the seller service (PlanEntitlements.featureList).
 */
export function planFeatureList(e: StaffPlanEntitlements): string[] {
  const out = [e.listingsUnlimited || e.listings === null ? 'Unlimited listings' : `Up to ${e.listings.toLocaleString('en-US')} listings`];
  if (e.bulkUpload) out.push('Bulk upload');
  if (e.imageLibrary) out.push('Image library');
  if (e.coupons) out.push('Coupons');
  if (e.aiListingWriter) out.push('AI listing writer');
  if (e.lolaTier !== 'NONE') out.push(`Lola ${e.lolaTier.charAt(0)}${e.lolaTier.slice(1).toLowerCase()} assistant`);
  return out;
}

/** How a plan charges its fee (stored in the plan's `limits` as charge* keys; mirrors the seller service's PlanChargePolicy). */
export type ChargeThen = 'CHARGE' | 'CHARGE_WHEN' | 'WAIVE_WHILE';
export type ChargeMetric = 'GROSS_SALES' | 'COMPLETED_ORDERS' | 'COMPLETED_BOOKINGS' | 'ACTIVE_LISTINGS';
export interface ChargeClause {
  metric: ChargeMetric;
  /** Cents for GROSS_SALES, a count otherwise. */
  threshold: number;
}
export interface ChargePolicy {
  /** Months free at the start. */
  freeMonths: number;
  then: ChargeThen;
  match: 'ALL' | 'ANY';
  clauses: ChargeClause[];
  /** Sales and orders are counted over the last N months; null = since the subscription started. */
  windowMonths: number | null;
}
export const CHARGE_METRICS: ChargeMetric[] = ['GROSS_SALES', 'COMPLETED_ORDERS', 'COMPLETED_BOOKINGS', 'ACTIVE_LISTINGS'];
export const CHARGE_KEYS = ['chargeFreeMonths', 'chargeThen', 'chargeMatch', 'chargeClauses', 'chargeWindowMonths'] as const;

export function chargePolicyOf(limits: Record<string, string> | undefined): ChargePolicy {
  const l = limits ?? {};
  const clauses: ChargeClause[] = (l.chargeClauses ?? '')
    .split(',')
    .map((p) => p.trim().split(':'))
    .filter((kv) => CHARGE_METRICS.includes(kv[0] as ChargeMetric) && Number.isFinite(Number(kv[1])))
    .map((kv) => ({ metric: kv[0] as ChargeMetric, threshold: Number(kv[1]) }));
  const then = (['CHARGE', 'CHARGE_WHEN', 'WAIVE_WHILE'] as const).find((v) => v === l.chargeThen) ?? 'CHARGE';
  const window = Number.parseInt(l.chargeWindowMonths ?? '', 10);
  return {
    freeMonths: Number.parseInt(l.chargeFreeMonths ?? '0', 10) || 0,
    then,
    match: l.chargeMatch === 'ANY' ? 'ANY' : 'ALL',
    clauses,
    windowMonths: Number.isFinite(window) && window > 0 ? window : null
  };
}

/** The charge* limits of a policy ({} for "charge every period from the start"), without touching other limits. */
export function chargePolicyLimits(p: ChargePolicy): Record<string, string> {
  if (p.freeMonths === 0 && p.then === 'CHARGE') return {};
  const out: Record<string, string> = { chargeFreeMonths: String(p.freeMonths), chargeThen: p.then };
  if (p.clauses.length) {
    out.chargeMatch = p.match;
    out.chargeClauses = p.clauses.map((c) => `${c.metric}:${c.threshold}`).join(',');
  }
  if (p.windowMonths !== null) out.chargeWindowMonths = String(p.windowMonths);
  return out;
}

const usdWhole = (cents: number) => `$${(cents / 100).toLocaleString('en-US', { minimumFractionDigits: cents % 100 === 0 ? 0 : 2, maximumFractionDigits: 2 })}`;
const metricWords = (c: ChargeClause) =>
  c.metric === 'GROSS_SALES' ? `reach ${usdWhole(c.threshold)} in sales`
    : c.metric === 'ACTIVE_LISTINGS' ? `have ${c.threshold} active listings`
      : c.metric === 'COMPLETED_ORDERS' ? `reach ${c.threshold} completed orders` : `reach ${c.threshold} completed bookings`;

/** "First 3 months free, then $39/mo": what a plan charges, in words (mirrors PlanChargePolicy.summary). */
export function chargeTermsOf(limits: Record<string, string> | undefined, monthlyCents: number): string {
  if (monthlyCents <= 0) return 'No monthly fee — commission only';
  const p = chargePolicyOf(limits);
  const price = `${usdWhole(monthlyCents)}/mo`;
  const parts: string[] = [];
  if (p.freeMonths > 0) parts.push(p.freeMonths === 1 ? 'First month free' : `First ${p.freeMonths} months free`);
  const words = p.clauses.map(metricWords).join(p.match === 'ALL' ? ' and ' : ' or ');
  const lead = p.freeMonths > 0 ? 'then ' : '';
  if (p.then === 'CHARGE') parts.push(p.freeMonths > 0 ? `then ${price}` : price);
  else if (p.then === 'CHARGE_WHEN') parts.push(`${lead}free until you ${words}`);
  else parts.push(`${lead}${price}, free while you ${words}`);
  return parts.join(', ');
}

export const StaffPlanVersionSchema = z.object({
  version: z.number().int(),
  status: z.enum(['DRAFT', 'PUBLISHED', 'SUPERSEDED']),
  trialDays: z.number().int(),
  note: nullableString.default(null),
  createdBy: nullableString.default(null),
  createdAt: nullableString.default(null),
  publishedAt: nullableString.default(null),
  subscribers: z.number().int(),
  prices: z.array(StaffPlanVersionPriceSchema)
});
export type StaffPlanVersion = z.infer<typeof StaffPlanVersionSchema>;

export const StaffPlanSchema = z.object({
  code: z.string(),
  kind: z.enum(['SELLER', 'PROVIDER']),
  name: z.string(),
  tagline: nullableString.default(null),
  features: z.array(z.string()),
  limits: z.record(z.string(), z.string()).default({}),
  entitlements: StaffPlanEntitlementsSchema.optional(),
  /** What the plan charges, in words (from its charge* limits). */
  chargeTerms: z.string().optional(),
  visibility: PlanVisibilitySchema,
  status: PlanStatusSchema,
  sortOrder: z.number().int().default(0),
  currentVersion: z.number().int(),
  monthlyCents: z.number().int(),
  quarterlyCents: z.number().int(),
  annualCents: z.number().int(),
  trialDays: z.number().int(),
  rowVersion: z.number().int(),
  subscribers: z.number().int(),
  invites: z.array(z.string()),
  versions: z.array(StaffPlanVersionSchema)
});
export type StaffPlan = z.infer<typeof StaffPlanSchema>;

export interface StaffPlanPrices {
  monthlyCents: number;
  quarterlyCents: number;
  annualCents: number;
  trialDays: number;
  note?: string | null;
}

export interface StaffPlanInput {
  name: string;
  tagline?: string | null;
  features: string[];
  limits?: Record<string, string>;
  visibility: PlanVisibility;
  sortOrder?: number;
}

export interface StaffCreatePlanInput extends StaffPlanInput {
  code: string;
  kind: 'SELLER' | 'PROVIDER';
  prices: StaffPlanPrices;
}

export const StaffPlanMigrationRowSchema = z.object({
  orgId: z.string(),
  orgName: nullableString.default(null),
  fromVersion: z.number().int(),
  cadence: PlanCadenceSchema,
  fromCents: z.number().int(),
  toCents: z.number().int(),
  status: z.string(),
  effectiveAt: nullableString.default(null)
});
export type StaffPlanMigrationRow = z.infer<typeof StaffPlanMigrationRowSchema>;
export const StaffPlanMigrationPreviewSchema = z.object({ planCode: z.string(), toVersion: z.number().int(), rows: z.array(StaffPlanMigrationRowSchema) });
export type StaffPlanMigrationPreview = z.infer<typeof StaffPlanMigrationPreviewSchema>;
export const StaffPlanMigrationResultSchema = z.object({
  planCode: z.string(),
  toVersion: z.number().int(),
  migrated: z.array(z.string()),
  failed: z.array(z.object({ orgId: z.string(), error: nullableString.default(null) }))
});
export type StaffPlanMigrationResult = z.infer<typeof StaffPlanMigrationResultSchema>;

export const StaffTrialCampaignSchema = z.object({
  id: z.string(),
  name: z.string(),
  promoCode: nullableString.default(null),
  trialDays: z.number().int(),
  signupFrom: nullableString.default(null),
  signupTo: nullableString.default(null),
  kind: z.enum(['SELLER', 'PROVIDER']).nullable().default(null),
  eligiblePlans: z.array(z.string()),
  maxRedemptions: nullableInt.default(null),
  redemptions: z.number().int(),
  status: z.enum(['ACTIVE', 'PAUSED', 'ENDED']),
  createdBy: nullableString.default(null),
  createdAt: nullableString.default(null),
  updatedBy: nullableString.default(null),
  updatedAt: nullableString.default(null),
  version: z.number().int()
});
export type StaffTrialCampaign = z.infer<typeof StaffTrialCampaignSchema>;
export interface StaffTrialCampaignInput {
  name: string;
  promoCode?: string | null;
  trialDays: number;
  signupFrom?: string | null;
  signupTo?: string | null;
  kind?: 'SELLER' | 'PROVIDER' | null;
  eligiblePlans: string[];
  maxRedemptions?: number | null;
  status?: 'ACTIVE' | 'PAUSED' | 'ENDED' | null;
  expectedVersion?: number | null;
}

export const StaffRewardRuleSchema = z.object({
  id: z.string(),
  name: z.string(),
  description: nullableString.default(null),
  status: z.enum(['DRAFT', 'ACTIVE', 'PAUSED', 'ENDED']),
  kind: z.enum(['SELLER', 'PROVIDER']).nullable().default(null),
  metric: RewardMetricSchema,
  threshold: z.number().int(),
  windowDays: nullableInt.default(null),
  withinDaysOfApproval: nullableInt.default(null),
  comparison: RewardComparisonSchema.default('AT_LEAST'),
  windowAnchor: RewardWindowAnchorSchema.default('APPROVAL'),
  /** Decided once when the window closes (always for LESS_THAN). */
  checkAtWindowEnd: z.boolean().default(false),
  /** Hours before the window (or the trial) ends that the check runs. */
  checkLeadHours: z.number().int().default(24),
  rewardType: RewardTypeSchema,
  freeDays: nullableInt.default(null),
  creditCents: nullableInt.default(null),
  percentOff: z.number().nullable().default(null),
  durationInvoices: nullableInt.default(null),
  eligiblePlans: z.array(z.string()),
  startsAt: nullableString.default(null),
  endsAt: nullableString.default(null),
  maxGrants: nullableInt.default(null),
  grantsCount: z.number().int(),
  rewardLabel: z.string(),
  conditionLabel: z.string(),
  createdBy: nullableString.default(null),
  createdAt: nullableString.default(null),
  updatedBy: nullableString.default(null),
  updatedAt: nullableString.default(null),
  version: z.number().int()
});
export type StaffRewardRule = z.infer<typeof StaffRewardRuleSchema>;
export interface StaffRewardRuleInput {
  name: string;
  description?: string | null;
  kind?: 'SELLER' | 'PROVIDER' | null;
  metric: RewardMetric;
  threshold: number;
  windowDays?: number | null;
  withinDaysOfApproval?: number | null;
  comparison?: RewardComparison | null;
  windowAnchor?: RewardWindowAnchor | null;
  checkAtWindowEnd?: boolean | null;
  checkLeadHours?: number | null;
  rewardType: RewardType;
  freeDays?: number | null;
  creditCents?: number | null;
  percentOff?: number | null;
  durationInvoices?: number | null;
  eligiblePlans: string[];
  startsAt?: string | null;
  endsAt?: string | null;
  maxGrants?: number | null;
  status?: 'DRAFT' | 'ACTIVE' | 'PAUSED' | 'ENDED' | null;
  expectedVersion?: number | null;
  ruleId?: string | null;
}

export const StaffRewardPreviewRowSchema = z.object({
  orgId: z.string(),
  orgName: nullableString.default(null),
  planCode: nullableString.default(null),
  value: z.number().int(),
  threshold: z.number().int(),
  percent: z.number().int(),
  qualifies: z.boolean(),
  alreadyGranted: z.boolean(),
  reason: nullableString.default(null),
  /** PENDING: in the window of a rule checked at window end (decided when it closes). */
  status: z.enum(['GRANTED', 'QUALIFIES', 'PENDING', 'CHECKED', 'NOT_YET', 'INELIGIBLE']).default('NOT_YET'),
  checkAt: nullableString.default(null)
});
export type StaffRewardPreviewRow = z.infer<typeof StaffRewardPreviewRowSchema>;
export const StaffRewardPreviewSchema = z.object({
  evaluated: z.number().int(),
  qualifying: z.number().int(),
  /** Rules checked at window end: organisations still in their window. */
  pending: z.number().int().default(0),
  rows: z.array(StaffRewardPreviewRowSchema)
});
export type StaffRewardPreview = z.infer<typeof StaffRewardPreviewSchema>;

export const StaffBillingGrantSchema = z.object({
  id: z.string(),
  orgId: z.string(),
  /** The organisation's name (the BFF names every id; "Unknown seller" when it cannot). */
  orgName: nullableString.default(null),
  source: z.enum(['RULE', 'MANUAL']),
  ruleId: nullableString.default(null),
  type: GrantTypeSchema,
  creditCents: nullableInt.default(null),
  freeDays: nullableInt.default(null),
  freeMonths: nullableInt.default(null),
  percentOff: z.number().nullable().default(null),
  durationInvoices: nullableInt.default(null),
  remainingInvoices: nullableInt.default(null),
  gateMode: z.enum(['START_AFTER', 'WAIVE_WHILE', 'WAIVE_UNTIL']).nullable().default(null),
  invoiceId: nullableString.default(null),
  status: z.enum(['PENDING', 'APPLIED', 'FAILED']),
  reasonCode: nullableString.default(null),
  note: nullableString.default(null),
  summary: z.string(),
  actorId: z.string(),
  /** Who made it, by name ("System" for a rule). */
  actorName: nullableString.default(null),
  metric: nullableString.default(null),
  metricValue: nullableInt.default(null),
  reference: nullableString.default(null),
  previousTrialEnd: nullableString.default(null),
  newTrialEnd: nullableString.default(null),
  attempts: z.number().int(),
  lastError: nullableString.default(null),
  createdAt: z.string(),
  appliedAt: nullableString.default(null)
});
export type StaffBillingGrant = z.infer<typeof StaffBillingGrantSchema>;
export const StaffBillingGrantPageSchema = z.object({
  items: z.array(StaffBillingGrantSchema),
  page: z.number().int(),
  size: z.number().int(),
  total: z.number().int()
});
export type StaffBillingGrantPage = z.infer<typeof StaffBillingGrantPageSchema>;
export interface StaffGrantQuery {
  orgId?: string;
  source?: 'RULE' | 'MANUAL';
  type?: GrantType;
  ruleId?: string;
  q?: string;
  page: number;
  size: number;
}

export const StaffBillingReasonCodeSchema = z.object({ code: z.string(), label: z.string() });
export type StaffBillingReasonCode = z.infer<typeof StaffBillingReasonCodeSchema>;

export const StaffBillingOrgSearchSchema = z.object({
  items: z.array(z.object({ orgId: z.string(), name: z.string(), kind: z.string(), status: z.string() }))
});
export type StaffBillingOrgSearch = z.infer<typeof StaffBillingOrgSearchSchema>;

export const StaffChargeGateSchema = z.object({
  mode: z.enum(['START_AFTER', 'WAIVE_WHILE', 'WAIVE_UNTIL']),
  metric: z.enum(['GROSS_SALES', 'COMPLETED_ORDERS', 'COMPLETED_BOOKINGS', 'ACTIVE_LISTINGS']).nullable().default(null),
  threshold: nullableInt.default(null),
  progress: z.number().int(),
  since: nullableString.default(null),
  until: nullableString.default(null),
  reasonCode: nullableString.default(null),
  note: nullableString.default(null),
  setAt: nullableString.default(null),
  metAt: nullableString.default(null),
  label: z.string()
});
export type StaffChargeGate = z.infer<typeof StaffChargeGateSchema>;
export interface StaffChargeGateInput {
  mode: 'START_AFTER' | 'WAIVE_WHILE' | 'WAIVE_UNTIL';
  metric?: 'GROSS_SALES' | 'COMPLETED_ORDERS' | 'COMPLETED_BOOKINGS' | 'ACTIVE_LISTINGS' | null;
  threshold?: number | null;
  until?: string | null;
}

export const StaffBillingSubscriptionSchema = z.object({
  orgId: z.string(),
  planCode: z.string(),
  planName: z.string(),
  cadence: PlanCadenceSchema,
  status: z.string(),
  trialEndsAt: nullableString.default(null),
  currentPeriodEnd: nullableString.default(null),
  nextChargeAt: nullableString.default(null),
  nextChargeCents: z.number().int().default(0),
  nextChargeLabel: z.string(),
  cancelAtPeriodEnd: z.boolean(),
  dunning: nullableString.default(null),
  failedPaymentAttempts: z.number().int().default(0),
  planVersion: z.number().int(),
  creditBalanceCents: z.number().int(),
  trialSource: z.enum(['PLAN', 'CAMPAIGN', 'OVERRIDE', 'NONE']).nullable().default(null),
  trialDaysOverride: nullableInt.default(null),
  /** How the plan is paid (S298). */
  planFeeCollection: z.enum(['EARNINGS_FIRST', 'CARD']).default('CARD'),
  chargeGate: StaffChargeGateSchema.nullable().default(null),
  entitlements: StaffPlanEntitlementsSchema.nullable().default(null)
});
export type StaffBillingSubscription = z.infer<typeof StaffBillingSubscriptionSchema>;

export const StaffBillingInvoiceLineSchema = z.object({
  kind: z.enum(['PLAN', 'USAGE', 'COUPON', 'PRORATION', 'WAIVER']),
  description: z.string(),
  amountCents: z.number().int(),
  quantity: nullableInt.default(null),
  meter: nullableString.default(null)
});
export const StaffBillingInvoicePaymentSchema = z.object({
  source: z.enum(['CREDIT', 'EARNINGS', 'CARD', 'WAIVER']),
  amountCents: z.number().int(),
  status: z.enum(['APPLIED', 'PENDING', 'FAILED', 'REVERSED']),
  note: nullableString.default(null),
  createdAt: nullableString.default(null)
});
/** An invoice AfroTransact issued: credit, then sales (only if the seller chose that), then the saved card or bank. */
export const StaffBillingInvoiceSchema = z.object({
  id: z.string(),
  number: nullableString.default(null),
  periodStart: nullableString.default(null),
  periodEnd: nullableString.default(null),
  subtotalCents: z.number().int(),
  discountCents: z.number().int(),
  amountDueCents: z.number().int(),
  amountPaidCents: z.number().int(),
  currency: z.string(),
  status: z.enum(['open', 'paid', 'void', 'uncollectible']).nullable().default(null),
  billingReason: nullableString.default(null),
  planCode: nullableString.default(null),
  attempts: z.number().int().default(0),
  nextAttemptAt: nullableString.default(null),
  lastError: nullableString.default(null),
  paidAt: nullableString.default(null),
  createdAt: z.string(),
  lines: z.array(StaffBillingInvoiceLineSchema).default([]),
  payments: z.array(StaffBillingInvoicePaymentSchema).default([]),
  paidFromCreditCents: z.number().int(),
  paidFromSalesCents: z.number().int(),
  paidByCardCents: z.number().int(),
  waivedCents: z.number().int()
});
export type StaffBillingInvoice = z.infer<typeof StaffBillingInvoiceSchema>;

/** One plan invoice paid (or asked to be paid) from earnings (S298). */
export const StaffEarningsCollectionSchema = z.object({
  invoiceId: z.string(),
  invoiceNumber: nullableString.default(null),
  invoiceAmountCents: nullableInt.default(null),
  amountCents: z.number().int(),
  withheldCents: z.number().int().default(0),
  creditedCents: z.number().int().default(0),
  releasedCents: z.number().int().default(0),
  paidFromSalesCents: z.number().int().default(0),
  status: z.enum(['REQUESTED', 'WITHHELD', 'CREDITED', 'DECLINED', 'MISSED', 'FAILED', 'RELEASED']),
  paymentsReference: nullableString.default(null),
  reason: nullableString.default(null),
  attempts: z.number().int().default(0),
  lastError: nullableString.default(null),
  createdAt: nullableString.default(null),
  updatedAt: nullableString.default(null)
});
export type StaffEarningsCollection = z.infer<typeof StaffEarningsCollectionSchema>;

export const StaffPlanFeeModeSchema = z.enum(['EARNINGS_FIRST', 'CARD']);
export type StaffPlanFeeMode = z.infer<typeof StaffPlanFeeModeSchema>;
export const StaffPlanFeeCollectionChangeSchema = z.object({
  id: z.string(),
  previous: StaffPlanFeeModeSchema.nullable().default(null),
  mode: StaffPlanFeeModeSchema,
  source: z.enum(['ONBOARDING', 'SELLER', 'STAFF']),
  actorId: z.string(),
  actorName: nullableString.default(null),
  reasonCode: nullableString.default(null),
  note: nullableString.default(null),
  appliesFrom: nullableString.default(null),
  createdAt: z.string()
});
export type StaffPlanFeeCollectionChange = z.infer<typeof StaffPlanFeeCollectionChangeSchema>;
/** How one organisation pays its plan, with its change log (S298). */
export const StaffPlanFeeCollectionSchema = z.object({
  orgId: z.string(),
  available: z.boolean(),
  mode: StaffPlanFeeModeSchema,
  effective: StaffPlanFeeModeSchema,
  source: z.enum(['DEFAULT', 'ONBOARDING', 'SELLER', 'STAFF']),
  changedAt: nullableString.default(null),
  changedBy: nullableString.default(null),
  platformDefault: StaffPlanFeeModeSchema,
  appliesFromInvoiceAt: nullableString.default(null),
  appliesFromLabel: z.string(),
  inProgressInvoiceId: nullableString.default(null),
  history: z.array(StaffPlanFeeCollectionChangeSchema)
});
export type StaffPlanFeeCollection = z.infer<typeof StaffPlanFeeCollectionSchema>;
/** Plans & billing › Settings (S298). */
export const StaffBillingSettingsSchema = z.object({
  /** The kill switch COLLECT_PLAN_FEES_FROM_EARNINGS (deployment configuration, read-only here). */
  earningsCollectionEnabled: z.boolean(),
  planFeeCollectionDefault: StaffPlanFeeModeSchema,
  updatedBy: nullableString.default(null),
  updatedAt: nullableString.default(null)
});
export type StaffBillingSettings = z.infer<typeof StaffBillingSettingsSchema>;

export const StaffPlanAssignmentSchema = z.object({
  id: z.string(),
  fromPlan: nullableString.default(null),
  toPlan: z.string(),
  cadence: z.enum(['MONTHLY', 'QUARTERLY', 'ANNUAL']),
  applied: z.enum(['IMMEDIATE_UPGRADE', 'AT_RENEWAL', 'NO_CHANGE']),
  reason: z.string(),
  assignedBy: z.string(),
  assignedAt: z.string()
});
export type StaffPlanAssignment = z.infer<typeof StaffPlanAssignmentSchema>;

export const StaffBillingPanelSchema = z.object({
  orgId: z.string(),
  orgName: nullableString.default(null),
  kind: z.enum(['SELLER', 'PROVIDER']),
  orgStatus: z.string(),
  billing: StaffBillingSubscriptionSchema.nullable().default(null),
  invoices: z.array(StaffBillingInvoiceSchema),
  grants: z.array(StaffBillingGrantSchema),
  collections: z.array(StaffEarningsCollectionSchema),
  earningsCollectionEnabled: z.boolean(),
  planFeeCollection: StaffPlanFeeCollectionSchema.nullable().default(null),
  /** Plans staff assigned to the organisation, newest first (S306). */
  planAssignments: z.array(StaffPlanAssignmentSchema).default([])
});
export type StaffBillingPanel = z.infer<typeof StaffBillingPanelSchema>;

export interface StaffAdjustment {
  reasonCode: string;
  note?: string | null;
}

const B = '/v1/staff/billing';
const enc = encodeURIComponent;
const idem = (key?: string): Record<string, string> | undefined => (key ? { 'Idempotency-Key': key } : undefined);

export function createStaffPlansApi(request: Requester) {
  const plan = (code: string) => `${B}/plans/${enc(code)}`;
  const org = (orgId: string) => `${B}/orgs/${enc(orgId)}`;
  return {
    plans: (signal?: AbortSignal): Promise<StaffPlan[]> => request(`${B}/plans`, z.array(StaffPlanSchema), { signal }),
    plan: (code: string, signal?: AbortSignal): Promise<StaffPlan> => request(plan(code), StaffPlanSchema, { signal }),
    createPlan: (body: StaffCreatePlanInput): Promise<StaffPlan> => request(`${B}/plans`, StaffPlanSchema, { method: 'POST', body }),
    /** Metadata only; `expectedVersion` is the plan's rowVersion (409 when someone else changed it). */
    updatePlan: (code: string, body: StaffPlanInput, expectedVersion: number): Promise<StaffPlan> =>
      request(plan(code), StaffPlanSchema, { method: 'PUT', body: { ...body, expectedVersion } }),
    saveDraft: (code: string, body: StaffPlanPrices): Promise<StaffPlan> => request(`${plan(code)}/draft`, StaffPlanSchema, { method: 'PUT', body }),
    publish: (code: string): Promise<StaffPlan> => request(`${plan(code)}/publish`, StaffPlanSchema, { method: 'POST' }),
    retire: (code: string): Promise<StaffPlan> => request(`${plan(code)}/retire`, StaffPlanSchema, { method: 'POST' }),
    setInvites: (code: string, orgIds: string[]): Promise<StaffPlan> => request(`${plan(code)}/invites`, StaffPlanSchema, { method: 'PUT', body: { orgIds } }),
    migrationPreview: (code: string, signal?: AbortSignal): Promise<StaffPlanMigrationPreview> =>
      request(`${plan(code)}/migration`, StaffPlanMigrationPreviewSchema, { signal }),
    migrate: (code: string, orgIds?: string[]): Promise<StaffPlanMigrationResult> =>
      request(`${plan(code)}/migration`, StaffPlanMigrationResultSchema, { method: 'POST', body: { orgIds: orgIds ?? [] } }),

    campaigns: (signal?: AbortSignal): Promise<StaffTrialCampaign[]> => request(`${B}/trial-campaigns`, z.array(StaffTrialCampaignSchema), { signal }),
    createCampaign: (body: StaffTrialCampaignInput): Promise<StaffTrialCampaign> =>
      request(`${B}/trial-campaigns`, StaffTrialCampaignSchema, { method: 'POST', body }),
    updateCampaign: (id: string, body: StaffTrialCampaignInput): Promise<StaffTrialCampaign> =>
      request(`${B}/trial-campaigns/${enc(id)}`, StaffTrialCampaignSchema, { method: 'PUT', body }),
    setCampaignStatus: (id: string, status: 'ACTIVE' | 'PAUSED' | 'ENDED'): Promise<StaffTrialCampaign> =>
      request(`${B}/trial-campaigns/${enc(id)}/status`, StaffTrialCampaignSchema, { method: 'POST', body: { status } }),

    rules: (signal?: AbortSignal): Promise<StaffRewardRule[]> => request(`${B}/reward-rules`, z.array(StaffRewardRuleSchema), { signal }),
    createRule: (body: StaffRewardRuleInput): Promise<StaffRewardRule> => request(`${B}/reward-rules`, StaffRewardRuleSchema, { method: 'POST', body }),
    updateRule: (id: string, body: StaffRewardRuleInput): Promise<StaffRewardRule> =>
      request(`${B}/reward-rules/${enc(id)}`, StaffRewardRuleSchema, { method: 'PUT', body }),
    setRuleStatus: (id: string, status: 'DRAFT' | 'ACTIVE' | 'PAUSED' | 'ENDED'): Promise<StaffRewardRule> =>
      request(`${B}/reward-rules/${enc(id)}/status`, StaffRewardRuleSchema, { method: 'POST', body: { status } }),
    previewRule: (body: StaffRewardRuleInput, signal?: AbortSignal): Promise<StaffRewardPreview> =>
      request(`${B}/reward-rules/preview`, StaffRewardPreviewSchema, { method: 'POST', body, signal }),

    grants: (q: StaffGrantQuery, signal?: AbortSignal): Promise<StaffBillingGrantPage> =>
      request(`${B}/grants`, StaffBillingGrantPageSchema, {
        query: { orgId: q.orgId || undefined, source: q.source, type: q.type, ruleId: q.ruleId || undefined, q: q.q || undefined, page: String(q.page), size: String(q.size) },
        signal
      }),
    reasonCodes: (signal?: AbortSignal): Promise<StaffBillingReasonCode[]> => request(`${B}/reason-codes`, z.array(StaffBillingReasonCodeSchema), { signal }),
    searchOrgs: (q: string, signal?: AbortSignal): Promise<StaffBillingOrgSearch> => request(`${B}/orgs`, StaffBillingOrgSearchSchema, { query: { q }, signal }),
    panel: (orgId: string, signal?: AbortSignal): Promise<StaffBillingPanel> => request(org(orgId), StaffBillingPanelSchema, { signal }),
    credit: (orgId: string, amountCents: number, adj: StaffAdjustment, key: string): Promise<StaffBillingGrant> =>
      request(`${org(orgId)}/credits`, StaffBillingGrantSchema, { method: 'POST', body: { amountCents, ...adj }, headers: idem(key) }),
    freeDays: (orgId: string, days: number, adj: StaffAdjustment, key: string): Promise<StaffBillingGrant> =>
      request(`${org(orgId)}/free-days`, StaffBillingGrantSchema, { method: 'POST', body: { days, ...adj }, headers: idem(key) }),
    overrideTrial: (orgId: string, days: number, adj: StaffAdjustment, key: string): Promise<StaffBillingGrant> =>
      request(`${org(orgId)}/trial`, StaffBillingGrantSchema, { method: 'POST', body: { days, ...adj }, headers: idem(key) }),
    freeMonths: (orgId: string, months: number, adj: StaffAdjustment, key: string): Promise<StaffBillingGrant> =>
      request(`${org(orgId)}/free-months`, StaffBillingGrantSchema, { method: 'POST', body: { months, ...adj }, headers: idem(key) }),
    setChargeGate: (orgId: string, gate: StaffChargeGateInput, adj: StaffAdjustment, key: string): Promise<StaffBillingGrant> =>
      request(`${org(orgId)}/charge-gate`, StaffBillingGrantSchema, { method: 'PUT', body: { ...gate, ...adj }, headers: idem(key) }),
    clearChargeGate: (orgId: string, adj: StaffAdjustment, key: string): Promise<StaffBillingGrant> =>
      request(`${org(orgId)}/charge-gate/clear`, StaffBillingGrantSchema, { method: 'POST', body: adj, headers: idem(key) }),
    waiveInvoice: (orgId: string, invoiceId: string, adj: StaffAdjustment, key: string): Promise<StaffBillingGrant> =>
      request(`${org(orgId)}/invoices/${enc(invoiceId)}/waive`, StaffBillingGrantSchema, { method: 'POST', body: adj, headers: idem(key) }),
    /** S298: per-seller override of how the plan is paid, audited with a reason (billing:adjust). */
    overridePlanFeeCollection: (orgId: string, mode: StaffPlanFeeMode, adj: StaffAdjustment): Promise<StaffPlanFeeCollection> =>
      request(`${org(orgId)}/plan-fee-collection`, StaffPlanFeeCollectionSchema, { method: 'PUT', body: { mode, ...adj } }),
    billingSettings: (signal?: AbortSignal): Promise<StaffBillingSettings> => request(`${B}/settings`, StaffBillingSettingsSchema, { signal }),
    /** The default for new sellers (plans:manage). */
    updateBillingSettings: (planFeeCollectionDefault: StaffPlanFeeMode): Promise<StaffBillingSettings> =>
      request(`${B}/settings`, StaffBillingSettingsSchema, { method: 'PUT', body: { planFeeCollectionDefault } })
  };
}
export type StaffPlansApi = ReturnType<typeof createStaffPlansApi>;

export const staffPlanKeys = {
  all: ['staff', 'plans'] as const,
  plans: () => [...staffPlanKeys.all, 'catalogue'] as const,
  plan: (code: string) => [...staffPlanKeys.plans(), code] as const,
  migration: (code: string) => [...staffPlanKeys.plan(code), 'migration'] as const,
  campaigns: () => [...staffPlanKeys.all, 'trial-campaigns'] as const,
  rules: () => [...staffPlanKeys.all, 'reward-rules'] as const,
  rulePreview: (input: StaffRewardRuleInput | null) => [...staffPlanKeys.rules(), 'preview', input] as const,
  grantLists: () => [...staffPlanKeys.all, 'grants'] as const,
  grants: (q: StaffGrantQuery) => [...staffPlanKeys.grantLists(), q] as const,
  reasonCodes: () => [...staffPlanKeys.all, 'reason-codes'] as const,
  orgs: (q: string) => [...staffPlanKeys.all, 'orgs', q] as const,
  panel: (orgId: string) => [...staffPlanKeys.all, 'panel', orgId] as const,
  settings: () => [...staffPlanKeys.all, 'settings'] as const
};

export const staffPlanQueries = {
  plans: (api: StaffPlansApi) => queryOptions({ queryKey: staffPlanKeys.plans(), queryFn: ({ signal }) => api.plans(signal), staleTime: 15_000 }),
  migration: (api: StaffPlansApi, code: string) =>
    queryOptions({ queryKey: staffPlanKeys.migration(code), queryFn: ({ signal }) => api.migrationPreview(code, signal) }),
  campaigns: (api: StaffPlansApi) => queryOptions({ queryKey: staffPlanKeys.campaigns(), queryFn: ({ signal }) => api.campaigns(signal), staleTime: 15_000 }),
  rules: (api: StaffPlansApi) => queryOptions({ queryKey: staffPlanKeys.rules(), queryFn: ({ signal }) => api.rules(signal), staleTime: 15_000 }),
  /** Who qualifies now for an unsaved rule (nothing is granted). */
  rulePreview: (api: StaffPlansApi, input: StaffRewardRuleInput | null) =>
    queryOptions({
      queryKey: staffPlanKeys.rulePreview(input),
      queryFn: ({ signal }) => (input ? api.previewRule(input, signal) : Promise.reject(new Error('no rule'))),
      enabled: input !== null
    }),
  grants: (api: StaffPlansApi, q: StaffGrantQuery) =>
    queryOptions({ queryKey: staffPlanKeys.grants(q), queryFn: ({ signal }) => api.grants(q, signal), placeholderData: keepPreviousData, staleTime: 10_000 }),
  reasonCodes: (api: StaffPlansApi) =>
    queryOptions({ queryKey: staffPlanKeys.reasonCodes(), queryFn: ({ signal }) => api.reasonCodes(signal), staleTime: 5 * 60_000 }),
  orgs: (api: StaffPlansApi, q: string) =>
    queryOptions({ queryKey: staffPlanKeys.orgs(q), queryFn: ({ signal }) => api.searchOrgs(q, signal), enabled: q.trim().length >= 2, staleTime: 30_000 }),
  panel: (api: StaffPlansApi, orgId: string) =>
    queryOptions({ queryKey: staffPlanKeys.panel(orgId), queryFn: ({ signal }) => api.panel(orgId, signal), enabled: Boolean(orgId), staleTime: 10_000 }),
  settings: (api: StaffPlansApi) => queryOptions({ queryKey: staffPlanKeys.settings(), queryFn: ({ signal }) => api.billingSettings(signal), staleTime: 30_000 })
};
