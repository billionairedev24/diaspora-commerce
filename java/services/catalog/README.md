# catalog-service

Products, variants, the category tree, product-type schemas and new-category requests.
Spring Boot 4.1 · Java 25 · PostgreSQL · local port **8101**.

## What it owns

- **Products** with an AfroTransact Identification Number (ATIN, `AT` + 8 base-36 chars) per product and per variant.
  One product page per manufacturer barcode: creating a product with a GTIN that already exists returns `409`
  with the existing ATIN, so the seller adds an offer instead of a duplicate page.
- **Listing owner** is never a form field: sellers list for their organisation; staff with `catalog:retail`
  list for AfroTransact Retail (first-party).
- **Category tree** (department › category › product type) and **product-type schemas**: required details
  (with allowed values), variation themes (max two dimensions) and the identifier rule (GTIN, or ISBN-13 for books).
  Seeded from the design by `tools/seed/catalog_taxonomy.py`.
- **Category requests**: sellers can request a missing category and build products against it right away; those
  products wait in `PENDING_CATEGORY_APPROVAL` and move to `IN_REVIEW` when staff approve the request.
- **Moderation**: `IN_REVIEW → LIVE | REJECTED`, owners can pause/resume live listings.
- **Live listing reports and takedowns (S228)**: signed-in shoppers report a live listing (one report per shopper
  and listing), staff and service clients flag live or paused listings; each new report or flag is stored in
  `product_flag` and published as `catalog.product.flagged`, which trust-ops queues for review. Trust and Safety
  (`catalog:moderate`, e.g. the `trust-ops-service` client) takes a live or paused listing down with a reason code:
  status `TAKEN_DOWN` (hidden from `/v1/public/**`, the owner cannot resume it, note `[CODE] Taken down: note`),
  recorded in `product_takedown` and published as `catalog.product.taken_down` (with `reasonCode`). Restoring returns
  the listing to its previous status (`LIVE` or `PAUSED`) and publishes `catalog.product.reinstated`. Both calls are
  idempotent.

Validation of a submission (`DefaultProductDraftPolicy`) returns every failing field at once
(`fieldErrors` in the RFC 9457 problem): title 10–200 chars, required details, 1–10 JPG/PNG/WebP images ≤ 8 MB
with a main image ≥ 1000×1000, variation values from the theme, unique SKUs, valid GTIN check digit (with a
suggested correction) or exemption, ISBN-13 for books, price > 0.

## API

| Method | Path | Permission |
|---|---|---|
| GET | `/v1/categories`, `/v1/product-types`, `/v1/product-types/{code}` | public |
| POST | `/v1/products` | `catalog:write` |
| GET | `/v1/products`, `/v1/products/{id}`, `/v1/products/atin/{atin}` | `catalog:write` (own org; staff all) |
| POST | `/v1/products/{id}/approve`, `/reject` | `catalog:moderate` |
| POST | `/v1/products/{id}/pause`, `/resume` | `catalog:write` (owner) |
| POST/GET | `/v1/category-requests`, `/v1/category-requests/mine` | `catalog:write` |
| GET/POST | `/v1/category-requests?status=`, `/{id}/approve`, `/{id}/reject` | `catalog:taxonomy` |
| POST | `/v1/products/{id}/flags {reasonCode, details, riskLevel}` | `catalog:moderate` (staff = `STAFF`, principal type `SERVICE` = `AUTOMATED`) |
| POST | `/v1/products/{id}/takedown {reasonCode, note}`, `/v1/products/{id}/restore {note}` | `catalog:moderate` |
| POST | `/v1/public/products/{atin}/reports {reasonCode, details}` | any signed-in user (201 new, 200 repeated report) |
| GET | `/v1/identifiers/gtin/{code}?isbn=` | `catalog:read` (service tokens such as offers-service, S224) or `catalog:write` |

Contract: [`contracts/openapi/catalog.yaml`](../../../contracts/openapi/catalog.yaml).
Interactive docs when running: `/swagger-ui.html`, `/docs` (Scalar), `/redoc`.

## Events (transactional outbox → `at.product.events`, `at.category-request.events`)

`catalog.product.created|approved|rejected|paused|resumed|hidden|restored|flagged|taken_down|reinstated`,
`catalog.category-request.created|approved|rejected` —
see [`contracts/asyncapi/fragments/catalog.yaml`](../../../contracts/asyncapi/fragments/catalog.yaml). Product payloads
carry `sellerHidden` (true while the owner's listings are hidden).

Consumed (group `catalog-service`, inbox-deduplicated): `seller.listings.hidden|restored` on
`at.seller-compliance.events` record the organisation in `org_listing_suppression` (V3), hide its products from
`/v1/public/**` and publish `catalog.product.hidden|restored` for each of its products so search follows. This also
covers Trust and Safety suspensions (reason `SUSPENDED` / `REINSTATED` from seller-service).

Listing reason codes (reports, flags, takedowns) match the trust-ops moderation codes: `PROHIBITED_ITEM`,
`UNAPPROVED_HEALTH_CLAIM`, `RESTRICTED_INGREDIENT`, `COUNTERFEIT`, `IMAGE_POLICY`, `MISLEADING_CONTENT`, `LABELLING`,
`SHIPPING_METHOD`, `DUPLICATE_LISTING`, `WRONG_CATEGORY`, `OTHER` (details / note required for `OTHER`).

Known gap: the indexer and offers-service do not consume `catalog.product.taken_down|reinstated` yet, so a taken-down
listing leaves catalog's public API at once but stays in search and the buy box until they do.

## Configuration

| Env var | Purpose |
|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | PostgreSQL |
| `KAFKA_BOOTSTRAP` | Kafka for the outbox relay |
| `AUTH_ISSUER_URI` | identity service issuer (JWT validation) |
| `RETAIL_ORG_ID` | organisation id of AfroTransact Retail |
| `OTEL_EXPORTER_OTLP_ENDPOINT`, `TRACE_SAMPLING` | tracing |

Profiles: `local` (security off, debug logs), `dev`, `uat`, `prod` (API reference pages off).

## Run and test

```bash
make up                                           # Postgres, Kafka … from infra/local
cd java && ./mvnw -B -f services/catalog/pom.xml verify     # checkstyle, unit, ArchUnit, Testcontainers ITs
./mvnw -f services/catalog/pom.xml spring-boot:run          # http://localhost:8101/docs
```

Deploy: `deploy/helm` (depends on `infra/helm/library/at-service`), one Argo CD Application per environment.
