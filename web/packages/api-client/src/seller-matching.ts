/* Seller Center "Add a product" (S382) and Improve your image (S391): the search box (a name, no barcode needed), the optional barcode shortcut, the offer-only offer, change requests
   and image tasks (contracts/openapi/gateway-bff-seller.yaml, tags Matching and Image quality). catalog owns the page; a seller
   who finds it sells an offer on it and never changes the page. Everything here is plain words: no ids reach the screen. */
import { queryOptions } from '@tanstack/react-query';
import { z } from 'zod';

import { type Requester } from './http';

const nullableString = z.string().nullable();
const enc = encodeURIComponent;

/* ── Image rules (media, S388): what an upload answers with ── */

export const ProductImageSlotSchema = z.enum(['MAIN', 'OTHER', 'SWATCH', 'LABEL']);
export type ProductImageSlot = z.infer<typeof ProductImageSlotSchema>;
export const ImageVerdictSchema = z.enum(['PASS', 'WARN', 'HOLD', 'REJECT']);
export type ImageVerdict = z.infer<typeof ImageVerdictSchema>;
export const ImageCheckLevelSchema = z.enum(['INFO', 'WARN', 'HOLD', 'REJECT']);
export const ImageCheckSchema = z.object({
  /** Translate by this code (sc.img.check.<CODE>); the message is English for API readers. */
  code: z.string(),
  level: ImageCheckLevelSchema,
  /** Numbers the text needs: w, h, px, min, mb, fill, actual. */
  params: z.record(z.string(), z.unknown()).default({}),
  message: z.string()
});
export type ImageCheck = z.infer<typeof ImageCheckSchema>;

/* ── The barcode shortcut ── */

export const MatchRequestSchema = z.object({ gtin: z.string().max(20) });
export type MatchRequest = z.infer<typeof MatchRequestSchema>;
export const MatchResultKindSchema = z.enum(['MATCH', 'NO_MATCH', 'INVALID', 'RESTRICTED']);
export type MatchResultKind = z.infer<typeof MatchResultKindSchema>;
export const BlockerSchema = z.object({ code: z.string(), message: z.string() });
export type Blocker = z.infer<typeof BlockerSchema>;
export const MatchVariantSchema = z.object({ atin: z.string(), options: z.record(z.string(), z.string()) });
export type MatchVariant = z.infer<typeof MatchVariantSchema>;
export const CatalogMatchSchema = z.object({
  result: MatchResultKindSchema,
  digits: nullableString.optional(),
  /** The matched variant: offers attach to it. Never shown. */
  atin: nullableString.optional(),
  parentAtin: nullableString.optional(),
  productStatus: nullableString.optional(),
  title: nullableString.optional(),
  brand: nullableString.optional(),
  image: z.object({ url: z.string(), width: z.number().int().nullish(), height: z.number().int().nullish() }).nullish(),
  category: nullableString.optional(),
  variantOptions: z.record(z.string(), z.string()).nullish(),
  variants: z.array(MatchVariantSchema).default([]),
  sellerCount: z.number().int().nullish(),
  canOffer: z.boolean(),
  blockers: z.array(BlockerSchema),
  canCreate: z.boolean(),
  createBlockers: z.array(BlockerSchema),
  suggestion: nullableString.optional(),
  message: nullableString.optional()
});
export type CatalogMatch = z.infer<typeof CatalogMatchSchema>;

/* ── The search box: "What are you selling?" Name, brand, size and category are read from the one text; nothing about any seller is returned ── */

export const CandidateRequestSchema = z.object({ query: z.string().min(1).max(200), categoryId: z.string().optional() });
export type CandidateRequest = z.infer<typeof CandidateRequestSchema>;
export const MatchCategorySchema = z.object({ id: z.string(), path: z.string() });
export type MatchCategory = z.infer<typeof MatchCategorySchema>;
export const MatchCandidateSchema = z.object({
  parentAtin: z.string(),
  /** The variant to sell when the page has exactly one. Never shown. */
  atin: nullableString.optional(),
  title: z.string(),
  brand: nullableString.optional(),
  image: z.object({ url: z.string(), width: z.number().int().nullish(), height: z.number().int().nullish() }).nullish(),
  category: nullableString.optional(),
  /** The page's size in words, for the one-tap "Same size, 5 kg?"; null when it states none. */
  sizeLabel: nullableString.optional(),
  variants: z.array(MatchVariantSchema).default([]),
  /** SAME_NAME is exact and SIMILAR a suggestion; both always need the seller's click. */
  kind: z.enum(['SAME_NAME', 'SIMILAR']),
  likely: z.boolean(),
  sellerCount: z.number().int().nullish(),
  canOffer: z.boolean(),
  blockers: z.array(BlockerSchema)
});
export type MatchCandidate = z.infer<typeof MatchCandidateSchema>;
export const CandidateListSchema = z.object({
  candidates: z.array(MatchCandidateSchema),
  suggestedCategory: MatchCategorySchema.nullish(),
  category: MatchCategorySchema.nullish(),
  tooShort: z.boolean(),
  /** The search did not answer: no suggestions, and the seller may still add the product as new. */
  searchUnavailable: z.boolean()
});
export type CandidateList = z.infer<typeof CandidateListSchema>;
export const DecisionRequestSchema = z.object({
  choice: z.enum(['SELL_EXISTING', 'NONE_OF_THESE']),
  query: z.string().min(1).max(200),
  categoryId: z.string().optional(),
  atin: z.string().optional(),
  sizeConfirmed: z.boolean().optional()
});
export type DecisionRequest = z.infer<typeof DecisionRequestSchema>;
export const MatchDecisionSchema = z.object({
  decisionId: nullableString.optional(),
  choice: z.enum(['SELL_EXISTING', 'NONE_OF_THESE']),
  atin: nullableString.optional(),
  canOffer: z.boolean(),
  blockers: z.array(BlockerSchema),
  possibleDuplicateQueued: z.boolean()
});
export type MatchDecision = z.infer<typeof MatchDecisionSchema>;

/* ── The price-and-stock form: terms only, never page content ── */

export const OFFER_CONDITIONS = ['NEW', 'RENEWED', 'USED_LIKE_NEW', 'USED_VERY_GOOD', 'USED_GOOD', 'USED_ACCEPTABLE'] as const;
export type OfferCondition = (typeof OFFER_CONDITIONS)[number];
export const OFFER_FULFILMENTS = ['SELLER', 'AFROTRANSACT', 'AFROTRANSACT_DELIVERY'] as const;
export type OfferFulfilment = (typeof OFFER_FULFILMENTS)[number];
export const OfferOnlyInputSchema = z.object({
  atin: z.string(),
  sellerSku: z.string().max(40).optional(),
  condition: z.enum(OFFER_CONDITIONS).optional(),
  conditionNote: z.string().max(200).optional(),
  priceMinor: z.number().int().min(1),
  listPriceMinor: z.number().int().min(1).optional(),
  shippingMinor: z.number().int().min(0).optional(),
  fulfilment: z.enum(OFFER_FULFILMENTS),
  quantity: z.number().int().min(0).max(100_000).optional(),
  handlingDays: z.number().int().min(0).max(30).optional(),
  shipFromLocationId: z.string().max(64).optional(),
  packaging: z.object({ lengthIn: z.number().positive(), widthIn: z.number().positive(), heightIn: z.number().positive(), weightLb: z.number().positive() }).optional()
});
export type OfferOnlyInput = z.infer<typeof OfferOnlyInputSchema>;
export const OfferOnlyResultSchema = z.object({
  offerId: z.string(),
  atin: z.string(),
  sellerSku: z.string(),
  /** TAX_HELD: not buyable until the seller's own tax decision for the item is resolved. */
  status: z.enum(['ACTIVE', 'TAX_HELD', 'PAUSED', 'WITHDRAWN']),
  heldReason: nullableString.optional(),
  message: z.string()
});
export type OfferOnlyResult = z.infer<typeof OfferOnlyResultSchema>;

/* ── Suggest a change, wrong product, duplicate ── */

export const ChangeRequestTypeSchema = z.enum(['IMAGE_ADD', 'IMAGE_REPLACE', 'TITLE', 'ATTRIBUTE', 'DESCRIPTION', 'CATEGORY', 'BRAND', 'NEW_VARIANT', 'WRONG_MATCH', 'DUPLICATE_PAGE']);
export type ChangeRequestType = z.infer<typeof ChangeRequestTypeSchema>;
export const ChangeRequestStatusSchema = z.enum(['PENDING', 'APPLIED', 'REJECTED', 'WITHDRAWN']);
export type ChangeRequestStatus = z.infer<typeof ChangeRequestStatusSchema>;
export const ChangeRequestInputSchema = z.object({
  type: ChangeRequestTypeSchema,
  field: z.string().max(60).optional(),
  payload: z.record(z.string(), z.unknown()).optional(),
  note: z.string().max(1000).optional()
});
export type ChangeRequestInput = z.infer<typeof ChangeRequestInputSchema>;
export const ChangeRequestSchema = z.object({
  id: z.string(),
  atin: z.string(),
  title: nullableString.optional(),
  type: ChangeRequestTypeSchema,
  field: nullableString.optional(),
  payload: z.record(z.string(), z.unknown()).nullish(),
  note: nullableString.optional(),
  status: ChangeRequestStatusSchema,
  requestedAt: z.string(),
  slaDueAt: nullableString.optional(),
  decidedAt: nullableString.optional(),
  /** Staff's reason: "Not applied, because ...". */
  reason: nullableString.optional(),
  resolution: nullableString.optional()
});
export type ChangeRequest = z.infer<typeof ChangeRequestSchema>;
export const ChangeRequestPageSchema = z.object({ items: z.array(ChangeRequestSchema), total: z.number().int(), page: z.number().int(), size: z.number().int() });
export type ChangeRequestPage = z.infer<typeof ChangeRequestPageSchema>;

/* ── Improve your image ── */

export const ImageTaskReasonSchema = z.object({ code: z.string(), level: z.string(), message: z.string() });
export type ImageTaskReason = z.infer<typeof ImageTaskReasonSchema>;
export const ImageTaskSchema = z.object({
  productId: z.string(),
  atin: nullableString.optional(),
  title: z.string(),
  state: z.enum(['IMPROVE', 'FAIL']),
  reasons: z.array(ImageTaskReasonSchema),
  failingSince: nullableString.optional(),
  hideAfter: nullableString.optional(),
  daysLeft: z.number().int().nullish(),
  hidden: z.boolean()
});
export type ImageTask = z.infer<typeof ImageTaskSchema>;
export const ImageTaskListSchema = z.object({ items: z.array(ImageTaskSchema) });
export type ImageTaskList = z.infer<typeof ImageTaskListSchema>;

const idem = (key?: string): Record<string, string> | undefined => (key ? { 'Idempotency-Key': key } : undefined);

/** The matching routes of the seller BFF, for the active organisation (Listings module). */
export function createSellerMatchingApi(request: Requester) {
  return {
    match: (input: MatchRequest, signal?: AbortSignal): Promise<CatalogMatch> => request('/v1/seller/catalog/matches', CatalogMatchSchema, { method: 'POST', body: input, signal }),
    candidates: (input: CandidateRequest, signal?: AbortSignal): Promise<CandidateList> =>
      request('/v1/seller/catalog/candidates', CandidateListSchema, { method: 'POST', body: input, signal }),
    decide: (input: DecisionRequest): Promise<MatchDecision> => request('/v1/seller/catalog/candidates/decisions', MatchDecisionSchema, { method: 'POST', body: input }),
    createOffer: (input: OfferOnlyInput, key?: string): Promise<OfferOnlyResult> =>
      request('/v1/seller/offers', OfferOnlyResultSchema, { method: 'POST', body: input, headers: idem(key) }),
    fileChangeRequest: (atin: string, input: ChangeRequestInput): Promise<ChangeRequest> =>
      request(`/v1/seller/products/${enc(atin)}/change-requests`, ChangeRequestSchema, { method: 'POST', body: input }),
    listChangeRequests: (q: { status?: ChangeRequestStatus; page?: number; size?: number } = {}, signal?: AbortSignal): Promise<ChangeRequestPage> =>
      request('/v1/seller/change-requests', ChangeRequestPageSchema, { query: { status: q.status, page: q.page === undefined ? undefined : String(q.page), size: q.size === undefined ? undefined : String(q.size) }, signal }),
    withdrawChangeRequest: (id: string): Promise<ChangeRequest> => request(`/v1/seller/change-requests/${enc(id)}/withdraw`, ChangeRequestSchema, { method: 'POST' }),
    listImageTasks: (signal?: AbortSignal): Promise<ImageTaskList> => request('/v1/seller/image-tasks', ImageTaskListSchema, { signal })
  };
}
export type SellerMatchingApi = ReturnType<typeof createSellerMatchingApi>;

/* ── Query keys and options ── */

export const matchingKeys = {
  all: ['seller-matching'] as const,
  candidates: (orgId: string, q: CandidateRequest) => [...matchingKeys.all, orgId, 'candidates', q] as const,
  match: (orgId: string, q: MatchRequest) => [...matchingKeys.all, orgId, 'match', q] as const,
  changeRequests: (orgId: string) => [...matchingKeys.all, orgId, 'change-requests'] as const,
  imageTasks: (orgId: string) => [...matchingKeys.all, orgId, 'image-tasks'] as const
};

export const matchingQueries = {
  /** One lookup per barcode; the answer is never reused, because another seller may create the page, or staff approve it, meanwhile. */
  match: (api: SellerMatchingApi, orgId: string, q: MatchRequest, enabled: boolean) =>
    queryOptions({ queryKey: matchingKeys.match(orgId, q), queryFn: ({ signal }) => api.match(q, signal), enabled, staleTime: 0, gcTime: 0, retry: false }),
  /** One search per typed name and category; a stale list is never reused, because another seller may add the page meanwhile. */
  candidates: (api: SellerMatchingApi, orgId: string, q: CandidateRequest, enabled: boolean) =>
    queryOptions({ queryKey: matchingKeys.candidates(orgId, q), queryFn: ({ signal }) => api.candidates(q, signal), enabled, staleTime: 0, gcTime: 0, retry: false }),
  changeRequests: (api: SellerMatchingApi, orgId: string) =>
    queryOptions({ queryKey: matchingKeys.changeRequests(orgId), queryFn: ({ signal }) => api.listChangeRequests({ size: 50 }, signal), staleTime: 30_000 }),
  imageTasks: (api: SellerMatchingApi, orgId: string) =>
    queryOptions({ queryKey: matchingKeys.imageTasks(orgId), queryFn: ({ signal }) => api.listImageTasks(signal), staleTime: 60_000 })
};
