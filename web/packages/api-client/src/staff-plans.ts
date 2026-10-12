/* Plans & billing (contracts/openapi/gateway-bff-staff.yaml, tag Plans & billing; S292–S297): AfroTransact's plan
   catalogue (versions, publish with Stripe sync, migrations, invites), trial campaigns, reward rules with a preview, the
   grants log and the per-seller billing panel with audited adjustments. Stripe Billing only executes the charges.
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
export const GrantTypeSchema = z.enum(['FREE_DAYS', 'CREDIT', 'PERCENT_OFF', 'WAIVE_INVOICE', 'TRIAL_END']);
export type GrantType = z.infer<typeof GrantTypeSchema>;

export const StaffPlanVersionPriceSchema = z.object({
  cadence: PlanCadenceSchema,
  amountCents: z.number().int(),
  lookupKey: z.string(),
  stripePriceId: nullableString.default(null),
  syncedAt: nullableString.default(null),
  syncError: nullableString.default(null)
});
export type StaffPlanVersionPrice = z.infer<typeof StaffPlanVersionPriceSchema>;

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
  visibility: PlanVisibilitySchema,
  status: PlanStatusSchema,
  sortOrder: z.number().int().default(0),
  currentVersion: z.number().int(),
  monthlyCents: z.number().int(),
  quarterlyCents: z.number().int(),
  annualCents: z.number().int(),
  trialDays: z.number().int(),
  rowVersion: z.number().int(),
  stripeProductId: nullableString.default(null),
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

export const StaffPlanSyncLineSchema = z.object({
  planCode: z.string(),
  planName: nullableString.default(null),
  version: z.number().int(),
  cadence: PlanCadenceSchema,
  amountCents: z.number().int(),
  lookupKey: nullableString.default(null),
  state: z.enum(['IN_SYNC', 'FREE', 'ADOPTABLE', 'MISSING', 'DRIFT', 'SUPERSEDED']),
  action: z.enum(['NONE', 'ADOPTED', 'CREATED', 'REPLACED', 'FAILED']),
  stripePriceId: nullableString.default(null),
  stripeAmountCents: nullableInt.default(null),
  error: nullableString.default(null)
});
export type StaffPlanSyncLine = z.infer<typeof StaffPlanSyncLineSchema>;
export const StaffPlanSyncReportSchema = z.object({
  checkedAt: z.string(),
  applied: z.boolean(),
  drifted: z.number().int(),
  lines: z.array(StaffPlanSyncLineSchema)
});
export type StaffPlanSyncReport = z.infer<typeof StaffPlanSyncReportSchema>;

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
  source: z.enum(['RULE', 'MANUAL']),
  ruleId: nullableString.default(null),
  type: GrantTypeSchema,
  creditCents: nullableInt.default(null),
  freeDays: nullableInt.default(null),
  percentOff: z.number().nullable().default(null),
  durationInvoices: nullableInt.default(null),
  invoiceId: nullableString.default(null),
  status: z.enum(['PENDING', 'APPLIED', 'FAILED']),
  reasonCode: nullableString.default(null),
  note: nullableString.default(null),
  summary: z.string(),
  actorId: z.string(),
  metric: nullableString.default(null),
  metricValue: nullableInt.default(null),
  stripeCustomerId: nullableString.default(null),
  stripeSubscriptionId: nullableString.default(null),
  stripeBalanceTransactionId: nullableString.default(null),
  stripeCouponId: nullableString.default(null),
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
  planFeeCollection: z.enum(['EARNINGS_FIRST', 'CARD']).default('CARD')
});
export type StaffBillingSubscription = z.infer<typeof StaffBillingSubscriptionSchema>;

export const StaffBillingInvoiceSchema = z.object({
  id: z.string(),
  number: nullableString.default(null),
  periodStart: nullableString.default(null),
  periodEnd: nullableString.default(null),
  amountDueCents: z.number().int(),
  amountPaidCents: z.number().int(),
  currency: z.string(),
  status: nullableString.default(null),
  billingReason: nullableString.default(null),
  hostedInvoiceUrl: nullableString.default(null),
  invoicePdf: nullableString.default(null),
  createdAt: z.string(),
  /** S298: paid from the organisation's sales, and by card. */
  paidFromSalesCents: z.number().int().default(0),
  paidByCardCents: z.number().int().default(0)
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
  stripeBalanceTransactionId: nullableString.default(null),
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
  planFeeCollection: StaffPlanFeeCollectionSchema.nullable().default(null)
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
    stripeSync: (signal?: AbortSignal): Promise<StaffPlanSyncReport> => request(`${B}/stripe-sync`, StaffPlanSyncReportSchema, { signal }),
    syncStripe: (): Promise<StaffPlanSyncReport> => request(`${B}/stripe-sync`, StaffPlanSyncReportSchema, { method: 'POST' }),

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
  sync: () => [...staffPlanKeys.all, 'stripe-sync'] as const,
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
  sync: (api: StaffPlansApi) => queryOptions({ queryKey: staffPlanKeys.sync(), queryFn: ({ signal }) => api.stripeSync(signal), staleTime: 30_000 }),
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
