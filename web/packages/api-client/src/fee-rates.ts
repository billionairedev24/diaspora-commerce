/* Commission and connection fees on top of plan fees (gateway-bff-staff.yaml tag Fees; gateway-bff-seller.yaml Money and
   Provider Hub): product lines pay a commission, service bookings a connection fee. First match wins: an active
   negotiated rate (category, then every category) → the organisation's plan rate (category, then the plan's *) → the
   category rate → the default; AfroTransact Retail pays none. Rates are basis points (800 = 8 %). */
import { keepPreviousData, queryOptions } from '@tanstack/react-query';
import { z } from 'zod';

import { type Requester } from './http';

const nullableString = z.string().nullable();

export const RateSourceSchema = z.enum(['OVERRIDE', 'PLAN', 'CATEGORY', 'DEFAULT', 'RETAIL']);
export type RateSource = z.infer<typeof RateSourceSchema>;
export const FeeKindSchema = z.enum(['PRODUCT', 'SERVICE']);
export type FeeKind = z.infer<typeof FeeKindSchema>;
export const NegotiatedRateTypeSchema = z.enum(['COMMISSION', 'CONNECTION_FEE']);
export type NegotiatedRateType = z.infer<typeof NegotiatedRateTypeSchema>;
export const NegotiatedRateStatusSchema = z.enum(['SCHEDULED', 'ACTIVE', 'ENDED', 'EXPIRED']);
export type NegotiatedRateStatus = z.infer<typeof NegotiatedRateStatusSchema>;

/* ── Fee-schedule sections (staff) ── */

export const FeePlanRateSchema = z.object({ planCode: z.string(), planName: z.string(), category: z.string(), label: z.string(), bps: z.number().int() });
export type FeePlanRate = z.infer<typeof FeePlanRateSchema>;
export const FeeRateSchema = z.object({ category: z.string(), label: z.string(), bps: z.number().int() });
export type FeeRate = z.infer<typeof FeeRateSchema>;
export const FeeConnectionFeeSchema = z.object({
  defaultBps: z.number().int(),
  categoryRates: z.array(FeeRateSchema),
  planRates: z.array(FeePlanRateSchema)
});
export type FeeConnectionFee = z.infer<typeof FeeConnectionFeeSchema>;
export const FeeRangeSchema = z.object({ minBps: z.number().int(), maxBps: z.number().int() });
export type FeeRange = z.infer<typeof FeeRangeSchema>;
export const FeePlanSchema = z.object({
  code: z.string(),
  kind: z.string(),
  name: z.string(),
  status: z.string(),
  visibility: z.string(),
  featureText: nullableString,
  advertised: FeeRangeSchema.nullable(),
  configured: FeeRangeSchema.nullable(),
  warning: nullableString
});
export type FeePlan = z.infer<typeof FeePlanSchema>;

export interface FeeDraftPlanRate {
  planCode: string;
  /** '*' or empty = the plan's other categories. */
  category: string;
  bps: number;
}
export interface FeeConnectionDraft {
  defaultBps: number;
  categoryRates: { category: string; bps: number }[];
  planRates: FeeDraftPlanRate[];
}

/* ── Negotiated rates (staff) ── */

export const NegotiatedRateAuditEntrySchema = z.object({
  action: z.enum(['CREATED', 'ENDED', 'EXPIRED']),
  actor: z.string(),
  reason: nullableString,
  at: z.string()
});
export type NegotiatedRateAuditEntry = z.infer<typeof NegotiatedRateAuditEntrySchema>;
export const NegotiatedRateSchema = z.object({
  id: z.string(),
  orgId: z.string(),
  orgName: nullableString,
  type: NegotiatedRateTypeSchema,
  category: z.string(),
  categoryLabel: z.string(),
  rateBps: z.number().int(),
  startsAt: z.string(),
  endsAt: nullableString,
  status: NegotiatedRateStatusSchema,
  reason: z.string(),
  negotiatedBy: z.string(),
  reference: nullableString,
  createdBy: z.string(),
  createdAt: z.string(),
  endedAt: nullableString,
  endedBy: nullableString,
  endReason: nullableString,
  expiredAt: nullableString,
  audit: z.array(NegotiatedRateAuditEntrySchema),
  canEnd: z.boolean()
});
export type NegotiatedRate = z.infer<typeof NegotiatedRateSchema>;
export const NegotiatedRatePageSchema = z.object({
  items: z.array(NegotiatedRateSchema),
  total: z.number().int(),
  page: z.number().int(),
  size: z.number().int(),
  canConfigure: z.boolean()
});
export type NegotiatedRatePage = z.infer<typeof NegotiatedRatePageSchema>;

export interface NegotiatedRateDraft {
  orgId: string;
  type: NegotiatedRateType;
  /** null or '*' = every category. */
  category: string | null;
  rateBps: number;
  startsAt: string | null;
  endsAt: string | null;
  reason: string;
  negotiatedBy: string | null;
  reference: string | null;
}

export interface NegotiatedRateQuery {
  orgId?: string;
  type?: NegotiatedRateType;
  status?: NegotiatedRateStatus;
  q?: string;
  page: number;
  size: number;
}

export const FeeRateQuoteSchema = z.object({
  orgId: z.string(),
  orgName: nullableString,
  kind: FeeKindSchema,
  category: z.string(),
  categoryLabel: z.string(),
  bps: z.number().int(),
  source: RateSourceSchema,
  planCode: nullableString,
  planName: nullableString,
  negotiatedRateId: nullableString,
  negotiatedUntil: nullableString,
  version: z.number().int(),
  exampleSaleMinor: z.number().int(),
  exampleFeeMinor: z.number().int(),
  explanation: z.string()
});
export type FeeRateQuote = z.infer<typeof FeeRateQuoteSchema>;

export interface NegotiatedPlanDraft {
  basePlanCode: string;
  monthlyCents: number;
  quarterlyCents: number | null;
  annualCents: number | null;
  reason: string;
  reference: string | null;
}
export const NegotiatedPlanSchema = z.object({
  planCode: z.string(),
  planName: z.string(),
  basePlanCode: z.string(),
  orgId: z.string(),
  visibility: z.literal('INVITE_ONLY'),
  published: z.boolean(),
  monthlyCents: z.number().int(),
  quarterlyCents: z.number().int(),
  annualCents: z.number().int(),
  note: z.string()
});
export type NegotiatedPlan = z.infer<typeof NegotiatedPlanSchema>;

/* ── Seller Center and Provider Hub ── */

export const SellerAppliedRateSchema = z.object({
  category: z.string(),
  label: z.string(),
  bps: z.number().int(),
  source: RateSourceSchema,
  planCode: nullableString,
  planName: nullableString,
  negotiatedUntil: nullableString
});
export type SellerAppliedRate = z.infer<typeof SellerAppliedRateSchema>;
export const SellerNegotiatedRateSchema = z.object({
  type: NegotiatedRateTypeSchema,
  category: z.string(),
  label: z.string(),
  rateBps: z.number().int(),
  startsAt: z.string(),
  endsAt: nullableString,
  status: NegotiatedRateStatusSchema
});
export type SellerNegotiatedRate = z.infer<typeof SellerNegotiatedRateSchema>;
export const SellerRateCardSchema = z.object({
  kind: FeeKindSchema,
  version: z.number().int(),
  effectiveFrom: z.string(),
  planCode: nullableString,
  planName: nullableString,
  other: SellerAppliedRateSchema,
  categories: z.array(SellerAppliedRateSchema),
  negotiated: z.array(SellerNegotiatedRateSchema)
});
export type SellerRateCard = z.infer<typeof SellerRateCardSchema>;
export const BookingTakeHomeSchema = z.object({
  connectionFeeBps: z.number().int(),
  connectionFeeMinor: z.number().int(),
  cardFeesMinor: z.number().int(),
  keepMinor: z.number().int(),
  source: RateSourceSchema,
  currency: z.string()
});
export type BookingTakeHome = z.infer<typeof BookingTakeHomeSchema>;

/* ── Staff API, query keys and queries ── */

const F = '/v1/staff/fees';
const enc = encodeURIComponent;

export function createStaffFeeRatesApi(request: Requester) {
  return {
    negotiatedRates: (q: NegotiatedRateQuery, signal?: AbortSignal): Promise<NegotiatedRatePage> =>
      request(`${F}/negotiated-rates`, NegotiatedRatePageSchema, {
        query: { orgId: q.orgId || undefined, type: q.type, status: q.status, q: q.q || undefined, page: String(q.page), size: String(q.size) },
        signal
      }),
    createNegotiatedRate: (body: NegotiatedRateDraft): Promise<NegotiatedRate> =>
      request(`${F}/negotiated-rates`, NegotiatedRateSchema, { method: 'POST', body }),
    endNegotiatedRate: (id: string, reason: string): Promise<NegotiatedRate> =>
      request(`${F}/negotiated-rates/${enc(id)}/end`, NegotiatedRateSchema, { method: 'POST', body: { reason } }),
    calculate: (orgId: string, category: string, kind: FeeKind, signal?: AbortSignal): Promise<FeeRateQuote> =>
      request(`${F}/calculator`, FeeRateQuoteSchema, { query: { orgId, category, kind }, signal }),
    negotiatePlan: (orgId: string, body: NegotiatedPlanDraft): Promise<NegotiatedPlan> =>
      request(`${F}/orgs/${enc(orgId)}/negotiated-plan`, NegotiatedPlanSchema, { method: 'POST', body })
  };
}
export type StaffFeeRatesApi = ReturnType<typeof createStaffFeeRatesApi>;

export const staffFeeRateKeys = {
  all: ['staff', 'fee-rates'] as const,
  negotiatedLists: () => [...staffFeeRateKeys.all, 'negotiated'] as const,
  negotiated: (q: NegotiatedRateQuery) => [...staffFeeRateKeys.negotiatedLists(), q] as const,
  calculator: (orgId: string, category: string, kind: FeeKind) => [...staffFeeRateKeys.all, 'calculator', orgId, category, kind] as const
};

export const staffFeeRateQueries = {
  negotiated: (api: StaffFeeRatesApi, q: NegotiatedRateQuery) =>
    queryOptions({ queryKey: staffFeeRateKeys.negotiated(q), queryFn: ({ signal }) => api.negotiatedRates(q, signal), placeholderData: keepPreviousData, staleTime: 10_000 }),
  calculator: (api: StaffFeeRatesApi, orgId: string, category: string, kind: FeeKind) =>
    queryOptions({
      queryKey: staffFeeRateKeys.calculator(orgId, category, kind),
      queryFn: ({ signal }) => api.calculate(orgId, category, kind, signal),
      enabled: Boolean(orgId) && category.trim().length > 0,
      staleTime: 10_000
    })
};
