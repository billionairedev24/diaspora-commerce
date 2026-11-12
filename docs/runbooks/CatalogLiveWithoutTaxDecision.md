# CatalogLiveWithoutTaxDecision

**Severity:** `page` (the closed-listings and missing-metric alerts are `ticket`) · **Defined in:** `infra/observability/rules/catalog-tax-gate.yaml` · **Design:** [tax-gate-paths.md](../engineering/tax-gate-paths.md)

## What it means
`catalog_live_without_tax_decision` is above 0: at least one listing is live (purchasable) although a variant has no stored, resolved tax decision. The owner's rule is that this is never allowed. The catalog sweep (every 60 seconds) closes each such listing, so the number normally returns to 0 on its own; the page means a code path skipped the tax gate.

## Impact
The listing was visible in search and could be added to a cart for a short time. **Checkout still blocks it** (a line without a definite decision cannot be ordered), so no order can be placed without tax, but shoppers saw an item they could not buy.

## Diagnose
1. Staff console: **Fees & commissions > Sales tax > Tax review**: the red banner shows the count and lists the products (seller and category by name), with what is missing for each variant.
2. API: `GET /v1/tax-gate/live-without-decision` (permission `tax:read`).
3. Find the path. Look at the catalog logs for `Listing ... closed for tax (<reason>)` (reason `TAX_<status>`), and at `catalog.product.updated` / `.approved` / `.resumed` / `.reinstated` events for the product around the time it went live. Compare with the path table in `docs/engineering/tax-gate-paths.md`: a path that is not in the table is the bug.
4. Data written outside the service (SQL, a data tool, a restore from backup) is the other usual cause: it bypasses the gate; the sweep and the persistence guard catch it afterwards.

## Mitigate
- Nothing to do for the listings: the sweep closes them (status back to in review, seller sees "We are checking the tax details for this item", search and offers drop them). If the sweep is not running, restart the catalog pods; as a last resort run the migration's `update` by hand for the listed product ids and publish `catalog.product.paused`.
- Do not invent a tax decision to make the number go to 0. A listing comes back only when tax review approves its classification (it returns on its own, or a moderator approves it).

## After the incident
- Fix the path so it goes through `ProductLiveGate`, and add it to the table and to `TaxGatePathsTest`. `TaxGateArchitectureTest` should have caught a new call to a live transition; if the path used another way, extend the rule.
- Link the incident ticket to this runbook and update the steps if they were wrong or incomplete.
