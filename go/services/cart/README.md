# cart

Shopping carts for the storefront and app (via the BFF). Go 1.27.2 · Valkey/Redis + PostgreSQL · local port **8203**.

- **Packages per seller.** The cart view groups lines by seller and fulfilment (`AFROTRANSACT` first), with a
  subtotal per package — exactly how checkout splits shipments. `AFROTRANSACT_DELIVERY` lines (AfroTransact Delivery
  from the seller's store, S259) are further split per seller pickup location: the offer's `shipFromLocationId`
  becomes the package's `pickupLocationId`, one driver pickup per location.
- **Stock caps everywhere.** Add, the − / + stepper (`PUT /v1/cart/items/{atin}` with an exact quantity, 0 removes),
  move-to-cart and guest merge are all capped by live sellable stock from inventory (and 99 per line).
  Over-asking returns `409 cart.insufficient-stock` with "only N available".
- **Live view.** Each view re-reads live offers (catalog `/v1/public/variants/{atin}`, cached 30 s) and stock:
  `priceChanged` when the price moved since the line was added, `lowStock` at ≤ 5 ("Only N left"), `unavailable`
  (excluded from checkout) when the product is no longer live or the stock can't cover the quantity.
- **Offers.** Lines carry the chosen `offerId` (`POST /v1/cart/items {atin, offerId?, quantity}`); every view and
  add validates it against offers `GET /v1/public/offers/{id}` (cached 15 s, `OFFERS_URL`). An offer that is not
  buyable or belongs to another ATIN returns `409 cart.offer-not-buyable`; the offer's price drives `priceChanged`.
  Adding the same ATIN with a different offer replaces the offer and resets the added price.
- **Save for later**, move back to cart.
- **Guests and customers.** Guest carts live in Valkey with a sliding 30-day TTL, keyed by `X-Guest-Cart` (issued by
  the service). Customer carts are durable in Postgres. `POST /v1/cart/merge` folds the guest cart into the
  customer's cart on sign-in (quantities add up, capped by stock) and deletes the guest cart.
- **Concurrency.** Both stores use optimistic versions (WATCH/MULTI in Redis, `version` column in Postgres);
  the application retries a conflicting edit up to three times, so concurrent tabs never lose an update.
- **After checkout** the orders service calls `POST /v1/carts/{customerId}/ordered` (`cart:service`) to clear the
  ordered lines.

Contract: [`contracts/openapi/cart.yaml`](../../../contracts/openapi/cart.yaml).

## Configuration

`DB_URL`, `REDIS_URL`, `CATALOG_URL`, `INVENTORY_URL`, `OFFERS_URL`, `RETAIL_ORG_ID`, `GUEST_TTL_DAYS`, `AUTH_ENABLED`,
`AUTH_ISSUER_URL`, `DOCS_ENABLED`.

## Run and test

```bash
cd go/services/cart
GOTOOLCHAIN=go1.27.2 go test ./...   # domain rules, conflict retry, catalog client, Valkey + Postgres Testcontainers
```
