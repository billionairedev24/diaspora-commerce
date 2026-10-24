/* Mock of Plans & billing (/v1/staff/billing/**, the seller service's catalogue, trials, rewards, grants and per-seller
   billing through the BFF; S292–S297). Permissions as the BFF checks them: module VIEW to read, plans:manage to change
   the catalogue, campaigns and rules, billing:adjust for an organisation's credits, free days, trials and waivers.
   Staff adjustments honour Idempotency-Key: a replay returns the first grant. */
import { http } from 'msw';
import { z } from 'zod';

import {
  type GrantType,
  type PlanCadence,
  planFeatureList,
  type RewardMetric,
  type StaffBillingGrant,
  type StaffBillingInvoice,
  type StaffBillingPanel,
  type StaffBillingSettings,
  type StaffChargeGate,
  type StaffEarningsCollection,
  type StaffPlan,
  type StaffPlanEntitlements,
  type StaffPlanFeeCollection,
  type StaffPlanAssignment,
  type StaffPlanFeeCollectionChange,
  type StaffPlanFeeMode,
  type StaffRewardPreviewRow,
  type StaffRewardRule,
  type StaffTrialCampaign
} from '../../staff-plans';
import { allows } from './model';
import { type PreorderCtx, type PreorderHandlerDeps } from './preorders';

interface MockBillingOrg {
  orgId: string;
  name: string;
  kind: 'SELLER' | 'PROVIDER';
  status: string;
  planCode: string;
  cadence: PlanCadence;
  subStatus: string;
  trialEndsAt: string | null;
  periodEnd: string;
  creditBalanceCents: number;
  trialDaysOverride: number | null;
  chargeGate: StaffChargeGate | null;
  /** How it pays its plan (S298), who chose it and the change log; invoices paid from earnings. */
  planFeeCollection: StaffPlanFeeMode;
  planFeeSource: StaffPlanFeeCollection['source'];
  planFeeHistory: StaffPlanFeeCollectionChange[];
  /** Plans staff assigned (S306), newest first. */
  planAssignments?: StaffPlanAssignment[];
  collections: StaffEarningsCollection[];
  /** Reward metrics the preview evaluates. */
  facts: Record<RewardMetric, number>;
  invoices: StaffBillingInvoice[];
}

export interface MockPlansState {
  plans: StaffPlan[];
  campaigns: StaffTrialCampaign[];
  rules: StaffRewardRule[];
  grants: StaffBillingGrant[];
  orgs: MockBillingOrg[];
  /** Idempotency-Key → grant id. */
  keys: Record<string, string>;
  /** Plans & billing › Settings (S298): the kill switch (deployment) and the default for new sellers. */
  settings: StaffBillingSettings;
  seq: number;
}

const DAY = 86_400_000;
const CADENCES: PlanCadence[] = ['MONTHLY', 'QUARTERLY', 'ANNUAL'];
const usd = (cents: number) => `$${(cents / 100).toLocaleString('en-US', { minimumFractionDigits: cents % 100 ? 2 : 0, maximumFractionDigits: 2 })}`;

export const REASON_CODES = [
  { code: 'GOODWILL', label: 'Goodwill gesture' },
  { code: 'SERVICE_ISSUE', label: 'Service issue or outage on our side' },
  { code: 'BILLING_ERROR', label: 'Billing error or double charge' },
  { code: 'PROMOTION', label: 'Promotion or partnership agreement' },
  { code: 'RETENTION', label: 'Retention offer' },
  { code: 'ONBOARDING_DELAY', label: 'Onboarding or verification delay' },
  { code: 'SELLER_REQUEST', label: 'Requested by the seller' },
  { code: 'OTHER', label: 'Other (see note)' }
];

const SEED_PLANS: [string, 'SELLER' | 'PROVIDER', string, string, number, number, string[]][] = [
  ['seller_essentials', 'SELLER', 'Seller Essentials', 'Start selling', 0, 0, ['Up to 50 listings', '15% referral fee', 'Basic storefront', '1 user']],
  ['seller_growth', 'SELLER', 'Seller Growth', 'Most popular', 3900, 30, ['Up to 1,000 listings', '8–12% referral fee by category', 'Storefront themes and AI listing writer', '3 users']],
  ['seller_pro', 'SELLER', 'Seller Pro', 'For established brands', 12900, 14, ['Unlimited listings', '6–10% referral fee', 'Fulfilled by AfroTransact priority, ad credits, API', '10 users']],
  ['provider_solo', 'PROVIDER', 'Provider Solo', 'Independent pros', 1900, 30, ['1 practitioner', '10% booking fee', 'Online booking and deposits']],
  ['provider_studio', 'PROVIDER', 'Provider Studio', 'Most popular', 4900, 30, ['Up to 5 practitioners', '8% booking fee', 'Waitlist and reminders by SMS']],
  ['provider_business', 'PROVIDER', 'Provider Business', 'Multi-location', 9900, 14, ['Unlimited staff', '6% booking fee', 'Multiple locations and API']]
];

const off = { bulkUpload: 'false', imageLibrary: 'false', coupons: 'false' };
const on = { bulkUpload: 'true', imageLibrary: 'true', coupons: 'true' };
const LIMITS: Record<string, Record<string, string>> = {
  seller_essentials: { listings: '50', aiListingWriter: 'false', lolaTier: 'ESSENTIALS', ...off },
  seller_growth: { listings: '1000', aiListingWriter: 'true', lolaTier: 'GROWTH', ...on, chargeFreeMonths: '3', chargeThen: 'CHARGE' },
  seller_pro: { listings: 'unlimited', aiListingWriter: 'true', lolaTier: 'PRO', ...on },
  provider_solo: { listings: '20', aiListingWriter: 'false', lolaTier: 'ESSENTIALS', ...off },
  provider_studio: { listings: '100', aiListingWriter: 'true', lolaTier: 'GROWTH', ...off },
  provider_business: { listings: 'unlimited', aiListingWriter: 'true', lolaTier: 'PRO', ...off }
};

/** Reads a plan's entitlements from its `limits`, as the seller service does. */
export function entitlementsFrom(limits: Record<string, string>): StaffPlanEntitlements {
  const raw = (limits.listings ?? 'unlimited').toLowerCase();
  const n = Number(raw);
  const unlimited = raw === 'unlimited' || !Number.isFinite(n);
  const tier = (limits.lolaTier ?? 'ESSENTIALS').toUpperCase();
  return {
    listings: unlimited ? null : n, listingsUnlimited: unlimited, aiListingWriter: limits.aiListingWriter === 'true',
    lolaTier: tier === 'PRO' || tier === 'GROWTH' || tier === 'NONE' ? tier : 'ESSENTIALS',
    bulkUpload: limits.bulkUpload === 'true', imageLibrary: limits.imageLibrary === 'true', coupons: limits.coupons === 'true'
  };
}

function seedPlan([code, kind, name, tagline, monthly, trial]: (typeof SEED_PLANS)[number], i: number, now: Date): StaffPlan {
  const quarterly = Math.round(monthly * 3 * 0.95);
  const annual = monthly * 10;
  const amounts: Record<PlanCadence, number> = { MONTHLY: monthly, QUARTERLY: quarterly, ANNUAL: annual };
  return {
    code, kind, name, tagline, features: planFeatureList(entitlementsFrom(LIMITS[code] ?? {})), limits: LIMITS[code] ?? {}, entitlements: entitlementsFrom(LIMITS[code] ?? {}), visibility: 'PUBLIC', status: 'ACTIVE', sortOrder: (i + 1) * 10, currentVersion: 1,
    monthlyCents: monthly, quarterlyCents: quarterly, annualCents: annual, trialDays: trial, rowVersion: 0,
    subscribers: monthly ? 3 - (i % 3) : 0, invites: [],
    versions: [{
      version: 1, status: 'PUBLISHED', trialDays: trial, note: 'Seeded catalogue', createdBy: 'system', createdAt: new Date(now.getTime() - 200 * DAY).toISOString(),
      publishedAt: new Date(now.getTime() - 200 * DAY).toISOString(), subscribers: monthly ? 3 - (i % 3) : 0,
      prices: CADENCES.map((c) => ({
        cadence: c, amountCents: amounts[c]
      }))
    }]
  };
}

const facts = (gross: number, listings: number, orders: number, bookings: number, days: number): Record<RewardMetric, number> => ({
  GROSS_SALES: gross, ACTIVE_LISTINGS: listings, COMPLETED_ORDERS: orders, COMPLETED_BOOKINGS: bookings, DAYS_SINCE_APPROVAL: days
});

export function seedPlans(now: Date): MockPlansState {
  const iso = (d: number) => new Date(now.getTime() + d * DAY).toISOString();
  const invoice = (id: string, cents: number, status: 'open' | 'paid' | 'void', daysAgo: number, fromSales = 0): StaffBillingInvoice => ({
    id, number: `AT-${id.slice(4).toUpperCase()}`, periodStart: iso(-daysAgo - 30), periodEnd: iso(-daysAgo), subtotalCents: cents, discountCents: 0, amountDueCents: cents,
    amountPaidCents: status === 'paid' ? cents : 0, currency: 'usd', status, billingReason: 'subscription_cycle', planCode: null, attempts: status === 'open' ? 1 : 0,
    nextAttemptAt: status === 'open' ? iso(2) : null, lastError: status === 'open' ? 'Your card was declined.' : null, paidAt: status === 'paid' ? iso(-daysAgo) : null, createdAt: iso(-daysAgo),
    lines: [{ kind: 'PLAN', description: 'Plan fee', amountCents: cents, quantity: null, meter: null }],
    payments: status === 'paid' ? [
      ...(fromSales ? [{ source: 'EARNINGS' as const, amountCents: fromSales, status: 'APPLIED' as const, note: null, createdAt: iso(-daysAgo) }] : []),
      ...(cents - fromSales ? [{ source: 'CARD' as const, amountCents: cents - fromSales, status: 'APPLIED' as const, note: null, createdAt: iso(-daysAgo) }] : [])
    ] : [],
    paidFromCreditCents: 0, paidFromSalesCents: status === 'paid' ? fromSales : 0, paidByCardCents: status === 'paid' ? cents - fromSales : 0, waivedCents: 0
  });
  const org = (orgId: string, name: string, kind: 'SELLER' | 'PROVIDER', planCode: string, subStatus: string, f: Record<RewardMetric, number>, invoices: StaffBillingInvoice[]): MockBillingOrg => ({
    orgId, name, kind, status: 'APPROVED', planCode, cadence: 'MONTHLY', subStatus, trialEndsAt: subStatus === 'trialing' ? iso(12) : null, periodEnd: iso(subStatus === 'trialing' ? 12 : 20),
    creditBalanceCents: 0, trialDaysOverride: null, chargeGate: null, planFeeCollection: kind === 'SELLER' ? 'EARNINGS_FIRST' : 'CARD', planFeeSource: 'DEFAULT', planFeeHistory: [],
    collections: invoices.filter((i) => i.paidFromSalesCents > 0).map((i) => ({
      invoiceId: i.id, invoiceNumber: i.number, invoiceAmountCents: i.amountDueCents, amountCents: i.amountDueCents,
      withheldCents: i.paidFromSalesCents, creditedCents: i.paidFromSalesCents, releasedCents: 0, paidFromSalesCents: i.paidFromSalesCents, status: 'CREDITED' as const,
      paymentsReference: `wh_${i.id}`, reason: 'Only part was available; the card paid the rest', attempts: 1, lastError: null,
      createdAt: i.createdAt, updatedAt: i.createdAt
    })),
    facts: f, invoices
  });
  return {
    plans: SEED_PLANS.map((p, i) => seedPlan(p, i, now)),
    campaigns: [{
      id: 'tc_grow60', name: 'Growth 60', promoCode: 'GROW60', trialDays: 60, signupFrom: null, signupTo: null, kind: 'SELLER', eligiblePlans: ['seller_growth', 'seller_pro'],
      maxRedemptions: 100, redemptions: 14, status: 'ACTIVE', createdBy: 'Amaka Eze', createdAt: iso(-30), updatedBy: null, updatedAt: null, version: 0
    }],
    rules: [{
      id: 'rr_sales', name: 'First $1,000 in sales', description: 'Reach $1,000 in delivered sales within 60 days of approval.', status: 'ACTIVE', kind: 'SELLER', metric: 'GROSS_SALES',
      threshold: 100000, windowDays: null, withinDaysOfApproval: 60, comparison: 'AT_LEAST', windowAnchor: 'APPROVAL', checkAtWindowEnd: false, checkLeadHours: 24,
      rewardType: 'FREE_DAYS', freeDays: 30, creditCents: null, percentOff: null, durationInvoices: null,
      eligiblePlans: [], startsAt: null, endsAt: null, maxGrants: 500, grantsCount: 1, rewardLabel: '30 days free', conditionLabel: '$1,000 in sales within 60 days of approval',
      createdBy: 'Amaka Eze', createdAt: iso(-40), updatedBy: null, updatedAt: null, version: 0
    }, {
      // S299: low-sales relief - the 2nd month free when the first 30 days of the subscription sold less than $1,000.
      id: 'rr_relief', name: 'Low-sales relief', description: 'A quiet first month? The second one is on us.', status: 'ACTIVE', kind: 'SELLER', metric: 'GROSS_SALES',
      threshold: 100000, windowDays: null, withinDaysOfApproval: 30, comparison: 'LESS_THAN', windowAnchor: 'SUBSCRIPTION_START', checkAtWindowEnd: true, checkLeadHours: 24,
      rewardType: 'FREE_DAYS', freeDays: 30, creditCents: null, percentOff: null, durationInvoices: null,
      eligiblePlans: ['seller_growth'], startsAt: null, endsAt: null, maxGrants: null, grantsCount: 0, rewardLabel: '30 days free',
      conditionLabel: 'less than $1,000 in sales in the first 30 days of the subscription', createdBy: 'Amaka Eze', createdAt: iso(-20), updatedBy: null, updatedAt: null, version: 0
    }],
    grants: [{
      id: 'gr_seed1', orgId: 'org-ades-kitchen', source: 'RULE', ruleId: 'rr_sales', type: 'FREE_DAYS', creditCents: null, freeDays: 30, freeMonths: null, percentOff: null, durationInvoices: null, remainingInvoices: null, gateMode: null,
      invoiceId: null, status: 'APPLIED', reasonCode: null, note: null, summary: '30 days free', actorId: 'system', metric: 'GROSS_SALES', metricValue: 104500,
      reference: null, previousTrialEnd: null, newTrialEnd: iso(50),
      attempts: 1, lastError: null, createdAt: iso(-3), appliedAt: iso(-3)
    }],
    orgs: [
      org('org-ades-kitchen', 'Ade’s Kitchen', 'SELLER', 'seller_growth', 'active', facts(104500, 42, 61, 0, 40), [invoice('inv_ades1', 3900, 'paid', 10, 2500), invoice('inv_ades2', 3900, 'open', 1)]),
      org('org-lagos-textile', 'Lagos Textile Hub', 'SELLER', 'seller_pro', 'active', facts(64000, 18, 22, 0, 75), [invoice('inv_lth1', 12900, 'paid', 5)]),
      org('org-braids-by-bisi', 'Braids by Bisi', 'PROVIDER', 'provider_studio', 'trialing', facts(0, 0, 0, 3, 18), []),
      // A seller still in its 30-day trial (S299: in the window of a low-sales relief rule).
      org('org-mama-put', 'Mama Put', 'SELLER', 'seller_growth', 'trialing', facts(32000, 6, 5, 0, 18), [])
    ],
    keys: {},
    settings: { earningsCollectionEnabled: true, planFeeCollectionDefault: 'CARD', updatedBy: 'system', updatedAt: iso(-60) },
    seq: 1
  };
}

const GateBody = z.object({
  mode: z.enum(['START_AFTER', 'WAIVE_WHILE', 'WAIVE_UNTIL']),
  metric: z.enum(['GROSS_SALES', 'COMPLETED_ORDERS', 'COMPLETED_BOOKINGS', 'ACTIVE_LISTINGS']).nullish(),
  threshold: z.number().int().min(1).nullish(),
  until: z.string().nullish(),
  reasonCode: z.string(),
  note: z.string().max(500).nullish()
});
const PlanBody = z.object({
  name: z.string().trim().min(1, 'Enter a name').max(80), tagline: z.string().max(120).nullish(), features: z.array(z.string().max(200)).max(20).default([]),
  limits: z.record(z.string(), z.string()).optional(), visibility: z.enum(['PUBLIC', 'HIDDEN', 'INVITE_ONLY']), sortOrder: z.number().int().optional()
});
const PricesBody = z.object({
  monthlyCents: z.number().int().min(0), quarterlyCents: z.number().int().min(0), annualCents: z.number().int().min(0), trialDays: z.number().int().min(0).max(365), note: z.string().nullish()
});
const CreatePlanBody = PlanBody.extend({
  code: z.string().regex(/^[a-z][a-z0-9_]{2,39}$/, 'Lower-case letters, digits and _ (3–40)'), kind: z.enum(['SELLER', 'PROVIDER']), prices: PricesBody
});
const CampaignBody = z.object({
  name: z.string().trim().min(1, 'Enter a name'), promoCode: z.string().nullish(), trialDays: z.number().int().min(1, 'At least 1 day').max(365), signupFrom: z.string().nullish(),
  signupTo: z.string().nullish(), kind: z.enum(['SELLER', 'PROVIDER']).nullish(), eligiblePlans: z.array(z.string()).default([]), maxRedemptions: z.number().int().min(1).nullish(),
  status: z.enum(['ACTIVE', 'PAUSED', 'ENDED']).nullish(), expectedVersion: z.number().int().nullish()
});
const RuleBody = z.object({
  name: z.string().trim().min(1, 'Enter a name'), description: z.string().nullish(), kind: z.enum(['SELLER', 'PROVIDER']).nullish(),
  metric: z.enum(['GROSS_SALES', 'ACTIVE_LISTINGS', 'COMPLETED_ORDERS', 'COMPLETED_BOOKINGS', 'DAYS_SINCE_APPROVAL']), threshold: z.number().int().min(1, 'At least 1'),
  windowDays: z.number().int().min(1).nullish(), withinDaysOfApproval: z.number().int().min(1).nullish(),
  comparison: z.enum(['AT_LEAST', 'LESS_THAN']).nullish(), windowAnchor: z.enum(['APPROVAL', 'SUBSCRIPTION_START']).nullish(), checkAtWindowEnd: z.boolean().nullish(),
  checkLeadHours: z.number().int().min(0).max(168).nullish(), rewardType: z.enum(['FREE_DAYS', 'CREDIT', 'PERCENT_OFF']),
  freeDays: z.number().int().min(1).nullish(), creditCents: z.number().int().min(1).nullish(), percentOff: z.number().min(1).max(100).nullish(),
  durationInvoices: z.number().int().min(1).nullish(), eligiblePlans: z.array(z.string()).default([]), startsAt: z.string().nullish(), endsAt: z.string().nullish(),
  maxGrants: z.number().int().min(1).nullish(), status: z.enum(['DRAFT', 'ACTIVE', 'PAUSED', 'ENDED']).nullish(), expectedVersion: z.number().int().nullish(), ruleId: z.string().nullish()
});
const AdjustBody = z.object({ reasonCode: z.string().min(1, 'Choose a reason'), note: z.string().max(500).nullish() });
const StatusBody = z.object({ status: z.string() });

const metricLabel = (m: RewardMetric, v: number) =>
  m === 'GROSS_SALES' ? `${usd(v)} in sales` : m === 'ACTIVE_LISTINGS' ? `${v} active listings` : m === 'COMPLETED_ORDERS' ? `${v} completed orders` : m === 'COMPLETED_BOOKINGS' ? `${v} completed bookings` : `${v} days since approval`;

function ruleLabels(r: z.infer<typeof RuleBody>): { rewardLabel: string; conditionLabel: string } {
  const rewardLabel = r.rewardType === 'FREE_DAYS' ? `${r.freeDays ?? 0} days free` : r.rewardType === 'CREDIT' ? `${usd(r.creditCents ?? 0)} credit` : `${r.percentOff ?? 0}% off ${r.durationInvoices ?? 1} invoices`;
  const atEnd = atWindowEnd(r);
  let conditionLabel = (r.comparison === 'LESS_THAN' ? 'less than ' : '') + metricLabel(r.metric, r.threshold);
  if (r.windowDays) conditionLabel += ` in ${r.windowDays} days`;
  const of = r.windowAnchor === 'SUBSCRIPTION_START' ? 'the subscription' : 'approval';
  if (r.withinDaysOfApproval) conditionLabel += atEnd ? ` in the first ${r.withinDaysOfApproval} days of ${of}` : ` within ${r.withinDaysOfApproval} days of ${of}`;
  return { rewardLabel, conditionLabel };
}

/** S299: LESS_THAN rules (and AT_LEAST ones that ask) are decided once, when the window closes. */
const atWindowEnd = (r: z.infer<typeof RuleBody>) => r.comparison === 'LESS_THAN' || !!r.checkAtWindowEnd;
const SUMMED: RewardMetric[] = ['GROSS_SALES', 'COMPLETED_ORDERS', 'COMPLETED_BOOKINGS'];

export function planHandlers(W: string, deps: PreorderHandlerDeps) {
  const { route, json, problem } = deps;
  const B = `${W}/billing`;
  const st = (ctx: PreorderCtx) => ctx.db.plans;
  const view = (ctx: PreorderCtx) => {
    if (!allows(ctx.roles, 'plans')) throw problem(403, 'FORBIDDEN', 'Forbidden', 'Your roles can’t open Plans & billing.');
  };
  const need = (ctx: PreorderCtx, perm: string) => {
    view(ctx);
    if (!ctx.permissions.includes(perm)) throw problem(403, 'FORBIDDEN', 'Forbidden', `This needs the ${perm} permission.`);
  };
  const invalid = (errors: Record<string, string>) => problem(422, 'VALIDATION_FAILED', 'Validation failed', 'One or more fields are invalid.', errors);
  const planOf = (ctx: PreorderCtx, code: string | undefined) => {
    const p = st(ctx).plans.find((x) => x.code === code);
    if (!p) throw problem(404, 'NOT_FOUND', 'Not found', 'No such plan.');
    return p;
  };
  const orgOf = (ctx: PreorderCtx, id: string | undefined) => {
    const o = st(ctx).orgs.find((x) => x.orgId === id);
    if (!o) throw problem(404, 'NOT_FOUND', 'Not found', 'No such organisation.');
    return o;
  };
  const id = (ctx: PreorderCtx, prefix: string) => `${prefix}_${++st(ctx).seq}`;
  const audit = (ctx: PreorderCtx, action: string, entityId: string, summary: string) =>
    ctx.db.audit.unshift({ at: ctx.now.toISOString(), actor: ctx.action.actor, action, summary, entityId });

  const panel = (ctx: PreorderCtx, o: MockBillingOrg): StaffBillingPanel => {
    const plan = st(ctx).plans.find((p) => p.code === o.planCode);
    const cents = plan ? { MONTHLY: plan.monthlyCents, QUARTERLY: plan.quarterlyCents, ANNUAL: plan.annualCents }[o.cadence] : 0;
    return {
      orgId: o.orgId, orgName: o.name, kind: o.kind, orgStatus: o.status,
      billing: {
        orgId: o.orgId, planCode: o.planCode, planName: plan?.name ?? o.planCode, cadence: o.cadence, status: o.subStatus, trialEndsAt: o.trialEndsAt, currentPeriodEnd: o.periodEnd,
        nextChargeAt: o.periodEnd, nextChargeCents: cents, nextChargeLabel: `${usd(cents)} on ${new Date(o.periodEnd).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })}`,
        cancelAtPeriodEnd: false, dunning: null, failedPaymentAttempts: 0, planVersion: plan?.currentVersion ?? 1, creditBalanceCents: o.creditBalanceCents,
        trialSource: o.trialDaysOverride ? 'OVERRIDE' : 'PLAN', trialDaysOverride: o.trialDaysOverride, planFeeCollection: o.planFeeCollection,
        chargeGate: o.chargeGate, entitlements: plan?.entitlements ?? null
      },
      invoices: o.invoices,
      grants: st(ctx).grants.filter((g) => g.orgId === o.orgId),
      collections: o.collections,
      earningsCollectionEnabled: st(ctx).settings.earningsCollectionEnabled,
      planFeeCollection: planFees(ctx, o),
      planAssignments: o.planAssignments ?? []
    };
  };

  /** How the organisation pays its plan (S298), as the seller service answers it. */
  const planFees = (ctx: PreorderCtx, o: MockBillingOrg): StaffPlanFeeCollection => {
    const on = st(ctx).settings.earningsCollectionEnabled;
    const day = new Date(o.trialEndsAt ?? o.periodEnd).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric', timeZone: 'UTC' });
    return {
      orgId: o.orgId, available: on, mode: o.planFeeCollection, effective: on ? o.planFeeCollection : 'CARD', source: o.planFeeSource,
      changedAt: o.planFeeHistory[0]?.createdAt ?? null, changedBy: o.planFeeHistory[0]?.actorId ?? null, platformDefault: st(ctx).settings.planFeeCollectionDefault,
      appliesFromInvoiceAt: o.trialEndsAt ?? o.periodEnd, appliesFromLabel: `Applies from your next invoice, on ${day}`, inProgressInvoiceId: null, history: o.planFeeHistory
    };
  };

  /** Records a staff adjustment once per Idempotency-Key, as the seller service does. */
  const adjust = async (ctx: PreorderCtx, o: MockBillingOrg, type: GrantType, extra: Partial<StaffBillingGrant>, summary: string, apply: () => void): Promise<StaffBillingGrant> => {
    const key = ctx.request.headers.get('idempotency-key');
    const seen = key ? st(ctx).keys[key] : undefined;
    const prior = seen ? st(ctx).grants.find((g) => g.id === seen) : undefined;
    if (prior) return prior;
    const b = await json(ctx.request.clone(), AdjustBody);
    if (!REASON_CODES.some((r) => r.code === b.reasonCode)) throw invalid({ reasonCode: 'Unknown reason code' });
    if (b.reasonCode === 'OTHER' && !b.note?.trim()) throw invalid({ note: 'Say why when the reason is Other' });
    apply();
    const g: StaffBillingGrant = {
      id: id(ctx, 'gr'), orgId: o.orgId, source: 'MANUAL', ruleId: null, type, creditCents: null, freeDays: null, freeMonths: null, percentOff: null, durationInvoices: null, remainingInvoices: null, gateMode: null, invoiceId: null,
      status: 'APPLIED', reasonCode: b.reasonCode, note: b.note ?? null, summary, actorId: ctx.action.actorId, metric: null, metricValue: null,
      reference: null, previousTrialEnd: null, newTrialEnd: null, attempts: 1, lastError: null,
      createdAt: ctx.now.toISOString(), appliedAt: ctx.now.toISOString(), ...extra
    };
    st(ctx).grants.unshift(g);
    if (key) st(ctx).keys[key] = g.id;
    audit(ctx, `billing.${type.toLowerCase()}`, o.orgId, `${summary} for ${o.name} (${b.reasonCode})`);
    return g;
  };

  const ruleFrom = (ctx: PreorderCtx, b: z.infer<typeof RuleBody>, prior?: StaffRewardRule): StaffRewardRule => {
    const errors: Record<string, string> = {};
    if (b.rewardType === 'FREE_DAYS' && !b.freeDays) errors.freeDays = 'Enter the free days';
    if (b.rewardType === 'CREDIT' && !b.creditCents) errors.creditCents = 'Enter the credit';
    if (b.rewardType === 'PERCENT_OFF' && (!b.percentOff || !b.durationInvoices)) errors.percentOff = 'Enter the discount and the number of invoices';
    if (atWindowEnd(b)) {
      if (!b.withinDaysOfApproval) errors.withinDaysOfApproval = 'A “less than” rule needs a window: the first N days';
      if (b.windowDays) errors.windowDays = 'Leave the rolling window empty: the whole window counts';
      if (!SUMMED.includes(b.metric)) errors.metric = 'Choose sales, completed orders or completed bookings';
    }
    if (Object.keys(errors).length) throw invalid(errors);
    return {
      id: prior?.id ?? id(ctx, 'rr'), name: b.name, description: b.description ?? null, status: b.status ?? prior?.status ?? 'DRAFT', kind: b.kind ?? null, metric: b.metric, threshold: b.threshold,
      windowDays: b.windowDays ?? null, withinDaysOfApproval: b.withinDaysOfApproval ?? null, comparison: b.comparison ?? 'AT_LEAST', windowAnchor: b.windowAnchor ?? 'APPROVAL',
      checkAtWindowEnd: atWindowEnd(b), checkLeadHours: b.checkLeadHours ?? 24, rewardType: b.rewardType, freeDays: b.freeDays ?? null, creditCents: b.creditCents ?? null,
      percentOff: b.percentOff ?? null, durationInvoices: b.durationInvoices ?? null, eligiblePlans: b.eligiblePlans, startsAt: b.startsAt ?? null, endsAt: b.endsAt ?? null,
      maxGrants: b.maxGrants ?? null, grantsCount: prior?.grantsCount ?? 0, ...ruleLabels(b), createdBy: prior?.createdBy ?? ctx.action.actor, createdAt: prior?.createdAt ?? ctx.now.toISOString(),
      updatedBy: prior ? ctx.action.actor : null, updatedAt: prior ? ctx.now.toISOString() : null, version: (prior?.version ?? -1) + 1
    };
  };

  return [
    http.get(`${B}/plans`, route((ctx) => { view(ctx); return st(ctx).plans; })),
    http.post(`${B}/plans`, route(async (ctx) => {
      need(ctx, 'plans:manage');
      const b = await json(ctx.request, CreatePlanBody);
      if (st(ctx).plans.some((p) => p.code === b.code)) throw invalid({ code: 'Already used' });
      const plan = seedPlan([b.code, b.kind, b.name, b.tagline ?? '', b.prices.monthlyCents, b.prices.trialDays, b.features], st(ctx).plans.length, ctx.now);
      const amounts: Record<PlanCadence, number> = { MONTHLY: b.prices.monthlyCents, QUARTERLY: b.prices.quarterlyCents, ANNUAL: b.prices.annualCents };
      const v = plan.versions[0];
      if (v) Object.assign(v, { status: 'DRAFT', publishedAt: null, subscribers: 0, note: b.prices.note ?? null, createdBy: ctx.action.actor, prices: v.prices.map((p) => ({ ...p, amountCents: amounts[p.cadence] })) });
      Object.assign(plan, { status: 'DRAFT', visibility: b.visibility, quarterlyCents: b.prices.quarterlyCents, annualCents: b.prices.annualCents, subscribers: 0, limits: b.limits ?? {}, entitlements: entitlementsFrom(b.limits ?? {}) });
      st(ctx).plans.push(plan);
      audit(ctx, 'billing.plan.created', plan.code, `Created plan ${plan.name}`);
      return plan;
    }, 201)),
    http.get(`${B}/plans/:code`, route((ctx, p) => { view(ctx); return planOf(ctx, p.code); })),
    http.put(`${B}/plans/:code`, route(async (ctx, p) => {
      need(ctx, 'plans:manage');
      const plan = planOf(ctx, p.code);
      const b = await json(ctx.request, PlanBody.extend({ expectedVersion: z.number().int() }));
      if (b.expectedVersion !== plan.rowVersion) throw problem(409, 'CONFLICT', 'Changed by someone else', 'This plan was changed by someone else. Refresh to see the latest.');
      Object.assign(plan, { name: b.name, tagline: b.tagline ?? null, features: planFeatureList(entitlementsFrom(b.limits ?? plan.limits)), limits: b.limits ?? plan.limits, entitlements: entitlementsFrom(b.limits ?? plan.limits), visibility: b.visibility, sortOrder: b.sortOrder ?? plan.sortOrder, rowVersion: plan.rowVersion + 1 });
      return plan;
    })),
    http.put(`${B}/plans/:code/draft`, route(async (ctx, p) => {
      need(ctx, 'plans:manage');
      const plan = planOf(ctx, p.code);
      const b = await json(ctx.request, PricesBody);
      const amounts: Record<PlanCadence, number> = { MONTHLY: b.monthlyCents, QUARTERLY: b.quarterlyCents, ANNUAL: b.annualCents };
      const draft = plan.versions.find((v) => v.status === 'DRAFT');
      const prices = CADENCES.map((c) => ({ cadence: c, amountCents: amounts[c] }));
      if (draft) Object.assign(draft, { trialDays: b.trialDays, note: b.note ?? null, prices });
      else plan.versions.unshift({ version: Math.max(...plan.versions.map((v) => v.version)) + 1, status: 'DRAFT', trialDays: b.trialDays, note: b.note ?? null, createdBy: ctx.action.actor, createdAt: ctx.now.toISOString(), publishedAt: null, subscribers: 0, prices });
      return plan;
    })),
    http.post(`${B}/plans/:code/publish`, route((ctx, p) => {
      need(ctx, 'plans:manage');
      const plan = planOf(ctx, p.code);
      const draft = plan.versions.find((v) => v.status === 'DRAFT');
      if (!draft) throw problem(409, 'CONFLICT', 'Nothing to publish', 'Save a draft version first.');
      plan.versions.forEach((v) => { if (v.status === 'PUBLISHED') v.status = 'SUPERSEDED'; });
      Object.assign(draft, { status: 'PUBLISHED', publishedAt: ctx.now.toISOString() });
      const by = (c: PlanCadence) => draft.prices.find((x) => x.cadence === c)?.amountCents ?? 0;
      Object.assign(plan, { status: 'ACTIVE', currentVersion: draft.version, trialDays: draft.trialDays, monthlyCents: by('MONTHLY'), quarterlyCents: by('QUARTERLY'), annualCents: by('ANNUAL') });
      audit(ctx, 'billing.plan.published', plan.code, `Published ${plan.name} v${draft.version}`);
      return plan;
    })),
    http.post(`${B}/plans/:code/retire`, route((ctx, p) => {
      need(ctx, 'plans:manage');
      const plan = planOf(ctx, p.code);
      plan.status = 'RETIRED';
      audit(ctx, 'billing.plan.retired', plan.code, `Retired ${plan.name}`);
      return plan;
    })),
    http.put(`${B}/plans/:code/invites`, route(async (ctx, p) => {
      need(ctx, 'plans:manage');
      const plan = planOf(ctx, p.code);
      plan.invites = (await json(ctx.request, z.object({ orgIds: z.array(z.string()) }))).orgIds;
      return plan;
    })),
    http.get(`${B}/plans/:code/migration`, route((ctx, p) => {
      view(ctx);
      const plan = planOf(ctx, p.code);
      const old = plan.versions.find((v) => v.status === 'SUPERSEDED');
      const now = plan.versions.find((v) => v.status === 'PUBLISHED');
      const rows = old && now ? st(ctx).orgs.filter((o) => o.planCode === plan.code).map((o) => ({
        orgId: o.orgId, orgName: o.name, fromVersion: old.version, cadence: o.cadence, fromCents: old.prices.find((x) => x.cadence === o.cadence)?.amountCents ?? 0,
        toCents: now.prices.find((x) => x.cadence === o.cadence)?.amountCents ?? 0, status: o.subStatus, effectiveAt: o.periodEnd
      })) : [];
      return { planCode: plan.code, toVersion: plan.currentVersion, rows };
    })),
    http.post(`${B}/plans/:code/migration`, route(async (ctx, p) => {
      need(ctx, 'plans:manage');
      const plan = planOf(ctx, p.code);
      const b = await json(ctx.request, z.object({ orgIds: z.array(z.string()).default([]) }));
      const migrated = st(ctx).orgs.filter((o) => o.planCode === plan.code && (b.orgIds.length === 0 || b.orgIds.includes(o.orgId))).map((o) => o.orgId);
      return { planCode: plan.code, toVersion: plan.currentVersion, migrated, failed: [] };
    })),
    http.get(`${B}/trial-campaigns`, route((ctx) => { view(ctx); return st(ctx).campaigns; })),
    http.post(`${B}/trial-campaigns`, route(async (ctx) => {
      need(ctx, 'plans:manage');
      const b = await json(ctx.request, CampaignBody);
      const code = b.promoCode?.trim().toUpperCase() || null;
      if (!code && !b.signupFrom) throw invalid({ signupFrom: 'A campaign needs a promo code or a sign-up window' });
      if (code && st(ctx).campaigns.some((c) => c.promoCode === code)) throw invalid({ promoCode: 'Already used' });
      const c: StaffTrialCampaign = {
        id: id(ctx, 'tc'), name: b.name, promoCode: code, trialDays: b.trialDays, signupFrom: b.signupFrom ?? null, signupTo: b.signupTo ?? null, kind: b.kind ?? null, eligiblePlans: b.eligiblePlans,
        maxRedemptions: b.maxRedemptions ?? null, redemptions: 0, status: b.status ?? 'ACTIVE', createdBy: ctx.action.actor, createdAt: ctx.now.toISOString(), updatedBy: null, updatedAt: null, version: 0
      };
      st(ctx).campaigns.unshift(c);
      return c;
    }, 201)),
    http.put(`${B}/trial-campaigns/:id`, route(async (ctx, p) => {
      need(ctx, 'plans:manage');
      const c = st(ctx).campaigns.find((x) => x.id === p.id);
      if (!c) throw problem(404, 'NOT_FOUND', 'Not found', 'No such campaign.');
      const b = await json(ctx.request, CampaignBody);
      if (b.expectedVersion != null && b.expectedVersion !== c.version) throw problem(409, 'CONFLICT', 'Changed by someone else', 'Refresh to see the latest.');
      Object.assign(c, { name: b.name, promoCode: b.promoCode?.trim().toUpperCase() || null, trialDays: b.trialDays, signupFrom: b.signupFrom ?? null, signupTo: b.signupTo ?? null,
        kind: b.kind ?? null, eligiblePlans: b.eligiblePlans, maxRedemptions: b.maxRedemptions ?? null, updatedBy: ctx.action.actorId, updatedAt: ctx.now.toISOString(), version: c.version + 1 });
      return c;
    })),
    http.post(`${B}/trial-campaigns/:id/status`, route(async (ctx, p) => {
      need(ctx, 'plans:manage');
      const c = st(ctx).campaigns.find((x) => x.id === p.id);
      if (!c) throw problem(404, 'NOT_FOUND', 'Not found', 'No such campaign.');
      const { status } = await json(ctx.request, StatusBody);
      if (status !== 'ACTIVE' && status !== 'PAUSED' && status !== 'ENDED') throw invalid({ status: 'Unknown status' });
      Object.assign(c, { status, version: c.version + 1 });
      return c;
    })),

    http.get(`${B}/reward-rules`, route((ctx) => { view(ctx); return st(ctx).rules; })),
    http.post(`${B}/reward-rules/preview`, route(async (ctx) => {
      view(ctx);
      const b = await json(ctx.request, RuleBody);
      const atEnd = atWindowEnd(b);
      const lead = (b.checkLeadHours ?? 24) * 3_600_000;
      const rows: StaffRewardPreviewRow[] = st(ctx).orgs.filter((o) => !b.kind || o.kind === b.kind).map((o) => {
        const value = o.facts[b.metric];
        const eligible = b.eligiblePlans.length === 0 || b.eligiblePlans.includes(o.planCode);
        // Days into the window: since the subscription started (a 30-day trial ending at trialEndsAt) or since approval.
        const sinceSub = o.trialEndsAt ? 30 - Math.round((Date.parse(o.trialEndsAt) - ctx.now.getTime()) / DAY) : o.facts.DAYS_SINCE_APPROVAL;
        const into = b.windowAnchor === 'SUBSCRIPTION_START' ? sinceSub : o.facts.DAYS_SINCE_APPROVAL;
        const late = b.withinDaysOfApproval != null && into > b.withinDaysOfApproval;
        const already = st(ctx).grants.some((g) => g.ruleId != null && g.ruleId === b.ruleId && g.orgId === o.orgId);
        const reason = !eligible ? 'Plan not eligible' : late ? (atEnd ? 'Window closed' : 'Approval window closed') : already ? 'Already granted' : null;
        const holds = b.comparison === 'LESS_THAN' ? value < b.threshold : value >= b.threshold;
        const pending = atEnd && !reason;
        const checkAt = pending && b.withinDaysOfApproval != null ? new Date(ctx.now.getTime() + (b.withinDaysOfApproval - into) * DAY - lead).toISOString() : null;
        const qualifies = !reason && !atEnd && holds;
        const status = already ? 'GRANTED' : qualifies ? 'QUALIFIES' : pending ? 'PENDING' : reason ? 'INELIGIBLE' : 'NOT_YET';
        return { orgId: o.orgId, orgName: o.name, planCode: o.planCode, value, threshold: b.threshold, percent: Math.min(100, Math.floor((value * 100) / b.threshold)),
          qualifies, alreadyGranted: already, reason, status, checkAt };
      });
      rows.sort((x, y) => Number(y.qualifies) - Number(x.qualifies) || Number(y.status === 'PENDING') - Number(x.status === 'PENDING') || y.value - x.value);
      return { evaluated: rows.length, qualifying: rows.filter((r) => r.qualifies).length, pending: rows.filter((r) => r.status === 'PENDING').length, rows };
    })),
    http.post(`${B}/reward-rules`, route(async (ctx) => {
      need(ctx, 'plans:manage');
      const r = ruleFrom(ctx, await json(ctx.request, RuleBody));
      st(ctx).rules.unshift(r);
      return r;
    }, 201)),
    http.put(`${B}/reward-rules/:id`, route(async (ctx, p) => {
      need(ctx, 'plans:manage');
      const i = st(ctx).rules.findIndex((x) => x.id === p.id);
      const prior = st(ctx).rules[i];
      if (!prior) throw problem(404, 'NOT_FOUND', 'Not found', 'No such rule.');
      const b = await json(ctx.request, RuleBody);
      if (b.expectedVersion != null && b.expectedVersion !== prior.version) throw problem(409, 'CONFLICT', 'Changed by someone else', 'Refresh to see the latest.');
      const r = ruleFrom(ctx, b, prior);
      st(ctx).rules[i] = r;
      return r;
    })),
    http.post(`${B}/reward-rules/:id/status`, route(async (ctx, p) => {
      need(ctx, 'plans:manage');
      const r = st(ctx).rules.find((x) => x.id === p.id);
      if (!r) throw problem(404, 'NOT_FOUND', 'Not found', 'No such rule.');
      const { status } = await json(ctx.request, StatusBody);
      if (!['DRAFT', 'ACTIVE', 'PAUSED', 'ENDED'].includes(status)) throw invalid({ status: 'Unknown status' });
      Object.assign(r, { status, version: r.version + 1 });
      return r;
    })),

    http.get(`${B}/grants`, route((ctx) => {
      view(ctx);
      const q = new URL(ctx.request.url).searchParams;
      const text = (q.get('q') ?? '').toLowerCase();
      const items = st(ctx).grants.filter((g) => (!q.get('orgId') || g.orgId === q.get('orgId')) && (!q.get('source') || g.source === q.get('source')) && (!q.get('type') || g.type === q.get('type'))
        && (!q.get('ruleId') || g.ruleId === q.get('ruleId')) && (!text || `${g.orgId} ${g.summary} ${g.reasonCode ?? ''}`.toLowerCase().includes(text)));
      const page = Number(q.get('page') ?? 0);
      const size = Math.min(100, Number(q.get('size') ?? 25));
      return { items: items.slice(page * size, page * size + size), page, size, total: items.length };
    })),
    http.get(`${B}/reason-codes`, route((ctx) => { view(ctx); return REASON_CODES; })),
    http.get(`${B}/orgs`, route((ctx) => {
      view(ctx);
      const q = (new URL(ctx.request.url).searchParams.get('q') ?? '').trim().toLowerCase();
      if (q.length < 2) throw invalid({ q: 'Type at least 2 characters' });
      return { items: st(ctx).orgs.filter((o) => o.name.toLowerCase().includes(q) || o.orgId.includes(q)).map((o) => ({ orgId: o.orgId, name: o.name, kind: o.kind, status: o.status })) };
    })),
    http.get(`${B}/orgs/:orgId`, route((ctx, p) => { view(ctx); return panel(ctx, orgOf(ctx, p.orgId)); })),
    http.post(`${B}/orgs/:orgId/credits`, route(async (ctx, p) => {
      need(ctx, 'billing:adjust');
      const o = orgOf(ctx, p.orgId);
      const { amountCents } = await json(ctx.request.clone(), z.object({ amountCents: z.number().int().min(100, 'At least $1').max(100_000, 'At most $1,000') }));
      return adjust(ctx, o, 'CREDIT', { creditCents: amountCents, reference: `credit_${st(ctx).seq + 1}` }, `${usd(amountCents)} credit`, () => { o.creditBalanceCents += amountCents; });
    }, 201)),
    http.post(`${B}/orgs/:orgId/free-days`, route(async (ctx, p) => {
      need(ctx, 'billing:adjust');
      const o = orgOf(ctx, p.orgId);
      const { days } = await json(ctx.request.clone(), z.object({ days: z.number().int().min(1, 'At least 1 day').max(365) }));
      const from = o.trialEndsAt ?? o.periodEnd;
      const to = new Date(new Date(from).getTime() + days * DAY).toISOString();
      return adjust(ctx, o, 'FREE_DAYS', { freeDays: days, previousTrialEnd: from, newTrialEnd: to }, `${days} days free`, () => {
        Object.assign(o, { trialEndsAt: to, periodEnd: to, subStatus: 'trialing' });
      });
    }, 201)),
    http.post(`${B}/orgs/:orgId/free-months`, route(async (ctx, p) => {
      need(ctx, 'billing:adjust');
      const o = orgOf(ctx, p.orgId);
      const { months } = await json(ctx.request.clone(), z.object({ months: z.number().int().min(1, 'At least 1 month').max(36, 'At most 36 months') }));
      const from = new Date(o.trialEndsAt ?? o.periodEnd);
      const to = new Date(from);
      to.setUTCMonth(to.getUTCMonth() + months);
      return adjust(ctx, o, 'FREE_MONTHS', { freeMonths: months, previousTrialEnd: from.toISOString(), newTrialEnd: to.toISOString() }, `${months} ${months === 1 ? 'month' : 'months'} free`, () => {
        Object.assign(o, { trialEndsAt: to.toISOString(), periodEnd: to.toISOString(), subStatus: 'trialing' });
      });
    }, 201)),
    http.put(`${B}/orgs/:orgId/charge-gate`, route(async (ctx, p) => {
      need(ctx, 'billing:adjust');
      const o = orgOf(ctx, p.orgId);
      const g = await json(ctx.request.clone(), GateBody);
      if (g.mode === 'WAIVE_UNTIL' && !g.until) throw invalid({ until: 'Choose the date the free stretch ends' });
      if (g.mode !== 'WAIVE_UNTIL' && (!g.metric || !g.threshold)) throw invalid({ threshold: 'Say what the seller has to reach' });
      const progress = g.metric ? (o.facts[g.metric === 'GROSS_SALES' ? 'GROSS_SALES' : g.metric === 'ACTIVE_LISTINGS' ? 'ACTIVE_LISTINGS' : g.metric === 'COMPLETED_BOOKINGS' ? 'COMPLETED_BOOKINGS' : 'COMPLETED_ORDERS']) : 0;
      const what = g.metric === 'GROSS_SALES' ? usd(g.threshold ?? 0) + ' in sales' : `${g.threshold} ${g.metric === 'ACTIVE_LISTINGS' ? 'live listings' : g.metric === 'COMPLETED_BOOKINGS' ? 'completed bookings' : 'completed orders'}`;
      const label = g.mode === 'START_AFTER' ? `Charging starts after ${what}` : g.mode === 'WAIVE_WHILE' ? `Free while you have ${what}` : `Free until ${new Date(g.until ?? ctx.now).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric', timeZone: 'UTC' })}`;
      return adjust(ctx, o, 'CHARGE_GATE', { gateMode: g.mode }, label, () => {
        o.chargeGate = { mode: g.mode, metric: g.metric ?? null, threshold: g.threshold ?? null, progress, since: ctx.now.toISOString(), until: g.until ?? null, reasonCode: g.reasonCode,
          note: g.note ?? null, setAt: ctx.now.toISOString(), metAt: null, label };
      });
    }, 201)),
    http.post(`${B}/orgs/:orgId/charge-gate/clear`, route(async (ctx, p) => {
      need(ctx, 'billing:adjust');
      const o = orgOf(ctx, p.orgId);
      if (!o.chargeGate) throw problem(409, 'seller.no-charge-gate', 'No condition set', 'This organisation has no condition on its plan charges.');
      return adjust(ctx, o, 'CHARGE_GATE', { gateMode: null }, 'Charge condition removed', () => { o.chargeGate = null; });
    }, 201)),
    http.post(`${B}/orgs/:orgId/trial`, route(async (ctx, p) => {
      need(ctx, 'billing:adjust');
      const o = orgOf(ctx, p.orgId);
      if (o.subStatus !== 'trialing') throw problem(409, 'seller.not-trialing', 'Not in a trial', 'Only a trial can be changed. Give free days instead.');
      const { days } = await json(ctx.request.clone(), z.object({ days: z.number().int().min(0).max(365) }));
      return adjust(ctx, o, 'TRIAL_END', { freeDays: days }, `Trial set to ${days} days`, () => { o.trialDaysOverride = days; });
    }, 201)),
    http.post(`${B}/orgs/:orgId/invoices/:invoiceId/waive`, route(async (ctx, p) => {
      need(ctx, 'billing:adjust');
      const o = orgOf(ctx, p.orgId);
      const inv = o.invoices.find((i) => i.id === p.invoiceId);
      if (!inv) throw problem(404, 'NOT_FOUND', 'Not found', 'No such invoice.');
      if (inv.status !== 'open') throw problem(409, 'seller.invoice-not-open', 'Invoice not open', 'Only an open invoice can be waived.');
      return adjust(ctx, o, 'WAIVE_INVOICE', { invoiceId: inv.id }, `Invoice ${inv.number ?? inv.id} waived`, () => { Object.assign(inv, { status: 'paid', waivedCents: inv.amountDueCents, amountPaidCents: inv.amountDueCents, paidAt: ctx.now.toISOString(), lastError: null, nextAttemptAt: null, payments: [{ source: 'WAIVER' as const, amountCents: inv.amountDueCents, status: 'APPLIED' as const, note: null, createdAt: ctx.now.toISOString() }] }); });
    }, 201)),
    http.put(`${B}/orgs/:orgId/plan-fee-collection`, route(async (ctx, p) => {
      need(ctx, 'billing:adjust');
      const o = orgOf(ctx, p.orgId);
      const b = await json(ctx.request.clone(), z.object({
        mode: z.enum(['EARNINGS_FIRST', 'CARD'], 'EARNINGS_FIRST or CARD'), reasonCode: z.string().nullish(), note: z.string().max(500).nullish()
      }));
      if (!b.reasonCode || !REASON_CODES.some((r) => r.code === b.reasonCode)) throw invalid({ reasonCode: 'Choose a reason' });
      if (b.reasonCode === 'OTHER' && !b.note?.trim()) throw invalid({ note: 'Explain the reason' });
      if (!st(ctx).settings.earningsCollectionEnabled) {
        throw problem(409, 'seller.earnings-collection-off', 'Paying plans from sales is off', 'Paying plans from sales is switched off on this platform; plan fees are charged to the card.');
      }
      o.planFeeHistory.unshift({ id: id(ctx, 'pfc'), previous: o.planFeeCollection, mode: b.mode, source: 'STAFF', actorId: ctx.action.actorId, reasonCode: b.reasonCode,
        note: b.note?.trim() || null, appliesFrom: o.trialEndsAt ?? o.periodEnd, createdAt: ctx.now.toISOString() });
      Object.assign(o, { planFeeCollection: b.mode, planFeeSource: 'STAFF' });
      audit(ctx, 'billing.plan-fee-collection', o.orgId, `${o.name}: plan fees ${b.mode === 'CARD' ? 'charged to the card' : 'paid from sales first'} (${b.reasonCode})`);
      return planFees(ctx, o);
    })),
    http.get(`${B}/settings`, route((ctx) => { view(ctx); return st(ctx).settings; })),
    http.put(`${B}/settings`, route(async (ctx) => {
      need(ctx, 'plans:manage');
      const b = await json(ctx.request.clone(), z.object({ planFeeCollectionDefault: z.enum(['EARNINGS_FIRST', 'CARD'], 'EARNINGS_FIRST or CARD') }));
      Object.assign(st(ctx).settings, { planFeeCollectionDefault: b.planFeeCollectionDefault, updatedBy: ctx.action.actorId, updatedAt: ctx.now.toISOString() });
      audit(ctx, 'billing.settings', 'plan-fee-collection-default', `New sellers: plan fees ${b.planFeeCollectionDefault === 'CARD' ? 'charged to the card' : 'paid from sales first'}`);
      return st(ctx).settings;
    }))
  ];
}
