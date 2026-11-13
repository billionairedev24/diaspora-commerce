import { z } from 'zod';

/** Label per locale. `en` is always present; a missing locale falls back to `en`, never to a key. */
export const LocaleLabelsSchema = z.object({ en: z.string(), fr: z.string().optional(), es: z.string().optional(), ar: z.string().optional(), yo: z.string().optional() });
export type LocaleLabels = z.infer<typeof LocaleLabelsSchema>;

/** A department that has at least one eligible product (live, in stock, real photo). `name` is the catalog full name (the `department` search filter value). */
export const SiteDepartmentSchema = z.object({
  key: z.string(),
  name: z.string(),
  /** Short display label in the requested locale. */
  label: z.string(),
  labels: LocaleLabelsSchema,
  labelKey: z.string().optional(),
  count: z.number(),
  sellers: z.number().default(0),
  /** Absent when `count` is 0: not linked. */
  href: z.string().optional(),
  /** Photo of the best eligible product of the department; never an icon or emoji. */
  imageUrl: z.string().nullable().optional()
});
export type SiteDepartment = z.infer<typeof SiteDepartmentSchema>;

/** A community above the staff thresholds (default 3 communities, each with 8 eligible products and 2 sellers). `key` is the canonical key the route and the sitemap use; label and href come from it. */
export const SiteCommunitySchema = z.object({
  key: z.string(),
  label: z.string(),
  labels: LocaleLabelsSchema,
  count: z.number(),
  sellers: z.number().default(0),
  href: z.string(),
  imageUrl: z.string().nullable().optional(),
  imageSource: z.enum(['product', 'editorial', 'none']).optional()
});
export type SiteCommunity = z.infer<typeof SiteCommunitySchema>;

/**
 * GET /v1/site/navigation: what exists right now, from the same eligible-only counts the home uses. The department bar,
 * the "All" menu, the footer, the community routes and the sitemap read it; nothing in the storefront lists them by hand.
 */
export const SiteNavigationSchema = z.object({
  /** The largest departments by count (at most 7). */
  bar: z.array(SiteDepartmentSchema),
  /** Every department with at least one eligible product. */
  all: z.array(SiteDepartmentSchema),
  /** Empty until the community module's rule is met, so the footer and the routes follow the home. */
  communities: z.array(SiteCommunitySchema),
  /** `available` is true whenever any provider exists in the market: the Services entry shows only then. */
  services: z.object({ available: z.boolean(), providers: z.number().optional(), href: z.string().default('/services') }),
  deals: z.object({ available: z.boolean() }),
  brand: z.object({ available: z.boolean() }),
  generatedAt: z.string().optional()
});
export type SiteNavigation = z.infer<typeof SiteNavigationSchema>;

/** Used while the gateway cannot answer: nothing is linked rather than a guessed list. */
export const EMPTY_NAVIGATION: SiteNavigation = {
  bar: [],
  all: [],
  communities: [],
  services: { available: false, href: '/services' },
  deals: { available: false },
  brand: { available: false }
};

/** GET /v1/site/communities/{slug}: 200 when visible. `canonical: false` means redirect (308) to `community.key`. A hidden or unknown community answers 404 `community.not-visible`. */
export const SiteCommunityResolutionSchema = z.object({ community: SiteCommunitySchema, canonical: z.boolean() });
export type SiteCommunityResolution = z.infer<typeof SiteCommunityResolutionSchema>;

export const SitemapEntrySchema = z.object({ href: z.string(), count: z.number(), lastmod: z.string().optional() });
export type SitemapEntry = z.infer<typeof SitemapEntrySchema>;

/** GET /v1/site/sitemap: department and community URLs from the same snapshot as the home and the nav. */
export const SiteSitemapSchema = z.object({ departments: z.array(SitemapEntrySchema), communities: z.array(SitemapEntrySchema) });
export type SiteSitemap = z.infer<typeof SiteSitemapSchema>;
