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

## Catalog and tax-returns disagree (`CatalogTaxReturnsDisagreement`, `CatalogTaxReturnsUnreachable`, `CatalogTaxReturnsCheckStale`)
Catalog's own tax state is only a cache; **tax-returns' stored decision is the authority** (the one checkout reads). See "One authority" in [tax-gate-paths.md](../engineering/tax-gate-paths.md).
- **Disagreement** (ticket): a live listing was closed because tax-returns does not answer RESOLVED for a variant. Search, offers and carts already dropped it. Open **Tax review**, find the product by name, approve it (or ask the seller for more details); it returns on its own. If many appear at once, check the `catalog-tax-classification` consumer lag on `at.taxreturns.events` (a missed `tax.classification.changed` is the usual cause) and that the local profile did not turn the review off.
- **Unreachable** (ticket after 10 minutes): catalog could not ask tax-returns; every listing keeps its state and nothing is marked OK, but no listing can go live. Check tax-returns health and the `tax:classify` scope of catalog's client.
- **Stale check**: no successful check against tax-returns for 30 minutes. Run `make tax-gate-sweep` (or `POST /v1/tax-gate/sweep`, permission `tax:classify-admin`) and read the catalog log line "Tax gate sweep".
- Never write a decision by hand to clear an alert.

## Offers without a tax decision (`OffersActiveWithoutTaxDecision`, `OffersTaxGateMetricMissing`)
`offers_active_without_tax_decision` counts ACTIVE offers that carry no tax basis (S384): the seller has no verified RESOLVED decision in tax-returns for that ATIN. It must be 0. Page severity, because an offer like that is buyable-looking while checkout blocks the line.
- A seller who offers an item on a page another seller created gets an offer that is `TAX_HELD` (not buyable, not in search, not in the buy box) until tax-returns holds that seller's RESOLVED decision; the tax release component (and its sweep) moves it to ACTIVE. An offer that was moved by a page merge, or by staff moving a variant, waits the same way.
- Diagnose: the offers log for `tax release`, the `tax.classification.changed` consumer lag, and which path set the offer ACTIVE (`TaxGateArchitectureTest` in offers should have refused a new one). Never write a decision or a basis by hand to clear it.
- `OffersTaxGateMetricMissing`: the offers service is down or its count fails.
