# retail-service

AfroTransact Retail (1P) purchasing: the product master for first-party items, suppliers, purchase orders with
approval thresholds and FX, inbound shipments with advance ship notices, goods receipt into inventory lots, landed
cost with GL journal entries, and the PO · receipt · supplier-invoice three-way match.
Spring Boot 4.1 · Java 25 · PostgreSQL · local port **8107**.

It implements the design's nine-step workflow (`design/InventoryHub.dc.html`, `design/at-inventory-hub.js`):
product → PO → approval → shipment/ASN → transit & customs → receiving → validation → lots & GL → match and pay.

## What it owns

- **Product master (S068).** 1P items with a SKU (`AT-JOL-200061`) and a GTIN-12 issued from the AfroTransact GS1
  prefix (`86001…`, check digit), a link to the catalog variant **ATIN** (checked against catalog's public API; it must
  be a live `PLATFORM` listing and is linked to one item only), HTS code and duty rate, shelf life, storage, reorder
  point, pack size, inner pack and case pack, unit weight, standard cost and price, and one supplier SKU per supplier
  (case pack, MOQ and agreed cost in the supplier's currency). Listing readiness starts as `DRAFT`; `LIVE` needs an
  ATIN and a price.
- **Suppliers (S069).** Trading currency, payment terms, lead time, certifications, contacts and a status lifecycle
  `ONBOARDING → APPROVED ↔ ON_WATCH → INACTIVE → APPROVED` (watch and deactivation need a reason). Only `APPROVED` and
  `ON_WATCH` suppliers can receive new POs. Every completed receipt updates the **scorecard** (on-time %, defect %).
- **Purchase orders (S070).** In the supplier's currency with the FX rate captured at the PO date
  (`at.retail.fx-rates`, or entered by the buyer). `DRAFT → PENDING_APPROVAL → APPROVED → SENT → CONFIRMED →
  PARTIALLY_RECEIVED → RECEIVED → CLOSED`, or `CANCELLED` until a shipment is booked; sent-back POs return to `DRAFT`
  with a note. Approval needs `retail:po:approve`; above the threshold (default USD 10,000.00) also `retail:finance`.
  Every write takes `If-Match` with the version from the `ETag`; stale versions and concurrent writes fail with 409.
  `GET /v1/purchase-orders/{id}/edi850` renders the ANSI X12 850 sent to the supplier.
- **Sending POs to suppliers (S225).** `POST /{id}/send` (approved POs) queues one delivery per channel in
  `po_dispatch`, in the same transaction:
  - **EMAIL** — the PO PDF (PDFBox 3.0.8, same as catalog: buyer / supplier / ship-to blocks, dates, Incoterm, payment
    terms, FX, one row per line with our SKU, supplier SKU, GTIN, quantity, unit price and amount, subtotal, total and
    base-currency equivalent, standard terms; `GET /{id}/pdf`) is uploaded to **media** (purpose `purchase-order`:
    `POST /v1/uploads`, direct `PUT` to storage, `POST /v1/assets/{id}/complete`), a signed link is requested
    (`GET /v1/assets/{id}?urlTtlSeconds=…`, 14 days asked) and `notification.requested` is written to the outbox for
    notifications-service: template `retail.purchase_order.sent`, channel `email`, recipient = the supplier's
    purchasing contact (first contact with an email whose role mentions sales / orders / purchasing, else the first
    with an email), `data` with PO number, supplier, dates, terms, totals, `documentUrl` / `documentExpiresAt` and
    `attachments: [{assetId, fileName, contentType, url, expiresAt}]` (an attachment *reference*: notifications does
    not attach files today, so the email links to the PDF).
  - **EDI** — only for suppliers with an enabled EDI profile (`GET/PUT /v1/suppliers/{id}/edi`: protocol `SFTP` or
    `AS2`, ISA interchange id, endpoint): the existing X12 850, addressed to the partner's interchange id, goes
    through the `EdiTransport` port. Adapters: `SftpEdiTransport` and `As2EdiTransport` are **skeletons** (settings
    validated, remote path / AS2 headers built, then a non-retryable failure until an SFTP / AS2 library is wired),
    and the whole transport is **off by default** (`EDI_ENABLED=false` → EDI dispatches fail with "EDI transport
    disabled"); tests use a fake transport. The ISA13 control number and the transport receipt are kept.

  `PoDispatchJob` (every 30 s, every replica; rows claimed with `FOR UPDATE SKIP LOCKED`) works them off. Media or
  partner outages retry with backoff (1, 2, 4 … min, at most 1 h) up to 5 attempts; permanent problems (no contact
  email, EDI disabled / not configured) fail at once. Each channel ends in `retail.po.dispatched` or
  `retail.po.dispatch-failed`; `GET /{id}/dispatches` shows them and `POST /{id}/dispatches/retry` (`retail:po:write`)
  re-queues failed ones (the email then goes to the supplier's current contact).
- **Inbound shipments / ASN (S071).** Booking a shipment creates the ASN from the PO (or the given lines) and the
  documents required for the mode (ocean, air, road) as `MISSING`. Document files (the supplier's ASN / packing list,
  bill of lading, certificates) are **media asset ids** (S225): `POST /v1/shipments/{id}/documents {…, assetIds}`
  checks each asset with media (`GET /v1/assets/{id}`: must exist and be finalised, else `422
  retail.document-asset-invalid`; media down → `503 retail.media-unavailable`); omitting `assetIds` keeps the files,
  `[]` removes them. The supplier's packing list sets batch, made and
  expiry dates per line plus GS1-128 cartons (SSCC); the ASN is editable until departure, then locked. The nine-step
  timeline blocks departure while documents are missing and customs clearance while a document is on hold.
  Departure posts **Dr 1310 In transit / Cr 2110 GR/IR** for the FOB value at the PO rate.
- **Goods receipt (S072).** Opened at the dock against a shipment delivered to the FC (lines pre-filled from the ASN)
  or directly against a PO. Receivers record lot, made/expiry, good and damaged units, bin and QA per batch and can
  split mixed batches. Validation (`POST …/validate`): lot 4–20 letters/digits/dashes and new for the SKU; made ≤
  today; expiry after made and not passed; remaining life ≥ max(90 days, 50% of shelf life) or the batch goes to
  quarantine; bin `A-00-00` matching the storage type (chilled → `K-`); QA recorded; more than 2% over the expected
  quantity is blocked until purchasing approves. Completing it allocates landed cost and posts the lots to
  inventory-service (`POST /v1/receipts`, Idempotency-Key `retail-<receipt id>`, client-credentials token); damaged
  units become inventory's `-DMG` quarantine lot and QA-failed batches are quarantined (`POST /v1/lots/{id}/quarantine`).
  Shortages raise a claim against the supplier, damage a claim against the carrier.
- **Landed cost and GL (S073).** FOB (PO price × units at the PO rate) + duty + freight, insurance, brokerage,
  drayage, fees and receiving. Each charge is allocated across the receipt lines by **value, weight or quantity**
  (defaults per charge type) with BigDecimal and deterministic rounding: every line gets the floor of its exact
  share and the remainder goes to the last weighted line, so shares always sum to the charge. Without a duty charge,
  duty is estimated from the item's HTS rate. The receipt posts **Dr 1300 sellable / Dr 1320 quarantine** at landed
  cost, **Cr 1310** for the FOB in transit (or **Cr 2110** for receipts without a shipment) and **Cr 2120** accrued
  duty and charges. Shipped 1P units post **Dr 5000 COGS / Cr 1300** at lot landed cost from inventory's
  `inventory.reservation.committed` events. Every entry is stored and published as `retail.gl.journal.posted`.
- **Three-way match (S074).** Supplier invoices are matched line by line: PO price × received quantity vs the invoice,
  unit price and amount within ±1% (`at.retail.match.price-tolerance-percent`). Outcomes: `MATCHED`, `EXCEPTION`
  (price/amount variance, more invoiced than ordered, line not on the PO, wrong currency — routed to the buyer via
  `retail.match.exception`) or `ON_HOLD` (goods not received yet, or open receiving claims). Finance can approve or
  reject exceptions and re-match held invoices. A payable invoice posts **Dr 2110 GR/IR** (PO price, PO rate),
  **5010** purchase price variance, **7100** FX gain/loss and **Cr 2100 AP** (invoice rate). Closing the PO (all
  received units invoiced, no open claims) releases payment (`retail.payment.released`).

Money is always integer minor units of an ISO 4217 currency (XOF has none); landed cost and GL are in the base
currency (USD).

## API

| Method | Path | Permission |
|---|---|---|
| GET | `/v1/suppliers`, `/v1/suppliers/{id}` | `retail:read` |
| POST/PUT | `/v1/suppliers`, `/v1/suppliers/{id}`, `/v1/suppliers/{id}/status` | `retail:suppliers:write` |
| GET | `/v1/items`, `/v1/items/{id}` | `retail:read` |
| POST/PUT | `/v1/items`, `/v1/items/{id}`, `/v1/items/{id}/atin`, `/v1/items/{id}/listing` | `retail:items:write` |
| GET | `/v1/purchase-orders`, `/{id}`, `/{id}/edi850`, `/{id}/pdf`, `/{id}/dispatches` | `retail:read` |
| POST | `/v1/purchase-orders/{id}/dispatches/retry` | `retail:po:write` |
| GET · PUT | `/v1/suppliers/{id}/edi` | `retail:read` · `retail:suppliers:write` |
| POST/PUT | `/v1/purchase-orders`, `/{id}/lines`, `/{id}/submit`, `/{id}/send`, `/{id}/confirm`, `/{id}/cancel` | `retail:po:write` |
| POST | `/v1/purchase-orders/{id}/approve`, `/{id}/send-back` | `retail:po:approve` (+ `retail:finance` above the threshold) |
| POST | `/v1/purchase-orders/{id}/close` | `retail:finance` |
| GET | `/v1/shipments`, `/{id}`, `/{id}/landed-cost` | `retail:read` |
| POST/PUT | `/v1/shipments`, `/{id}/asn`, `/{id}/documents`, `/{id}/charges`, `/{id}/advance` | `retail:logistics` |
| GET | `/v1/receipts`, `/{id}` | `retail:read` |
| POST/PUT | `/v1/receipts`, `/{id}/lines`, `/{id}/validate`, `/{id}/complete` | `retail:receive` |
| PUT | `/v1/receipts/{id}/charges` (receipts without a shipment) | `retail:finance` |
| POST | `/v1/receipts/{id}/approve-over-receipt` | `retail:po:approve` |
| POST | `/v1/receipts/{id}/claims/{claimRef}/resolve` | `retail:po:write` |
| GET/POST | `/v1/invoices` (`Idempotency-Key`), `/{id}`, `/{id}/rematch`, `/{id}/approve`, `/{id}/reject` | `retail:finance` |
| GET | `/v1/gl/accounts` · `/v1/gl/journal`, `/v1/gl/journal/{id}` | `retail:read` · `retail:finance` |

Errors are RFC 9457 problems with stable `retail.*` codes (`fieldErrors` for receipt validation).
Contract: [`contracts/openapi/retail.yaml`](../../../contracts/openapi/retail.yaml).
Interactive docs when running: `/swagger-ui.html`, `/docs` (Scalar), `/redoc`.

### Suggested roles (identity-service)

| Role | Permissions |
|---|---|
| Retail buyer | `retail:read`, `retail:po:write`, `retail:suppliers:write`, `retail:items:write`, `retail:logistics` |
| Category manager | `retail:read`, `retail:items:write` |
| Purchasing lead | `retail:read`, `retail:po:approve` |
| Warehouse receiver | `retail:read`, `retail:receive` |
| Retail finance | `retail:read`, `retail:po:approve`, `retail:finance` |
| Retail admin | all of the above |

## Events (transactional outbox → `at.retail.events`)

`retail.item.created|updated|listing-changed`, `retail.supplier.created|updated|status-changed`,
`retail.po.created|updated|submitted|approved|sent-back|sent|confirmed|cancelled|received|closed`,
`retail.po.dispatched|dispatch-failed`, `notification.requested` (supplier PO email, see *Sending POs*),
`retail.shipment.booked|updated|stage-changed`, `retail.receipt.opened|posted|discrepancy-raised|claim-resolved`,
`retail.landed-cost.allocated`, `retail.gl.journal.posted`,
`retail.invoice.matched|held|approved|rejected`, `retail.match.exception`, `retail.payment.released`.
Consumes `inventory.reservation.committed` from `at.inventory-reservation.events` (group `retail-service`, inbox
de-duplicated) for COGS. See [`contracts/asyncapi/fragments/retail.yaml`](../../../contracts/asyncapi/fragments/retail.yaml).

## Outbound calls

| Port | Adapter | Call |
|---|---|---|
| `CatalogClient` | `HttpCatalogClient` | `GET {CATALOG_BASE_URL}/v1/public/variants/{atin}` (anonymous) |
| `InventoryClient` | `HttpInventoryClient` | `POST {INVENTORY_BASE_URL}/v1/receipts` (Idempotency-Key), `POST /v1/lots/{id}/quarantine` |
| `MediaStore` | `HttpMediaStore` | `POST {MEDIA_BASE_URL}/v1/uploads`, `PUT` to the signed storage URL, `POST /v1/assets/{id}/complete`, `GET /v1/assets/{id}` |
| `EdiTransport` | `RoutingEdiTransport` → `SftpEdiTransport` / `As2EdiTransport` (skeletons, off by default) | SFTP file drop / AS2 POST to the partner |
| `PurchaseOrderPdfRenderer` | `PdfPurchaseOrderRenderer` | — (PDFBox) |
| `ServiceTokenProvider` | `ClientCredentialsTokenProvider` | `POST {AUTH_TOKEN_URI}` client credentials as `retail-service`, scopes `inventory:receive inventory:adjust media:write` |

Inventory 4xx answers reopen the receipt with the problem's field errors; 5xx, timeouts and auth failures leave it
`POSTING` (HTTP 503) and the next `complete` retries with the same Idempotency-Key.

## Configuration

| Env var | Purpose |
|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | PostgreSQL (database `retail`) |
| `KAFKA_BOOTSTRAP` | Kafka for the outbox relay and the COGS consumer |
| `AUTH_ISSUER_URI` | identity service issuer (JWT validation) |
| `AUTH_TOKEN_URI`, `RETAIL_CLIENT_ID`, `RETAIL_CLIENT_SECRET` | client-credentials token for inventory (blank secret = no token, local only) |
| `CATALOG_BASE_URL`, `INVENTORY_BASE_URL`, `INVENTORY_TIMEOUT` | downstream services |
| `MEDIA_BASE_URL`, `MEDIA_TIMEOUT` | media-service (PO PDFs, shipment documents) |
| `PO_DISPATCH_INTERVAL`, `PO_DISPATCH_MAX_ATTEMPTS`, `PO_PDF_LINK_TTL`, `RETAIL_BUYER_ADDRESS` | PO delivery job (30s, 5, 14d) and PDF buyer block |
| `EDI_ENABLED` (default false), `EDI_SFTP_USERNAME`, `EDI_SFTP_PRIVATE_KEY_PATH`, `EDI_SFTP_KNOWN_HOSTS_PATH`, `EDI_AS2_FROM`, `EDI_AS2_KEYSTORE_PATH` | EDI transport (adapters are skeletons) |
| `PO_FINANCE_THRESHOLD_MINOR` | Finance approval threshold in base-currency minor units (default 1000000 = USD 10,000.00) |
| `MATCH_PRICE_TOLERANCE_PERCENT` | three-way match tolerance (default 1.0) |
| `GS1_COMPANY_PREFIX` | UPC company prefix for issued GTINs (default 86001) |
| `RETAIL_CONSUMERS_ENABLED` | COGS consumer on/off |
| `OTEL_EXPORTER_OTLP_ENDPOINT`, `TRACE_SAMPLING` | tracing |

FX rates of the day and receiving rules (over-receipt tolerance, minimum remaining life) are under `at.retail.*` in
`application.yml`. Profiles: `local` (security off, debug logs), `dev`, `uat`, `prod` (API reference pages off).

## Run and test

```bash
make up                                                     # Postgres, Kafka … from infra/local
cd java && ./mvnw -B -f services/retail/pom.xml verify      # checkstyle, unit, ArchUnit, Testcontainers + WireMock ITs
./mvnw -f services/retail/pom.xml spring-boot:run           # http://localhost:8107/docs
```

Unit tests cover the PO PDF (text extracted with PDFBox: lines, terms, totals, paging, unencodable characters),
the EDI transports (off by default, skeleton behaviour), dispatch retries and contact choice, the PO state machine, the cost allocator (including randomised invariants), landed cost, receipt
validation, the three-way match and GL postings; the ITs run the whole workflow against PostgreSQL with catalog,
inventory, media and identity stubbed by WireMock; `PoDispatchIT` covers email + EDI delivery, outages / retries /
buyer retry, a supplier without contact email and shipment documents as media asset ids.

## Needed from other services (S225)

- **notifications**: consume `at.retail.events` (new topic for it) and map `notification.requested` there (already
  generic); seed the `retail.purchase_order.sent` email template (`contactName`, `poNumber`, `supplierName`, `orderDate`,
  `deliverBy`, `incoterm`, `paymentTerms`, `total`, `documentUrl`, `documentExpiresAt`); recipients are external
  addresses (no identity user). Optional: real attachments from `data.attachments[].assetId`.
- **media**: purposes `purchase-order` (PDF, private) and `shipping-document` (PDF / images, private, retail org);
  accept the `retail-service` client token (scope `media:write`) and honour `urlTtlSeconds` up to a per-purpose cap
  (≥ 14 days for `purchase-order`) returning `urlExpiresAt` — today signed URLs last 5 minutes, which is too short for
  an email link.
- **identity**: grant the `retail-service` client the `media:write` scope.

Deploy: `deploy/helm` (depends on `infra/helm/library/at-service`), one Argo CD Application per environment.
