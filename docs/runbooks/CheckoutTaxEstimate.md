# CheckoutTaxEstimate

**Severity:** `CheckoutTaxEstimateMismatch` and `CheckoutTaxEstimateUnavailable` are `page`; `CheckoutTaxQuoteLockNotSent` is `ticket` · **Defined in:** `infra/observability/rules/checkout-tax-estimate.yaml` · **Design:** [checkout-tax-estimate.md](../engineering/checkout-tax-estimate.md)

## CheckoutTaxEstimateUnavailable (shoppers cannot see their tax; Place order is blocked)

`tax_estimate_unavailable_total{reason}` counts checkout summaries without a tax estimate. The alert excludes the shopper states (`needs_zip`, `address_incomplete`, `blocked_items`, `no_items`). What the failure reasons mean and where to look:

| `reason` | Meaning | Look at |
|---|---|---|
| `tax_returns_timeout`, `tax_returns_breaker_open`, `tax_returns_server_error` | tax-returns slow, down or erroring | tax-returns health, its dependencies (rates, providers); `bff_upstream_*{upstream="tax_returns"}` |
| `tax_returns_unauthorised` | the gateway client lost the `tax:quote` scope | identity client `gateway-bff` scopes (application-<env>.yml) |
| `tax_returns_rejected` | tax-returns answered 400/422 | the gateway log line `checkout tax estimate unavailable` carries the problem (a state or ZIP it cannot take, a line it refuses) |
| `service_token_failed` | the client-credentials token for tax-returns could not be fetched | identity availability, `OIDC_CLIENT_SECRET` |
| `returns_not_configured` | the gateway was started without a tax-returns client | `TAX_RETURNS_URL` / `upstreams.taxReturns` |
| `catalog_facts_missing` | catalog's facts for a cart item could not be read, so the quote would not match orders' | catalog availability |
| `tax_returns_other` | anything else | the gateway log line (it carries the error) |

Every one of these is also an `ERROR` log line `checkout tax estimate unavailable` with `reason` and `error`. The shopper sees "We couldn't work out your tax right now. Try again." with a Retry button; nothing is charged.

## A plain item is blocked at checkout ("We can't sell Basmati Rice yet", no tax in the cart)

This is not an alert, it is the `blocked_items` state: tax-returns has no `APPROVED` and `RESOLVED` decision for the variant. Tax is
automatic from the rules (`taxReview.mode` `RISK_BASED`, the default), so a plain grocery should be approved by `auto` within seconds of
catalog reporting it. If it is not:

1. Open Settings → Sales tax → *Tax review*, search the product **by name**. If it is waiting, the line under it says why:
   *Conflict*, *No matching rule*, *Missing answer*, *Missing proof*, *Regulated category* or *Needs sign-off*. Those need a person
   (or the seller's answer); nothing else should wait. The shopper only sees the generic "We're still checking its details" on purpose:
   no tax terms and no internal reasons on the storefront.
2. If *Review settings* says *Review every item*, staff chose `ALL`; switching back to *Review by risk* approves the waiting items the rules
   decide within seconds.
3. If it waits with no reason line, or has no record at all: restart order is tax-returns, then catalog, then gateway-bff. tax-returns'
   sweep approves the waiting records that qualify 20 s after it starts (and every 5 minutes: `at.taxreturns.jobs.review-sweep-interval`);
   a record that has not arrived is created when catalog reports the product again. Then run `make tax-gate-sweep` so catalog re-opens
   listings that were held (`TAX_RETURNS_*` holds) without waiting for its own schedule.
4. Approved by `auto` but still blocked: the category is not `RESOLVED` for the line's facts (see the quote's `decisionStatus`), which
   is a data problem for the seller or a rule for the tax professional, never something to approve around.
5. Staff console says the variant is *Approved* and *Exempt*, yet checkout blocks it: the quote was asked about a different id. Records are
   keyed by **variant ATIN** (`lineId` in the summary JSON); the product ATIN (`atin`) is only for product-page links. Look for these `WARN`
   logs: gateway-bff `checkout tax line blocked` (per line: `lineId`, `variantAtin` sent, `productAtin`, `decisionStatus`, `blockedReason`;
   `variantAtin` must equal `lineId`) and tax-returns `Tax quote line ... was looked up by ATIN X and no classification record exists for it`
   (X is the id it looked up) or `No classification record for X: ingesting one from catalog facts`. If X is a product ATIN the caller is
   wrong (fixed in gateway-bff by story S442: the quote line carries `CartLine.VariantATIN`); the stray product-level record it
   ingested sits in Tax review as a never-sold item and can be rejected. The shopper still only sees the generic message.

## CheckoutTaxEstimateMismatch (more than 1% of orders charged a different tax than the estimate)

`checkout_tax_estimate_vs_final_total{inputs="same",outcome="mismatch"}` over `{inputs="same"}` (orders placed with a lock, for the inputs it was issued for). `checkout_tax_estimate_vs_final_delta_*` has the number and the amount of the differences. Orders also logs a `WARN` `final tax X differs from the estimate Y for unchanged inputs` per order. Causes, most likely first:

1. The two services' shipping allocation differ: `CHECKOUT_SHIPPING_ALLOCATION` (gateway-bff) and `ORDERS_TAX_SHIPPING_ALLOCATION` (orders) must be the same.
2. A package split into several shipments by fulfilment node (orders charges and allocates delivery per shipment).
3. A rate or rule change in tax-returns between the summary and the order (a staff rate import).
4. Delivery fee, membership waiver or coupon rules read differently by the two services (the parity vectors in `tools/checkout/` should be extended with the case).

## CheckoutTaxQuoteLockNotSent

Orders are placed without a valid `taxQuoteId`: an older client, or gateway replicas that sign with different keys (`SESSION_SIGNING_KEY` must be the same on every replica). Enable `CHECKOUT_REQUIRE_TAX_QUOTE` once every client sends it.
