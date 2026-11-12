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

## CheckoutTaxEstimateMismatch (more than 1% of orders charged a different tax than the estimate)

`checkout_tax_estimate_vs_final_total{inputs="same",outcome="mismatch"}` over `{inputs="same"}` (orders placed with a lock, for the inputs it was issued for). `checkout_tax_estimate_vs_final_delta_*` has the number and the amount of the differences. Orders also logs a `WARN` `final tax X differs from the estimate Y for unchanged inputs` per order. Causes, most likely first:

1. The two services' shipping allocation differ: `CHECKOUT_SHIPPING_ALLOCATION` (gateway-bff) and `ORDERS_TAX_SHIPPING_ALLOCATION` (orders) must be the same.
2. A package split into several shipments by fulfilment node (orders charges and allocates delivery per shipment).
3. A rate or rule change in tax-returns between the summary and the order (a staff rate import).
4. Delivery fee, membership waiver or coupon rules read differently by the two services (the parity vectors in `tools/checkout/` should be extended with the case).

## CheckoutTaxQuoteLockNotSent

Orders are placed without a valid `taxQuoteId`: an older client, or gateway replicas that sign with different keys (`SESSION_SIGNING_KEY` must be the same on every replica). Enable `CHECKOUT_REQUIRE_TAX_QUOTE` once every client sends it.
