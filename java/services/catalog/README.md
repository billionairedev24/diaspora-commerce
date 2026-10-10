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

- **GTIN prefill (S031)**: when a seller types or scans a GTIN in the product builder,
  `GET /v1/identifiers/gtin/{code}/prefill` checks the GTIN-8/12/13/14 check digit (422 with a suggested fix), then
  asks the product-data providers behind the `ProductDataSource` port in order (`PRODUCT_DATA_ORDER`, default
  GS1 then Open Food Facts) until title, brand, net content and an image are known. Each field comes from the first
  provider that has it and `importedFrom` names that provider, so the form marks imported fields (they stay
  editable); `found=false` falls back to manual entry. Provider categories (Open Food Facts tags such as
  `en:long-grain-rices`, GS1 GPC segments) are mapped onto the seeded taxonomy (`DefaultCategoryHintPolicy`) and
  returned as up to three `categoryHints` with category id, path and product type. A GTIN that already has a product
  page returns `existingAtin` without calling any provider. Answers are cached per GTIN-14 in `product_data_lookup`
  (V5): hits for `PRODUCT_DATA_CACHE_TTL` (7 days), misses for `PRODUCT_DATA_MISS_TTL` (1 day). Provider errors and
  timeouts (3 s, one retry on a dropped connection) skip to the next provider and are never cached.
  - *Open Food Facts* (`adapter/out/productdata/OpenFoodFactsSource`): `GET /api/v2/product/{ean}.json`, open data,
    on by default, sends `OFF_USER_AGENT`.
  - *Verified by GS1* (`Gs1VerifiedSource`): `POST /grp/v3.2/gtins/verified` with the `APIKey` header. Off unless
    `GS1_ENABLED=true` and `GS1_API_KEY` is set.
- **Barcode labels (S032)**: catalog encodes Code 128 (code sets B/C), GS1-128 and EAN-13 itself
  (`domain/barcode`, written against ISO/IEC 15417 and the GS1 General Specifications; bar widths match
  `design/at-barcode.js`) and renders them as SVG (vector label), PNG (the bare symbol with quiet zones, no text, so
  no server fonts are needed), ZPL (203 dpi Zebra, `^BC` mode N with the same codewords, `^BE` for EAN-13, `^PQ`
  copies) or PDF (Apache PDFBox: one page per label, or Letter / A4 sheets with cut lines). Label kinds and default
  sizes: `UNIT` 2×1 in, `RETAIL` 2×1.25 in, `SHELF` 3×1 in, `CASE` 4×3 in, `PALLET` 4×6 in, `CUSTOM` 2×1 in.
  - Retail units carry the manufacturer EAN-13 / UPC-A (GS1-128 (01) for GTIN-8 and GTIN-14).
  - Case labels carry GS1-128 with (01) GTIN and optional (10) lot, (11) production date, (15) best before,
    (17) expiry and (21) serial. Pallet labels carry (00) SSCC and optional (02) content GTIN with (37) count.
    Predefined-length AIs go first, and FNC1 separates variable-length fields (never after the last one). The
    48-character limit and GS1 pairing rules are checked.
  - Unit labels (`GET /v1/labels/units/{atin}`): AfroTransact Retail stock with a vendor GTIN keeps the manufacturer
    barcode. Every other unit gets the stock owner's **AT-FN code** (`ATF` + 7 base-36, Code 128), issued once per
    owner organisation and variant ATIN in `unit_label` (V5), so units of different sellers never mix and platform
    stock without a GTIN is labelled at receiving.

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
| GET | `/v1/identifiers/gtin/{code}/prefill` (S031) | `catalog:write` or `catalog:read` |
| POST | `/v1/labels?format=svg\|png\|zpl\|pdf&layout=&paper=` (one label) | `catalog:write` or `catalog:read` |
| POST | `/v1/labels/batch?format=pdf\|zpl {labels[≤500], layout: LABEL\|SHEET, paper: LETTER\|A4}` | `catalog:write` or `catalog:read` |
| GET | `/v1/labels/units/{atin}?format=&copies=&orgId=` | `catalog:write` (sellers: own org, own or live pages; staff/services: any `orgId`) |

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

**Paged hide / restore (S216).** The decision (inbox, suppression row, `listing_enforcement_run` row) commits in one
short transaction; then the organisation's products are republished in pages of `at.catalog.listing-enforcement.page-size`
(500), one transaction per page, walking a keyset cursor on `(owner_org, id)` (V6 adds that index and
`product_variant (product_id)`, so each page is constant cost however big the catalog). Each page's outbox rows and
the run's cursor commit together, so a page is never published twice. Runs are tracked per enforcement event
(`listing_enforcement_run`: status `RUNNING|DONE|SUPERSEDED`, cursor, processed, pages): a redelivered event resumes
its run after the last committed page, `ListingEnforcementResumeJob` (every 60 s) resumes runs with no page for 2 min
(a pod died mid-run), and a newer decision for the same organisation supersedes a run still in progress (its own run
republishes everything). `LargeCatalogEnforcementIT` runs 3,250 products (7 pages) plus crash/redelivery, stalled-job
and supersede scenarios; it is not sized at the 50k of the acceptance criteria because pages are constant-size and
independent of catalog size (50k = 100 identical pages), and seeding 50k products plus outbox rows would add minutes
and hundreds of MB to every CI run. It logs the per-page time (≈ 340 ms per 500 products on a laptop container, so a
50k hide takes ≈ 35 s in many short transactions instead of one long one).

Listing reason codes (reports, flags, takedowns) match the trust-ops moderation codes: `PROHIBITED_ITEM`,
`UNAPPROVED_HEALTH_CLAIM`, `RESTRICTED_INGREDIENT`, `COUNTERFEIT`, `IMAGE_POLICY`, `MISLEADING_CONTENT`, `LABELLING`,
`SHIPPING_METHOD`, `DUPLICATE_LISTING`, `WRONG_CATEGORY`, `OTHER` (details / note required for `OTHER`).

Known gaps:
- The indexer and offers-service do not consume `catalog.product.taken_down|reinstated` yet, so a taken-down listing
  leaves catalog's public API at once but stays in search and the buy box until they do.
- The GS1 adapter follows the published Verified by GS1 v3.2 shape (language-tagged `productDescription`,
  `brandName`, `productImageUrl`, `netContent`, `gpcCategoryCode`). It is tested against WireMock only and still
  needs a check against a live GS1 US subscription key. GS1 GPC codes map to a department only.
- ZPL targets 203 dpi printers. The PNG is the symbol only, without text; use SVG, PDF or ZPL for full labels.
- Nothing calls the label endpoints yet (Seller Center inbound shipments, fulfilment receiving).

## Configuration

| Env var | Purpose |
|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | PostgreSQL |
| `KAFKA_BOOTSTRAP` | Kafka for the outbox relay |
| `AUTH_ISSUER_URL` | identity service issuer (JWT validation) |
| `RETAIL_ORG_ID` | organisation id of AfroTransact Retail |
| `LISTING_ENFORCEMENT_PAGE_SIZE` | products per hide / restore page (500) |
| `PRODUCT_DATA_ORDER`, `PRODUCT_DATA_CACHE_TTL`, `PRODUCT_DATA_MISS_TTL` | GTIN prefill provider order (`GS1,OPEN_FOOD_FACTS`) and cache (7d / 1d) |
| `GS1_ENABLED`, `GS1_API_KEY` (secret), `GS1_BASE_URL`, `GS1_TIMEOUT` | Verified by GS1 (off by default; needs a key) |
| `OFF_ENABLED`, `OFF_BASE_URL`, `OFF_USER_AGENT`, `OFF_TIMEOUT` | Open Food Facts (on by default, 3 s) |
| `OTEL_EXPORTER_OTLP_ENDPOINT`, `TRACE_SAMPLING` | tracing |

Profiles: `local` (security off, debug logs), `dev`, `uat`, `prod` (API reference pages off).

## Run and test

```bash
make up                                           # Postgres, Kafka … from infra/local
cd java && ./mvnw -B -f services/catalog/pom.xml verify     # checkstyle, unit, ArchUnit, Testcontainers ITs
./mvnw -f services/catalog/pom.xml spring-boot:run          # http://localhost:8101/docs
```

Libraries added for S031/S032: `spring-boot-starter-restclient` (provider calls), Apache PDFBox 3.0.8 (PDF labels);
test-only ZXing core 3.5.4 (every barcode test decodes the generated symbol, so it is known to scan) and WireMock
3.13.2 (providers). The ZPL output is pinned by snapshots in `src/test/resources/labels`.
Migrations: `V5__barcode_prefill_and_unit_labels.sql` (`product_data_lookup`, `unit_label`, `unit_label_seq`).

### Test notes

- **S244: `BarcodeIT.unknownGtinFallsBackToManualEntryAndTheMissIsCachedButErrorsAreNot` was flaky (timing).**
  The cause was not WireMock state, the cache TTL or connection reuse. JUnit's method order puts that test first in
  `BarcodeIT`, and `BarcodeIT` is the first IT failsafe runs, so its first GS1 call is the JVM's first HTTP exchange
  with WireMock. The JDK `HttpClient`, Spring's `RestClient` and WireMock's Jetty and request-matching classes are
  still loading then (and `verify` adds the JaCoCo agent, which instruments every class as it loads). The test also
  set 1 s provider timeouts. On a busy runner that first call took more than a second, so Spring's read timeout
  cancelled it (logged as `GS1 unavailable: … Request cancelled` or `… : get`). The lookup then counted as a provider
  error, and errors are never cached (correct behaviour). So the second `prefill` was not served from the cache, and
  `cached()` (line 208) was false. Reproduced 4/4 with JaCoCo and 8 CPU-bound processes: the test took 4.7 to 5.9 s,
  against 0.07 s for the same flow once warm.
  **Fix:** the IT's provider timeouts are now 30 s. The test checks cache semantics, not latency, and no stub in it
  is delayed. Timeout handling keeps its deterministic coverage in `ProductDataSourcesTest` (2 s delay against an
  800 ms timeout). No retries or reordering were added. Verified with 20 consecutive runs of the class under
  JaCoCo and extra CPU load.
  `ProductDataSourcesTest` (a unit test, run by surefire) uses 800 ms timeouts for its non-delayed calls too, so
  it carries the same, smaller cold-start risk.

Deploy: `deploy/helm` (depends on `infra/helm/library/at-service`), one Argo CD Application per environment.
