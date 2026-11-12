import { z } from 'zod';

/** A department that has at least one eligible product (in stock, real photo). `name` is the catalog name; `labels` are per-locale display names. */
export const NavDepartmentSchema = z.object({
  key: z.string(),
  name: z.string(),
  labels: z.record(z.string(), z.string()).optional(),
  count: z.number(),
  href: z.string()
});
export type NavDepartment = z.infer<typeof NavDepartmentSchema>;

/** A community above the staff thresholds (default 8 eligible products and 2 sellers). `key` is the canonical key the route and the sitemap use. */
export const NavCommunitySchema = z.object({ key: z.string(), label: z.string(), labels: z.record(z.string(), z.string()).optional(), count: z.number(), href: z.string() });
export type NavCommunity = z.infer<typeof NavCommunitySchema>;

/**
 * GET /v1/site/departments: what exists right now, from the same eligible-only counts the home uses.
 * The department bar, "All" menu, footer, community routes and the sitemap read it; nothing in the storefront lists them by hand.
 */
export const SiteNavigationSchema = z.object({
  departments: z.array(NavDepartmentSchema),
  /** Empty until 3 communities qualify (the home module's rule), so the footer and routes follow the home. */
  communities: z.array(NavCommunitySchema),
  /** True when any provider exists in the market: the Services entry shows only then. */
  servicesAvailable: z.boolean(),
  /** True when the first-party retail business has at least one eligible product (the Brand entry). */
  brandAvailable: z.boolean().default(false),
  /** True when any deal qualifies (the Deals entry). */
  dealsAvailable: z.boolean().default(false)
});
export type SiteNavigation = z.infer<typeof SiteNavigationSchema>;

/** Used while the gateway cannot answer: nothing is linked rather than a guessed list. */
export const EMPTY_NAVIGATION: SiteNavigation = { departments: [], communities: [], servicesAvailable: false, brandAvailable: false, dealsAvailable: false };
