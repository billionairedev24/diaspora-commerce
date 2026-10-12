# Checkout estimated sales tax

For **which data points decide taxability and rates**, and how **shipping** relates to tax, see [shipping-and-tax-data.md](./shipping-and-tax-data.md).

Shoppers see **estimated tax** on the checkout summary before they place an order. The amount is informational; the orders checkout saga **re-quotes** tax at place time and persists line-level tax on the order.

## Flow

1. Storefront calls `GET /v1/checkout/summary?addressId=&packageDelivery=&couponCode=` (comma-separated `packageId:optionKey` per package; optional coupon code for a **validate-only** quote).
2. Gateway BFF enriches the cart (delivery options, AfroTransact Delivery, promises), validates the coupon with **offers** `POST /v1/coupons/validate` (shopper bearer), then calls **tax-returns** `POST /v1/tax/quotes` with the **gateway-bff** client-credentials token (`tax:quote` scope)—not the shopper JWT.
3. BFF maps the quote to `discountMinor`, `shippingDiscountMinor`, `coupon` (funding + per-seller shares), `taxMinor`, `taxRateLabel`, and `totalMinor` with shipping and line discounts aligned to the same delivery selections.

When the shopper changes address, delivery option, or promo code, the UI refetches the summary so shipping, coupon discounts, tax, and the Payment Element amount stay aligned. **Redemption** still happens only when orders places the checkout (`couponCode` on `POST /v1/checkout/orders`); failed payments release the redemption idempotently.

## How tax-returns calculates tax

Implementation: `DefaultTaxRules` in the tax-returns service.

- **Ship-to**: US state + postal code (and city when used for local rates).
- **Marketplace facilitator**: Tax is collected only in states where the platform is configured as facilitator; otherwise lines are `NOT_COLLECTED`.
- **Per line**: Category from product type / ATIN mapping; combined rate = state rate + local (ZIP) rate for that category.
- **Taxable base**: `(unit price × quantity − discount)` plus the line’s **shipping** when the state taxes shipping (package shipping is attached to the **first line** of each package, matching orders at place time).
- **Rounding**: `tax = taxable × rate`, rounded **half up to one minor unit per line**; checkout total tax is the sum of line taxes.

## Place order vs estimate

Orders uses the same quote shape in `CheckoutTaxImpl` / `HttpTaxQuoteClient` with the **orders-service** token (`tax:quote`). Coupons are **redeemed** in the checkout saga after validate (`offers` redemption, `funding` PLATFORM vs SELLER on the redemption for settlement). Minor differences (inventory, timing) can make the final tax differ slightly from the estimate; the UI copy states that the final amount is confirmed at checkout.

## Local development

- With mocks (`web` MSW), Texas addresses approximate **8.25%** on non-grocery merchandise plus shipping (grocery `productType`s are exempt in the mock, matching TX rules).
- With the full stack, ensure identity grants `tax:quote` to `gateway-bff` and tax-returns is reachable on port **8111** (see `infra/local/PORTS.md`).

## Updating rate data (staff)

Checkout estimates use whatever is in **tax-returns** (`tax_state_rule` + `tax_local_rate`). To load or refresh rates without a migration:

1. **Staff Console** → **Fees & commissions** → **Tax rates** → **Import tax rates (CSV)** (needs `tax:configure`), or `POST /v1/staff/tax-rates/import` with `{ "csv": "…" }`.
2. CSV header: `kind,country,state,name,zipPrefix,generalRate,groceryRate,preparedFoodRate,shippingTaxable,facilitator,localRate,appliesToGrocery` with `kind=STATE|LOCAL`.
3. Ready-made test imports: [`tools/tax/test-import-texas.csv`](../../tools/tax/test-import-texas.csv) and [`tools/tax/test-import-pennsylvania.csv`](../../tools/tax/test-import-pennsylvania.csv) (notes in [`tools/tax/test-import-README.md`](../../tools/tax/test-import-README.md)). Minimal template: [`tools/tax/us-rates-template.csv`](../../tools/tax/us-rates-template.csv).

After import, refetch checkout summary (change address or delivery) so the storefront picks up new quotes.
