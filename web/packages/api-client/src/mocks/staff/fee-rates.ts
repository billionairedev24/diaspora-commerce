/* Mock of Fees & commissions › negotiated rates, the rate calculator and negotiated plan prices
   (gateway-bff-staff.yaml tag Fees) over payments' negotiated rates and fee schedule. Precedence, validation and
   permissions follow the Go BFF (internal/app/staff_fee_rates.go) and payments (FeeSchedule.rateFor). */
import { http } from 'msw';
import { z } from 'zod';

import {
  type FeeKind,
  type FeeRateQuote,
  type NegotiatedPlan,
  type NegotiatedRate,
  type NegotiatedRatePage,
  type NegotiatedRateStatus,
  NegotiatedRateStatusSchema,
  NegotiatedRateTypeSchema,
  type RateSource
} from '../../fee-rates';
import { applyBps } from '../../fees';
import { ALL_CATEGORIES, normalizeFeeCategory } from '../../validation/fee-draft';
import { FEE_ORGS, MOCK_RETAIL_ORG_ID, type MockFeeSchedule, planName } from './fees';
import { allows } from './model';
import { type PreorderCtx, type PreorderHandlerDeps } from './preorders';

export interface MockNegotiatedRate {
  id: string;
  orgId: string;
  type: 'COMMISSION' | 'CONNECTION_FEE' | 'FBA_FEE';
  category: string;
  rateBps: number;
  amountMinor: number | null;
  startsAt: string;
  endsAt: string | null;
  reason: string;
  negotiatedBy: string;
  reference: string | null;
  createdBy: string;
  createdAt: string;
  endedAt: string | null;
  endedBy: string | null;
  endReason: string | null;
  expiredAt: string | null;
  audit: { action: 'CREATED' | 'ENDED' | 'EXPIRED'; actor: string; reason: string | null; at: string }[];
}

export interface MockFeeRatesState {
  rates: MockNegotiatedRate[];
  /** Organisation → plan code (payments' projection of seller.subscription.changed). */
  plans: Record<string, string>;
  seq: number;
}

export function seedFeeRates(now: Date): MockFeeRatesState {
  const day = 86_400_000;
  const at = (d: number) => new Date(now.getTime() + d * day).toISOString();
  const rate = (r: Partial<MockNegotiatedRate> & Pick<MockNegotiatedRate, 'id' | 'orgId' | 'type' | 'category' | 'rateBps' | 'startsAt' | 'reason'>): MockNegotiatedRate => ({
    amountMinor: null, endsAt: null, negotiatedBy: 'Musa Bello', reference: null, createdBy: 'Musa Bello', createdAt: r.startsAt, endedAt: null, endedBy: null, endReason: null, expiredAt: null,
    audit: [{ action: 'CREATED', actor: 'Musa Bello', reason: r.reason, at: r.startsAt }], ...r
  });
  return {
    rates: [
      rate({ id: 'nr-kente-groceries', orgId: 'org-kente', type: 'COMMISSION', category: 'groceries', rateBps: 600, startsAt: at(-30), endsAt: at(80),
        reason: 'Wholesale partner: 6 % on groceries for the first year', reference: 'SF-1001' }),
      rate({ id: 'nr-braids', orgId: 'org-braids', type: 'CONNECTION_FEE', category: ALL_CATEGORIES, rateBps: 500, startsAt: at(-10), endsAt: at(50),
        reason: 'Launch provider: 5 % connection fee', negotiatedBy: 'Ada Obi', reference: 'TKT-208' }),
      rate({ id: 'nr-ade-old', orgId: 'org-ade', type: 'COMMISSION', category: ALL_CATEGORIES, rateBps: 900, startsAt: at(-200), endsAt: at(-20),
        reason: 'Launch partner (first 6 months)', expiredAt: at(-20) })
    ],
    plans: { 'org-kente': 'seller_growth', 'org-ade': 'seller_essentials', 'org-lagos': 'seller_pro', 'org-braids': 'provider_studio' },
    seq: 1
  };
}

export function rateStatus(r: MockNegotiatedRate, now: Date): NegotiatedRateStatus {
  const t = now.getTime();
  if (r.endedAt && new Date(r.endedAt).getTime() <= t && (!r.endsAt || r.endedAt < r.endsAt)) return 'ENDED';
  if (r.endsAt && new Date(r.endsAt).getTime() <= t) return 'EXPIRED';
  return new Date(r.startsAt).getTime() > t ? 'SCHEDULED' : 'ACTIVE';
}

const orgName = (orgId: string) => FEE_ORGS.find((o) => o.id === orgId)?.name ?? null;
const catLabel = (c: string) => (c === ALL_CATEGORIES ? 'All categories' : c.charAt(0).toUpperCase() + c.slice(1));

function view(r: MockNegotiatedRate, now: Date, canConfigure: boolean): NegotiatedRate {
  const status = rateStatus(r, now);
  const { audit, ...rest } = r;
  return { ...rest, orgName: orgName(r.orgId), categoryLabel: catLabel(r.category), status, audit: [...audit], canEnd: canConfigure && (status === 'ACTIVE' || status === 'SCHEDULED') };
}

/** payments' FeeSchedule.rateFor: negotiated (category, then *) → plan (category, then *) → category → default. */
export function effectiveRate(schedule: MockFeeSchedule, st: MockFeeRatesState, orgId: string, category: string, kind: FeeKind, now: Date) {
  const cat = normalizeFeeCategory(category);
  const plan = st.plans[orgId] ?? null;
  if (orgId === MOCK_RETAIL_ORG_ID) return { bps: 0, source: 'RETAIL' as RateSource, plan: null, rate: null as MockNegotiatedRate | null };
  const type = kind === 'SERVICE' ? 'CONNECTION_FEE' : 'COMMISSION';
  const live = st.rates.filter((r) => r.orgId === orgId && r.type === type && rateStatus(r, now) === 'ACTIVE');
  for (const key of [cat, ALL_CATEGORIES]) {
    const r = live.find((x) => x.category === key);
    if (r) return { bps: r.rateBps, source: 'OVERRIDE' as RateSource, plan, rate: r };
  }
  const section = kind === 'SERVICE'
    ? (schedule.connectionFee ?? { defaultBps: schedule.categoryBps.services ?? schedule.defaultCommissionBps, categoryBps: {}, planBps: {} })
    : { defaultBps: schedule.defaultCommissionBps, categoryBps: schedule.categoryBps, planBps: schedule.planBps };
  const pr = plan ? (section.planBps[plan]?.[cat] ?? section.planBps[plan]?.[ALL_CATEGORIES]) : undefined;
  if (pr !== undefined) return { bps: pr, source: 'PLAN' as RateSource, plan, rate: null };
  const c = section.categoryBps[cat];
  if (c !== undefined) return { bps: c, source: 'CATEGORY' as RateSource, plan, rate: null };
  return { bps: section.defaultBps, source: 'DEFAULT' as RateSource, plan, rate: null };
}

const NegotiatedBody = z.object({
  orgId: z.string(),
  type: z.string(),
  category: z.string().nullish(),
  rateBps: z.number().int().nullish(),
  amountMinor: z.number().int().nullish(),
  startsAt: z.string().nullish(),
  endsAt: z.string().nullish(),
  reason: z.string(),
  negotiatedBy: z.string().nullish(),
  reference: z.string().nullish()
});

const PlanBody = z.object({
  basePlanCode: z.string(),
  monthlyCents: z.number().int().nullish(),
  quarterlyCents: z.number().int().nullish(),
  annualCents: z.number().int().nullish(),
  reason: z.string(),
  reference: z.string().nullish()
});

export function feeRateHandlers(W: string, deps: PreorderHandlerDeps, activeSchedule: (ctx: PreorderCtx) => MockFeeSchedule | null) {
  const { route, json, problem } = deps;
  const F = `${W}/fees`;
  const invalid = (errors: Record<string, string>) => problem(422, 'VALIDATION_FAILED', 'Validation failed', 'One or more fields are invalid.', errors);
  const canConfigure = (ctx: PreorderCtx) => ctx.permissions.includes('payments:configure') && allows(ctx.roles, 'fees', 'EDIT');
  const view$ = (ctx: PreorderCtx) => {
    if (!allows(ctx.roles, 'fees')) throw problem(403, 'FORBIDDEN', 'Forbidden', 'Your roles can’t open fees, commissions & tax.');
  };
  const configure = (ctx: PreorderCtx) => {
    if (!allows(ctx.roles, 'fees', 'EDIT')) throw problem(403, 'FORBIDDEN', 'Forbidden', 'Your roles can’t change fees, commissions & tax.');
    if (!ctx.permissions.includes('payments:configure')) throw problem(403, 'FORBIDDEN', 'Forbidden', 'This needs the payments:configure permission.');
  };
  const st = (ctx: PreorderCtx) => ctx.db.feeRates;
  return [
    http.get(
      `${F}/negotiated-rates`,
      route((ctx): NegotiatedRatePage => {
        view$(ctx);
        const q = new URL(ctx.request.url).searchParams;
        const status = q.get('status') ?? '';
        const type = q.get('type') ?? '';
        const errors: Record<string, string> = {};
        if (status && !NegotiatedRateStatusSchema.safeParse(status).success) errors.status = 'SCHEDULED, ACTIVE, ENDED or EXPIRED';
        if (type && !NegotiatedRateTypeSchema.safeParse(type).success) errors.type = 'COMMISSION, CONNECTION_FEE or FBA_FEE';
        if (Object.keys(errors).length) throw invalid(errors);
        const page = Number(q.get('page') ?? 0);
        const size = Number(q.get('size') ?? 25);
        const needle = (q.get('q') ?? '').trim().toLowerCase();
        const org = q.get('orgId') ?? '';
        const can = canConfigure(ctx);
        const rows = [...st(ctx).rates]
          .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
          .map((r) => view(r, ctx.now, can))
          .filter((r) => (!org || r.orgId === org) && (!type || r.type === type) && (!status || r.status === status))
          .filter((r) => !needle || [r.orgId, r.orgName ?? '', r.reason, r.reference ?? '', r.negotiatedBy, r.category].join(' ').toLowerCase().includes(needle));
        return { items: rows.slice(page * size, page * size + size), total: rows.length, page, size, canConfigure: can };
      })
    ),
    http.post(
      `${F}/negotiated-rates`,
      route(async (ctx) => {
        configure(ctx);
        const b = await json(ctx.request, NegotiatedBody);
        const errors: Record<string, string> = {};
        const orgId = b.orgId.trim();
        if (!orgId) errors.orgId = 'Choose a seller or provider organisation';
        else if (orgId === MOCK_RETAIL_ORG_ID) errors.orgId = 'AfroTransact Retail pays no commission and no fees (merchant of record); nothing to negotiate';
        if (b.type !== 'COMMISSION' && b.type !== 'CONNECTION_FEE' && b.type !== 'FBA_FEE') errors.type = 'Choose commission, connection fee or FBA fee';
        if (b.type === 'FBA_FEE') {
          if (b.amountMinor === null || b.amountMinor === undefined) errors.amountMinor = 'Enter the fee per unit';
          else if (b.amountMinor < 0 || b.amountMinor > 10_000) errors.amountMinor = 'Enter a fee between $0.00 and $100.00';
        } else if (b.rateBps === null || b.rateBps === undefined) errors.rateBps = 'Enter a rate';
        else if (b.rateBps < 0 || b.rateBps > 5000) errors.rateBps = 'Enter a rate between 0.00% and 50.00%';
        const start = b.startsAt && new Date(b.startsAt).getTime() > ctx.now.getTime() ? new Date(b.startsAt) : ctx.now;
        if (b.startsAt && new Date(b.startsAt).getTime() < ctx.now.getTime() - 60_000) errors.startsAt = 'Choose now or a later time: a negotiated rate never re-prices past orders';
        if (b.endsAt && new Date(b.endsAt).getTime() <= start.getTime()) errors.endsAt = 'The end must be after the start';
        const reason = b.reason.trim();
        if (reason.length < 3 || reason.length > 500) errors.reason = 'Say why this rate was negotiated (3 to 500 characters)';
        if (Object.keys(errors).length) throw invalid(errors);
        const category = b.type !== 'FBA_FEE' && b.category && normalizeFeeCategory(b.category) ? normalizeFeeCategory(b.category) : ALL_CATEGORIES;
        const type = b.type as MockNegotiatedRate['type'];
        const end = b.endsAt ? new Date(b.endsAt).getTime() : Infinity;
        const clash = st(ctx).rates.find((r) => {
          if (r.orgId !== orgId || r.type !== type || r.category !== category) return false;
          const rEnd = r.endedAt ? new Date(r.endedAt).getTime() : r.endsAt ? new Date(r.endsAt).getTime() : Infinity;
          return start.getTime() < rEnd && new Date(r.startsAt).getTime() < end;
        });
        if (clash) throw problem(409, 'CONFLICT', 'Conflict', `A negotiated ${type === 'COMMISSION' ? 'commission' : type === 'FBA_FEE' ? 'FBA fee' : 'connection fee'} for ${catLabel(category).toLowerCase()} already applies; end it first.`);
        const created: MockNegotiatedRate = {
          id: `nr-${st(ctx).seq++}`, orgId, type, category, rateBps: type === 'FBA_FEE' ? 0 : (b.rateBps ?? 0), amountMinor: type === 'FBA_FEE' ? (b.amountMinor ?? 0) : null, startsAt: start.toISOString(), endsAt: b.endsAt ? new Date(b.endsAt).toISOString() : null,
          reason, negotiatedBy: b.negotiatedBy?.trim() || ctx.action.actor, reference: b.reference?.trim() || null, createdBy: ctx.action.actor, createdAt: ctx.now.toISOString(),
          endedAt: null, endedBy: null, endReason: null, expiredAt: null, audit: [{ action: 'CREATED', actor: ctx.action.actor, reason, at: ctx.now.toISOString() }]
        };
        st(ctx).rates.push(created);
        return view(created, ctx.now, true);
      }, 201)
    ),
    http.post(
      `${F}/negotiated-rates/:id/end`,
      route(async (ctx, p) => {
        const { reason } = await json(ctx.request, z.object({ reason: z.string().trim().min(3, 'Say why the rate ends (3 to 500 characters)').max(500) }));
        configure(ctx);
        const r = st(ctx).rates.find((x) => x.id === p.id);
        if (!r) throw problem(404, 'NOT_FOUND', 'Not found', 'No such negotiated rate.');
        const status = rateStatus(r, ctx.now);
        if (status === 'ENDED' || status === 'EXPIRED') throw problem(409, 'CONFLICT', 'Conflict', 'This negotiated rate already ended.');
        r.endedAt = ctx.now.toISOString();
        r.endedBy = ctx.action.actor;
        r.endReason = reason;
        r.audit.push({ action: 'ENDED', actor: ctx.action.actor, reason, at: r.endedAt });
        return view(r, new Date(ctx.now.getTime() + 1), true);
      })
    ),
    http.get(
      `${F}/calculator`,
      route((ctx): FeeRateQuote => {
        view$(ctx);
        const q = new URL(ctx.request.url).searchParams;
        const orgId = (q.get('orgId') ?? '').trim();
        const category = normalizeFeeCategory(q.get('category') ?? '');
        const kind = (q.get('kind') ?? 'PRODUCT').toUpperCase();
        const errors: Record<string, string> = {};
        if (!orgId) errors.orgId = 'Choose an organisation';
        if (!category) errors.category = 'Choose a category';
        if (kind !== 'PRODUCT' && kind !== 'SERVICE') errors.kind = 'PRODUCT or SERVICE';
        if (Object.keys(errors).length) throw invalid(errors);
        const schedule = activeSchedule(ctx);
        if (!schedule) throw problem(503, 'UPSTREAM_UNAVAILABLE', 'Service unavailable', 'No fee schedule in force.');
        const r = effectiveRate(schedule, st(ctx), orgId, category, kind as FeeKind, ctx.now);
        const what = kind === 'SERVICE' ? 'connection fee' : 'commission';
        const pName = r.plan ? planName(r.plan) : null;
        const until = r.rate?.endsAt ?? null;
        const explanation =
          r.source === 'OVERRIDE' ? (until ? `Negotiated ${what} until ${new Date(until).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' })}` : `Negotiated ${what} (open-ended)`)
            : r.source === 'PLAN' ? `Plan rate: ${pName ?? ''}`
              : r.source === 'CATEGORY' ? 'Category rate of the fee schedule'
                : r.source === 'RETAIL' ? 'No commission, no fees (merchant of record)' : `Default ${what} of the fee schedule`;
        return {
          orgId, orgName: orgName(orgId), kind: kind as FeeKind, category, categoryLabel: catLabel(category), bps: r.bps, source: r.source, planCode: r.plan, planName: pName,
          negotiatedRateId: r.rate?.id ?? null, negotiatedUntil: until, version: schedule.version, exampleSaleMinor: 10_000, exampleFeeMinor: applyBps(10_000, r.bps), explanation
        };
      })
    ),
    http.post(
      `${F}/orgs/:orgId/negotiated-plan`,
      route(async (ctx, p): Promise<NegotiatedPlan> => {
        const b = await json(ctx.request, PlanBody);
        const orgId = (p.orgId ?? '').trim();
        const errors: Record<string, string> = {};
        if (!/^[a-z0-9][a-z0-9_-]{0,79}$/.test(b.basePlanCode.trim().toLowerCase())) errors.basePlanCode = 'Choose the plan to start from';
        if (b.monthlyCents === null || b.monthlyCents === undefined) errors.monthlyCents = 'Enter the negotiated price';
        else if (b.monthlyCents < 0) errors.monthlyCents = 'Enter a price between $0.00 and $100,000.00';
        if (b.reason.trim().length < 3 || b.reason.trim().length > 500) errors.reason = 'Say why this price was negotiated (3 to 500 characters)';
        if (orgId === MOCK_RETAIL_ORG_ID) errors.orgId = 'AfroTransact Retail is never billed for a plan';
        if (Object.keys(errors).length) throw invalid(errors);
        if (!allows(ctx.roles, 'plans', 'EDIT') || !ctx.permissions.includes('plans:manage')) throw problem(403, 'FORBIDDEN', 'Forbidden', 'This needs the plans:manage permission.');
        const base = ctx.db.plans.plans.find((x) => x.code === b.basePlanCode.trim().toLowerCase());
        if (!base) throw problem(404, 'NOT_FOUND', 'Not found', 'No such plan.');
        const code = `${base.code}_${orgId.toLowerCase().replace(/[^a-z0-9_]/g, '_')}`.slice(0, 60).replace(/_+$/, '');
        if (ctx.db.plans.plans.some((x) => x.code === code)) throw problem(409, 'CONFLICT', 'Conflict', `${code} already exists.`);
        const name = `${base.name} · ${orgName(orgId) ?? orgId}`;
        const monthly = b.monthlyCents ?? 0;
        const quarterly = b.quarterlyCents ?? Math.floor((monthly * 3 * 95) / 100);
        const annual = b.annualCents ?? monthly * 10;
        ctx.db.plans.plans.push({ ...structuredClone(base), code, name, tagline: `Negotiated for ${orgName(orgId) ?? orgId}`, visibility: 'INVITE_ONLY', status: 'ACTIVE',
          monthlyCents: monthly, quarterlyCents: quarterly, annualCents: annual, subscribers: 0, invites: [orgId], currentVersion: 1, versions: [], rowVersion: 0 });
        const n = orgName(orgId) ?? orgId;
        return { planCode: code, planName: name, basePlanCode: base.code, orgId, visibility: 'INVITE_ONLY', published: true, monthlyCents: monthly, quarterlyCents: quarterly,
          annualCents: annual, note: `${n} can now choose ${name} in Plan & billing; it stays on its current plan until it does.` };
      }, 201)
    )
  ];
}
