# Dead-code audit (web first pass)

Method: every candidate was grepped to zero references across `web/` (apps, packages, tests, stories), `contracts/`, `docs/`, including
dynamic i18n key prefixes (`t(\`prefix.${x}\`)`). Then `tsc --noEmit`, `eslint src` and `vitest run` per app.

## Removed
- Storefront i18n (5 locales): 72 keys (`account.phone*`, `acct.*Sub`, `sec.*` account-security copy now owned by `web/packages/account/src/i18n`,
  `ord.*` code strings, `gift.*`, `search.searchAll`, `ord.col.qty`, `ord.shipmentAria`).
- Seller-center i18n (5 locales): 33 keys (header account/security, dataTable paging, billing card, bulk toast/held/stock, size-guide aria,
  settings Departments panel, assorted `sc.pf.*`).
- Staff-console i18n (5 locales): 38 keys (negotiated plans panel, resource-table export/paging, delivery fees title, page row actions).
- Code: staff-console `components/ui/Kpi.tsx`; unused exports `earningsCsv`, `EXAMPLE_MILES`, `SETTINGS_SECTION_KEYS`, `BACK_OFFICE_APPS`,
  `safeReturnTo`, `PlacementSource`, `usePreorderItem`, `MAX_SITEMAPS_PER_INDEX`.

## Kept, and why
- `home.services.bookAgain.title`, `home.moreToExplore.title`: listed in `contracts/i18n/home-copy.json` (S426 contract test).
- `contracts/` operations and dynamic-home registry, `version: 1` classic fallback and `home.dynamic` kill switch: not touched.
- Seller-center `ui/Kpi.tsx`: imported by `web/packages/ui/stories/apps/seller-center/Primitives.stories.tsx`.
- OVH: `infra/tofu/modules/ovh/*`, `ovh-vps.yml`, wrangler/flat-host config are live deployment targets. Remaining "no OVH object storage"
  comments are accurate current statements, not leftovers (the OVH state bucket is still used by `github-oidc`).
- Tours: `packages/tour/src/progress.ts` localStorage is intentionally a cache of server-side progress, not old code.
- Known-stale generated clients (`generated/catalog.ts`, `tax-returns.ts`), migrations: out of scope.

## Revisit later (other agents are editing these areas)
- Department scope guard leftovers: `components/listings/RequestDepartment.tsx`, `lib/seller-scope.ts` (`exempt` is always true so every
  prompt renders null), their callers `form/CategoryPicker.tsx`, `bulk/BulkUploadView.tsx`, `size-charts/SizeChartEditor.tsx`,
  `SizeChartImportView.tsx`, and the keys `sc.scope.*`, `sc.pf.category.noDepartments`. Remove together once the product-form rewrite lands.
- `add/match-model.ts` `AUTO_LOOKUP_DIGITS`, `bulk/bulk-model.ts` `byProblems`, `form/RuleFields.tsx` `isRuleDetail`: unused today, but inside
  the add-product / form / bulk areas under rewrite.
- `web/packages/account` settings: re-check storefront `sec.*` deletion once its rewrite lands (it must keep using its own catalogue).

## Undecided / not covered
- Java, Go, Helm/CI/env vars, unused contract operations, bulk/matching/image-validation flags, gateway-bff home config: not audited (no
  knip/ts-prune/staticcheck/deadcode available offline; Java/Go need a reference-grep pass).
- Pre-existing failure, unrelated: seller-center `product-form-rules.test.ts` "selling status placeholder" calls a hook outside React.
