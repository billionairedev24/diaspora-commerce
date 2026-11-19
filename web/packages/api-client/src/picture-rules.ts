/* The ONE picture-rules setting (docs/engineering/picture-rules.md): a named profile (strict, startup, testing) plus per-knob overrides,
   owned and audited by catalog. Staff change it in Settings > Picture rules (contracts/openapi/gateway-bff-staff.yaml, tag Picture rules);
   Seller Center and the apps read the rule in force (GET /v1/seller/picture-rules, GET /v1/picture-rules) to word their messages. */
import { queryOptions } from '@tanstack/react-query';
import { z } from 'zod';

import { type Requester } from './http';

export const PICTURE_PROFILES = ['strict', 'startup', 'testing'] as const;
export type PictureProfile = (typeof PICTURE_PROFILES)[number];

/** Accepted image types under every profile (SVG, HTML and executables never are). */
export const PICTURE_FORMATS = ['image/jpeg', 'image/png', 'image/webp', 'image/avif'] as const;
export type PictureFormat = (typeof PICTURE_FORMATS)[number];

export const PictureKnobsSchema = z.object({
  minImages: z.number().int(),
  minLongSidePx: z.number().int(),
  minShortSidePx: z.number().int(),
  maxMegabytes: z.number().int(),
  checkBackground: z.boolean(),
  checkAspect: z.boolean(),
  checkTextOverlay: z.boolean(),
  requireRealPhotoForStorefront: z.boolean(),
  requirePhotoForMatching: z.boolean(),
  allowPlaceholders: z.boolean()
});
export type PictureKnobs = z.infer<typeof PictureKnobsSchema>;
export type PictureKnobName = keyof PictureKnobs;

export const PICTURE_INT_KNOBS = ['minImages', 'minLongSidePx', 'minShortSidePx', 'maxMegabytes'] as const satisfies readonly PictureKnobName[];
export const PICTURE_BOOL_KNOBS = ['checkBackground', 'checkAspect', 'checkTextOverlay', 'requireRealPhotoForStorefront', 'requirePhotoForMatching', 'allowPlaceholders'] as const satisfies readonly PictureKnobName[];
/** Knobs in the order the staff page shows them. */
export const PICTURE_KNOB_ORDER: readonly PictureKnobName[] = [...PICTURE_INT_KNOBS, ...PICTURE_BOOL_KNOBS];
/** Bounds the page validates before it asks (catalog checks them again). */
export const PICTURE_INT_BOUNDS: Record<(typeof PICTURE_INT_KNOBS)[number], { min: number; max: number }> = {
  minImages: { min: 0, max: 10 },
  minLongSidePx: { min: 1, max: 10_000 },
  minShortSidePx: { min: 1, max: 10_000 },
  maxMegabytes: { min: 1, max: 20 }
};

export const PictureOverridesSchema = PictureKnobsSchema.partial();
export type PictureOverrides = z.infer<typeof PictureOverridesSchema>;

export const PictureRulesSchema = z.object({
  profile: z.enum(PICTURE_PROFILES),
  knobs: PictureKnobsSchema,
  overridden: z.array(z.string()),
  formats: z.array(z.string()),
  version: z.number().int(),
  summary: z.string().optional(),
  relaxed: z.boolean().optional()
});
export type PictureRules = z.infer<typeof PictureRulesSchema>;

export const PictureRulesChangeSchema = z.object({
  version: z.number().int(),
  previousProfile: z.enum(PICTURE_PROFILES).nullable().optional(),
  profile: z.enum(PICTURE_PROFILES),
  overridden: z.array(z.string()),
  /** Who saved it, by name. */
  changedBy: z.string(),
  changedAt: z.string(),
  reason: z.string()
});
export type PictureRulesChange = z.infer<typeof PictureRulesChangeSchema>;

export const PictureRulesViewSchema = z.object({
  rules: PictureRulesSchema,
  overrides: PictureOverridesSchema,
  /** Who saved the rules in force, by name; absent while the environment default is in force. */
  updatedBy: z.string().nullable().optional(),
  updatedAt: z.string().nullable().optional(),
  canChange: z.boolean(),
  environmentDefault: z.enum(PICTURE_PROFILES),
  relaxed: z.boolean(),
  profiles: z.array(PictureRulesSchema),
  history: z.array(PictureRulesChangeSchema)
});
export type PictureRulesView = z.infer<typeof PictureRulesViewSchema>;

export interface PictureRulesInput {
  profile: PictureProfile;
  overrides: PictureOverrides;
  reason: string;
}
export const PICTURE_REASON_MIN = 3;
export const PICTURE_REASON_MAX = 300;

export function createStaffPictureRulesApi(request: Requester) {
  return {
    get: (signal?: AbortSignal): Promise<PictureRulesView> => request('/v1/staff/picture-rules', PictureRulesViewSchema, { signal }),
    /** Saves against the version the person loaded (If-Match); the answer is the new state. */
    set: (input: PictureRulesInput, version: number): Promise<PictureRulesView> =>
      request('/v1/staff/picture-rules', PictureRulesViewSchema, { method: 'PUT', body: input, headers: { 'If-Match': `"${version}"` } })
  };
}
export type StaffPictureRulesApi = ReturnType<typeof createStaffPictureRulesApi>;

export const staffPictureRulesKeys = { all: ['staff', 'picture-rules'] as const };
export const staffPictureRulesQueries = {
  get: (api: StaffPictureRulesApi) => queryOptions({ queryKey: staffPictureRulesKeys.all, queryFn: ({ signal }) => api.get(signal), staleTime: 10_000 })
};

/** The seller side: the rule in force, for messages that state the actual rule. */
export function createPictureRulesReader(request: Requester, path: '/v1/seller/picture-rules' | '/v1/picture-rules') {
  return { get: (signal?: AbortSignal): Promise<PictureRules> => request(path, PictureRulesSchema, { signal }) };
}
export type PictureRulesReader = ReturnType<typeof createPictureRulesReader>;
export const pictureRulesKeys = { rules: ['picture-rules'] as const };
export const pictureRulesQueries = {
  get: (api: PictureRulesReader) => queryOptions({ queryKey: pictureRulesKeys.rules, queryFn: ({ signal }) => api.get(signal), staleTime: 30_000 })
};

/** The strict standard: what a screen shows before the rule has loaded, or when it cannot (today's behaviour). */
export const STRICT_PICTURE_RULES: PictureRules = {
  profile: 'strict',
  knobs: { minImages: 1, minLongSidePx: 1000, minShortSidePx: 500, maxMegabytes: 8, checkBackground: true, checkAspect: true, checkTextOverlay: true, requireRealPhotoForStorefront: true, requirePhotoForMatching: true, allowPlaceholders: false },
  overridden: [],
  formats: [...PICTURE_FORMATS],
  version: 0
};

/** The default knob values of each profile (the same numbers as catalog's PictureKnobs.of and go/pkg/picturerules; contracts/picture-rules/vectors.json). */
export const PICTURE_PROFILE_KNOBS: Record<PictureProfile, PictureKnobs> = {
  strict: STRICT_PICTURE_RULES.knobs,
  startup: { minImages: 1, minLongSidePx: 500, minShortSidePx: 300, maxMegabytes: 8, checkBackground: false, checkAspect: false, checkTextOverlay: false, requireRealPhotoForStorefront: true, requirePhotoForMatching: true, allowPlaceholders: false },
  testing: { minImages: 0, minLongSidePx: 1, minShortSidePx: 1, maxMegabytes: 8, checkBackground: false, checkAspect: false, checkTextOverlay: false, requireRealPhotoForStorefront: false, requirePhotoForMatching: false, allowPlaceholders: true }
};

/** The plain words for a set of knobs, the way the servers word it ("at least 1 photo, at least 500 px on the longest side, ..."). */
export function summaryOfKnobs(k: PictureKnobs): string {
  const parts = [k.minImages === 0 ? 'photos are optional' : k.minImages === 1 ? 'at least 1 photo' : `at least ${k.minImages} photos`, `at least ${k.minLongSidePx} px on the longest side`];
  if (k.minShortSidePx > 1) parts.push(`at least ${k.minShortSidePx} px on the shortest side`);
  parts.push(`up to ${k.maxMegabytes} MB each`, PICTURE_FORMATS_LABEL);
  if (k.checkBackground) parts.push('main photo on a plain white background');
  return parts.join(', ');
}

/** Whether a file type is accepted (the bytes are checked again by the server, never the name). */
export function pictureFormatAccepted(contentType: string): boolean {
  return (PICTURE_FORMATS as readonly string[]).includes(contentType);
}

/** "JPG, PNG, WebP or AVIF". */
export const PICTURE_FORMATS_LABEL = 'JPG, PNG, WebP or AVIF';

/** The knobs that differ from the strict standard (what the warning banner can list). */
export function knobsLooserThanStrict(knobs: PictureKnobs): PictureKnobName[] {
  const strict = STRICT_PICTURE_RULES.knobs;
  return PICTURE_KNOB_ORDER.filter((k) => knobs[k] !== strict[k]);
}
