/* Back office Delivery fees (ADR 0005 §3): the staff BFF routes /v1/staff/delivery-fees/** that mirror delivery-runs'
   delivery fee rules, their Zod schemas, TanStack Query key factories and queryOptions, and the rules the console and
   the mock BFF share (draft validation, and the one resolution checkout runs: the window's own fee, else the rule of
   the delivery method). Money is in minor units; distances are miles from the hub. */
import { queryOptions } from '@tanstack/react-query';
import { z } from 'zod';

import { type ApiClientOptions, type Requester } from './http';

/* ── Enums ─────────────────────────────────────────────── */

/** AfroTransact's own delivery (a flat fee by distance band), a carrier beyond coverage, and pickup. */
export const FEE_METHODS = ['AFROTRANSACT_DELIVERY', 'CARRIER', 'PICKUP'] as const;
export const FeeMethodSchema = z.enum(FEE_METHODS);
export type FeeMethod = z.infer<typeof FeeMethodSchema>;

/** Where a fee came from: a rule, the pre-order's own fee, the carrier quote plus handling, or the carrier fallback. */
export const FeeSourceSchema = z.enum(['RULE', 'WINDOW_OVERRIDE', 'CARRIER_QUOTE', 'CARRIER_FALLBACK']);
export type FeeSource = z.infer<typeof FeeSourceSchema>;

/* ── Limits (delivery-runs checks them again) ──────────── */

export const FEE_MAX_MINOR = 10_000;
export const FEE_MAX_BANDS = 12;
export const FEE_MAX_MILES = 500;
export const FEE_REASON_MAX = 500;

/* ── Schemas ───────────────────────────────────────────── */

export const DeliveryFeeBandSchema = z.object({ fromMiles: z.number(), toMiles: z.number(), feeMinor: z.number().int() });
export type DeliveryFeeBand = z.infer<typeof DeliveryFeeBandSchema>;

export const DeliveryFeeRuleSchema = z.object({
  id: z.string(),
  method: FeeMethodSchema,
  currency: z.string(),
  /** Optimistic-lock version; PUT sends it back as If-Match (0 when the rule does not exist yet). */
  version: z.number().int(),
  bands: z.array(DeliveryFeeBandSchema).default([]),
  /** PICKUP: the fee. */
  feeMinor: z.number().int().default(0),
  /** CARRIER: added to the carrier-gateway quote. */
  handlingMinor: z.number().int().default(0),
  /** CARRIER: the fee when there is no quote. */
  fallbackMinor: z.number().int().default(0),
  /** CARRIER: goods subtotal from which shipping is free (null = never). */
  freeAboveMinor: z.number().int().nullable().default(null),
  /** AFROTRANSACT_DELIVERY: how far AfroTransact delivers (the end of the last band). */
  coverageMiles: z.number().default(0),
  updatedAt: z.string(),
  updatedBy: z.string().nullable().default(null),
  reason: z.string().nullable().default(null)
});
export type DeliveryFeeRule = z.infer<typeof DeliveryFeeRuleSchema>;

export const DeliveryFeeRulesSchema = z.object({
  items: z.array(DeliveryFeeRuleSchema),
  can: z.looseObject({ manage: z.boolean() })
});
export type DeliveryFeeRules = z.infer<typeof DeliveryFeeRulesSchema>;

/** The delivery fee rule behind a fee a shopper pays (window views, the cart, the order): what and which version. */
export const FeeRuleRefSchema = z.object({
  method: FeeMethodSchema,
  ruleId: z.string().nullable().default(null),
  ruleVersion: z.number().int(),
  currency: z.string().optional(),
  amountMinor: z.number().int(),
  source: FeeSourceSchema.default('RULE'),
  band: z.string().nullable().default(null),
  distanceMiles: z.number().nullable().default(null)
});
export type FeeRuleRef = z.infer<typeof FeeRuleRefSchema>;

/** One entry of the audit trail: the rule before (null for its first version) and after, who, when and why. */
export const DeliveryFeeChangeSchema = z.object({
  id: z.string().optional(),
  method: FeeMethodSchema,
  currency: z.string(),
  version: z.number().int(),
  before: DeliveryFeeRuleSchema.nullable().default(null),
  after: DeliveryFeeRuleSchema,
  actor: z.string(),
  reason: z.string(),
  at: z.string()
});
export type DeliveryFeeChange = z.infer<typeof DeliveryFeeChangeSchema>;
export const DeliveryFeeChangesSchema = z.object({ items: z.array(DeliveryFeeChangeSchema) });

export const FeePreviewSchema = z.object({ applies: z.boolean(), fee: FeeRuleRefSchema.nullable().default(null) });
export type FeePreview = z.infer<typeof FeePreviewSchema>;

/** Body of PUT /v1/staff/delivery-fees/{method}/{currency}: the numbers the method uses, plus the reason. */
export interface DeliveryFeeInput {
  bands?: DeliveryFeeBand[];
  feeMinor?: number;
  handlingMinor?: number;
  fallbackMinor?: number;
  freeAboveMinor?: number | null;
  reason: string;
}

export interface FeePreviewInput {
  method: FeeMethod;
  currency: string;
  distanceMiles?: number | null;
  windowId?: string | null;
  carrierQuoteMinor?: number | null;
  itemsMinor?: number | null;
}

/* ── Draft validation (shared by the console form and the mock BFF) ───────── */

/** An amount people type ("7.99", "$7", "0") → minor units; null when blank, NaN when not an amount. */
export function parseFeeAmount(text: string): number | null {
  const t = text.trim();
  if (!t) return null;
  const m = /^\$?\s*(\d{1,3})(?:\.(\d{0,2}))?$/.exec(t);
  if (!m) return Number.NaN;
  return Number(m[1]) * 100 + Number((m[2] ?? '').padEnd(2, '0'));
}

/** Minor units as an editable amount ("7.99"). */
export const feeAmountText = (minor: number | null | undefined): string => (minor === null || minor === undefined ? '' : `${Math.floor(minor / 100)}.${String(minor % 100).padStart(2, '0')}`);

/** A distance people type ("12", "12.5") → miles; NaN when it is not a distance of at most two decimals. */
export function parseMiles(text: string): number {
  const t = text.trim();
  if (!/^\d{1,3}(?:\.\d{1,2})?$/.test(t)) return Number.NaN;
  return Number(t);
}

/** Miles as shown and edited ("12", "12.5"). */
export const milesText = (miles: number): string => String(Math.round(miles * 100) / 100);

/** One row of the distance table as people edit it: "up to N miles costs $X" (each band starts where the last ended). */
export interface BandDraft {
  upTo: string;
  fee: string;
}

export const bandDraftsOf = (rule: Pick<DeliveryFeeRule, 'bands'> | null | undefined): BandDraft[] =>
  (rule?.bands ?? []).map((b) => ({ upTo: milesText(b.toMiles), fee: feeAmountText(b.feeMinor) }));

/** What is wrong with a distance row's end ("upTo") or fee, as a code the screen words (and translates). */
export type BandProblem = 'notDistance' | 'tooFar' | 'notAbove' | 'notAmount' | 'tooHigh';

export interface BandProblems {
  /** Per row: the problem under "upTo" or "fee"; `after` is the miles the distance must exceed. */
  rows: Record<number, { upTo?: BandProblem; fee?: BandProblem; after?: number }>;
  /** About the table as a whole. */
  table?: 'none' | 'tooMany';
}

/** The bands the drafts describe (the first starts at 0), or the problems that stop it. */
export function bandsFromDrafts(drafts: BandDraft[]): { bands: DeliveryFeeBand[]; problems: BandProblems | null } {
  const problems: BandProblems = { rows: {} };
  if (drafts.length === 0 || drafts.length > FEE_MAX_BANDS) {
    problems.table = drafts.length === 0 ? 'none' : 'tooMany';
    return { bands: [], problems };
  }
  const bands: DeliveryFeeBand[] = [];
  let from = 0;
  drafts.forEach((d, i) => {
    const to = parseMiles(d.upTo);
    const fee = parseFeeAmount(d.fee);
    const row: BandProblems['rows'][number] = {};
    if (Number.isNaN(to)) row.upTo = 'notDistance';
    else if (to > FEE_MAX_MILES) row.upTo = 'tooFar';
    else if (to <= from) {
      row.upTo = 'notAbove';
      row.after = from;
    }
    if (fee === null || Number.isNaN(fee)) row.fee = 'notAmount';
    else if (fee > FEE_MAX_MINOR) row.fee = 'tooHigh';
    if (row.upTo || row.fee) problems.rows[i] = row;
    else {
      bands.push({ fromMiles: from, toMiles: to, feeMinor: fee as number });
      from = to;
    }
  });
  return Object.keys(problems.rows).length ? { bands: [], problems } : { bands, problems: null };
}

/** "Up to 10 miles" / "10 to 40 miles" for the band a row describes. */
export const bandLabel = (b: Pick<DeliveryFeeBand, 'fromMiles' | 'toMiles'>): string =>
  b.fromMiles === 0 ? `Up to ${milesText(b.toMiles)} miles` : `${milesText(b.fromMiles)} to ${milesText(b.toMiles)} miles`;

/** The reason is required (422) and at most 500 characters; it is logged with who and when. */
export function feeReasonProblem(reason: string): 'required' | 'tooLong' | null {
  const t = reason.trim();
  if (!t) return 'required';
  return t.length > FEE_REASON_MAX ? 'tooLong' : null;
}

/**
 * The problems of a rule as it is sent (keyed like the BFF's 422: "bands[1].toMiles", "fallbackMinor", "reason"):
 * the numbers the method uses and the required reason. Empty when delivery-runs would take it.
 */
export function ruleInputProblems(method: FeeMethod, input: DeliveryFeeInput): Record<string, string> {
  const out: Record<string, string> = {};
  const fee = (key: string, v: number | null | undefined) => {
    if (v === null || v === undefined) out[key] = 'Enter an amount (0 is free)';
    else if (!Number.isInteger(v) || v < 0 || v > FEE_MAX_MINOR) out[key] = 'Enter an amount between 0 and 100.00';
  };
  if (method === 'AFROTRANSACT_DELIVERY') {
    const bands = input.bands ?? [];
    if (bands.length === 0 || bands.length > FEE_MAX_BANDS) out.bands = `Add between 1 and ${FEE_MAX_BANDS} distance bands`;
    let reached = 0;
    [...bands]
      .sort((a, b) => a.fromMiles - b.fromMiles)
      .forEach((b, i) => {
        if (b.fromMiles !== reached) out[`bands[${i}].fromMiles`] = i === 0 ? 'The first band starts at 0 miles' : b.fromMiles < reached ? 'Bands must not overlap' : `Bands must not leave a gap: start at ${milesText(reached)} miles`;
        if (!(b.toMiles > b.fromMiles) || b.toMiles > FEE_MAX_MILES) out[`bands[${i}].toMiles`] = 'The band must end after it starts, within 500 miles';
        fee(`bands[${i}].feeMinor`, b.feeMinor);
        reached = b.toMiles;
      });
  } else if (method === 'CARRIER') {
    fee('handlingMinor', input.handlingMinor);
    fee('fallbackMinor', input.fallbackMinor);
    if (input.freeAboveMinor !== null && input.freeAboveMinor !== undefined && (!Number.isInteger(input.freeAboveMinor) || input.freeAboveMinor < 0)) {
      out.freeAboveMinor = 'Enter an amount of 0 or more, or leave it empty';
    }
  } else fee('feeMinor', input.feeMinor);
  const reason = feeReasonProblem(input.reason);
  if (reason === 'required') out.reason = 'Say why: the reason is logged with your name';
  if (reason === 'tooLong') out.reason = `At most ${FEE_REASON_MAX} characters`;
  return out;
}

/* ── The one resolution (what checkout shows and the order keeps) ─────────── */

export const feeRuleOf = (rules: readonly DeliveryFeeRule[], method: FeeMethod, currency: string): DeliveryFeeRule | undefined =>
  rules.find((r) => r.method === method && r.currency === currency);

const refOf = (method: FeeMethod, r: DeliveryFeeRule | undefined, currency: string, amountMinor: number, source: FeeSource, band: string | null = null, distanceMiles: number | null = null): FeeRuleRef => ({
  method,
  ruleId: r?.id ?? null,
  ruleVersion: r?.version ?? 0,
  currency,
  amountMinor,
  source,
  band,
  distanceMiles
});

/**
 * The door fee of a pre-order: its own fee, else the band of the AfroTransact delivery rule for how far it delivers
 * (unmeasured: the farthest band, so a fee is never understated). Null beyond the last band: the order ships by carrier.
 */
export function resolveDoorFee(rules: readonly DeliveryFeeRule[], currency: string, miles: number | null, override: number | null): FeeRuleRef | null {
  const rule = feeRuleOf(rules, 'AFROTRANSACT_DELIVERY', currency);
  if (override !== null) return refOf('AFROTRANSACT_DELIVERY', rule, currency, override, 'WINDOW_OVERRIDE', null, miles);
  if (!rule || rule.bands.length === 0) return null;
  const band = miles === null ? rule.bands[rule.bands.length - 1] : rule.bands.find((b) => miles >= b.fromMiles && miles < b.toMiles);
  return band ? refOf('AFROTRANSACT_DELIVERY', rule, currency, band.feeMinor, 'RULE', `${milesText(band.fromMiles)}–${milesText(band.toMiles)} mi`, miles) : null;
}

/** The pickup fee of a pre-order: its own fee, else the pickup rule (free when staff set none). */
export function resolvePickupFee(rules: readonly DeliveryFeeRule[], currency: string, override: number | null): FeeRuleRef {
  const rule = feeRuleOf(rules, 'PICKUP', currency);
  return refOf('PICKUP', rule, currency, override ?? rule?.feeMinor ?? 0, override === null ? 'RULE' : 'WINDOW_OVERRIDE');
}

/** Carrier shipping beyond coverage: the quote plus handling (the fallback fee without a quote); free over the threshold. Null without a rule. */
export function resolveCarrierFee(rules: readonly DeliveryFeeRule[], currency: string, quoteMinor: number | null, itemsMinor: number): FeeRuleRef | null {
  const rule = feeRuleOf(rules, 'CARRIER', currency);
  if (!rule) return null;
  const base = refOf('CARRIER', rule, currency, 0, 'RULE');
  if (rule.freeAboveMinor !== null && itemsMinor >= rule.freeAboveMinor) return { ...base, band: `free over ${feeAmountText(rule.freeAboveMinor)}` };
  if (quoteMinor !== null) return { ...base, amountMinor: quoteMinor + rule.handlingMinor, source: 'CARRIER_QUOTE' };
  return { ...base, amountMinor: rule.fallbackMinor, source: 'CARRIER_FALLBACK' };
}

/** The range of door fees a rule charges, "$5.99" when flat or "$5.99–$12.99" by distance; null without bands. */
export function doorFeeRange(rule: DeliveryFeeRule | undefined, format: (minor: number) => string): string | null {
  if (!rule || rule.bands.length === 0) return null;
  const fees = rule.bands.map((b) => b.feeMinor);
  const lo = Math.min(...fees);
  const hi = Math.max(...fees);
  return lo === hi ? format(lo) : `${format(lo)}–${format(hi)}`;
}

/* ── Client ────────────────────────────────────────────── */

const enc = encodeURIComponent;
const P = '/v1/staff/delivery-fees';
const ifMatch = (version: number): Record<string, string> => ({ 'If-Match': `"${version}"` });

/** Delivery fee routes of the staff BFF (mounted as `staffApi().deliveryFees`). */
export function createStaffDeliveryFeesApi(request: Requester, _options: ApiClientOptions) {
  return {
    list: (signal?: AbortSignal): Promise<DeliveryFeeRules> => request(P, DeliveryFeeRulesSchema, { signal }),
    /**
     * Sets one rule (creating it when `version` is 0). The reason is required (422) and logged with who and when;
     * 412 when someone saved the rule in between.
     */
    update: (method: FeeMethod, currency: string, body: DeliveryFeeInput, version: number): Promise<DeliveryFeeRule> =>
      request(`${P}/${method}/${enc(currency)}`, DeliveryFeeRuleSchema, { method: 'PUT', body, headers: ifMatch(version) }),
    /** The audit trail, newest first. */
    changes: (q: { method?: FeeMethod; currency?: string; limit?: number } = {}, signal?: AbortSignal): Promise<DeliveryFeeChange[]> =>
      request(`${P}/changes`, DeliveryFeeChangesSchema, { query: { method: q.method, currency: q.currency, limit: q.limit ? String(q.limit) : undefined }, signal }).then((r) => r.items),
    /** What would a customer pay: the resolution checkout runs. */
    preview: (body: FeePreviewInput): Promise<FeePreview> => request(`${P}/preview`, FeePreviewSchema, { method: 'POST', body })
  };
}

export type StaffDeliveryFeesApi = ReturnType<typeof createStaffDeliveryFeesApi>;

/* ── Query keys & options ──────────────────────────────── */

export const staffDeliveryFeeKeys = {
  all: ['staff', 'delivery-fees'] as const,
  rules: () => [...staffDeliveryFeeKeys.all, 'rules'] as const,
  changes: (q: { method?: FeeMethod; currency?: string; limit?: number }) => [...staffDeliveryFeeKeys.all, 'changes', q] as const
};

export const staffDeliveryFeeQueries = {
  rules: (api: StaffDeliveryFeesApi) => queryOptions({ queryKey: staffDeliveryFeeKeys.rules(), queryFn: ({ signal }) => api.list(signal), staleTime: 15_000 }),
  changes: (api: StaffDeliveryFeesApi, q: { method?: FeeMethod; currency?: string; limit?: number } = {}) =>
    queryOptions({ queryKey: staffDeliveryFeeKeys.changes(q), queryFn: ({ signal }) => api.changes(q, signal), staleTime: 5_000 })
};
