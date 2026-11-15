import { z } from 'zod';

import { ProductSummarySchema } from './catalog';

/* Zod schemas for the seller storefront: GET /v1/storefronts/{slug} (public, contracts/openapi/gateway-bff.yaml) and
   /v1/seller/store-page (Settings > Store page, gateway-bff-seller.yaml). test/storefront-contract.test.ts keeps them
   assignable to the generated contract types. */

export const THEME_PRESET_IDS = ['clean', 'warm-market', 'bold', 'midnight', 'heritage', 'fresh'] as const;
export const SELLER_SECTION_IDS = ['announcement', 'featured', 'categories', 'about', 'policies', 'reviews'] as const;
/** A provider's page: no product sections; its services, the appointment booker, the weekly schedule with the service area, its team and reviews. */
export const PROVIDER_SECTION_IDS = ['announcement', 'about', 'services', 'book', 'schedule', 'team', 'reviews'] as const;
export const StoreSectionIdSchema = z.enum(['announcement', 'featured', 'categories', 'about', 'policies', 'reviews', 'services', 'book', 'schedule', 'team']);
export type StoreSectionId = z.infer<typeof StoreSectionIdSchema>;
export const StoreSocialNetworkSchema = z.enum(['instagram', 'facebook', 'tiktok', 'x', 'youtube', 'whatsapp', 'website']);
export type StoreSocialNetwork = z.infer<typeof StoreSocialNetworkSchema>;

export const StoreConfigSchema = z.object({
  schemaVersion: z.literal(1),
  theme: z.object({ preset: z.enum(THEME_PRESET_IDS), accent: z.string().nullable() }),
  hero: z.object({ style: z.enum(['image', 'colour', 'split']), tagline: z.string(), bannerUrl: z.string().nullable(), focalX: z.number().int(), focalY: z.number().int() }),
  announcement: z.object({ text: z.string(), href: z.string().nullable() }),
  grid: z.object({ columns: z.union([z.literal(3), z.literal(4)]) }),
  sections: z.array(z.object({ id: StoreSectionIdSchema, enabled: z.boolean() })),
  featured: z.array(z.string()),
  categories: z.object({ mode: z.enum(['auto', 'chosen']), chosen: z.array(z.string()) }),
  about: z.object({ heading: z.string(), body: z.string(), photoUrl: z.string().nullable() }),
  policies: z.object({ shipping: z.string(), returns: z.string() }),
  contact: z.object({ showEmail: z.boolean(), phone: z.string(), hours: z.string() }),
  pickup: z.object({ show: z.boolean() }),
  social: z.array(z.object({ network: StoreSocialNetworkSchema, url: z.string() }))
});
export type StoreConfig = z.infer<typeof StoreConfigSchema>;
export type StoreThemePreset = StoreConfig['theme']['preset'];

export const StorefrontCategorySchema = z.object({ name: z.string(), count: z.number().int() });
export type StorefrontCategory = z.infer<typeof StorefrontCategorySchema>;

export const StorefrontProductsSchema = z.object({
  hits: z.array(ProductSummarySchema),
  total: z.number().int(),
  page: z.number().int(),
  size: z.number().int(),
  pageCount: z.number().int(),
  categories: z.array(StorefrontCategorySchema),
  prices: z.array(z.object({ value: z.string(), label: z.string(), count: z.number().int() }))
});
export type StorefrontProducts = z.infer<typeof StorefrontProductsSchema>;

export const StoreKindSchema = z.enum(['SELLER', 'PROVIDER']);
export type StoreKind = z.infer<typeof StoreKindSchema>;

/** What makes a provider's page bookable: real, public booking data (bookings). Services are in person (no virtual). Empty `hours` means none set (no schedule shown). */
export const StorefrontProviderSchema = z.object({
  orgId: z.string(),
  timeZone: z.string(),
  location: z.string(),
  instantBooking: z.boolean(),
  policyLabel: z.string(),
  policyText: z.string(),
  services: z.array(
    z.object({
      id: z.string(),
      name: z.string(),
      description: z.string(),
      category: z.string(),
      durationMinutes: z.number().int(),
      priceMinor: z.number().int(),
      depositMinor: z.number().int(),
      currency: z.string(),
      modes: z.array(z.enum(['IN_PERSON', 'AT_HOME'])),
      imageUrl: z.string().nullable()
    })
  ),
  hours: z.array(z.object({ day: z.enum(['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY']), ranges: z.array(z.object({ start: z.string(), end: z.string() })) })),
  area: z.object({ baseCity: z.string(), baseState: z.string(), radiusMiles: z.number().int(), servedCities: z.array(z.string()), extraAreas: z.array(z.string()) }).nullable(),
  team: z.array(z.object({ id: z.string(), displayName: z.string() }))
});
export type StorefrontProvider = z.infer<typeof StorefrontProviderSchema>;

export const StorefrontSchema = z.object({
  kind: StoreKindSchema,
  provider: StorefrontProviderSchema.nullable(),
  slug: z.string(),
  name: z.string(),
  logoUrl: z.string().nullable(),
  description: z.string().nullable(),
  config: StoreConfigSchema,
  preview: z.boolean(),
  indexable: z.boolean(),
  verified: z.boolean(),
  sellingSinceYear: z.number().int().nullable(),
  contactEmail: z.string().nullable(),
  pickup: z.object({ zip: z.string() }).nullable(),
  categories: z.array(StorefrontCategorySchema),
  featured: z.array(ProductSummarySchema),
  /** Backend aggregate over REAL reviews (catalog for a seller, bookings for a provider). Null unless there is at least one: never "0 reviews". */
  reviews: z.object({ average: z.number(), count: z.number().int() }).nullable(),
  products: StorefrontProductsSchema
});
export type Storefront = z.infer<typeof StorefrontSchema>;

export interface StorefrontQuery {
  q?: string;
  category?: string;
  price?: string;
  sort?: 'relevance' | 'price_asc' | 'price_desc' | 'rating' | 'newest';
  /** Only products with a real was-price above the price. */
  onSale?: boolean;
  page?: number;
  size?: number;
}

/* ── Seller Center: Settings > Store page ── */

export const StorePageSettingsSchema = z.object({
  kind: StoreKindSchema,
  slug: z.string().nullable(),
  name: z.string(),
  logoUrl: z.string().nullable(),
  contactEmail: z.string().nullable(),
  draft: StoreConfigSchema,
  published: StoreConfigSchema.nullable(),
  isPublished: z.boolean(),
  version: z.number().int(),
  publishedVersion: z.number().int().nullable(),
  hasUnpublishedChanges: z.boolean(),
  updatedAt: z.string().nullable(),
  publishedAt: z.string().nullable(),
  approved: z.boolean(),
  canEdit: z.boolean(),
  canPublish: z.boolean()
});
export type StorePageSettings = z.infer<typeof StorePageSettingsSchema>;

export const StorePageAuditSchema = z.object({
  items: z.array(z.object({ at: z.string(), action: z.enum(['DRAFT_SAVED', 'PUBLISHED', 'UNPUBLISHED']), actor: z.string(), version: z.number().int() }))
});
export type StorePageAudit = z.infer<typeof StorePageAuditSchema>;

export const StorePageProductListSchema = z.object({ items: z.array(ProductSummarySchema) });
export type StorePageProductList = z.infer<typeof StorePageProductListSchema>;

/** The editor's starting point for an organisation that never saved: the Clean look, every optional section of its kind on except the announcement. */
export function defaultStoreConfig(kind: StoreKind = 'SELLER'): StoreConfig {
  return {
    schemaVersion: 1,
    theme: { preset: 'clean', accent: null },
    hero: { style: 'image', tagline: '', bannerUrl: null, focalX: 50, focalY: 50 },
    announcement: { text: '', href: null },
    grid: { columns: 4 },
    sections: (kind === 'PROVIDER' ? PROVIDER_SECTION_IDS : SELLER_SECTION_IDS).map((id) => ({ id, enabled: id !== 'announcement' })),
    featured: [],
    categories: { mode: 'auto', chosen: [] },
    about: { heading: '', body: '', photoUrl: null },
    policies: { shipping: '', returns: '' },
    contact: { showEmail: false, phone: '', hours: '' },
    pickup: { show: false },
    social: []
  };
}

/** GET /v1/storefronts: published stores with at least one sellable product, and published providers with at least one service (the sitemap). */
export const StorefrontListSchema = z.object({ items: z.array(z.object({ kind: StoreKindSchema, slug: z.string(), publishedAt: z.string() })) });
export type StorefrontList = z.infer<typeof StorefrontListSchema>;
