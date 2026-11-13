/* Staff Home settings, community registry and Home preview (S417, S418, S420, S424, S430; docs/engineering/dynamic-home.md).
   GET/PUT /v1/staff/home/settings: the eligibility thresholds, caps, department labels and order bands of every home module.
   GET /v1/staff/home/settings/versions, POST .../revert: who changed what, when and why, and a one-step way back.
   GET /v1/staff/home/communities, PUT/DELETE .../{key}: the community registry (canonical key, five-locale labels, aliases).
   GET /v1/staff/home/preview: the home as shoppers would see it for a ZIP, signed-in state, language and date, plus one decision per
   module (shown or hidden, a reason code, a sentence in plain words and the live count against the threshold).
   Module names, never ids. A change needs a reason (10 characters or more) and is audit-logged by the gateway. */
import { queryOptions } from '@tanstack/react-query';
import { z } from 'zod';

import { type Requester } from './http';
import { HomeBlockSchema, HomeModuleDecisionSchema } from './schemas/home';
import { SiteDepartmentSchema } from './schemas/site-nav';

/** Order bands: bands keep their order, modules inside a `discovery` band rotate by visit. */
export const HOME_BANDS = ['hero', 'personal', 'discovery', 'promo', 'brand'] as const;
export const HomeBandSchema = z.enum(HOME_BANDS);
export type HomeBand = z.infer<typeof HomeBandSchema>;

export const LOCALES = ['en', 'fr', 'es', 'ar', 'yo'] as const;
export type StaffLocale = (typeof LOCALES)[number];
export const StaffLocaleLabelsSchema = z.object({ en: z.string(), fr: z.string().optional(), es: z.string().optional(), ar: z.string().optional(), yo: z.string().optional() });
export type StaffLocaleLabels = z.infer<typeof StaffLocaleLabelsSchema>;

/** One staff-editable module row. Zero means "no requirement". */
export const HomeModuleRuleSchema = z.object({
  enabled: z.boolean().default(true),
  minProducts: z.number().int().min(0).default(0),
  minSellers: z.number().int().min(0).default(0),
  /** Departments or communities the module needs, and what each must have. */
  minUnits: z.number().int().min(0).default(0),
  unitMinProducts: z.number().int().min(0).default(0),
  unitMinSellers: z.number().int().min(0).default(0),
  /** Newest eligible product may not be older than this many days (0 = no limit). */
  maxAgeDays: z.number().int().min(0).default(0),
  /** Order inside the module's band, higher first (0 to 100). */
  weight: z.number().int().min(0).max(100).default(0),
  /** Items the module shows (1 to 24); the variety pool is `poolMultiplier` times this. */
  show: z.number().int().min(1).max(24).optional(),
  band: HomeBandSchema.optional()
});
export type HomeModuleRule = z.infer<typeof HomeModuleRuleSchema>;

export const HomeCapsSchema = z.object({
  maxModules: z.number().int().default(12),
  minModules: z.number().int().default(3),
  sellerShare: z.number().int().default(2),
  /** At most 1 sponsored module per this many organic ones. */
  sponsoredPerOrganic: z.number().int().default(3),
  poolMultiplier: z.number().int().default(3),
  noveltyLoads: z.number().int().default(3),
  noveltyPenalty: z.number().default(0.6),
  visitWindowMinutes: z.number().int().default(30),
  hysteresis: z.number().int().default(2),
  dealMinPercent: z.number().int().default(10),
  navBarSize: z.number().int().default(7)
});
export type HomeCaps = z.infer<typeof HomeCapsSchema>;

export const HomeDynamicModeSchema = z.enum(['off', 'shadow', 'on']);
export type HomeDynamicMode = z.infer<typeof HomeDynamicModeSchema>;

export const HomeSettingsSchema = z.object({
  /** 0 = defaults, nothing stored yet. */
  version: z.number().int(),
  /** `null` follows the environment default. */
  dynamic: HomeDynamicModeSchema.nullable().default(null),
  /** What the page runs now: the stored `dynamic` value, else the environment default. */
  effectiveDynamic: HomeDynamicModeSchema,
  modules: z.record(z.string(), HomeModuleRuleSchema),
  /** Module key to the name staff read (never show the key). */
  moduleNames: z.record(z.string(), z.string()),
  caps: HomeCapsSchema,
  /** Display-label overrides by department key; without one the label is derived from the live taxonomy. */
  departmentLabels: z.record(z.string(), StaffLocaleLabelsSchema).default({}),
  updatedAt: z.string().nullish(),
  /** A name, never an id. */
  updatedBy: z.string().nullish()
});
export type HomeSettings = z.infer<typeof HomeSettingsSchema>;

/** What a save sends: the editable fields, the version being replaced and the mandatory reason. */
export interface HomeSettingsInput {
  dynamic: HomeDynamicMode | null;
  modules: Record<string, HomeModuleRule>;
  caps: HomeCaps;
  departmentLabels: Record<string, StaffLocaleLabels>;
}

export const MIN_REASON_LENGTH = 10;
export const MAX_REASON_LENGTH = 300;

export const HomeSettingsVersionSchema = z.object({
  version: z.number().int(),
  reason: z.string(),
  changedBy: z.string(),
  changedAt: z.string(),
  revertOf: z.number().int().nullish(),
  changes: z.array(z.object({ field: z.string(), from: z.unknown(), to: z.unknown() }))
});
export type HomeSettingsVersion = z.infer<typeof HomeSettingsVersionSchema>;

export const StaffCommunitySchema = z.object({
  key: z.string(),
  labels: StaffLocaleLabelsSchema,
  aliases: z.array(z.string()).default([]),
  imageUrl: z.string().nullish(),
  /** False when the editorial image fails the real-photo check (then the top product photo is used). */
  imageAccepted: z.boolean().optional(),
  eligibleProducts: z.number().optional(),
  sellers: z.number().optional(),
  /** Above the thresholds now. */
  visible: z.boolean().optional()
});
export type StaffCommunity = z.infer<typeof StaffCommunitySchema>;

export const StaffCommunitiesSchema = z.object({
  registered: z.array(StaffCommunitySchema),
  unregistered: z.array(z.object({ value: z.string(), eligibleProducts: z.number() }))
});
export type StaffCommunities = z.infer<typeof StaffCommunitiesSchema>;

export interface StaffCommunityWrite {
  labels: StaffLocaleLabels;
  aliases: string[];
  imageUrl: string | null;
}

/** Catalogues the preview can replay instead of the live one (S418 "simulate a catalogue"). */
export const SIMULATED_CATALOGUES = ['0', '10', '100', '10000'] as const;
export type SimulatedCatalogue = (typeof SIMULATED_CATALOGUES)[number];
export type PreviewCatalogue = 'live' | SimulatedCatalogue;

export const HomePreviewSchema = z.object({
  asOf: z.string(),
  locale: z.string(),
  catalog: z.string(),
  /** The seed the preview used; paste it back to reproduce the same view. */
  seed: z.string(),
  deliverTo: z.object({ zip: z.string().optional(), city: z.string().optional(), state: z.string().optional() }).optional(),
  blocks: z.array(HomeBlockSchema),
  decisions: z.array(HomeModuleDecisionSchema),
  /** The season window active at `asOf` and the modules it boosted. */
  season: z.object({ name: z.string(), startsAt: z.string().optional(), endsAt: z.string().optional(), boosted: z.array(z.string()).default([]) }).nullish()
});
export type HomePreview = z.infer<typeof HomePreviewSchema>;

export interface HomePreviewQuery {
  zip?: string;
  signedIn?: boolean;
  locale?: string;
  /** RFC 3339 instant; a day alone (YYYY-MM-DD) means the start of that day (UTC). */
  asOf?: string;
  catalog?: PreviewCatalogue;
  seed?: string;
  deterministic?: boolean;
  variant?: string;
}

/** Why a save is refused before the request: the mandatory reason and values that cannot be right. Returns field keys. */
export function homeSettingsProblems(input: HomeSettingsInput, reason: string): string[] {
  const out: string[] = [];
  const r = reason.trim();
  if (r.length < MIN_REASON_LENGTH || r.length > MAX_REASON_LENGTH) out.push('reason');
  if (input.caps.minModules > input.caps.maxModules) out.push('caps.minModules');
  for (const [key, m] of Object.entries(input.modules)) {
    if (m.weight < 0 || m.weight > 100) out.push(`modules.${key}.weight`);
    if (m.show !== undefined && (m.show < 1 || m.show > 24)) out.push(`modules.${key}.show`);
  }
  for (const [key, l] of Object.entries(input.departmentLabels)) {
    const others = [l.fr, l.es, l.ar, l.yo].some((v) => !!v?.trim());
    if (others && !(l.en ?? '').trim()) out.push(`departmentLabels.${key}`);
  }
  return out;
}

/** "2026-12-01" becomes the start of that day (UTC); a full instant is kept. */
export function asOfInstant(v: string): string {
  return /^\d{4}-\d{2}-\d{2}$/.test(v) ? `${v}T00:00:00Z` : v;
}

export function createStaffHomeApi(request: Requester) {
  return {
    settings: (signal?: AbortSignal): Promise<HomeSettings> => request('/v1/staff/home/settings', HomeSettingsSchema, { signal }),
    /** Saves a new version; 409 when someone saved in between, 422 on `reason` or a threshold. */
    saveSettings: (input: HomeSettingsInput, expectedVersion: number, reason: string): Promise<HomeSettings> =>
      request('/v1/staff/home/settings', HomeSettingsSchema, { method: 'PUT', body: { ...input, expectedVersion, reason: reason.trim() } }),
    versions: (signal?: AbortSignal): Promise<HomeSettingsVersion[]> => request('/v1/staff/home/settings/versions', z.array(HomeSettingsVersionSchema), { signal }),
    revert: (toVersion: number, reason: string): Promise<HomeSettings> =>
      request('/v1/staff/home/settings/revert', HomeSettingsSchema, { method: 'POST', body: { toVersion, reason: reason.trim() } }),
    /** Every catalog department with its eligible count, the empty ones too (`count: 0`, not linked): the choices of the label editor. */
    departments: (signal?: AbortSignal) =>
      request('/v1/site/departments', z.object({ departments: z.array(SiteDepartmentSchema) }), { query: { includeEmpty: 'true' }, signal }).then((r) => r.departments),
    communities: (signal?: AbortSignal): Promise<StaffCommunities> => request('/v1/staff/home/communities', StaffCommunitiesSchema, { signal }),
    saveCommunity: (key: string, input: StaffCommunityWrite, reason: string): Promise<StaffCommunity> =>
      request(`/v1/staff/home/communities/${encodeURIComponent(key)}`, StaffCommunitySchema, { method: 'PUT', body: { ...input, reason: reason.trim() } }),
    deleteCommunity: (key: string, reason: string): Promise<void> =>
      request(`/v1/staff/home/communities/${encodeURIComponent(key)}`, z.void(), { method: 'DELETE', query: { reason: reason.trim() } }),
    preview: (q: HomePreviewQuery, signal?: AbortSignal): Promise<HomePreview> => {
      const p = new URLSearchParams();
      if (q.zip?.trim()) p.set('zip', q.zip.trim());
      p.set('signedIn', String(q.signedIn ?? false));
      if (q.locale) p.set('locale', q.locale);
      if (q.asOf) p.set('asOf', asOfInstant(q.asOf));
      if (q.catalog && q.catalog !== 'live') p.set('catalog', q.catalog);
      if (q.seed?.trim()) p.set('seed', q.seed.trim());
      if (q.deterministic) p.set('deterministic', 'true');
      if (q.variant?.trim()) p.set('variant', q.variant.trim());
      return request('/v1/staff/home/preview', HomePreviewSchema, { query: p, signal });
    }
  };
}
export type StaffHomeApi = ReturnType<typeof createStaffHomeApi>;

export const staffHomeKeys = {
  all: ['staff', 'home'] as const,
  settings: () => [...staffHomeKeys.all, 'settings'] as const,
  versions: () => [...staffHomeKeys.all, 'versions'] as const,
  communities: () => [...staffHomeKeys.all, 'communities'] as const,
  departments: () => [...staffHomeKeys.all, 'departments'] as const,
  preview: (q: HomePreviewQuery) => [...staffHomeKeys.all, 'preview', q] as const
};

export const staffHomeQueries = {
  settings: (api: StaffHomeApi) => queryOptions({ queryKey: staffHomeKeys.settings(), queryFn: ({ signal }) => api.settings(signal), staleTime: 15_000 }),
  versions: (api: StaffHomeApi) => queryOptions({ queryKey: staffHomeKeys.versions(), queryFn: ({ signal }) => api.versions(signal), staleTime: 15_000 }),
  departments: (api: StaffHomeApi) => queryOptions({ queryKey: staffHomeKeys.departments(), queryFn: ({ signal }) => api.departments(signal), staleTime: 60_000 }),
  communities: (api: StaffHomeApi) => queryOptions({ queryKey: staffHomeKeys.communities(), queryFn: ({ signal }) => api.communities(signal), staleTime: 15_000 }),
  /** A preview is a read: no refetch on focus, so a reproduced view does not move under the reader. */
  preview: (api: StaffHomeApi, q: HomePreviewQuery) =>
    queryOptions({ queryKey: staffHomeKeys.preview(q), queryFn: ({ signal }) => api.preview(q, signal), staleTime: Infinity })
};
