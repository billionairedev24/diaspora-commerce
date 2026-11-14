# Checkout estimated sales tax

For **which data points decide taxability and rates**, and how **shipping** relates to tax, see [shipping-and-tax-data.md](./shipping-and-tax-data.md). The design and the rules TX-01 to TX-12 are in [category-tax-design.md](./category-tax-design.md) (sections 5 and 7); this page is how checkout (gateway-bff, orders, storefront, customer app) applies them.

Shoppers see **estimated tax** on the checkout summary before they press Pay, and what they see is what they pay. The estimate and the tax charged come from **the same tax-returns quote function with the same request** (see [The guarantee](#the-guarantee)); the orders checkout saga still **quotes again** at place time, **persists the decision each line was taxed with**, and charges that, but it never charges more than the total shown without the shopper confirming the new total first.

**The tax row never says the tax is "calculated at checkout" or "when you place your order".** It shows an amount, asks for the one thing it needs (a ZIP code, a state), or, in the exceptional case that tax-returns cannot answer, says so plainly with a Retry button and blocks Pay.

> **Open legal decisions that gate production** (nothing here may be switched on for real customers before the Texas tax professional signs off):
> **Open decision 5 (TX-07): how the delivery charge is attributed in a mixed basket.** The build allocates it over the package's lines proportionally to item value (default) and the summary says so (`shippingTaxAllocation.approved = false`). The adviser must approve the method, and it interacts with the still-open final delivery price. Rollback: `FIRST_LINE` (see settings). Also see the "not done yet" column of the design's section 5 (ZIP-based local rates, tax holidays, bundles, platform fee classification, gift cards).

## Never guess

- **No default rate, no interim table.** The per-state table (`StateRateTaxCalculator`, `at.orders.tax.rates`, `fallback-to-table`, the `TABLE` provider) is deleted. Orders and the BFF take every rate, outcome and code from tax-returns' stored decision; when tax-returns is down, the saga's tax step fails and is retried, and the summary says so (Retry state, Pay blocked) instead of showing an empty row. Nothing can silently supply a rate in any profile.
- **A line whose decision is not definite is blocked.** Definite means `decisionStatus = RESOLVED` **and** an outcome of `EXEMPT` or `TAXABLE`. Anything else (`MISSING_FACTS`, `BLOCKED`, `NEEDS_APPROVAL`, `REFINE_CODE`, `CONFLICT_OPEN`, no status at all, no answer for the line) is blocked.
  - **Summary:** the BFF lists the line in `blockedLines` (`lineId`, `offerId`, `title`, `packageId`, `action: REMOVE`, plain `message`), sets `canPlaceOrder = false`, and gives it no tax. The rest of the cart is still estimated.
  - **Place order:** orders refuses with `orders.tax-line-blocked` (422, RFC 9457, stable code; the BFF maps it to `CHECKOUT_ITEM_BLOCKED`). The detail names the items in plain words ("We can't sell Gift card yet. We're still checking its details. Remove it from your cart to continue, or try again later."): no ids, no tax terms. Why a person has to look at an item (conflict, no matching rule, missing answer or proof, regulated category) is shown to staff in the Tax review queue and never to the shopper; with the default `taxReview.mode` `RISK_BASED` only those exceptions are blocked for review, a plain grocery is approved by the rules ([category-tax-design.md](./category-tax-design.md) section 9).
  - **Shopper:** the storefront and the customer app show the item with a **Remove** action (it leaves this order and stays in the cart); Place order is disabled until none remains. The storefront words the message itself per locale (en, fr, es, ar, yo); the server's text is English.

## Flow

1. Storefront calls `GET /v1/checkout/summary?addressId=&packageDelivery=&couponCode=` (comma-separated `packageId:optionKey` per package; optional coupon code for a **validate-only** quote).
2. Gateway BFF enriches the cart (delivery options, AfroTransact Delivery, promises), validates the coupon with **offers**, then calls **tax-returns** `POST /v1/tax/quotes` with the **gateway-bff** client-credentials token (`tax:quote`), not the shopper JWT. Each quote line carries the **variant ATIN**, the **seller org** (as the cart service gives it: none for AfroTransact's own items), the product type, quantity, unit price, discount, the line's delivery share and the seller's **behaviour facts** (sold heated, utensils, bakery; catalog's listing facts, for the owner's own offers only), exactly as orders sends them (pinned by the parity vectors below). tax-returns reads the stored decision for that variant and seller.
3. The BFF maps the quote to `taxMinor`, `taxRateLabel`, `taxJurisdictionLabel` ("Houston, TX", from the delivery address), `taxZip`, `taxLines` (per line: `TAXABLE`, `EXEMPT` or `BLOCKED`, its tax and its delivery share), `blockedLines`, `canPlaceOrder`, `shippingTaxAllocation`, plus `discountMinor`, `shippingDiscountMinor`, `coupon`, `totalMinor` aligned with the same delivery selections, and the **quote lock** `taxQuoteId` / `taxQuoteExpiresAt`. Without an estimate it sets `taxEstimateStatus` and `taxUnavailableReason` instead, and `canPlaceOrder = false`.
4. Orders' `CheckoutTaxImpl` quotes the same way when the order is placed (orders-service token, `tax:quote`), blocks undecided lines, and writes the **decision snapshot** on each order line.

When the shopper changes address, delivery option, or promo code, the UI refetches the summary.

## Delivery in the estimate (launch shipping v1)

The shipping line is **what orders will charge**, from the **same record** (orders' staff-edited shipping settings; [shipping-and-tax-data.md](./shipping-and-tax-data.md#shipping-at-launch-v1-afrotransact-sets-all-shipping)):

- **Options per package:** *Delivery by AfroTransact* (Fulfilled by AfroTransact) or *AfroTransact collects from the store and delivers* (seller-store item) at **one flat fee** (default $5.99, free above an optional threshold); *Pickup at an AfroTransact point* or *Collect from the store* at $0. No express and no carrier name; seller-shipped carrier options exist only behind the staff switch (off at launch).
- **Pre-orders:** a pre-order line is its own package and pays **its window's or run's own fee** (door or pickup); `shippingMinor` includes it. Tax on shipping follows the item through the proportional allocation below, unchanged.
- **Outside every delivery zone:** the package's delivery option is the single `quote_required` ("Too far for standard delivery. Ask us for a quote"), pickup stays, `canPlaceOrder = false` and `deliveryBlockedReason` says why. The storefront disables payment for it and shows a button (stub `onRequestQuote`).
- **If the fee cannot be read:** the estimate keeps the last known fee (15 second poll, logged); with none ever read the option is `feeUnknown` (never $0 or FREE) and the order cannot be placed from the estimate. Orders itself never guesses.
- **Estimate equals charge:** proven on shared vectors (`tools/checkout/shipping-fee-vectors.json`) by orders, gateway-bff and both mocks.
 **Redemption** of a coupon still happens only when orders places the checkout; failed payments release it idempotently.

## What the summary shows

- Row **"Estimated tax"** with the amount, "Estimated for Houston, TX" under it (`taxJurisdictionLabel`) and "This is the tax you will pay unless something in your order changes". The total is labelled "Order total" with "Includes tax. If your total would change before you pay, we show you the new total first. You are never charged more without your OK." No ids.
- Without an estimate the amount is "—" and the row says what is needed (see [Where there is no estimate](#where-there-is-no-estimate)). The total is then labelled "Total before tax" and Pay is disabled.
- Mixed baskets: items with no tax are tagged "No tax" in the item list, with "No tax on some of your items, or on their delivery." A basket of exempt items only shows $0.00 with "No tax on these items, or on their delivery."
- Blocked items: see above.

## Delivery charge allocation (TX-05, TX-06, TX-07)

The delivery charge follows the sale it belongs to. A package's charge (after the membership waiver and any coupon shipping discount) is **no longer attached to the package's first line**: it is allocated over the package's lines, so a delivery tied only to exempt lines is taxed at 0% and a mixed package is split. Each quote line carries its own `shippingMinor`; tax-returns taxes it by that line's outcome.

- **PROPORTIONAL (default):** by each line's net item value (items minus discount). `floor(total x weight / sum of weights)` per line, spare minor units to the largest remainder (ties: the earliest line); all on the first line when every weight is 0. Integer arithmetic only.
- **FIRST_LINE (rollback):** the previous behaviour.
- **Setting:** orders `at.orders.tax.shipping-allocation` (`ORDERS_TAX_SHIPPING_ALLOCATION`), gateway-bff `checkout.shippingAllocation` (`CHECKOUT_SHIPPING_ALLOCATION`). **Both must have the same value**, or the estimate and the charged tax can differ. An unknown value fails start-up.
- **Same arithmetic everywhere:** orders (`ShippingAllocation`), the BFF (`app.ShippingAllocation`), the web mock and the customer app mock are all tested against [`tools/checkout/shipping-allocation-vectors.json`](../../tools/checkout/shipping-allocation-vectors.json). Add a case there when the rule changes; four test suites then check it.
- **Itemisation is kept on the order:** each order line stores its allocated share (`order_line.shipping_alloc_minor`) and its shipping tax. Payments lines, refunds and the service order lookup all use the stored allocation. Orders placed before the allocation was kept (null) keep their first-line behaviour.
- **Open legal decision 5:** the method is flagged in code (`ShippingAllocation`), here, in the design, in the summary response (`shippingTaxAllocation`: `method`, `approved: false`, `note`) and in the launch checklist. The tax adviser must approve it, or give another method, before production.

## The decision snapshot on the order line (orders V10)

Each placed order line stores what the sale used, from the quote (never a live recompute later): `tax_decision_status` (always `RESOLVED`), `tax_outcome` (EXEMPT or TAXABLE), `internal_tax_code`, `provider_tax_code`, `tax_rule_version`, `tax_provider` (the method the quote reports: `RULES`, `STRIPE_TAX`, `AVALARA`), `tax_address_precision` (ZIP or ADDRESS, when tax-returns reports it), and `shipping_alloc_minor`. Blocked lines never reach an order. The snapshot is also in `orders.order.placed` (line summaries) and the service lookup (`GET /v1/lookup/orders/{id}`), so tax-returns books and reverses with the code the sale used.

## Refunds and returns

Orders reverses by what was stored: refund lines carry the stored per-line tax and the line's stored shipping share (`PaymentLines.refundOf`), never a recomputed amount. Shadow mode never affects what is charged. See "Follow-ups for tax-returns" in the change description for what the ledger side should read from the snapshot.

## Gift cards and credit

- **Selling a gift card is not an order line.** It is a separate payments flow (`POST /v1/gift-cards/purchases`, a Stripe PaymentIntent on its own); no tax is added to it, and it is not part of a checkout quote. The category is held `NEEDS_APPROVAL` in the dataset; do not enable new gift card sales through the catalog until the adviser approves the rule (open decision 4). A catalog item in that category is blocked at checkout like any undecided line.
- **Redemption is a tender.** A redeemed card becomes AfroTransact credit; at checkout credit pays part or all of an order (`useCredit`). The items bought are taxed at their own rates; credit and gift cards are never quote lines and never taxable. Tests: `CheckoutTaxImplTest` (credit order quotes the items only), gateway-bff `TestCreditNeverEntersTheTaxQuote`.
- Per-redemption tax by `gift_card_event` stays held until gift cards are approved (design section 7).

## Local development

- With mocks (`web` MSW and the customer app mock): groceries are exempt, held product types (`gift-cards`, `digital-downloads`, `event-tickets`, `tutoring`) are blocked, everything else is taxable at 8.25% in Texas and untaxed elsewhere; delivery is allocated as above. The mocks stand in for tax-returns' decisions; they are not rules.
- With the full stack, ensure identity grants `tax:quote` to `gateway-bff` and orders, and that tax-returns (built to `contracts/openapi/tax-returns.yaml`) is reachable on port **8111** (see `infra/local/PORTS.md`). **There is no fallback:** against a tax-returns that does not return `decisionStatus` on quote lines, every line is blocked at checkout.

## Updating rate data (staff)

Checkout estimates use whatever is in **tax-returns**. To load or refresh rates without a migration:

1. **Staff Console** → **Fees & commissions** → **Tax rates** → **Import tax rates (CSV)** (needs `tax:configure`), or `POST /v1/staff/tax-rates/import` with `{ "csv": "…" }`.
2. CSV header: `kind,country,state,name,zipPrefix,generalRate,groceryRate,preparedFoodRate,shippingTaxable,facilitator,localRate,appliesToGrocery` with `kind=STATE|LOCAL`.
3. Ready-made test imports: [`tools/tax/test-import-texas.csv`](../../tools/tax/test-import-texas.csv) and [`tools/tax/test-import-pennsylvania.csv`](../../tools/tax/test-import-pennsylvania.csv) (notes in [`tools/tax/test-import-README.md`](../../tools/tax/test-import-README.md)). Minimal template: [`tools/tax/us-rates-template.csv`](../../tools/tax/us-rates-template.csv).

After import, refetch the checkout summary (change address or delivery) so the storefront picks up new quotes.

## The guarantee

> **Before the shopper presses Pay, the summary shows the tax and the total that will be charged. They agree for the same cart lines, address, delivery options and coupon (target: 99% of orders, ideally all). If anything would make the total higher, nothing is charged: the shopper sees the new total and confirms it first.**

Three mechanisms, in order of strength:

1. **Same engine, same inputs.** The estimate (`checkout_tax_engine.go`: `buildTaxQuote`) and the order (`CheckoutTaxImpl`) build the tax-returns `POST /v1/tax/quotes` request the same way: the lines (variant ATIN, seller org, product type, quantity, unit price, the coupon discount of the line's offer, the seller's behaviour facts), the line's share of its package's delivery charge (after the membership waiver and the coupon's shipping discount, spread by the configured allocation) and the ship-to address as the address book holds it. [`tools/checkout/checkout-tax-parity.json`](../../tools/checkout/checkout-tax-parity.json) holds eleven representative baskets (taxable, all-exempt, mixed package delivery, coupon on a line and on delivery, two sellers, pickup, different states, membership waiver, seller behaviour, AfroTransact's own item, the FIRST_LINE rollback) with the exact request lines expected and the tax they add up to. `checkout_tax_parity_test.go` (gateway-bff) and `CheckoutTaxParityTest` (orders, through the real coupon step) both run the whole file. Same request into the same tax-returns function gives the same tax; tax-returns' own determinism is its own test.
2. **The quote lock.** The summary returns `taxQuoteId`, a token signed with a key derived from the session signing key (so every gateway replica verifies it), valid 15 minutes (`taxQuoteExpiresAt`), carrying the shopper, the tax and total shown and a hash of the inputs it was made for (the cart lines with quantity and unit price, the address id/state/ZIP/city, the delivery option per package, the coupon code). Place order carries it. The gateway verifies it and gives orders the shown total and tax (`taxGuard`).
3. **The guard in orders.** After the draft is final (tax quoted, coupon applied) and **before any payment exists**, orders compares the total with the one shown (`CheckoutTotalActivities.checkShownTotal`, behind the workflow change id `tax-guard-v1`). A lower or equal total goes ahead. A higher one fails the saga with `orders.total-changed`; stock, coupon and run slot are released by the usual compensations; the gateway answers `409 CHECKOUT_TOTAL_CHANGED` with the previous and new total, tax, delivery, subtotal, discount and a fresh `taxQuoteId`. The storefront shows "Your total changed: it was $A and is now $B. Nothing has been charged." with **Place order for $B** and **Review my order**; confirming places the order again with a **new Idempotency-Key** (the refused attempt is over, same as any 4xx) and the new lock. The customer app shows the same as a message, reads the summary again and the shopper taps Place order again. Idempotency-Key semantics are unchanged: the same key replays the same result, the guard is not part of the request fingerprint.

**Why the lock is a guard and not a frozen rate.** The lock does not make orders charge the locked amount when the rate has moved in between: orders needs a fresh quote to write the decision snapshot (refunds reverse with the code the sale used) and charging a tax other than the one computed is a legal question, not an engineering one. It guarantees the other half, that the shopper is never charged more than shown without saying yes. If tax-returns' rate changes between the summary and the order, the total goes up by the difference and the shopper confirms; if it goes down they pay less.

### What can still make the final tax differ

| Cause | What happens | Neutralised by |
|---|---|---|
| Address changed (or edited) | the lock's inputs differ; a different tax | the summary is read again when the address changes; a total above the one shown is refused (409) |
| Delivery option or pickup point changed | delivery fee and so its tax differ | same; Place order is disabled while the summary is being read again (`taxPending`) |
| Coupon added, removed or changed | discount and shipping discount differ | same |
| Item price or quantity changed, stock changed | a line differs or is refused (`STOCK_CHANGED`) | same; stock problems keep their own 409 |
| Rate or rule changed in tax-returns between summary and order (a staff rate import, a decision approved) | tax differs by the rate change | the 15-minute lock expiry makes the storefront read the summary again; a higher total is refused (409) and confirmed first |
| Rounding | none: both sides use the same integer arithmetic on the same request; tax-returns rounds once per line | parity vectors |
| A package that orders splits into several shipments by fulfilment node | orders charges and allocates delivery per shipment, the estimate per package | remains: counted in `checkout_tax_estimate_vs_final_total{outcome="mismatch"}`, refused (409) if the total is higher |
| The shipping allocation setting differs between gateway-bff and orders | different line shares | startup parity of the two settings is an ops rule (runbook `CheckoutTaxEstimate`) |
| Membership waiver or coupon rules read differently (payments, offers answer differently between the two calls) | different delivery or discount | remains: same counter, same refusal |
| The lock expired or was edited | the guard still holds with the shown total; the order is not counted as "same inputs" | metrics only |
| An older client sends no lock | no guard | counted in `checkout_tax_quote_lock_total{result="missing"}`; `CHECKOUT_REQUIRE_TAX_QUOTE` refuses them once every client sends it |

### Measuring the 99% target

Orders records, for every order placed with a lock (`CheckoutTotalActivitiesImpl`):

- `checkout_tax_estimate_vs_final_total{inputs,outcome}`: `outcome` is `match` (final tax = estimated tax) or `mismatch`; `inputs` is `same` when the order is for the inputs the estimate was made for (lock honoured, not expired) and `changed` otherwise.
- `checkout_tax_estimate_vs_final_delta_{count,sum,max}`: the absolute tax difference in minor units of every mismatch (the count is the number of mismatches, the sum their amount).
- `checkout_total_refused_total{inputs}`: orders refused because the total would be higher.

The **accuracy** is `sum(increase(...{inputs="same",outcome="match"})) / sum(increase(...{inputs="same"}))`. The alert `CheckoutTaxEstimateMismatch` pages when more than 1% of `inputs="same"` orders over 6 hours (at least 50 orders) mismatched; the orders log carries a `WARN` per mismatching order. The gateway adds `checkout_tax_quote_lock_total{result}` (issued, honoured, inputs_changed, expired, invalid, missing, total_changed) and `tax_estimate_unavailable_total{reason}` (alert `CheckoutTaxEstimateUnavailable`). Rules: `infra/observability/rules/checkout-tax-estimate.yaml`; runbook: [`CheckoutTaxEstimate`](../runbooks/CheckoutTaxEstimate.md).

## Where there is no estimate

The estimate needs a destination (state and ZIP code). The checkout puts the **address first** (step 1), and the totals show the estimate as soon as a deliverable address exists. Without one:

| `taxEstimateStatus` / `taxUnavailableReason` | When | The row says |
|---|---|---|
| `NEEDS_ADDRESS` / `NEEDS_ZIP` | no usable place: a guest or a shopper with no saved address and no chosen delivery place (see [below](#the-cart-page-never-asks-for-what-it-already-knows)) | an input: **"Enter your ZIP code to see tax"** with a "See tax" button (`GET /v1/checkout/tax-estimate?zip=`). A saved address or a chosen delivery place is used on the cart page without typing. Never an amount, never $0.00. |
| `UNAVAILABLE` / `ADDRESS_INCOMPLETE` | the chosen address has no state or ZIP code (identity does not require a state) | "Add your state and ZIP code to this address to see your tax." with **Edit address**. Place order to such an address is refused by the gateway (`errors.addressId`) before orders is called. |
| `UNAVAILABLE` / `BLOCKED_ITEMS` | an item has no definite tax decision (`blockedLines`) | "Remove the items we can't sell yet to see your tax." (the blocked-items panel above offers Remove) |
| `UNAVAILABLE` / `SERVICE_UNAVAILABLE`, `FACTS_UNAVAILABLE` | tax-returns (or catalog's facts for an item) did not answer: the **only** exceptional state | "We couldn't work out your tax right now. Try again." with **Retry**; Pay is disabled; `tax_estimate_unavailable_total` counts it and an alert pages |

`GET /v1/checkout/tax-estimate` (cart page, new shopper): the same quote for the selected lines at each package's first delivery option, in the place the ZIP code is in (city and state from geo). No coupon, membership or delivery choice is known yet, so it can differ from the checkout's locked estimate; it is labelled "Estimated for Houston, TX".

### The cart page never asks for what it already knows

The cart's tax row (`TaxZipEstimate`) asks the BFF with no `zip`; the BFF (`PlaceResolver`, `go/services/gateway-bff/internal/app/place.go`) works the place out in one priority order and **asks for a ZIP code only when none of these has a real ZIP and state** (rare):

1. **The saved address** (the shopper's token): the default address, else the first saved one with a state and ZIP. `taxPlaceSource: SAVED_ADDRESS`. A saved address that cannot be taxed does not block the estimate: the next sources are tried, and only with none of them is it `ADDRESS_INCOMPLETE`. An address book that cannot be read is `SERVICE_UNAVAILABLE` (Retry).
2. **The delivery place chosen under "Deliver to"** (session location, source `chosen`): `CHOSEN_PLACE`.
3. **The place detected from Cloudflare's visitor-location headers** (source `detected`; the storefront forwards `CF-IPPostalCode`, `CF-IPCity`, `CF-Region-Code`, `CF-IPLatitude/Longitude`; the first estimate call detects it when the header never did): `DETECTED_PLACE`. Detection order: edge ZIP, edge coordinates (reverse geocode), then **city + two-letter state with no ZIP**: the ZIP is the city's representative ZIP from geo's embedded US ZIP table (`GET /v1/zips/by-city`: the ZIP nearest the centre of the city's ZIPs, never outside the table) and the place is marked **CITY precision**. A region that is not a state code, or a city the table lacks, detects nothing.
4. **The market's default place** (`commerce.defaultZip`, the place the header shows when nothing else is known): `DEFAULT_PLACE`. Owner decision: it is a real ZIP, shown as "based on your delivery area".
5. **The ZIP prompt**: nothing above gave a place.

The response carries `taxPlaceSource` and `taxPlacePrecision` (`ZIP` or `CITY`). For CITY precision the BFF sends tax-returns `shipTo.precision = CITY`; the rate looked up is still the ZIP's (tax-returns has no city table), and the quote lines report `addressPrecision: CITY`. The row reads "$x.xx · Estimated for Houston, TX · based on your location" (detected) or "based on your delivery area" (default) with a small **Change** link that opens the ZIP input. The checkout recomputes from the real chosen address. Tests: `checkout_tax_known_place_test.go`, `location_detect_test.go`, `web/apps/storefront/test/cart-tax.test.tsx`.

What the row then shows comes from `taxEstimateStatus` / `taxUnavailableReason` and is **never replaced by the ZIP prompt** because something failed:

| Answer | Cart row |
|---|---|
| `ESTIMATED` with a place | the amount and "Estimated for Houston, TX"; "Estimate for another ZIP code" is a small secondary link (for a typed ZIP it reads "Change ZIP code"). An estimate with no place is treated as a failure, never shown |
| `UNAVAILABLE` / `BLOCKED_ITEMS` | "We can't sell {names} yet. Remove it to see your tax." (the item names from `blockedLines`, never ids; plural-aware) with one **Remove** button per item (removes the line from the cart; same keys as the checkout's blocked-items panel) |
| `UNAVAILABLE` / `ADDRESS_INCOMPLETE` | the saved address has no state or ZIP: "Add your state and ZIP code to this address to see your tax." with **Edit address** (`/account/addresses`) |
| `UNAVAILABLE` / `SERVICE_UNAVAILABLE`, `FACTS_UNAVAILABLE`, or the request itself failing | "We couldn't work out your tax right now. Try again." with **Retry** |
| `NEEDS_ADDRESS` / `NEEDS_ZIP`, `NO_ITEMS` | the ZIP prompt (only with no saved, chosen, detected or default place) |

The BFF no longer hides failures behind `NEEDS_ZIP`: a saved address that cannot be taxed answers `ADDRESS_INCOMPLETE`, and an address book that could not be read answers `SERVICE_UNAVAILABLE` (both counted in `tax_estimate_unavailable_total`). The row reads again when the cart changes and when the signed-in state or the address book changes. Nothing here weakens a gate: the cart's Proceed to checkout is unchanged, and Pay stays blocked in the checkout while there is no estimate.

Singular and plural counts ("1 item", "From 1 seller", "1 pickup") use the ICU plural messages of the i18n package in all five locales (the non-English ones are machine drafts, `needsReview`).

### Pickup

Pickup is taxed at the **delivery address** the shopper chose, exactly as orders does today: orders sends the address-book ship-to to tax-returns for every package whatever its delivery option, and tax-returns has no pickup-location concept (`TaxQuoteCommand` takes one ship-to). The estimate does the same, so estimate and order agree. Whether a pickup order should instead be taxed by the pickup location's jurisdiction is a **tax-rules question that needs the tax professional**: it would change orders' and tax-returns' behaviour (a ship-to per package), not only the estimate. Not changed here.

## Root cause of the "blank tax estimate"

The summary showed the label "Calculated when you place your order" (or nothing) on a normal checkout through any of these paths, all silent before this change:

1. **`shopper_checkout.go` early return**: when the delivery client was missing or the address ZIP was shorter than five characters, `summary()` returned before `priceTax` ran, leaving the status at UNAVAILABLE. Fixed: that branch now prices membership, coupon and tax too.
2. **Address without a state or ZIP code**: identity accepts an address with no `state`, so `priceTax` fell back silently, and orders (tax-returns requires a state) would have refused the order later. Now: reason `ADDRESS_INCOMPLETE`, Edit address, and Place order refused with a plain message.
3. **tax-returns errors swallowed**: any error from `TaxQuote` (timeout after 5 s, circuit breaker open, 401/403 when `tax:quote` is missing from the gateway client, 400/422 for a state or line tax-returns refuses, the service-token fetch failing) was logged at WARN and turned into the same empty row. Now: classified, counted in `tax_estimate_unavailable_total{reason}`, logged at ERROR with the cause, Retry state, Pay blocked.
4. **tax-returns client not configured** (`Returns == nil`): the same silent fallback. Now: reason `returns_not_configured`, loud.
5. **Estimate request differed from orders'** (`checkout_tax_engine.go`): the estimate sent the display fallback seller id ("afrotransact") where the cart service sends no seller org (orders then sends none), and no `sellerBehaviour`; tax-returns' owner check and fallback depend on both, so an estimate could differ from the order, or block a line orders would sell. Now identical (parity vectors). Lower probability than 1 to 4.
6. **Catalog facts unreadable** (enrichment swallowed the error, so product type and behaviour were empty): now a `FACTS_UNAVAILABLE` retry rather than a quote without them.
7. **Stale total next to a fresh tax**: the UI's total followed delivery choices immediately while the tax followed the last summary. Place order is now disabled while the summary is read again, and the total carries the lock of the summary it came from.

## Wording that needs the tax professional's sign-off

- The exempt-line reasons shown in the summary are tax-returns' own explanation per decision ("Groceries: no sales tax applies. Standard qualifying item."), unchanged. **Marked for tax-professional sign-off** together with the new sentence "This is the tax you will pay unless something in your order changes" and the shipping attribution note (open decision 5).
- Pickup taxed at the delivery address (above).

## Translations

The new strings (`co.taxZip.*`, `co.taxAddress.*`, `co.taxRetry.*`, `co.totalChanged.*`, `co.totalTrust`, `co.totalBeforeTax`, `co.taxBlocked`, `co.taxNoItems`, `co.taxExplain`, `co.taxExplainRate`) exist in en, fr, es, ar and yo. **fr, es, ar and yo are machine drafts and need native review** (yo least certain). The copy "calculated when you place your order" (`co.taxLater`, `co.taxLaterNote`, `co.taxNote`) was removed from all five.
