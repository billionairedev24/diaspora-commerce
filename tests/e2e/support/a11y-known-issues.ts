/*
 * Serious/critical axe findings that are tracked for a fix. The a11y gate reports these as annotations instead of
 * failing, so any NEW serious/critical rule on a page still fails the build.
 *
 * Rules: one entry per (page, rule); every entry names an owning team and the story/issue that fixes it.
 * Delete the entry in the same PR as the fix — a stale entry hides regressions.
 *
 * Empty since S194 (2026-10-10): every serious/critical finding across the storefront, Seller Center / Provider Hub
 * and staff console journeys is fixed, so axe fails on any regression. Keep it empty; add an entry only with an
 * owner and a linked issue.
 */
export interface KnownA11yIssue {
  /** The `page` option passed to expectNoSeriousA11yViolations. */
  page: string;
  /** axe rule id. */
  rule: string;
  owner: string;
  note: string;
}

export const KNOWN_A11Y_ISSUES: KnownA11yIssue[] = [];
