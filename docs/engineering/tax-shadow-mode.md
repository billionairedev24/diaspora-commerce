# Tax shadow mode: every configured provider next to our own rules

Service: `java/services/tax-returns`. Staff screen: Sales tax, **Shadow report**. Cutover procedure:
[tax-provider-cutover.md](../runbooks/tax-provider-cutover.md). Design: [category-tax-design.md](category-tax-design.md) section 8.

## What shadow mode does

The charging method (`RULES`, `STRIPE_TAX` or `AVALARA`, set in *Who calculates tax*) prices the order. In the background, for every real order
quote (a quote that carries an order reference; cart estimates only with `at.taxreturns.tax.shadow.cart-quotes`), the same lines are also sent to
**every provider that is set up and is not the one charging**, each with the code the provider code map holds for the line's outcome. Each answer is compared with
our rules engine's answer for the same line and stored in `tax_shadow_quote` (one row per provider per line). Nothing a provider says is ever charged,
and nothing a provider does can delay or fail a checkout (timeout `TAX_SHADOW_TIMEOUT`, at most 50 comparisons in flight).

Turning shadow mode on is still the *Shadow mode* choice in *Who calculates tax* (`PUT /v1/tax/settings`, `shadowProvider`): a non-empty value switches it on, and
from then on **all** configured non-charging providers are asked, not only the one named. (A plain on/off switch would be clearer; the contract keeps the
provider enum for now so existing clients keep working.) `TaxMethodSource.shadowProviders()` computes the list.

## What the report says for each line and provider

| State | Row kind / failure | Meaning |
|---|---|---|
| Answered | `MATCH`, `OUTCOME`, `RATE` (`BOTH` reserved) | the provider priced the line; tax, rate and the difference from ours are shown |
| No code mapped | `NO_CODE` | no (verified, when `TAX_REQUIRE_VERIFIED_CODES=true`) code of this provider exists for the line's outcome, so it was **not asked**. Never a $0.00 quote |
| Not configured | `PROVIDER_ERROR` + `NOT_CONFIGURED` | credentials missing, so it was not asked. The text names the provider and the variables to set |
| Timed out | `PROVIDER_ERROR` + `TIMEOUT` | the provider did not answer within `TAX_SHADOW_TIMEOUT` (default 4s) |
| Provider error | `PROVIDER_ERROR` + `ERROR` | asked and refused or failed: the HTTP status and the provider's own message, or a response that missed the line |
| No decision | `NO_DECISION` | our engine had no decision for the line (blocked), so there is nothing to compare |

The reason text always names the provider ("Avalara did not answer within PT4S", "Stripe Tax is not configured ..."). The staff console shows our quote, then one column per provider,
with a state pill and the reason when there is no quote, plus a card per provider (configured, charging or asked, and its tallies). A provider that is not configured has no rows at all; its column
says so for every line. Search (order number, category, outcome code, ATIN, line id) and the *Show* filter (differs, matches, did not answer, no code) are applied by the BFF
(`GET /v1/staff/tax/shadow?status=&search=&page=&size=`), which reads up to 2000 recorded rows of the period.

## Why a line could show "The provider did not answer" before this change

1. Only one provider was ever asked (the one named in the shadow setting); the other had no data.
2. The Avalara adapter was a skeleton: configured but always failing with "not implemented", so every Avalara row was a provider error.
3. A missing mapped code (the Avalara column of the code map starts empty) was recorded as the same kind of row as a real outage, with no provider named.
4. For Stripe Tax: a non-US ship-to, a timeout, an HTTP error or a response without the line each gave the same generic text.

## The Avalara adapter (`AvalaraTaxCalculator`)

* `POST {host}/api/v2/transactions/create` (AvaTax REST v2), HTTP Basic auth with the account id and license key.
* Body: `type: SalesOrder`, `commit: false`, `companyCode`, today's date, `currencyCode`, `addresses.shipTo`, one line per item with `taxCode` (from the code map), `amount`
  (taxable amount after discount), `quantity`, `itemCode`, and a separate shipping line (`AVALARA_SHIPPING_TAX_CODE`) when the line carries a delivery charge. A SalesOrder is a quote:
  AvaTax records nothing and nothing is committed. A line without a code is not sent; strict calls refuse the whole call so no line is priced on a guess.
* Response: tax and the sum of the jurisdiction rates per line; the shipping line's tax is folded into its item line.
* A charging quote (`method = AVALARA`) falls back visibly to our rules (`RULES-FALLBACK`) on any of: not configured, a line without a code, an AvaTax failure.

### Settings (names only; values live in the secret store, never in the repo)

| Variable | Meaning |
|---|---|
| `AVALARA_ACCOUNT_ID` | AvaTax account id |
| `AVALARA_LICENSE_KEY` | AvaTax license key (secret) |
| `AVALARA_COMPANY_CODE` | company the transactions belong to in AvaTax |
| `AVALARA_ENVIRONMENT` | `sandbox` (default, `sandbox-rest.avatax.com`) or `production` (`rest.avatax.com`); anything else stops the service at start |
| `AVALARA_SHIPPING_TAX_CODE` | optional; AvaTax tax code for a separately stated delivery charge (default `FR020100`, verify against Avalara's list) |
| `AVALARA_BASE_URL` | optional host override (tests, proxies); normally unset |

Avalara counts as configured when the first three are set. Stripe Tax needs `STRIPE_SECRET_KEY`.

## Known limits

* The adapter is tested against a fake AvaTax (JDK HttpServer): request shape, authentication, parsing, error and timeout handling. **It has not been run against a real Avalara sandbox.**
* Only `shipTo` is sent. AvaTax may also need a ship-from address or a default company location to resolve origin-based jurisdictions; if the sandbox rejects calls for that reason, the failure shows as a provider error with Avalara's message.
* Two-decimal currencies only (USD at launch).
* The staff report recomputes its tallies per request (a few calls to tax-returns); the line table reads at most 2000 recorded rows of the period.
