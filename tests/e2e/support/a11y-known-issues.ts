/*
 * Serious/critical axe findings that exist today and are tracked for a fix. The a11y smoke gate reports these as
 * annotations instead of failing, so any NEW serious/critical rule on a page still fails the build.
 *
 * Rules: one entry per (page, rule); every entry names an owning team and the story/issue that fixes it.
 * Delete the entry in the same PR as the fix — a stale entry hides regressions.
 * Found by tests/e2e on 2026-10-10 (axe-core 4.13, WCAG 2.2 AA tags).
 */
export interface KnownA11yIssue {
  /** The `page` option passed to expectNoSeriousA11yViolations. */
  page: string;
  /** axe rule id. */
  rule: string;
  owner: string;
  note: string;
}

export const KNOWN_A11Y_ISSUES: KnownA11yIssue[] = [
  // web/packages/ui ImagePlaceholder renders role="img" with an empty aria-label when it is decorative.
  { page: 'home', rule: 'role-img-alt', owner: 'web-platform', note: 'ImagePlaceholder: use aria-hidden for decorative placeholders' },
  { page: 'product page', rule: 'role-img-alt', owner: 'web-platform', note: 'ImagePlaceholder inside gallery thumbnails' },
  // Muted text tokens below 4.5:1 on white.
  { page: 'home', rule: 'color-contrast', owner: 'storefront', note: 'Home ad note (.adNote) muted text' },
  { page: 'search results', rule: 'color-contrast', owner: 'storefront', note: 'facet count (.count) muted text' },
  { page: 'product page', rule: 'color-contrast', owner: 'web-platform', note: 'Stars empty-star glyphs' },
  { page: 'seller home', rule: 'color-contrast', owner: 'seller-center', note: 'sidebar access note (.mt-auto) and KPI text-xl' },
  { page: 'provider calendar', rule: 'color-contrast', owner: 'seller-center', note: 'sidebar access note (.mt-auto)' },
  // Carousel dots are smaller than 24×24 CSS px (WCAG 2.2 target size).
  { page: 'home', rule: 'target-size', owner: 'storefront', note: 'hero carousel "Go to slide n" dots' },
  // Week grid uses role=grid with columnheaders not wrapped in role=row.
  { page: 'provider calendar', rule: 'aria-required-children', owner: 'seller-center', note: 'CalendarView week grid needs role=row wrappers' },
  { page: 'provider calendar', rule: 'aria-required-parent', owner: 'seller-center', note: 'CalendarView columnheader outside role=row' }
];
