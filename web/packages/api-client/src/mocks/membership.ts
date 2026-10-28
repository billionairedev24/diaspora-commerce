/* Mock BFF for AfroTransact membership (S323): the same routes, shapes and refusals as contracts/openapi/gateway-bff.yaml,
   tag Membership. Plus is the default tier ($9.99 a month or $99 a year, a 14 day trial, free delivery and shipping on
   orders from $25.00); Premium shows an upgrade. Credit a membership grants has no way out. State per demo account on
   the mock database (resetDb() restores it). */
import { http } from 'msw';

import {
  type MembershipAccount,
  type MembershipBenefitUse,
  type MembershipCharge,
  MembershipPlanRequestSchema,
  type MembershipSavings,
  type MembershipSummary,
  type MembershipTier,
  type MembershipWaiver
} from '../schemas/membership';
import { type CheckoutPackage } from '../schemas/shopper';
import { body, json, requireUser, route } from './http-kit';
import { MockProblem } from './service';
import { account, type AccountState } from './shopper-state';

const DAY = 86_400_000;
const REFUND_WINDOW_DAYS = 7;
/** A saved card with this id is always declined, so browser tests can see the "card declined" state. */
export const MOCK_DECLINED_CARD = 'pm_declined';

const noNumbers = { minOrderMinor: null, capMinor: null, percentBps: null, hours: null, amountMinor: null };

export const MOCK_MEMBERSHIP_TIERS: MembershipTier[] = [
  {
    code: 'plus', name: 'Plus', description: 'Free delivery and shipping on orders from $25.00, cashback on every order and priority support.', sortOrder: 10, currency: 'USD',
    monthlyPriceMinor: 999, yearlyPriceMinor: 9900, trialDays: 14,
    benefits: [
      { ...noNumbers, code: 'FREE_DELIVERY', minOrderMinor: 2500 },
      { ...noNumbers, code: 'FREE_SHIPPING', minOrderMinor: 2500, capMinor: 1500 },
      { ...noNumbers, code: 'CASHBACK', percentBps: 200, minOrderMinor: 0 },
      { ...noNumbers, code: 'PRIORITY_SUPPORT' }
    ]
  },
  {
    code: 'premium', name: 'Premium', description: 'Everything in Plus on every order, bigger cashback, free returns and early access to pre-orders.', sortOrder: 20, currency: 'USD',
    monthlyPriceMinor: 1999, yearlyPriceMinor: 19_900, trialDays: 14,
    benefits: [
      { ...noNumbers, code: 'FREE_DELIVERY', minOrderMinor: 0 },
      { ...noNumbers, code: 'FREE_SHIPPING', minOrderMinor: 0 },
      { ...noNumbers, code: 'CASHBACK', percentBps: 500, minOrderMinor: 0 },
      { ...noNumbers, code: 'EARLY_ACCESS', hours: 24 },
      { ...noNumbers, code: 'PRIORITY_SUPPORT' },
      { ...noNumbers, code: 'FREE_RETURNS' }
    ]
  }
];

const tierOf = (code: string | null | undefined) => MOCK_MEMBERSHIP_TIERS.find((t) => t.code === code) ?? null;
const priceOf = (t: MembershipTier, interval: 'MONTHLY' | 'YEARLY') => (interval === 'YEARLY' ? t.yearlyPriceMinor : t.monthlyPriceMinor);

export interface MockMembershipState {
  membership: Omit<MembershipSummary, 'tierName' | 'pendingTierName' | 'nextRetryAt' | 'freeDaysGranted' | 'source' | 'currency'> | null;
  charges: MembershipCharge[];
  uses: MembershipBenefitUse[];
  /** A trial is once per customer. */
  trialUsed: boolean;
  keys: Map<string, MembershipAccount>;
}

const states = new WeakMap<AccountState, MockMembershipState>();

export function membershipState(a: AccountState): MockMembershipState {
  let s = states.get(a);
  if (!s) {
    s = { membership: null, charges: [], uses: [], trialUsed: false, keys: new Map() };
    states.set(a, s);
  }
  return s;
}

const isMember = (s: MockMembershipState) => !!s.membership && ['TRIALING', 'ACTIVE', 'PAST_DUE'].includes(s.membership.status);

function refundRule(s: MockMembershipState, now: Date): MembershipAccount['refund'] {
  const last = s.charges.find((c) => c.status === 'SUCCEEDED' && c.amountMinor > 0);
  const base = { amountMinor: last?.amountMinor ?? 0, currency: 'USD', windowDays: REFUND_WINDOW_DAYS };
  if (!s.membership || !last) return { ...base, eligible: false, reason: 'NOT_PAID', until: null };
  const until = new Date(Date.parse(last.createdAt) + REFUND_WINDOW_DAYS * DAY);
  if (until.getTime() < now.getTime()) return { ...base, eligible: false, reason: 'WINDOW_PASSED', until: until.toISOString() };
  if (s.uses.length) return { ...base, eligible: false, reason: 'BENEFIT_USED', until: until.toISOString() };
  return { ...base, eligible: true, reason: 'OK', until: until.toISOString() };
}

function accountView(a: AccountState, now: Date): MembershipAccount {
  const s = membershipState(a);
  const m = s.membership;
  const tier = tierOf(m?.tierCode);
  const pending = tierOf(m?.pendingTierCode);
  const card = a.paymentMethods.find((p) => p.isDefault) ?? a.paymentMethods[0];
  const usage = new Map<string, { benefit: string; uses: number; amountMinor: number; currency: string }>();
  for (const u of s.uses) {
    const row = usage.get(u.benefit) ?? { benefit: u.benefit, uses: 0, amountMinor: 0, currency: u.currency };
    row.uses += 1;
    row.amountMinor += u.amountMinor;
    usage.set(u.benefit, row);
  }
  return {
    member: isMember(s),
    membership: m && tier
      ? { ...m, source: 'PAID', currency: 'USD', tierName: tier.name, pendingTierName: pending?.name ?? null, nextRetryAt: null, freeDaysGranted: 0 }
      : null,
    tier,
    pendingTier: pending,
    charges: s.charges,
    usage: [...usage.values()],
    refund: refundRule(s, now),
    trialAvailable: !s.trialUsed && !m,
    card: card ? { brand: card.brand ?? null, last4: card.last4 ?? null, expMonth: null, expYear: null } : null,
    tiers: MOCK_MEMBERSHIP_TIERS
  };
}

/** Test and demo helper: the account had its free trial already, so joining charges the first fee at once. */
export function markTrialUsed(userId: string): void {
  membershipState(account(userId)).trialUsed = true;
}

/* ── What a membership does for an order's fees ─────────────────── */

function packageDeliveryMap(raw: string | null | undefined): Map<string, string> {
  const byPkg = new Map<string, string>();
  for (const part of (raw ?? '').split(',')) {
    const i = part.lastIndexOf(':'); // package ids hold a colon ("seller:FULFILMENT"), option keys never
    if (i > 0) byPkg.set(part.slice(0, i).trim(), part.slice(i + 1).trim());
  }
  return byPkg;
}

const benefitForOption = (key: string) => (key === 'afrotransact_delivery' ? 'FREE_DELIVERY' : key === 'express' || key === 'pickup' ? null : 'FREE_SHIPPING');

/** Which delivery fees the membership waives (order minimum and per-order cap of the tier) and, for a shopper who is
    not a member, what joining the entry tier would save: the same rule payments applies when the order is placed. */
export function membershipQuote(s: MockMembershipState, packages: CheckoutPackage[], packageDelivery: string | null | undefined): MembershipSavings {
  const chosen = packageDeliveryMap(packageDelivery);
  const items = packages.reduce((sum, p) => sum + p.subtotalMinor, 0);
  const member = isMember(s);
  const tier = member ? tierOf(s.membership?.tierCode) : MOCK_MEMBERSHIP_TIERS[0];
  const waivers: MembershipWaiver[] = [];
  const spent = new Map<string, number>();
  for (const p of packages) {
    const opt = p.deliveryOptions.find((o) => o.key === chosen.get(p.packageId)) ?? p.deliveryOptions[0];
    const benefit = opt ? benefitForOption(opt.key) : null;
    const rule = tier?.benefits.find((b) => b.code === benefit);
    if (!opt || !benefit || !rule || opt.feeMinor <= 0 || items < (rule.minOrderMinor ?? 0)) continue;
    const room = rule.capMinor === null ? opt.feeMinor : Math.max(0, rule.capMinor - (spent.get(benefit) ?? 0));
    const waived = Math.min(opt.feeMinor, room);
    if (waived <= 0) continue;
    spent.set(benefit, (spent.get(benefit) ?? 0) + waived);
    waivers.push({ packageId: p.packageId, seller: p.seller.name, benefit: benefit as MembershipWaiver['benefit'], waivedMinor: waived });
  }
  const total = waivers.reduce((sum, w) => sum + w.waivedMinor, 0);
  return {
    member,
    tierName: member ? (tier?.name ?? null) : null,
    waivers: member ? waivers : [],
    totalWaivedMinor: member ? total : 0,
    upsell: !member && tier && total > 0
      ? { tierCode: tier.code, tierName: tier.name, monthlyPriceMinor: tier.monthlyPriceMinor, yearlyPriceMinor: tier.yearlyPriceMinor, trialDays: tier.trialDays, savingMinor: total, currency: 'USD' }
      : null
  };
}

/** The benefit a member used on an order: remembered so the refund rule and the benefits list tell the truth. */
export function recordMembershipUse(s: MockMembershipState, savings: MembershipSavings, now: Date): void {
  const tierName = savings.tierName ?? '';
  for (const w of savings.waivers) s.uses.unshift({ benefit: w.benefit, tierName, amountMinor: w.waivedMinor, currency: 'USD', createdAt: now.toISOString() });
}

/* ── Handlers ─────────────────────────────────────────────────── */

const problemCodes = {
  exists: () => new MockProblem(409, 'MEMBERSHIP_EXISTS', 'Already a member', 'You are already a member. Change your plan instead.'),
  notFound: () => new MockProblem(404, 'MEMBERSHIP_NOT_FOUND', 'Not a member', 'You are not a member yet.'),
  tier: () => new MockProblem(409, 'MEMBERSHIP_TIER_UNAVAILABLE', 'Membership not available', 'That membership is not open to new members.'),
  noCard: () => new MockProblem(422, 'MEMBERSHIP_NO_CARD', 'Save a card first', 'Membership is billed to a saved card. Add a card in Your Payments and try again.'),
  declined: () => new MockProblem(422, 'MEMBERSHIP_CHARGE_FAILED', 'Card declined', 'Your card was not charged and nothing changed. Try another card.'),
  noChange: () => new MockProblem(409, 'MEMBERSHIP_NO_CHANGE', 'Nothing to change', 'That is your current plan.'),
  noRefund: () => new MockProblem(409, 'MEMBERSHIP_REFUND_NOT_ELIGIBLE', 'No refund', 'The fee can no longer be refunded. You can still cancel and keep your benefits until the period ends.')
};

function checkKey(key: string) {
  if (key.length < 16 || key.length > 64) throw new MockProblem(422, 'VALIDATION_FAILED', 'Validation failed', 'Send a 16–64 character Idempotency-Key header.', { errors: { 'Idempotency-Key': 'Required' } });
}

const mine = (ctx: Parameters<typeof requireUser>[0]) => account(requireUser(ctx).id);

export const membershipHandlers = [
  http.get('*/v1/membership/tiers', route(() => json({ tiers: MOCK_MEMBERSHIP_TIERS }))),
  http.get('*/v1/account/membership', route((ctx) => json(accountView(mine(ctx), ctx.now)))),

  http.post('*/v1/account/membership', route(async (ctx) => {
    const a = mine(ctx);
    const s = membershipState(a);
    const key = ctx.request.headers.get('idempotency-key') ?? '';
    checkKey(key);
    const replay = s.keys.get(key);
    if (replay) return json(replay, 201);
    const req = await body(ctx.request, MembershipPlanRequestSchema);
    const tier = tierOf(req.tierCode);
    if (!tier) throw problemCodes.tier();
    if (isMember(s)) throw problemCodes.exists();
    if (!a.paymentMethods.length) throw problemCodes.noCard();
    if (req.paymentMethodId === MOCK_DECLINED_CARD) throw problemCodes.declined();
    const price = priceOf(tier, req.interval);
    const trial = !s.trialUsed && tier.trialDays > 0;
    const end = new Date(ctx.now.getTime() + (trial ? tier.trialDays : req.interval === 'YEARLY' ? 365 : 30) * DAY);
    s.trialUsed = s.trialUsed || trial;
    s.membership = { status: trial ? 'TRIALING' : 'ACTIVE', tierCode: tier.code, interval: req.interval, priceMinor: price, startedAt: ctx.now.toISOString(), periodEnd: end.toISOString(),
      trialEnd: trial ? end.toISOString() : null, cancelAtPeriodEnd: false, endedAt: null, pendingTierCode: null, pendingInterval: null };
    if (!trial) s.charges.unshift({ kind: 'JOIN', amountMinor: price, currency: 'USD', status: 'SUCCEEDED', refundedMinor: 0, periodEnd: end.toISOString(), createdAt: ctx.now.toISOString() });
    const view = accountView(a, ctx.now);
    s.keys.set(key, view);
    return json(view, 201);
  })),

  http.put('*/v1/account/membership', route(async (ctx) => {
    const a = mine(ctx);
    const s = membershipState(a);
    const key = ctx.request.headers.get('idempotency-key') ?? '';
    checkKey(key);
    const replay = s.keys.get(key);
    if (replay) return json(replay);
    const req = await body(ctx.request, MembershipPlanRequestSchema);
    const m = s.membership;
    if (!m || !isMember(s)) throw problemCodes.notFound();
    const tier = tierOf(req.tierCode);
    if (!tier) throw problemCodes.tier();
    const current = tierOf(m.tierCode);
    if (!current) throw problemCodes.notFound();
    if (req.tierCode === m.tierCode && req.interval === m.interval) {
      if (!m.pendingTierCode) throw problemCodes.noChange();
      m.pendingTierCode = null;
      m.pendingInterval = null;
    } else if (tier.sortOrder > current.sortOrder) {
      // Upgrade now: the difference for what is left of the period, then the new price.
      const left = Math.max(0, Date.parse(m.periodEnd) - ctx.now.getTime());
      const periodMs = (m.interval === 'YEARLY' ? 365 : 30) * DAY;
      const diff = Math.max(0, priceOf(tier, req.interval) - m.priceMinor);
      const prorated = m.status === 'TRIALING' ? 0 : Math.round(diff * Math.min(1, left / periodMs));
      if (prorated > 0) s.charges.unshift({ kind: 'UPGRADE', amountMinor: prorated, currency: 'USD', status: 'SUCCEEDED', refundedMinor: 0, periodEnd: m.periodEnd, createdAt: ctx.now.toISOString() });
      Object.assign(m, { tierCode: tier.code, interval: req.interval, priceMinor: priceOf(tier, req.interval), pendingTierCode: null, pendingInterval: null });
    } else {
      m.pendingTierCode = tier.code;
      m.pendingInterval = req.interval;
    }
    const view = accountView(a, ctx.now);
    s.keys.set(key, view);
    return json(view);
  })),

  http.post('*/v1/account/membership/cancel', route(async (ctx) => {
    const a = mine(ctx);
    const s = membershipState(a);
    const req = (await ctx.request.json().catch(() => ({}))) as { refund?: boolean };
    if (!s.membership || !isMember(s)) throw problemCodes.notFound();
    if (req.refund) {
      const rule = refundRule(s, ctx.now);
      if (!rule.eligible) throw problemCodes.noRefund();
      const last = s.charges.find((c) => c.status === 'SUCCEEDED' && c.amountMinor > 0);
      if (last) Object.assign(last, { status: 'REFUNDED', refundedMinor: last.amountMinor });
      Object.assign(s.membership, { status: 'CANCELED', cancelAtPeriodEnd: false, endedAt: ctx.now.toISOString() });
    } else {
      s.membership.cancelAtPeriodEnd = true;
    }
    return json(accountView(a, ctx.now));
  })),

  http.post('*/v1/account/membership/resume', route((ctx) => {
    const a = mine(ctx);
    const s = membershipState(a);
    if (!s.membership || !isMember(s)) throw problemCodes.notFound();
    Object.assign(s.membership, { cancelAtPeriodEnd: false, pendingTierCode: null, pendingInterval: null });
    return json(accountView(a, ctx.now));
  })),

  http.get('*/v1/account/membership/benefits', route((ctx) => {
    const limit = Math.min(100, Math.max(1, Number(new URL(ctx.request.url).searchParams.get('limit') ?? 20) || 20));
    return json({ items: membershipState(mine(ctx)).uses.slice(0, limit), nextCursor: null });
  }))
];
