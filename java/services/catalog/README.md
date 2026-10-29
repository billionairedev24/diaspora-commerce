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

- **One-step product form (S307)**:
  - *Product types* carry `traits` (food, perishable, regulated, preorder) and attribute metadata: kind
    `TEXT|MULTILINE|ENUM|NUMBER`, `units` (e.g. shelf life in days or months; "12" is stored as "12 days"),
    `section` `DETAILS|FOOD|REGULATED` and `help`. Seller Center builds its conditional sections from them.
  - *Listings* carry `listing` details: description (≤ 5,000), condition, keywords (≤ 20), fulfilment
    `SELLER|AFROTRANSACT` (AfroTransact needs package dimensions and weight), handling days (1–30) and ship-from.
  - *Variants* carry a compare-at price (it must be above the price) and their own images.
  - A product without variations is one variant with an empty theme.
  - `PUT /v1/products/{id}` edits a listing:
    - a rejected listing goes back to review (`catalog.product.resubmitted`, which trust-ops queues again);
    - a live listing keeps its variants (set stock to 0 instead) and its category;
    - changed price, condition, fulfilment or ship-from publishes `catalog.product.terms_changed`, so offers-service
      revises the seller's offers.
  - *Drafts* (`/v1/product-drafts`, table `product_draft`) store the form's input as JSON (≤ 256 KB). They are
    versioned for autosave (409 on a stale version) and there is one per product for edits. Submitting with
    `draftId` deletes the draft in the same transaction.
- **Taxonomy administration (S325)** (`/v1/taxonomy/**`, guide: [docs/engineering/taxonomy.md](../../../docs/engineering/taxonomy.md)):
  - Staff create, rename, move, reorder, retire and restore departments, categories and product types, and edit a
    product type's schema (label, identifier rule, traits, attributes with kind, units, choices, section, required and
    `applies`). `V11` adds `category.code|version|retired_*`, `product_type.version` and the append-only
    `taxonomy_audit`; `V2` stays the initial data only.
  - Every change needs a reason (3 to 1,000 characters), writes an audit row and publishes `catalog.taxonomy.changed`.
    Nodes and schemas carry a `version`: edits send `If-Match` (412 `catalog.version-mismatch`, 428
    `catalog.if-match-required`); a reorder sends the whole sibling list.
  - Retire is soft and refused (409 `catalog.taxonomy-in-use`) while any non-rejected product is on the node or under
    it. Retired nodes leave `/v1/categories` (the sellers' tree) and are refused for new products, but stay on existing
    ones (`?includeRetired=true`). Names are unique among siblings, codes are unique, the tree has three levels.
  - Attributes with `applies: false` are kept but dropped from the public schema, the validators and the upload
    template. The sales-tax question (`tax_class`) cannot be reworded or removed here (tax-returns owns its choices).
  - Approving a category request goes through the same service: it creates the category (under a department) and the
    leaf with an existing or a new schema, in one transaction, and `approval-preview` returns the plan without writing.
  - `TaxonomyAdminIT` (HTTP, PostgreSQL; `AT_IT_POSTGRES_URL` runs it without Docker) covers permissions, reasons, audit,
    events, versions, retire with products, reorder, attribute rules and the request approval.
- **Bulk upload (S308)** (`/v1/imports`; the design is in [ADR 0003](../../../docs/adr/0003-bulk-product-upload-in-catalog.md)):
  - **Templates.** `GET /v1/imports/template?productType=|categoryId=&format=xlsx|csv` returns a generic or
    per-product-type template, generated with Apache POI.
    - The `Products` sheet's header row marks required columns with `*` and carries a note on each header.
    - Columns:
      - `Parent SKU`, `SKU`, `Category`, `Title` and `Brand`;
      - `Description`, `Condition`, `Image URLs` and `Search keywords`;
      - `Price (USD)`, `Compare-at price (USD)`, `Quantity`, the barcode (or `ISBN-13`) and `No barcode`;
      - the type's `Variation` and one column per dimension;
      - one column per attribute, plus a `… unit` column for numbers with units. The generic template has
        `Options` and `Attributes` as `key: value; …` instead;
      - `Fulfilment`, `Handling time (days)`, the package length, width, height and weight, and
        `Pre-order eligible`.
    - Dropdowns come from a hidden `Lists` sheet. Whole and decimal numbers are validated.
    - The workbook also has an `Instructions` sheet and an `Example` sheet.
    - The CSV version writes the instructions as `#` lines above the header.
    - Headers are matched by label or key, so columns can be reordered.
  - **Limits.** 10 MB, 5,000 rows, 200 columns, 5,000 characters per cell, 10 images and 200 variants per product.
    The streaming reader enforces zip-bomb limits. `.xls` is refused.
  - **Upload.** `POST /v1/imports` (multipart `file`, `mode=CREATE|UPDATE`, optional `productType`) validates
    every row:
    - required fields, enums, numbers and units, GTIN check digits;
    - duplicate SKUs in the file and against the seller's listings (Create), unknown SKUs (Update);
    - https image links and compare-at prices.

    It answers with the counts. `GET /v1/imports/{id}/rows?status=&outcome=` returns the preview: cells and
    per-column messages. `GET /v1/imports/{id}/report?format=` returns the uploaded columns plus `Row`,
    `Row status`, `Result` and `Errors`, with formula-like cells prefixed `'`.
  - **Import.** `POST /v1/imports/{id}/start {validOnly}` returns 409 `catalog.import-has-errors` unless
    `validOnly` is set. It runs the background job:
    - one product group per step, images copied through media (`POST /v1/assets/import` with the
      `catalog-service` client token), each product in a savepoint;
    - idempotent per `(import, SKU)` and resumed by `ImportResumeJob` when stalled (2 min);
    - `catalog.import.completed` is published on `at.product-import.events`, and notifications emails and pushes
      the uploader for imports of 100 rows or more.

    `GET /v1/imports` lists the upload history.

Validation of a submission (`DefaultProductDraftPolicy`) returns every failing field at once
(`fieldErrors` in the RFC 9457 problem): title 10–200 chars, required details, 1–10 JPG/PNG/WebP images ≤ 8 MB
with a main image ≥ 1000×1000, variation values from the theme, unique SKUs, valid GTIN check digit (with a
suggested correction) or exemption, ISBN-13 for books, price > 0.

- **Verified-purchase product reviews (S324).** A customer with a delivered order line for the ATIN (asked from
  orders-service through the `PurchaseVerifier` port, `HttpPurchaseVerifier`) rates it 1–5 stars with a title, text and up
  to 5 photos: one review per customer and product, editable for 30 days, deletable. `DefaultReviewContentPolicy`
  (profanity, links, e-mail, phone) publishes a clean review or holds it `PENDING` for trust-ops; staff approve, reject
  with a reason or remove (`/v1/product-reviews/{id}/approve|reject|remove`); customers and sellers report; the seller
  replies once (`/v1/seller/reviews`). `product_rating` (average, count, 1–5 breakdown) is rewritten in the transaction of
  every status change and published as `catalog.product.rating_changed`. Public: `/v1/public/products/{atin}/rating|reviews`.
  See [docs/engineering/product-reviews.md](../../../docs/engineering/product-reviews.md).

## API

| Method | Path | Permission |
|---|---|---|
| GET | `/v1/categories` (`?includeRetired=true` also lists retired nodes), `/v1/product-types`, `/v1/product-types/{code}` | public |
| POST | `/v1/products` | `catalog:write` |
| PUT | `/v1/products/{id}` (S307) | `catalog:write` (owner) |
| GET/POST/GET/PUT/DELETE | `/v1/product-drafts`, `/v1/product-drafts/{id}` (S307) | `catalog:write` (own org) |
| GET | `/v1/imports/template?productType=&categoryId=&format=` (S308) | `catalog:write` |
| POST/GET | `/v1/imports` (multipart upload / history), `/v1/imports/{id}`, `/{id}/rows`, `/{id}/report`, POST `/{id}/start` (S308) | `catalog:write` (own org) |
| GET | `/v1/products`, `/v1/products/{id}`, `/v1/products/atin/{atin}` | `catalog:write` (own org; staff all) |
| POST | `/v1/products/{id}/approve`, `/reject` | `catalog:moderate` |
| POST | `/v1/products/{id}/pause`, `/resume` | `catalog:write` (owner) |
| POST/GET | `/v1/category-requests`, `/v1/category-requests/mine` | `catalog:write` |
| GET/POST | `/v1/category-requests?status=` (with the products waiting), `/{id}` (detail and suggested parent), `/{id}/approval-preview`, `/{id}/approve` (creates the category and the product type), `/{id}/reject` | `catalog:taxonomy` |
| GET/POST/PATCH | `/v1/taxonomy/tree`, `/v1/taxonomy/nodes` (create), `PATCH /nodes/{id}` (rename), `POST /nodes/{id}/move\|retire\|restore`, `POST /v1/taxonomy/reorder`, `/v1/taxonomy/product-types[/{code}]` (create, edit), `/v1/taxonomy/audit` (S325) | `catalog:taxonomy` (every change: reason, audit row, `If-Match`) |
| POST | `/v1/products/{id}/flags {reasonCode, details, riskLevel}` | `catalog:moderate` (staff = `STAFF`, principal type `SERVICE` = `AUTOMATED`) |
| POST | `/v1/products/{id}/takedown {reasonCode, note}`, `/v1/products/{id}/restore {note}` | `catalog:moderate` |
| POST | `/v1/public/products/{atin}/reports {reasonCode, details}` | any signed-in user (201 new, 200 repeated report) |
| GET | `/v1/identifiers/gtin/{code}?isbn=` | `catalog:read` (service tokens such as offers-service, S224) or `catalog:write` |
| GET | `/v1/identifiers/gtin/{code}/prefill` (S031) | `catalog:write` or `catalog:read` |
| POST | `/v1/labels?format=svg\|png\|zpl\|pdf&layout=&paper=` (one label) | `catalog:write` or `catalog:read` |
| POST | `/v1/labels/batch?format=pdf\|zpl {labels[≤500], layout: LABEL\|SHEET, paper: LETTER\|A4}` | `catalog:write` or `catalog:read` |
| GET | `/v1/labels/units/{atin}?format=&copies=&orgId=` | `catalog:write` (sellers: own org, own or live pages; staff/services: any `orgId`); service clients and staff also with `catalog:read` (inventory-service). The AT-FN code is returned as data in `X-Unit-Label-Code` (absent for a manufacturer barcode) |
| GET | `/v1/variants/{atin}` (S226) | `catalog:read` (service clients such as retail-service, staff) or `catalog:write` (members of the owning org): the variant and its product in **any** status (draft, in review, paused, rejected, taken down, hidden), `{product, variant}`; 404 `catalog.variant-not-found`. `GET /v1/public/variants/{atin}` stays live-only |

### Relationship checks (S248, OpenFGA)

Product reads and owner actions use `ResourceAccessChecker` (`CatalogRelations`, see
`docs/engineering/authorisation.md`): the RBAC permission **and** a relation in OpenFGA, with the product's owner read
from catalog's database and passed as a contextual `product#org` tuple, so a forged `org` claim never reaches another
organisation's products (403 `auth.relationship-denied`).

| Use case | Relation |
|---|---|
| `GET /v1/products/{id}`, `/atin/{atin}`, `/v1/variants/{atin}` | `can_view` on `product:<id>` (members, staff of the org's market, first-party services; delegated viewers) |
| `POST /v1/products/{id}/pause`, `/resume` | `can_edit` on `product:<id>` (catalog role and above, catalog staff of the market) |
| `POST /v1/products` | `can_manage_products` on `organization:<owner>` (the org must sell products) |
| `GET /v1/products?orgId=` | `can_view_products` on the organisation; without `orgId` (staff, every org) `can_view_all` on `platform:afrotransact` |

Moderation, takedowns, flags and the taxonomy stay RBAC-only (`catalog:moderate`, `catalog:taxonomy`): they are
platform staff actions (catalog managers, Trust and Safety, the trust-ops client) and the model has no market-scoped
Trust and Safety relation on products yet. AfroTransact Retail's staff list through `platform.retail_buyer`, which
authz-sync maps to `catalog` on Retail's organisation (Retail's organisation needs its `sells_products` flag in the
store). Profiles: `local` off, `dev` enforced, `uat` shadow (`FGA_ENFORCE=false`, review `FGA shadow:` logs), `prod`
off until uat enforces. Secret `FGA_API_TOKEN`. `ProductRelationshipsIT` runs against the local
`openfga/openfga:v1.10.0` image.

Contract: [`contracts/openapi/catalog.yaml`](../../../contracts/openapi/catalog.yaml).
Interactive docs when running: `/swagger-ui.html`, `/docs` (Scalar), `/redoc`.

## Events (transactional outbox → `at.product.events`, `at.category-request.events`)

`catalog.product.created|updated|terms_changed|resubmitted|approved|rejected|paused|resumed|hidden|restored|flagged|taken_down|reinstated`,
`catalog.import.completed` (on `at.product-import.events`),
`catalog.product.rating_changed` and `catalog.review.submitted|published|updated|rejected|removed|deleted|reported|replied` (S324; the review events are on `at.product-review.events`),
`catalog.category-request.created|approved|rejected`, `catalog.taxonomy.changed` (S325, on `at.taxonomy.events`) —
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
| `FGA_ENABLED`, `FGA_ENFORCE`, `FGA_API_URL`, `FGA_STORE_ID`, `FGA_STORE_NAME`, `FGA_MODEL_ID`, `FGA_API_TOKEN` (secret), `FGA_TIMEOUT`, `FGA_CACHE_TTL` | OpenFGA relationship checks (S248) |
| `LISTING_ENFORCEMENT_PAGE_SIZE` | products per hide / restore page (500) |
| `PRODUCT_DATA_ORDER`, `PRODUCT_DATA_CACHE_TTL`, `PRODUCT_DATA_MISS_TTL` | GTIN prefill provider order (`GS1,OPEN_FOOD_FACTS`) and cache (7d / 1d) |
| `GS1_ENABLED`, `GS1_API_KEY` (secret), `GS1_BASE_URL`, `GS1_TIMEOUT` | Verified by GS1 (off by default; needs a key) |
| `OFF_ENABLED`, `OFF_BASE_URL`, `OFF_USER_AGENT`, `OFF_TIMEOUT` | Open Food Facts (on by default, 3 s) |
| `MEDIA_URL`, `MEDIA_TIMEOUT` | media-service for bulk-upload images (S308) |
| `CATALOG_CLIENT_ID`, `CATALOG_CLIENT_SECRET` (secret) | catalog's client-credentials token (`media:import`) |
| `IMPORT_NOTIFY_MIN_ROWS` | rows from which a finished import notifies the uploader (100) |
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
Apache POI 5.4.1 (`poi-ooxml`) for bulk upload templates and parsing (S308, see ADR 0003).
Migrations: `V8__one_step_form_and_bulk_upload.sql` (product-type traits and attribute metadata, listing details,
variant compare-at and images, `product_draft`, `product_import`, `product_import_row`);
`V5__barcode_prefill_and_unit_labels.sql` (`product_data_lookup`, `unit_label`, `unit_label_seq`).

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
