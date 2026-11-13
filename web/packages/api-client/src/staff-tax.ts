/* Fees & commissions → Sales tax: the category tax tools (contracts/openapi/gateway-bff-staff.yaml, tag Tax; tax-returns behind
   it, docs/engineering/category-tax-design.md): the 73 category profiles, the tax review queue and decisions, the provider
   code maps, the tax method with its SHADOW provider and the shadow report. Every label arrives in plain words, people and
   sellers by name. Every change carries a reason of at least TAX_REASON_MIN characters. */
import { keepPreviousData, queryOptions } from '@tanstack/react-query';
import { z } from 'zod';

import { type Requester } from './http';

/** The shortest reason any tax change may carry (the BFF refuses less). */
export const TAX_REASON_MIN = 10;
export const TAX_REASON_MAX = 1000;

/** Counts the characters the way the BFF does (runes, trimmed), so the inline rule and the server agree. */
export const reasonLength = (text: string): number => [...text.trim()].length;
export const reasonOk = (text: string): boolean => reasonLength(text) >= TAX_REASON_MIN && reasonLength(text) <= TAX_REASON_MAX;

export const TaxGateSchema = z.enum(['ACTIVE', 'NEEDS_APPROVAL', 'BLOCKED_BUNDLE', 'REFINE_CODE', 'CONFLICT_OPEN']);
export type TaxGate = z.infer<typeof TaxGateSchema>;
export const TaxProviderSchema = z.enum(['STRIPE', 'AVALARA']);
export type TaxProvider = z.infer<typeof TaxProviderSchema>;
export const TaxMethodKeySchema = z.enum(['RULES', 'STRIPE_TAX', 'AVALARA']);
export type TaxMethodKey = z.infer<typeof TaxMethodKeySchema>;

/* ── Profiles ─────────────────────────────────────────── */

export const TaxProfileRowSchema = z.object({
  categoryCode: z.string(),
  name: z.string(),
  department: z.string(),
  departmentName: z.string(),
  status: TaxGateSchema,
  statusLabel: z.string(),
  statusExplain: z.string().default(''),
  stripeCodes: z.array(z.string()).default([]),
  stripeCodeList: z.string().default(''),
  defaultOutcome: z.string().nullish(),
  defaultOutcomeLabel: z.string().default(''),
  scenarioCount: z.number().int(),
  ruleVersion: z.string().default(''),
  openDecision: z.number().int().nullish()
});
export type TaxProfileRow = z.infer<typeof TaxProfileRowSchema>;

const FacetSchema = z.object({ value: z.string(), label: z.string(), count: z.number().int() });

export const TaxProfilePageSchema = z.object({
  items: z.array(TaxProfileRowSchema),
  total: z.number().int(),
  page: z.number().int(),
  size: z.number().int(),
  departments: z.array(z.object({ code: z.string(), name: z.string(), count: z.number().int() })),
  statuses: z.array(FacetSchema),
  ruleVersion: z.string().default(''),
  canChange: z.boolean()
});
export type TaxProfilePage = z.infer<typeof TaxProfilePageSchema>;

export const TaxFactRowSchema = z.object({
  id: z.string(),
  label: z.string(),
  kind: z.string(),
  unit: z.string().default(''),
  help: z.string().default(''),
  owner: z.string(),
  ownerLabel: z.string().default(''),
  required: z.boolean().default(false),
  needed: z.string().default(''),
  evidence: z.boolean().default(false)
});
export type TaxFactRow = z.infer<typeof TaxFactRowSchema>;

export const TaxDecisionRowSchema = z.object({
  id: z.string(),
  sentence: z.string(),
  condition: z.string().default(''),
  outcome: z.string(),
  outcomeLabel: z.string(),
  internalTaxCode: z.string(),
  stripeCode: z.string().default(''),
  rateDisplay: z.string().default('')
});
export type TaxDecisionRow = z.infer<typeof TaxDecisionRowSchema>;

export const TaxNoteSchema = z.object({
  kind: z.enum(['RULE', 'GATE', 'CATEGORY']),
  title: z.string(),
  text: z.string(),
  rowId: z.string().optional(),
  decision: z.number().int().nullish()
});
export type TaxNote = z.infer<typeof TaxNoteSchema>;

export const TaxProfileActionSchema = z.object({
  key: z.enum(['approve', 'reopen']),
  label: z.string(),
  status: z.enum(['ACTIVE', 'NEEDS_APPROVAL']),
  needsSignOff: z.boolean(),
  explain: z.string(),
  available: z.boolean(),
  why: z.string().default('')
});
export type TaxProfileAction = z.infer<typeof TaxProfileActionSchema>;

export const TaxHistoryEntrySchema = z.object({
  at: z.string().optional(),
  changedAt: z.string().optional(),
  changedByName: z.string().optional(),
  status: z.string().optional(),
  reason: z.string().optional(),
  approvedBy: z.string().optional(),
  decisionRef: z.string().optional()
});
export type TaxHistoryEntry = z.infer<typeof TaxHistoryEntrySchema>;

export const TaxProfileSchema = TaxProfileRowSchema.extend({
  conflictingCodes: z.array(z.string()).default([]),
  facts: z.array(TaxFactRowSchema),
  decisions: z.array(TaxDecisionRowSchema),
  notes: z.array(TaxNoteSchema),
  history: z.array(TaxHistoryEntrySchema).default([]),
  changedByName: z.string().default(''),
  changedAt: z.string().default(''),
  canChange: z.boolean(),
  actions: z.array(TaxProfileActionSchema),
  readOnlyReason: z.string().default('')
});
export type TaxProfile = z.infer<typeof TaxProfileSchema>;

export interface TaxProfilesQuery {
  department?: string;
  status?: string;
  q?: string;
  sort?: string[];
  page?: number;
  size?: number;
}

export interface TaxProfileStatusChange {
  status: 'ACTIVE' | 'NEEDS_APPROVAL';
  reason: string;
  approvedBy?: string;
  decisionRef?: string;
}

/* ── Review queue and decisions ───────────────────────── */

export const TaxProposedSchema = z.object({
  status: z.string().default(''),
  statusLabel: z.string().default(''),
  outcome: z.string().default(''),
  outcomeLabel: z.string().default(''),
  internalTaxCode: z.string().default(''),
  rateDisplay: z.string().default(''),
  rowId: z.string().default(''),
  reason: z.string().default(''),
  ruleVersion: z.string().default(''),
  because: z.string().default('')
});
export type TaxProposed = z.infer<typeof TaxProposedSchema>;

export const TaxReviewRowSchema = z.object({
  atin: z.string(),
  gtin: z.string().default(''),
  title: z.string(),
  /** Seller names, never ids ("Unknown seller" when the directory cannot name one). */
  sellers: z.array(z.string()),
  /** First public photo of the product; empty when catalog has none. */
  imageUrl: z.string().default(''),
  categoryCode: z.string(),
  categoryName: z.string(),
  status: z.string(),
  statusLabel: z.string(),
  why: z.string(),
  /** Why a person is needed, as a code derived from the stored state (read only); empty when nothing waits. */
  reviewReasonCode: z.string().default(''),
  /** English fallback for `reviewReasonCode`: the console translates the code. */
  reviewReasonLabel: z.string().default(''),
  proposed: TaxProposedSchema,
  classifiedAt: z.string().default(''),
  classifiedByName: z.string().default(''),
  reviewedAt: z.string().default(''),
  reviewedByName: z.string().default(''),
  ruleVersion: z.string().default(''),
  /** Approved by the system under the risk-based rule, live, waiting in the Audit sample for a person. */
  auditSample: z.boolean().default(false),
  /** Approved by the system, not by a person. */
  autoApproved: z.boolean().default(false)
});
export type TaxReviewRow = z.infer<typeof TaxReviewRowSchema>;

export const TaxReviewPageSchema = z.object({
  items: z.array(TaxReviewRowSchema),
  total: z.number().int(),
  page: z.number().int(),
  size: z.number().int(),
  status: z.string(),
  statuses: z.array(z.object({ value: z.string(), label: z.string() })).default([]),
  /** The reasons a person is needed, for the filter. */
  reasons: z.array(z.object({ value: z.string(), label: z.string() })).default([]),
  canDecide: z.boolean()
});
export type TaxReviewPage = z.infer<typeof TaxReviewPageSchema>;

/** A live listing with a variant that has no stored, resolved tax decision. Never expected: the page is empty when the tax gate holds. */
export const TaxLiveWithoutDecisionRowSchema = z.object({
  atin: z.string(),
  title: z.string(),
  sellerName: z.string(),
  categoryName: z.string().default(''),
  updatedAt: z.string().default(''),
  variants: z.array(z.object({ atin: z.string(), sku: z.string(), status: z.string(), statusLabel: z.string(), why: z.string() }))
});
export type TaxLiveWithoutDecisionRow = z.infer<typeof TaxLiveWithoutDecisionRowSchema>;

export const TaxLiveWithoutDecisionPageSchema = z.object({
  items: z.array(TaxLiveWithoutDecisionRowSchema),
  total: z.number().int(),
  page: z.number().int(),
  size: z.number().int(),
  /** False when catalog could not ask tax-returns: the list may be incomplete and is never an all-clear. */
  taxReturnsChecked: z.boolean().optional()
});
export type TaxLiveWithoutDecisionPage = z.infer<typeof TaxLiveWithoutDecisionPageSchema>;

export const TaxEvidenceSchema = z.object({
  factId: z.string().default(''),
  factLabel: z.string(),
  fileName: z.string(),
  url: z.string(),
  isImage: z.boolean(),
  uploadedByName: z.string().default(''),
  uploadedAt: z.string().default('')
});
export type TaxEvidence = z.infer<typeof TaxEvidenceSchema>;

export const TaxAuditEntrySchema = z.object({
  at: z.string(),
  actorName: z.string(),
  action: z.string(),
  actionLabel: z.string(),
  reason: z.string().default(''),
  ruleVersion: z.string().default('')
});
export type TaxAuditEntry = z.infer<typeof TaxAuditEntrySchema>;

export const TaxApproveFormSchema = z.object({
  staffFacts: z.array(z.object({ id: z.string(), label: z.string(), kind: z.string(), help: z.string().default(''), value: z.unknown().optional() })),
  needsRefinedCode: z.boolean(),
  refinedProviders: z.array(z.object({ provider: TaxProviderSchema, label: z.string(), value: z.string().default(''), required: z.boolean() }))
});
export type TaxApproveForm = z.infer<typeof TaxApproveFormSchema>;

export const TaxReviewSchema = TaxReviewRowSchema.extend({
  departmentName: z.string().default(''),
  productTypeCode: z.string().default(''),
  profileStatus: z.string().default(''),
  profileStatusLabel: z.string().default(''),
  facts: z.array(z.object({ id: z.string(), label: z.string(), value: z.string(), owner: z.string().default('') })),
  sellerListings: z.array(z.object({ sellerName: z.string(), answers: z.array(z.object({ label: z.string(), value: z.string() })), outcomeLabel: z.string().default(''), statusLabel: z.string().default('') })).default([]),
  evidence: z.array(TaxEvidenceSchema),
  decision: z.object({ reviewedByName: z.string().default(''), reviewedAt: z.string().default(''), ruleVersion: z.string().default('') }),
  audit: z.array(TaxAuditEntrySchema),
  canDecide: z.boolean(),
  canApprove: z.boolean(),
  /** An Audit sample item the signed-in person may confirm (approve with a reason) or move to another category. */
  canConfirm: z.boolean().default(false),
  approve: TaxApproveFormSchema,
  refinedProviderCodes: z.record(z.string(), z.string()).default({}),
  /** Present only when tax-returns reports how many orders use this variant. */
  ordersAffected: z.number().int().nullish()
});
export type TaxReview = z.infer<typeof TaxReviewSchema>;

export interface TaxReviewsQuery {
  status?: string;
  categoryCode?: string;
  /** Only items sold by this organisation (the id of a seller found by `reviewSellers`). */
  sellerOrgId?: string;
  /** Only items that need a person for this reason (a `reasons` value). */
  reason?: string;
  /** Item name, ATIN, GTIN or seller name. */
  q?: string;
  /** `<column>,asc|desc` by title, atin, category, status, submitted or reviewed. */
  sort?: string[];
  page?: number;
  size?: number;
}

export const TaxReviewSellersSchema = z.object({ items: z.array(z.object({ id: z.string(), name: z.string() })) });
export type TaxReviewSellers = z.infer<typeof TaxReviewSellersSchema>;

export interface TaxApproveInput {
  reason: string;
  staffFacts?: Record<string, boolean | string | number>;
  refinedProviderCodes?: Record<string, string>;
}

/* ── Provider code maps ───────────────────────────────── */

export const TaxCodeStateSchema = z.enum(['NOT_NEEDED', 'UNMAPPED', 'UNVERIFIED', 'VERIFIED']);
export type TaxCodeState = z.infer<typeof TaxCodeStateSchema>;

export const TaxCodeEntrySchema = z.object({
  internalTaxCode: z.string(),
  categoryCode: z.string().default(''),
  categoryName: z.string().default(''),
  outcome: z.string(),
  outcomeLabel: z.string().default(''),
  outcomeName: z.string().default(''),
  provider: z.string(),
  providerCode: z.string().nullable().transform((v) => v ?? ''),
  verified: z.boolean(),
  state: TaxCodeStateSchema,
  stateLabel: z.string(),
  note: z.string().default(''),
  mapVersion: z.number().int().default(0),
  changedByName: z.string().default(''),
  changedAt: z.string().default('')
});
export type TaxCodeEntry = z.infer<typeof TaxCodeEntrySchema>;

export const TaxCompletenessSchema = z.object({
  needed: z.number().int(),
  mapped: z.number().int(),
  verified: z.number().int(),
  unmapped: z.number().int(),
  unverified: z.number().int(),
  percent: z.number().int(),
  complete: z.boolean()
});
export type TaxCompleteness = z.infer<typeof TaxCompletenessSchema>;

export const TaxCodeMapSchema = z.object({
  provider: TaxProviderSchema,
  providerLabel: z.string(),
  activeProvider: z.string().default(''),
  items: z.array(TaxCodeEntrySchema),
  total: z.number().int(),
  page: z.number().int(),
  size: z.number().int(),
  completeness: TaxCompletenessSchema,
  canEdit: z.boolean(),
  states: z.array(z.object({ value: z.string(), label: z.string() })).default([])
});
export type TaxCodeMap = z.infer<typeof TaxCodeMapSchema>;

export const TaxCodeMapImportResultSchema = z.object({
  provider: z.string(),
  rows: z.number().int(),
  changed: z.number().int(),
  unchanged: z.number().int(),
  applied: z.number().int(),
  dryRun: z.boolean(),
  blocked: z.boolean().default(false),
  problems: z.array(z.object({ line: z.number().int(), internalTaxCode: z.string().default(''), message: z.string() })),
  preview: z.array(z.object({ line: z.number().int(), internalTaxCode: z.string(), from: z.string().default(''), to: z.string().default(''), verified: z.boolean(), fromVerified: z.boolean() }))
});
export type TaxCodeMapImportResult = z.infer<typeof TaxCodeMapImportResultSchema>;

export const TaxCodeMapExportSchema = z.object({ filename: z.string(), csv: z.string(), rows: z.number().int() });
export type TaxCodeMapExport = z.infer<typeof TaxCodeMapExportSchema>;

export interface TaxCodeMapQuery {
  provider?: string;
  state?: string;
  categoryCode?: string;
  q?: string;
  sort?: string[];
  page?: number;
  size?: number;
}

export interface TaxCodeUpdate {
  provider: TaxProvider;
  providerCode: string;
  verified: boolean;
  reason: string;
}

/* ── Method and SHADOW provider ───────────────────────── */

export const TaxMethodSchema = z.object({
  method: TaxMethodKeySchema,
  methodLabel: z.string(),
  methodExplain: z.string(),
  shadowProvider: z.string().default(''),
  shadowLabel: z.string().default('Off'),
  activeProvider: z.string().default(''),
  options: z.array(z.object({ method: TaxMethodKeySchema, label: z.string(), explain: z.string(), available: z.boolean(), blockedReason: z.string().default(''), current: z.boolean() })),
  shadowOptions: z.array(z.object({ value: z.string(), label: z.string(), explain: z.string().default(''), available: z.boolean(), blockedReason: z.string().default('') })),
  providers: z.array(z.object({ provider: TaxProviderSchema, label: z.string(), configured: z.boolean(), completeness: TaxCompletenessSchema.optional() })),
  history: z.array(z.object({ at: z.string().default(''), changedByName: z.string().default(''), fromLabel: z.string().default(''), toLabel: z.string().default(''), reason: z.string().default('') })),
  changedByName: z.string().default(''),
  changedAt: z.string().default(''),
  runbookPath: z.string(),
  canConfigure: z.boolean(),
  draftNotice: z.string().default('')
});
export type TaxMethod = z.infer<typeof TaxMethodSchema>;

export interface TaxMethodChange {
  method: TaxMethodKey;
  shadowProvider: TaxProvider | '';
  reason: string;
}

/* ── Tax review mode, always-review categories and the Audit sample ──────────── */

export const TaxReviewModeSchema = z.enum(['ALL', 'RISK_BASED']);
export type TaxReviewMode = z.infer<typeof TaxReviewModeSchema>;

export const TaxReviewSettingsSchema = z.object({
  mode: TaxReviewModeSchema,
  modeLabel: z.string(),
  modeExplain: z.string(),
  options: z.array(z.object({ mode: TaxReviewModeSchema, label: z.string(), explain: z.string(), current: z.boolean() })),
  categories: z.array(z.object({ code: z.string(), name: z.string(), departmentName: z.string().default(''), alwaysReview: z.boolean(), gated: z.boolean(), gateLabel: z.string().default('') })),
  alwaysReviewNames: z.array(z.string()).default([]),
  alwaysReviewStatuses: z.array(z.object({ status: z.string(), label: z.string() })).default([]),
  auditPercent: z.number().int(),
  auditExplain: z.string().default(''),
  history: z.array(z.object({ at: z.string().default(''), changedByName: z.string().default(''), fromLabel: z.string().default(''), toLabel: z.string().default(''), summary: z.string().default(''), reason: z.string().default('') })),
  changedByName: z.string().default(''),
  changedAt: z.string().default(''),
  canChange: z.boolean(),
  /** Plain-language note: Review by risk is the default; what still waits for a tax reviewer in both modes. */
  warning: z.string()
});
export type TaxReviewSettings = z.infer<typeof TaxReviewSettingsSchema>;

export interface TaxReviewSettingsChange {
  mode: TaxReviewMode;
  /** Category codes; replaces the always-review list. */
  alwaysReview: string[];
  auditPercent: number;
  reason: string;
}

/** The queue tab of items the system approved under the risk-based rule that are live and wait for a person. */
export const TAX_AUDIT_SAMPLE = 'AUDIT_SAMPLE';

/* ── Shadow report ────────────────────────────────────── */

const ShadowGroupSchema = z.object({
  key: z.string(),
  label: z.string(),
  lines: z.number().optional(),
  differing: z.number(),
  differingPct: z.number().optional(),
  absoluteDifferenceMinor: z.number().optional()
});
export type TaxShadowGroup = z.infer<typeof ShadowGroupSchema>;

export const TaxReadinessSchema = z.object({
  ready: z.boolean(),
  headline: z.string(),
  checks: z.array(z.object({ key: z.string(), title: z.string(), ok: z.boolean(), detail: z.string() })),
  thresholdSource: z.enum(['DEFAULT', 'TAX_RETURNS']),
  thresholds: z.record(z.string(), z.number()),
  rateNote: z.string().default('')
});
export type TaxReadiness = z.infer<typeof TaxReadinessSchema>;

export const TaxShadowRowSchema = z.object({
  orderId: z.string().default(''),
  orderNumber: z.string().default(''),
  lineId: z.string().default(''),
  atin: z.string().default(''),
  internalTaxCode: z.string(),
  categoryName: z.string().default(''),
  providerCode: z.string().default(''),
  kind: z.string(),
  kindLabel: z.string(),
  inHouseText: z.string(),
  providerText: z.string(),
  differenceMinor: z.number(),
  differenceText: z.string().default(''),
  quotedAt: z.string().default('')
});
export type TaxShadowRow = z.infer<typeof TaxShadowRowSchema>;

export const TaxShadowSchema = z.object({
  provider: z.string(),
  providerLabel: z.string().default(''),
  from: z.string(),
  to: z.string(),
  shadowOff: z.boolean(),
  message: z.string().default(''),
  canConfigure: z.boolean().default(false),
  linesCompared: z.number().default(0),
  linesDiffering: z.number().default(0),
  differingPct: z.number().default(0),
  absoluteDifferenceText: z.string().default(''),
  netDifferenceText: z.string().default(''),
  byCode: z.array(z.object({ internalTaxCode: z.string(), providerCode: z.string().default(''), categoryName: z.string().default(''), outcomeLabel: z.string().default(''), lines: z.number(), differing: z.number(), differingPct: z.number() })).default([]),
  byCategory: z.array(ShadowGroupSchema).default([]),
  byOutcome: z.array(ShadowGroupSchema).default([]),
  byState: z.array(ShadowGroupSchema).default([]),
  kinds: z.array(z.object({ kind: z.string(), label: z.string(), count: z.number() })).default([]),
  items: z.array(TaxShadowRowSchema).default([]),
  total: z.number().default(0),
  page: z.number().default(0),
  size: z.number().default(25),
  partial: z.boolean().default(false),
  readiness: TaxReadinessSchema.optional(),
  runbookPath: z.string().default('')
});
export type TaxShadow = z.infer<typeof TaxShadowSchema>;

export interface TaxShadowQuery {
  from?: string;
  to?: string;
  provider?: string;
  page?: number;
  size?: number;
}

/* ── Client ────────────────────────────────────────────── */

const enc = encodeURIComponent;
const T = '/v1/staff/tax';

function params(values: Record<string, string | number | undefined>, sort?: string[]): URLSearchParams {
  const p = new URLSearchParams();
  Object.entries(values).forEach(([k, v]) => {
    if (v !== undefined && v !== '') p.set(k, String(v));
  });
  (sort ?? []).forEach((s) => p.append('sort', s));
  return p;
}

/** Category tax routes of the staff BFF (mounted as `staffApi().tax`). */
export function createStaffTaxApi(request: Requester) {
  return {
    profiles: (q: TaxProfilesQuery, signal?: AbortSignal): Promise<TaxProfilePage> =>
      request(`${T}/profiles`, TaxProfilePageSchema, { query: params({ department: q.department, status: q.status, q: q.q, page: q.page, size: q.size }, q.sort), signal }),
    profile: (code: string, signal?: AbortSignal): Promise<TaxProfile> => request(`${T}/profiles/${enc(code)}`, TaxProfileSchema, { signal }),
    /** Needs the reason (10+ characters); lifting a gate also needs `approvedBy` and `decisionRef`. 409 for REFINE_CODE. */
    changeProfileStatus: (code: string, body: TaxProfileStatusChange): Promise<TaxProfile> =>
      request(`${T}/profiles/${enc(code)}/status`, TaxProfileSchema, { method: 'POST', body }),
    reviews: (q: TaxReviewsQuery, signal?: AbortSignal): Promise<TaxReviewPage> =>
      request(`${T}/reviews`, TaxReviewPageSchema, { query: params({ status: q.status, categoryCode: q.categoryCode, sellerOrgId: q.sellerOrgId, reason: q.reason, q: q.q, page: q.page, size: q.size }, q.sort), signal }),
    /** Finds sellers by name for the queue's seller filter. */
    reviewSellers: (q: string, signal?: AbortSignal): Promise<TaxReviewSellers> => request(`${T}/review-sellers`, TaxReviewSellersSchema, { query: params({ q }), signal }),
    review: (atin: string, signal?: AbortSignal): Promise<TaxReview> => request(`${T}/reviews/${enc(atin)}`, TaxReviewSchema, { signal }),
    /** Live listings that have no resolved tax decision (always empty); `total` is the banner count. */
    liveWithoutDecision: (q: { page?: number; size?: number } = {}, signal?: AbortSignal): Promise<TaxLiveWithoutDecisionPage> =>
      request(`${T}/live-without-decision`, TaxLiveWithoutDecisionPageSchema, { query: params({ page: q.page, size: q.size }), signal }),
    approve: (atin: string, body: TaxApproveInput): Promise<TaxReview> => request(`${T}/reviews/${enc(atin)}/approve`, TaxReviewSchema, { method: 'POST', body }),
    reclassify: (atin: string, body: { categoryCode: string; productTypeCode?: string; reason: string }): Promise<TaxReview> =>
      request(`${T}/reviews/${enc(atin)}/reclassify`, TaxReviewSchema, { method: 'POST', body }),
    /** 503 until tax-returns serves the request-information route. */
    requestInformation: (atin: string, body: { message: string }): Promise<TaxReview> =>
      request(`${T}/reviews/${enc(atin)}/request-information`, TaxReviewSchema, { method: 'POST', body }),
    codeMap: (q: TaxCodeMapQuery, signal?: AbortSignal): Promise<TaxCodeMap> =>
      request(`${T}/code-map`, TaxCodeMapSchema, { query: params({ provider: q.provider, state: q.state, categoryCode: q.categoryCode, q: q.q, page: q.page, size: q.size }, q.sort), signal }),
    updateCode: (internalTaxCode: string, body: TaxCodeUpdate): Promise<TaxCodeEntry> =>
      request(`${T}/code-map/${enc(internalTaxCode)}`, TaxCodeEntrySchema, { method: 'PUT', body }),
    exportCodeMap: (provider: TaxProvider): Promise<TaxCodeMapExport> => request(`${T}/code-map/export`, TaxCodeMapExportSchema, { query: { provider } }),
    importCodeMap: (body: { provider: TaxProvider; csv: string; dryRun?: boolean; reason?: string }): Promise<TaxCodeMapImportResult> =>
      request(`${T}/code-map/import`, TaxCodeMapImportResultSchema, { method: 'POST', body }),
    method: (signal?: AbortSignal): Promise<TaxMethod> => request(`${T}/method`, TaxMethodSchema, { signal }),
    changeMethod: (body: TaxMethodChange): Promise<TaxMethod> => request(`${T}/method`, TaxMethodSchema, { method: 'PUT', body }),
    reviewSettings: (signal?: AbortSignal): Promise<TaxReviewSettings> => request(`${T}/review-settings`, TaxReviewSettingsSchema, { signal }),
    /** Needs tax:classify-admin and a reason (10+ characters). Applies to new classification events. */
    changeReviewSettings: (body: TaxReviewSettingsChange): Promise<TaxReviewSettings> => request(`${T}/review-settings`, TaxReviewSettingsSchema, { method: 'PUT', body }),
    shadow: (q: TaxShadowQuery, signal?: AbortSignal): Promise<TaxShadow> =>
      request(`${T}/shadow`, TaxShadowSchema, { query: params({ from: q.from, to: q.to, provider: q.provider, page: q.page, size: q.size }), signal })
  };
}

export type StaffTaxApi = ReturnType<typeof createStaffTaxApi>;

/* ── Query keys & options ──────────────────────────────── */

export const staffTaxKeys = {
  all: ['staff', 'tax'] as const,
  profiles: (q: TaxProfilesQuery) => [...staffTaxKeys.all, 'profiles', q] as const,
  profile: (code: string) => [...staffTaxKeys.all, 'profile', code] as const,
  reviews: (q: TaxReviewsQuery) => [...staffTaxKeys.all, 'reviews', q] as const,
  reviewSellers: (q: string) => [...staffTaxKeys.all, 'review-sellers', q] as const,
  review: (atin: string) => [...staffTaxKeys.all, 'review', atin] as const,
  liveWithoutDecision: (q: { page?: number; size?: number }) => [...staffTaxKeys.all, 'live-without-decision', q] as const,
  codeMap: (q: TaxCodeMapQuery) => [...staffTaxKeys.all, 'code-map', q] as const,
  method: () => [...staffTaxKeys.all, 'method'] as const,
  reviewSettings: () => [...staffTaxKeys.all, 'review-settings'] as const,
  shadow: (q: TaxShadowQuery) => [...staffTaxKeys.all, 'shadow', q] as const
};

export const staffTaxQueries = {
  profiles: (api: StaffTaxApi, q: TaxProfilesQuery) =>
    queryOptions({ queryKey: staffTaxKeys.profiles(q), queryFn: ({ signal }) => api.profiles(q, signal), placeholderData: keepPreviousData, staleTime: 30_000 }),
  profile: (api: StaffTaxApi, code: string) => queryOptions({ queryKey: staffTaxKeys.profile(code), queryFn: ({ signal }) => api.profile(code, signal), staleTime: 10_000 }),
  reviews: (api: StaffTaxApi, q: TaxReviewsQuery) =>
    queryOptions({ queryKey: staffTaxKeys.reviews(q), queryFn: ({ signal }) => api.reviews(q, signal), placeholderData: keepPreviousData, staleTime: 10_000 }),
  reviewSellers: (api: StaffTaxApi, q: string) =>
    queryOptions({ queryKey: staffTaxKeys.reviewSellers(q), queryFn: ({ signal }) => api.reviewSellers(q, signal), placeholderData: keepPreviousData, staleTime: 60_000 }),
  /** Not polled (a poll must carry the background flag, docs/engineering/session-timeouts.md): it is read when the page opens and after each action. */
  liveWithoutDecision: (api: StaffTaxApi, q: { page?: number; size?: number } = {}) =>
    queryOptions({ queryKey: staffTaxKeys.liveWithoutDecision(q), queryFn: ({ signal }) => api.liveWithoutDecision(q, signal), placeholderData: keepPreviousData, staleTime: 15_000 }),
  review: (api: StaffTaxApi, atin: string) => queryOptions({ queryKey: staffTaxKeys.review(atin), queryFn: ({ signal }) => api.review(atin, signal), staleTime: 10_000 }),
  codeMap: (api: StaffTaxApi, q: TaxCodeMapQuery) =>
    queryOptions({ queryKey: staffTaxKeys.codeMap(q), queryFn: ({ signal }) => api.codeMap(q, signal), placeholderData: keepPreviousData, staleTime: 10_000 }),
  method: (api: StaffTaxApi) => queryOptions({ queryKey: staffTaxKeys.method(), queryFn: ({ signal }) => api.method(signal), staleTime: 10_000 }),
  reviewSettings: (api: StaffTaxApi) => queryOptions({ queryKey: staffTaxKeys.reviewSettings(), queryFn: ({ signal }) => api.reviewSettings(signal), staleTime: 10_000 }),
  shadow: (api: StaffTaxApi, q: TaxShadowQuery) =>
    queryOptions({ queryKey: staffTaxKeys.shadow(q), queryFn: ({ signal }) => api.shadow(q, signal), placeholderData: keepPreviousData, staleTime: 30_000 })
};
