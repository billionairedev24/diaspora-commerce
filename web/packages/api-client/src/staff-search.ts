/* Search analytics (contracts/openapi/gateway-bff-staff.yaml, tag Search analytics): what shoppers search for, which
   searches find nothing, which results get clicked, and the synonym rules that fix zero-result queries. The search
   service reports whole windows (at most 500 queries); the BFF filters, sorts and pages them. Counts are buffered,
   aggregated events: a good indication, not accounting. */
import { keepPreviousData, queryOptions } from '@tanstack/react-query';
import { z } from 'zod';

import { type Requester } from './http';
import { tableSearchParams } from './table-query';

export const StaffSearchMarketsSchema = z.object({
  items: z.array(z.object({ code: z.string(), name: z.string() })),
  default: z.string(),
  canEditSynonyms: z.boolean()
});
export type StaffSearchMarkets = z.infer<typeof StaffSearchMarketsSchema>;

const WindowShape = { market: z.string(), marketName: z.string(), from: z.string(), to: z.string() };

export const StaffSearchQueryStatSchema = z.object({
  query: z.string(),
  searches: z.number(),
  zeroResults: z.number(),
  clicks: z.number(),
  ctr: z.number(),
  zeroResultRate: z.number()
});
export type StaffSearchQueryStat = z.infer<typeof StaffSearchQueryStatSchema>;

export const StaffSearchTotalsSchema = z.object({ searches: z.number(), zeroResults: z.number(), clicks: z.number(), ctr: z.number(), zeroResultRate: z.number() });
export type StaffSearchTotals = z.infer<typeof StaffSearchTotalsSchema>;

export const StaffSearchQueryReportSchema = z.object({
  ...WindowShape,
  items: z.array(StaffSearchQueryStatSchema),
  total: z.number().int(),
  page: z.number().int(),
  size: z.number().int(),
  /** The service lists at most 500 queries; rarer ones are not here. */
  truncated: z.boolean(),
  totals: StaffSearchTotalsSchema
});
export type StaffSearchQueryReport = z.infer<typeof StaffSearchQueryReportSchema>;

export const StaffSearchDaySchema = z.object({
  day: z.string(),
  searches: z.number(),
  clicks: z.number(),
  zeroResults: z.number(),
  ctr: z.number(),
  zeroResultRate: z.number()
});
export type StaffSearchDay = z.infer<typeof StaffSearchDaySchema>;

export const StaffSearchClickThroughSchema = z.object({
  ...WindowShape,
  items: z.array(StaffSearchDaySchema),
  total: z.number().int(),
  page: z.number().int(),
  size: z.number().int(),
  /** Every day of the window, oldest first (for the sparkline). */
  trend: z.array(StaffSearchDaySchema),
  totals: StaffSearchDaySchema
});
export type StaffSearchClickThrough = z.infer<typeof StaffSearchClickThroughSchema>;

export const StaffSynonymRuleSchema = z.object({ id: z.string(), terms: z.array(z.string()) });
export type StaffSynonymRule = z.infer<typeof StaffSynonymRuleSchema>;
export const StaffSynonymListSchema = z.object({ items: z.array(StaffSynonymRuleSchema), canEdit: z.boolean() });
export type StaffSynonymList = z.infer<typeof StaffSynonymListSchema>;

/** The two per-query reports. */
export type StaffSearchReportKind = 'top-queries' | 'zero-results';

/** A report request; `page` is 1-based. Days are YYYY-MM-DD (empty: the service's last 7 days). */
export interface StaffSearchQuery {
  market?: string;
  from?: string;
  to?: string;
  q?: string;
  sort?: string[];
  page?: number;
  size?: number;
}

/* ── Client ────────────────────────────────────────────── */

const S = '/v1/staff/search';

function params(q: StaffSearchQuery, withFilter: boolean): URLSearchParams {
  const p = tableSearchParams({ q: withFilter ? q.q : undefined, page: q.page ?? 1, size: q.size ?? 25 }, q.sort);
  if (q.market) p.set('market', q.market);
  if (q.from) p.set('from', q.from);
  if (q.to) p.set('to', q.to);
  return p;
}

/** Search analytics routes of the staff BFF (mounted as `staffApi().search`). */
export function createStaffSearchApi(request: Requester) {
  return {
    markets: (signal?: AbortSignal): Promise<StaffSearchMarkets> => request(`${S}/markets`, StaffSearchMarketsSchema, { signal }),
    queries: (kind: StaffSearchReportKind, q: StaffSearchQuery, signal?: AbortSignal): Promise<StaffSearchQueryReport> =>
      request(`${S}/analytics/${kind}`, StaffSearchQueryReportSchema, { query: params(q, true), signal }),
    clickThrough: (q: StaffSearchQuery, signal?: AbortSignal): Promise<StaffSearchClickThrough> =>
      request(`${S}/analytics/ctr`, StaffSearchClickThroughSchema, { query: params(q, false), signal }),
    synonyms: (signal?: AbortSignal): Promise<StaffSynonymList> => request(`${S}/synonyms`, StaffSynonymListSchema, { signal }),
    /** Makes 2 to 20 words or phrases mean the same to search (422 with `errors.terms` when search refuses them). */
    addSynonym: (terms: string[]): Promise<StaffSynonymRule> => request(`${S}/synonyms`, StaffSynonymRuleSchema, { method: 'POST', body: { terms } }),
    deleteSynonym: (id: string): Promise<void> => request(`${S}/synonyms/${encodeURIComponent(id)}`, z.undefined(), { method: 'DELETE' })
  };
}

export type StaffSearchApi = ReturnType<typeof createStaffSearchApi>;

/* ── Query keys & options ──────────────────────────────── */

export const staffSearchKeys = {
  all: ['staff', 'search'] as const,
  markets: () => [...staffSearchKeys.all, 'markets'] as const,
  queries: (kind: StaffSearchReportKind, q: StaffSearchQuery) => [...staffSearchKeys.all, 'queries', kind, q] as const,
  clickThrough: (q: StaffSearchQuery) => [...staffSearchKeys.all, 'click-through', q] as const,
  synonyms: () => [...staffSearchKeys.all, 'synonyms'] as const
};

export const staffSearchQueries = {
  markets: (api: StaffSearchApi) => queryOptions({ queryKey: staffSearchKeys.markets(), queryFn: ({ signal }) => api.markets(signal), staleTime: 5 * 60_000 }),
  queries: (api: StaffSearchApi, kind: StaffSearchReportKind, q: StaffSearchQuery) =>
    queryOptions({ queryKey: staffSearchKeys.queries(kind, q), queryFn: ({ signal }) => api.queries(kind, q, signal), placeholderData: keepPreviousData, staleTime: 30_000 }),
  clickThrough: (api: StaffSearchApi, q: StaffSearchQuery) =>
    queryOptions({ queryKey: staffSearchKeys.clickThrough(q), queryFn: ({ signal }) => api.clickThrough(q, signal), placeholderData: keepPreviousData, staleTime: 30_000 }),
  synonyms: (api: StaffSearchApi) => queryOptions({ queryKey: staffSearchKeys.synonyms(), queryFn: ({ signal }) => api.synonyms(signal), staleTime: 15_000 })
};
