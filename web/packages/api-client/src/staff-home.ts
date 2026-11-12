/* Staff Home settings and Home preview (S417, S418, S430; docs/engineering/dynamic-home.md).
   GET/PUT /v1/staff/home/settings: the eligibility thresholds, caps, labels, schedule and order bands of every home module.
   GET /v1/staff/home/preview: the home as shoppers would see it for a ZIP, signed-in state, language and date, plus one decision per module
   (shown or hidden, a reason code, a sentence in plain words and the live count against the threshold).
   Module names, never ids. A change needs a reason (4 characters or more) and is audit-logged by the gateway. */
import { queryOptions } from '@tanstack/react-query';
import { z } from 'zod';

import { type Requester } from './http';
import { HomeSchema } from './schemas/home';

/** Every module the registry knows, in the order staff see them. */
export const HOME_MODULE_KEYS = [
  'hero',
  'continue-shopping',
  'recently-viewed',
  'deals',
  'departments',
  'groceries',
  'services',
  'communities',
  'new-arrivals',
  'top-sellers',
  'pre-order',
  'plus',
  'sell'
] as const;
export type HomeModuleKey = (typeof HOME_MODULE_KEYS)[number];

/** Where a module sits when it is shown: `top` first, then `upper`, `middle`, `lower`. Weight orders modules inside one band. */
export const HOME_BANDS = ['top', 'upper', 'middle', 'lower'] as const;
export const HomeBandSchema = z.enum(HOME_BANDS);
export type HomeBand = z.infer<typeof HomeBandSchema>;

const LabelsSchema = z.record(z.string(), z.string());

export const HomeModuleRuleSchema = z.object({
  key: z.enum(HOME_MODULE_KEYS),
  /** The module's name as staff know it ("Shop by community"). */
  name: z.string(),
  enabled: z.boolean(),
  minProducts: z.number().int().min(0),
  minSellers: z.number().int().min(0),
  /** Communities and departments: how many qualifying units the module needs, and what each needs. */
  minUnits: z.number().int().min(0),
  unitMinProducts: z.number().int().min(0),
  unitMinSellers: z.number().int().min(0),
  /** Newest product may not be older than this many days (0 = no limit). */
  maxAgeDays: z.number().int().min(0),
  weight: z.number().int().min(0).max(100),
  band: HomeBandSchema,
  /** Staff-authored title per locale; English is required, other locales fall back to it. Empty = the built-in translated title. */
  labels: LabelsSchema.default({}),
  /** Schedule: the module exists only between these instants (RFC 3339). */
  startsAt: z.string().nullable().default(null),
  endsAt: z.string().nullable().default(null)
});
export type HomeModuleRule = z.infer<typeof HomeModuleRuleSchema>;

export const HomeDynamicModeSchema = z.enum(['off', 'shadow', 'on']);
export type HomeDynamicMode = z.infer<typeof HomeDynamicModeSchema>;

export const HomeSettingsSchema = z.object({
  version: z.number().int(),
  /** home.dynamic: `off` is today's composition, `shadow` logs differences only, `on` serves the eligibility-driven page. */
  dynamic: HomeDynamicModeSchema,
  /** At most one sponsored module per this many organic ones (default 3). */
  organicPerSponsored: z.number().int().min(1),
  minModules: z.number().int().min(0),
  maxModules: z.number().int().min(1),
  modules: z.array(HomeModuleRuleSchema),
  updatedAt: z.string().nullable(),
  /** A name, never an id. */
  updatedByName: z.string().nullable(),
  canEdit: z.boolean()
});
export type HomeSettings = z.infer<typeof HomeSettingsSchema>;

/** What a save sends: everything but the read-only fields, the version being replaced and the mandatory reason. */
export type HomeSettingsInput = Omit<HomeSettings, 'version' | 'updatedAt' | 'updatedByName' | 'canEdit'>;

export const MIN_REASON_LENGTH = 4;

export const HomeReasonCodeSchema = z.enum([
  'ok',
  'disabled',
  'scheduled-later',
  'scheduled-ended',
  'not-enough-products',
  'not-enough-sellers',
  'not-enough-units',
  'stale',
  'no-provider',
  'needs-sign-in',
  'no-signal',
  'no-open-window',
  'page-full',
  'fallback'
]);
export type HomeReasonCode = z.infer<typeof HomeReasonCodeSchema>;

export const HomeDecisionSchema = z.object({
  module: z.enum(HOME_MODULE_KEYS),
  /** Module name, e.g. "Shop by community". */
  label: z.string(),
  shown: z.boolean(),
  reason: HomeReasonCodeSchema,
  /** The sentence in plain words, e.g. "Needs 3 communities with 8 eligible products and 2 sellers each; 1 qualifies." */
  detail: z.string(),
  metrics: z.object({ qualifying: z.number(), required: z.number() }),
  /** Names of the qualifying (or near-qualifying) departments and communities. */
  units: z.array(z.string()).default([]),
  /** A staff-pinned placement won a tie against this automatic module. */
  pinned: z.boolean().default(false),
  /** 1-based place on the page when shown. */
  position: z.number().int().nullable().default(null)
});
export type HomeDecision = z.infer<typeof HomeDecisionSchema>;

export const HomePreviewSchema = z.object({
  home: HomeSchema,
  decisions: z.array(HomeDecisionSchema),
  /** The seed the preview used; paste it back to reproduce the same view. */
  seed: z.string(),
  asOf: z.string(),
  /** Signals behind the decisions, in the preview's own words (products, departments, communities, sellers, providers). */
  signals: z.object({ products: z.number(), departments: z.number(), communities: z.number(), sellers: z.number(), providers: z.number() })
});
export type HomePreview = z.infer<typeof HomePreviewSchema>;

/** Catalogues the preview can replay instead of the live one (S418 "simulate a catalogue"). */
export const SIMULATED_CATALOGUES = [0, 10, 100, 10000] as const;
export type SimulatedCatalogue = (typeof SIMULATED_CATALOGUES)[number];

export interface HomePreviewQuery {
  zip?: string;
  signedIn?: boolean;
  locale?: string;
  /** Local day, YYYY-MM-DD; evaluated at the start of that day (UTC). */
  asOf?: string;
  seed?: string;
  simulate?: SimulatedCatalogue;
}

/** Why a save is refused client-side before the request: the mandatory reason, and thresholds that cannot be right. */
export function homeSettingsProblems(input: HomeSettingsInput, reason: string): string[] {
  const out: string[] = [];
  if (reason.trim().length < MIN_REASON_LENGTH) out.push('reason');
  if (input.minModules > input.maxModules) out.push('modules');
  for (const m of input.modules) {
    if (!m.labels.en && Object.keys(m.labels).some((k) => m.labels[k])) out.push(`label:${m.key}`);
    if (m.startsAt && m.endsAt && Date.parse(m.endsAt) <= Date.parse(m.startsAt)) out.push(`schedule:${m.key}`);
  }
  return out;
}

export function createStaffHomeApi(request: Requester) {
  return {
    settings: (signal?: AbortSignal): Promise<HomeSettings> => request('/v1/staff/home/settings', HomeSettingsSchema, { signal }),
    /** Saves a new version; 409 when someone saved in between, 422 on `reason` or a threshold. */
    saveSettings: (input: HomeSettingsInput, version: number, reason: string): Promise<HomeSettings> =>
      request('/v1/staff/home/settings', HomeSettingsSchema, { method: 'PUT', body: { ...input, version, reason: reason.trim() } }),
    preview: (q: HomePreviewQuery, signal?: AbortSignal): Promise<HomePreview> => {
      const p = new URLSearchParams();
      if (q.zip?.trim()) p.set('zip', q.zip.trim());
      p.set('signedIn', String(q.signedIn ?? false));
      if (q.locale) p.set('locale', q.locale);
      if (q.asOf) p.set('asOf', q.asOf);
      if (q.seed?.trim()) p.set('seed', q.seed.trim());
      if (q.simulate !== undefined) p.set('simulate', String(q.simulate));
      return request('/v1/staff/home/preview', HomePreviewSchema, { query: p, signal });
    }
  };
}
export type StaffHomeApi = ReturnType<typeof createStaffHomeApi>;

export const staffHomeKeys = {
  all: ['staff', 'home'] as const,
  settings: () => [...staffHomeKeys.all, 'settings'] as const,
  preview: (q: HomePreviewQuery) => [...staffHomeKeys.all, 'preview', q] as const
};

export const staffHomeQueries = {
  settings: (api: StaffHomeApi) => queryOptions({ queryKey: staffHomeKeys.settings(), queryFn: ({ signal }) => api.settings(signal), staleTime: 15_000 }),
  /** A preview is a read: no refetch on focus, so a reproduced view does not move under the reader. */
  preview: (api: StaffHomeApi, q: HomePreviewQuery) =>
    queryOptions({ queryKey: staffHomeKeys.preview(q), queryFn: ({ signal }) => api.preview(q, signal), staleTime: Infinity })
};
