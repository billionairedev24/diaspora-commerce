# Checkout estimated sales tax

For **which data points decide taxability and rates**, and how **shipping** relates to tax, see [shipping-and-tax-data.md](./shipping-and-tax-data.md). The design and the rules TX-01 to TX-12 are in [category-tax-design.md](./category-tax-design.md) (sections 5 and 7); this page is how checkout (gateway-bff, orders, storefront, customer app) applies them.

Shoppers see **estimated tax** on the checkout summary before they place an order. The amount is informational; the orders checkout saga **re-quotes** tax at place time, **persists the decision each line was taxed with**, and charges that.

> **Open legal decisions that gate production** (nothing here may be switched on for real customers before the Texas tax professional signs off):
> **Open decision 5 (TX-07): how the delivery charge is attributed in a mixed basket.** The build allocates it over the package's lines proportionally to item value (default) and the summary says so (`shippingTaxAllocation.approved = false`). The adviser must approve the method, and it interacts with the still-open final delivery price. Rollback: `FIRST_LINE` (see settings). Also see the "not done yet" column of the design's section 5 (ZIP-based local rates, tax holidays, bundles, platform fee classification, gift cards).

## Never guess

- **No default rate, no interim table.** The per-state table (`StateRateTaxCalculator`, `at.orders.tax.rates`, `fallback-to-table`, the `TABLE` provider) is deleted. Orders and the BFF take every rate, outcome and code from tax-returns' stored decision; when tax-returns is down, the saga's tax step fails and is retried (the estimate stays empty in the BFF). Nothing can silently supply a rate in any profile.
- **A line whose decision is not definite is blocked.** Definite means `decisionStatus = RESOLVED` **and** an outcome of `EXEMPT` or `TAXABLE`. Anything else (`MISSING_FACTS`, `BLOCKED`, `NEEDS_APPROVAL`, `REFINE_CODE`, `CONFLICT_OPEN`, no status at all, no answer for the line) is blocked.
  - **Summary:** the BFF lists the line in `blockedLines` (`lineId`, `offerId`, `title`, `packageId`, `action: REMOVE`, plain `message`), sets `canPlaceOrder = false`, and gives it no tax. The rest of the cart is still estimated.
  - **Place order:** orders refuses with `orders.tax-line-blocked` (422, RFC 9457, stable code; the BFF maps it to `CHECKOUT_ITEM_BLOCKED`). The detail names the items in plain words ("We can't sell Gift card yet. We're still checking its details. Remove it from your cart to continue, or try again later."): no ids, no tax terms.
  - **Shopper:** the storefront and the customer app show the item with a **Remove** action (it leaves this order and stays in the cart); Place order is disabled until none remains. The storefront words the message itself per locale (en, fr, es, ar, yo); the server's text is English.

## Flow

1. Storefront calls `GET /v1/checkout/summary?addressId=&packageDelivery=&couponCode=` (comma-separated `packageId:optionKey` per package; optional coupon code for a **validate-only** quote).
2. Gateway BFF enriches the cart (delivery options, AfroTransact Delivery, promises), validates the coupon with **offers**, then calls **tax-returns** `POST /v1/tax/quotes` with the **gateway-bff** client-credentials token (`tax:quote`), not the shopper JWT. Each quote line carries the **variant ATIN** and the **seller org**; tax-returns reads the stored decision for that variant and seller, which already combines the product facts with the seller's behaviour facts (sold heated, utensils, bakery) held in the seller's listing. The quote line has no behaviour fields: behaviour is never re-sent or re-derived at checkout.
3. The BFF maps the quote to `taxMinor`, `taxRateLabel`, `taxJurisdictionLabel` ("Houston, TX", from the delivery address), `taxLines` (per line: `TAXABLE`, `EXEMPT` or `BLOCKED`, its tax and its delivery share), `blockedLines`, `canPlaceOrder`, `shippingTaxAllocation`, plus `discountMinor`, `shippingDiscountMinor`, `coupon` and `totalMinor` aligned with the same delivery selections.
4. Orders' `CheckoutTaxImpl` quotes the same way when the order is placed (orders-service token, `tax:quote`), blocks undecided lines, and writes the **decision snapshot** on each order line.

When the shopper changes address, delivery option, or promo code, the UI refetches the summary. **Redemption** of a coupon still happens only when orders places the checkout; failed payments release it idempotently.

## What the summary shows

- Row **"Estimated tax"** with "Estimated for Houston, TX" under it (`taxJurisdictionLabel`) and the explanation that the final tax is confirmed when the order is placed. No ids.
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
