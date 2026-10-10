import { z } from 'zod';

import { FulfilmentSchema, ProductSummarySchema, SellerKindSchema } from './catalog';
import { FacetBucketSchema } from './common';

export const SearchSortSchema = z.enum(['relevance', 'price_asc', 'price_desc', 'rating', 'fastest']);
export type SearchSort = z.infer<typeof SearchSortSchema>;

/** Price buckets shown in the filter rail (minor units, inclusive bounds). */
export const PRICE_BUCKETS = [
  { id: 'u10', label: 'Under $10', min: null, max: 999 },
  { id: '10-25', label: '$10 to $25', min: 1000, max: 2500 },
  { id: '25-50', label: '$25 to $50', min: 2501, max: 5000 },
  { id: 'o50', label: '$50 & above', min: 5001, max: null }
] as const;
export type PriceBucketId = (typeof PRICE_BUCKETS)[number]['id'];
export const PriceBucketSchema = z.enum(['u10', '10-25', '25-50', 'o50']);

export const RATING_OPTIONS = [4.5, 4] as const;

/** Search state shared by the URL, the BFF query string and the mocks. */
export const SearchQuerySchema = z.object({
  q: z.string().max(200).default(''),
  department: z.string().nullable().default(null),
  community: z.string().nullable().default(null),
  fulfilment: FulfilmentSchema.nullable().default(null),
  sellerKind: SellerKindSchema.nullable().default(null),
  minRating: z.number().min(0).max(5).nullable().default(null),
  price: PriceBucketSchema.nullable().default(null),
  /** Delivery within N days for the deliver-to ZIP (1 = "Get it by tomorrow"). */
  maxDays: z.number().int().min(0).nullable().default(null),
  sort: SearchSortSchema.default('relevance'),
  /** Zero-based page index. */
  page: z.number().int().min(0).default(0),
  size: z.number().int().min(1).max(60).default(12)
});
export type SearchQuery = z.infer<typeof SearchQuerySchema>;
export type SearchQueryInput = z.input<typeof SearchQuerySchema>;

export const SearchFacetsSchema = z.object({
  department: z.array(FacetBucketSchema),
  community: z.array(FacetBucketSchema),
  fulfilment: z.array(FacetBucketSchema),
  sellerKind: z.array(FacetBucketSchema),
  rating: z.array(FacetBucketSchema),
  price: z.array(FacetBucketSchema),
  delivery: z.array(FacetBucketSchema)
});
export type SearchFacets = z.infer<typeof SearchFacetsSchema>;

export const SearchPageSchema = z.object({
  hits: z.array(ProductSummarySchema),
  total: z.number().int().min(0),
  page: z.number().int().min(0),
  size: z.number().int().min(1),
  pageCount: z.number().int().min(0),
  facets: SearchFacetsSchema,
  deliverToLabel: z.string(),
  tookMs: z.number().int().min(0)
});
export type SearchPage = z.infer<typeof SearchPageSchema>;

export const SuggestionPhraseSchema = z.object({ text: z.string(), department: z.string().nullable() });
export type SuggestionPhrase = z.infer<typeof SuggestionPhraseSchema>;

export const SuggestionsSchema = z.object({
  query: z.string(),
  phrases: z.array(SuggestionPhraseSchema),
  products: z.array(ProductSummarySchema).max(5)
});
export type Suggestions = z.infer<typeof SuggestionsSchema>;
