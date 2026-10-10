# offers-service

Seller offers on shared product pages, buy box selection, the "other sellers on this product" listing, and
coupons & promotions. Spring Boot 4.1 · Java 25 · PostgreSQL `offers` · local port **8102**. Epic E05 (S040–S043).

## What it owns

- **Offers.** Many sellers can offer the same catalog variant (ATIN), and AfroTransact Retail (first party, seller kind
  `PLATFORM`) is just another seller. An offer has price, optional strike-through list price, shipping charge
  (landed price = price + shipping), condition (`NEW`, `RENEWED`, `USED_*`), fulfilment, handling time and
  ship-from. All money is in minor units (`long`) with an ISO 4217 currency.
  - `AFROTRANSACT`: "Fulfilled by AfroTransact". Quantity is the inventory-service availability of the offer's
    `inventorySku` owned by the seller org, which covers FBA stock or Retail's own stock.
  - `SELLER`: "Ships from seller". Quantity is declared on the offer, and a ship-from location is required.
  - `AFROTRANSACT_DELIVERY` (S255): "AfroTransact Delivery from the seller's store". The seller packs, the nearest
    AfroTransact driver collects and delivers. Quantity is declared on the offer (seller stock, `PUT /v1/offers/stock`
    works), and `shipFrom.locationId` must name one of the seller's ship-from locations **with AfroTransact pickup
    enabled** — checked against seller-service `GET /v1/ship-from-locations/{id}` (offers' client-credentials token,
    scope `sellers:ship-from.read`) on create and on every update; city, region, country and ZIP are copied from the
    location (`422 offers.rule-violation` with `fieldErrors["shipFrom.locationId"]`, `503 offers.seller-unavailable`).
    Backward compatible: existing `SELLER` / `AFROTRANSACT` offers and requests are unchanged, `locationId` is optional
    for them. Public offers return `shipFromLocationId` so checkout can price the delivery by distance.
- **Attaching to the catalog.** An offer is created on an existing live variant by ATIN or GTIN through the
  `CatalogClient` port, whose HTTP adapter calls catalog's `GET /v1/public/variants/{atin}`. GTIN lookups use
  `GET /v1/identifiers/gtin/{code}` with offers' own client-credentials token (client `offers-service`, scope
  `catalog:read`), then the product page. The seller org always
  comes from the token's `org` claim, and `PermissionChecker.requireForOrg` guards every change. There is one open
  offer per seller, ATIN, condition and **channel** — Fulfilled by AfroTransact (`AFROTRANSACT`) or seller-fulfilled
  (`SELLER` / `AFROTRANSACT_DELIVERY`) — so a seller can sell the same product from their own store and from an
  AfroTransact fulfilment centre at the same time (S257, V3). Seller SKUs are unique among open offers of a channel, so
  the FBA offer may reuse the own-store SKU (`PUT /v1/offers/stock/{sku}` then sets only the seller-fulfilled offer).
- **FBA switch (S257).** `inventory.inbound.received` (FBA stock received at a fulfilment centre, topic
  `at.inventory-inbound.events`) creates the seller's Fulfilled-by-AfroTransact offer on each received variant that has
  none yet, copied from their own-store offer (price, list price, condition, seller SKU; no shipping charge; inventory
  SKU = the received SKU). Its quantity is inventory's availability, so it competes in the buy box as soon as the
  stock is sellable. Variants without any offer of the seller stay stranded (shown in inventory's FBA view).
- **Optimistic locking (S259).** Offer responses carry `ETag: "<version>"`. `PATCH /v1/offers/{id}` and
  `pause` / `resume` / `withdraw` take `If-Match` (or `version` in the PATCH body): a newer stored version answers
  `409 offers.version-mismatch`; two writers racing on the same version get `409 offers.concurrent-update`. Without
  `If-Match` the change applies to the current version (backward compatible).
  `catalog.product.created` also creates the creating seller's offers. They stay off sale until
  `catalog.product.approved`.
- **Buy box.** One winner per (variant ATIN, currency), with reasons. It is recomputed on every offer, price,
  stock, product-state, seller-enforcement and seller-performance change. Each winner change is written to the
  audit trail and `offers.buybox.changed` is published.
- **Coupons.** Coupons can be `PERCENT` (optional cap), `FIXED` or `FREE_SHIPPING`. Each one has:
  - a scope: sitewide, first order, category, seller, product (ATIN) or Fulfilled by AfroTransact;
  - a minimum spend on qualifying lines, total and per-customer limits, and a validity window;
  - optional clip-to-apply;
  - a funding source, `PLATFORM` or `SELLER`, recorded on every redemption together with a per-line allocation
    for settlement.

## Buy box rules (`DefaultBuyBoxPolicy`)

1. **Listed** (shown on the product page): the offer is `ACTIVE`, its product is live, it has stock, and its seller
   is neither `restricted` nor hidden by `seller.listings.hidden`.
2. **Eligible to win**: the offer is listed, in `NEW` condition, and the seller's defect rate is ≤ 2% and on-time
   rate is ≥ 80% (unknown values pass).
3. **Score** (1000 points by default; weights are under `at.offers.buy-box.*`):

   | Factor | Points |
   |---|---|
   | Landed price | 600 × lowest landed / this landed |
   | Fulfilled by AfroTransact | +100 (FBA only; AfroTransact Delivery from a seller's store is seller-fulfilled) |
   | Delivery speed | 100 × fastest / this. Days = handling + transit (2 days AfroTransact, 1 day AfroTransact Delivery, 4 days seller) |
   | Seller health | 150 × (0.4·rating/5 + 0.3·on-time + 0.3·(1 − defect/2%)). New sellers count as 4.0★ and 95% on time |
   | Stock depth | 50 × min(1, available / 10) |

4. **Ties** go to the lower landed price, then Fulfilled by AfroTransact, then faster delivery, then the older
   offer, then the offer id. The result never depends on input order.

Every decision carries per-factor `components` (points, maximum and a sentence) and winner `reasons` with stable
codes: `HIGHEST_SCORE`, `LOWEST_LANDED_PRICE`, `FULFILLED_BY_AFROTRANSACT`, `FASTEST_DELIVERY`, `TIE_BREAK` and
so on. Winner changes are stored in `buy_box_decision` with the trigger and the full evaluation.

## API

| Method | Path | Who |
|---|---|---|
| POST | `/v1/offers` | `offers:write` (own org; staff/service may name `sellerOrgId`) |
| GET | `/v1/offers`, `/v1/offers/{id}` | `offers:read` (own org; staff all) |
| PATCH | `/v1/offers/{id}` | `offers:write`, owner |
| POST | `/v1/offers/{id}/pause`, `/resume`, `/withdraw` | `offers:write`, owner |
| GET | `/v1/buy-boxes/{atin}/explanation?currency=` | `offers:read` (sellers: winner + own offers; staff: all) |
| GET | `/v1/buy-boxes/{atin}/history` | `offers:read`, staff |
| GET | `/v1/sellers/{orgId}/standing` | `offers:read` (own org; staff any) |
| PUT | `/v1/sellers/{orgId}/performance` | `offers:write`, staff / service (seller-health job) |
| GET | `/v1/public/atins/{atin}/offers?currency=` | public: `buyBox` + reasons + `otherOffers` sorted by landed price, with badges |
| GET | `/v1/public/buy-boxes?atins=A,B&currency=&by=variant\|product` | public, up to 50 ATINs. `by=product` takes product ATINs (search hits) and returns per product the best variant winner, `offerCount` and lowest prices |
| PUT | `/v1/offers/stock/{sellerSku}` | `offers:write`; the SKU must belong to an open seller-fulfilled offer of the caller's org (else 404 `offers.sku-not-found`); AfroTransact-fulfilled stock is owned by inventory (409 `offers.stock-managed-by-inventory`). Recomputes the buy box |
| GET | `/v1/public/products/{productAtin}/buy-boxes` | public: every variant of a product page |
| GET | `/v1/public/offers/{offerId}` | public: one offer plus `buyable`, for adding a non-buy-box offer to the cart |
| GET | `/v1/public/coupons/clippable?atin=` | public: clip-to-apply coupons covering the ATIN |
| POST | `/v1/coupons` | PLATFORM funding: `coupons:platform:write` (staff). SELLER funding: `coupons:write` (own org) |
| GET | `/v1/coupons`, `/v1/coupons/{id}` | coupon owner, or staff with `coupons:platform:write` |
| POST | `/v1/coupons/{id}/status` | coupon owner (`ACTIVE`, `PAUSED`, `EXPIRED`) |
| POST | `/v1/coupons/{code}/clip` | signed-in customer |
| POST | `/v1/coupons/validate` | any signed-in caller. Always 200, with `applicable` and a `rejection` code |
| POST | `/v1/coupons/redemptions` | SERVICE / STAFF principal (orders-service). **`Idempotency-Key` required** |
| POST | `/v1/coupons/redemptions/{id}/release` | SERVICE / STAFF principal. Idempotent |

Coupon validation reads prices, sellers, categories and fulfilment from the offers. Callers send only `offerId`
and quantity.

Redeem with the same key and body returns the original redemption (`200`, `Idempotent-Replayed: true`). The same
key with a different body returns `409 offers.idempotency-key-reused`. A coupon that does not apply returns
`422 offers.coupon-not-applicable` with a `reason`.

Errors are RFC 9457 problems with codes such as `offers.rule-violation` (with `fieldErrors`),
`offers.offer-exists`, `offers.product-not-found` and `offers.catalog-unavailable` (503).

Contract: [`contracts/openapi/offers.yaml`](../../../contracts/openapi/offers.yaml).
Interactive docs when running: `/swagger-ui.html`, `/docs` (Scalar), `/redoc`.

### Mapping to the gateway BFF

`PublicOffer` mirrors the BFF `Offer`:

- `offerId`
- `seller{id,name,kind,city}`
- `priceMinor`, `listPriceMinor`, `currency`
- `fulfilment` (`AFROTRANSACT|SELLER|AFROTRANSACT_DELIVERY`; badge `AFROTRANSACT_DELIVERY`) and `shipFromLocationId`
- `availableQty`
- `stockStatus` (`IN_STOCK|LOW_STOCK|OUT_OF_STOCK`, where low stock is 1–5 units)

`delivery` still comes from promise-service. `BuyBox.buyBox` and `BuyBox.otherOffers` map to `ProductDetail`.
`shipsFrom` and `soldBy` are on each offer. `offerCount` and `minPriceMinor` map to `ProductSummary`.

## Events

| Direction | Topic | Events |
|---|---|---|
| publishes (outbox) | `at.offers.events` | `offers.offer.created\|updated\|withdrawn` (key ATIN), `offers.buybox.changed` (key ATIN), `offers.coupon.created\|updated\|redeemed\|released` (key coupon id) |
| consumes | `at.product.events` | `catalog.product.created\|approved\|rejected\|paused\|resumed\|taken_down\|reinstated` (takedown hides every offer of the product until reinstated) |
| consumes | `at.inventory.events` | `inventory.availability.changed` (projection per sku × node; older versions ignored) |
| consumes | `at.inventory-inbound.events` | `inventory.inbound.received` (S257: FBA stock received → Fulfilled by AfroTransact offer) |
| consumes | `at.seller-compliance.events` | `seller.listings.hidden\|restored` |
| consumes | `at.seller-org.events` | `seller.org.approved\|updated` (display name) |

Consumers de-duplicate on `event-id` with the platform inbox. See
[`contracts/asyncapi/fragments/offers.yaml`](../../../contracts/asyncapi/fragments/offers.yaml).

## Configuration

| Env var | Purpose |
|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | PostgreSQL (`offers`) |
| `KAFKA_BOOTSTRAP`, `KAFKA_GROUP` | Kafka outbox relay and consumers (group `offers-service`) |
| `AUTH_ISSUER_URL` | identity service issuer (JWT validation) |
| `CATALOG_URL`, `CATALOG_TIMEOUT` | catalog-service base URL (local `http://localhost:8101`, cluster `http://catalog:8080`) |
| `SELLER_URL`, `SELLER_TIMEOUT` | seller-service base URL for pickup locations (local `http://localhost:8106`, cluster `http://seller:8080`) |
| `OFFERS_CLIENT_ID`, `OFFERS_CLIENT_SECRET` | client credentials for catalog GTIN lookups and seller pickup locations (`offers-service`, scopes `catalog:read sellers:ship-from.read`; `at.offers.oauth.*`) |
| `RETAIL_ORG_ID` | organisation id of AfroTransact Retail (seller kind `PLATFORM`) |
| `CONSUMERS_ENABLED` | turn the Kafka listeners off |
| `FGA_ENABLED`, `FGA_ENFORCE`, `FGA_API_URL`, `FGA_API_TOKEN`, `FGA_STORE_NAME` / `FGA_STORE_ID`, `FGA_MODEL_ID`, `FGA_TIMEOUT`, `FGA_CACHE_TTL` | OpenFGA relationship checks (`at.security.fga.*`, S022; see below) |
| `OTEL_EXPORTER_OTLP_ENDPOINT`, `TRACE_SAMPLING` | tracing |

Buy box weights, transit estimates, default handling time and the low-stock threshold are under `at.offers.*` in
`application.yml`.

Profiles:

- `local`: security off, debug logs.
- `dev`, `uat`: API reference pages on.
- `prod`: API reference pages off.

### Relationship checks (S022 pilot)

Offer use cases check the RBAC permission **and** an OpenFGA relation through `ResourceAccessChecker`
([guide](../../../docs/engineering/authorisation.md)): `can_view` / `can_edit` on `offer:<id>` (the owning
organisation is read from the offers database and sent as a contextual tuple), `can_manage_products` /
`can_view_products` on `organization:<id>` for create, seller stock and listing, and `can_view_all` on the platform for
listing every organisation's offers. A member of another organisation is refused (`auth.relationship-denied`, 403)
even with `offers:write` and a forged `org` claim; staff are limited to the markets they hold a grant in. With
`FGA_ENABLED=false` the checks fall back to the previous RBAC + `org` claim behaviour. Enforced in `dev`, shadow mode
(`FGA_ENFORCE=false`, denials logged as `FGA shadow:`) in `uat`, off in `prod`. Coupons, buy box and seller standing
still use RBAC only.

## Run and test

```bash
make up                                                    # Postgres, Kafka … from infra/local
cd java && ./mvnw -B -f services/offers/pom.xml verify     # checkstyle, unit, ArchUnit, Testcontainers ITs
./mvnw -f services/offers/pom.xml spring-boot:run          # http://localhost:8102/docs
```

Test coverage:

- **Unit tests:** buy box scoring, eligibility and ties; coupon rules and allocation; offer invariants.
- **Catalog adapter test:** runs against a loopback stub server.
- **ITs (PostgreSQL via `PostgresTestContainer`):**
  - offers and buy box flow, including the audit trail and the HTTP docs pages;
  - coupons: idempotent redeem and release, limits, clip;
  - Kafka listeners, invoked directly with outbox-shaped records;
  - FBA switch and optimistic locking over HTTP (`FbaInboundOfferIT`);
  - relationship checks against a real OpenFGA (`OfferRelationshipsIT`, local image `openfga/openfga:v1.10.0`, model
    `contracts/openfga/model.json`): forged `org` claim refused, market-scoped staff grants, revocation.

Deploy: `deploy/helm` (depends on `infra/helm/library/at-service`), one Argo CD Application per environment.
