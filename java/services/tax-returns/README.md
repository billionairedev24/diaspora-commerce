# tax-returns-service

Marketplace-facilitator sales tax, returns (RMAs) and refund orchestration.
Spring Boot 4.1 · Java 25 · PostgreSQL `taxreturns` · local port **8111**. Epic E17: stories S114 (start a return),
S115 (refund orchestration), S116 (marketplace-facilitator sales tax).

## What it owns

| Table | What it holds |
|---|---|
| `order_snapshot`, `shipment_snapshot`, `order_line_snapshot` | Placed orders from `orders.order.placed` (customer, ship-to, sellers, fulfilment type, delivery method, lines, tax per line, product type / perishable from catalog) and delivery times from `orders.shipment.delivered` |
| `rma`, `rma_line`, `rma_status_change` | Returns: number `RMA-XXXXXXXX`, one shipment per RMA, method, status, refund state, label / QR / handover code, per-line reason, evidence (media asset ids), inspection outcome, inventory posting; every transition with actor and note |
| `refund`, `refund_line` | Refunds sent to payments-service (`Idempotency-Key` = refund id), per-line items / shipping / tax / restocking fee |
| `seller_return_policy` | A seller's window, restocking fee, "accepts no-fault returns", refund at first scan, return address |
| `tax_state_rule`, `tax_local_rate` | The rate table (seeded by `V2__seed_tax_rates.sql`, editable through the API) |
| `tax_transaction` | Tax ledger: one SALE entry per order line, one REFUND entry per refunded line (negative), unique per (kind, source, line) |
| `platform_outbox` / `platform_inbox` | Events out to `at.taxreturns.events`; consumed events de-duplicated per consumer |

## Returns (S114)

**Eligibility** (`DefaultReturnPolicy`, per delivered line): the window starts at delivery — seller policy for
seller-fulfilled shipments, else the product-type window (`window-days-by-product-type`), else 30 days. Food and other
perishables (catalog `perishable` attribute or `non-returnable-product-types`) can only be **reported as a problem**
with a seller-fault reason: refund without return ("Food items show 'report a problem' instead of return"). Lines whose
remaining value is at most `low-value-threshold-minor` (10.00) can be refunded without sending them back. Units
already in open RMAs are not returnable twice. A seller with `acceptsReturns=false` only takes seller-fault reasons.

**Reasons**: `DAMAGED`, `DEFECTIVE`, `WRONG_ITEM`, `EXPIRED_OR_SPOILED` (seller fault, 1–6 photos required),
`NOT_AS_DESCRIBED`, `MISSING_PARTS`, `ARRIVED_LATE` (seller fault), `NO_LONGER_NEEDED`, `ORDERED_BY_MISTAKE`,
`BETTER_PRICE_AVAILABLE` (no fault). Evidence photos are media-service asset ids.

**Methods**: `DROP_OFF` — a return label bought from carrier-gateway (`POST /v1/labels`, reference = RMA number,
from the customer's `returnFrom` address to the seller's return address or the AfroTransact return centre) and a QR
payload `AFROTRANSACT-RETURN:<rma>:<tracking>` the apps render; `DRIVER_PICKUP` — only where the order came by
AfroTransact Delivery: a 6-digit handover code (shown to the customer and staff only) the driver checks;
`NO_RETURN` — refund without return.

**Lifecycle** (`RmaStatus`, enforced in the domain):
`REQUESTED → APPROVED | AUTO_APPROVED → LABEL_ISSUED → IN_TRANSIT → RECEIVED → INSPECTED → REFUNDED | REJECTED`;
refunds without return go `APPROVED → REFUNDED`; the customer cancels until the parcel is handed over
(`REQUESTED`, approved, `LABEL_ISSUED` → `CANCELLED`); sellers / staff reject requests with a note.

**Approval**: AfroTransact-fulfilled (FBA, Retail 1P) returns auto-approve. Seller-fulfilled requests wait for the
seller (`returns:manage` of the shipment's organisation) until `seller-approval-sla` (48 h); the job then
auto-approves them. Keep-the-item refunds up to the low-value threshold auto-approve; larger problem reports wait for
staff (AfroTransact-fulfilled) or the seller until the SLA (seller-fulfilled). Approval immediately buys the label / issues the handover code / issues the refund.

**Transit and inspection**: `carrier.tracking.updated` for the label moves the RMA to `IN_TRANSIT` (first scan) and
`RECEIVED` (delivered); drivers confirm pickup with the code. The seller (seller-fulfilled) or staff
(AfroTransact-fulfilled) receive and inspect per line: `PASS`/`FAIL` and `RESTOCK`/`DAMAGED` (default RESTOCK when
passed, DAMAGED when failed). AfroTransact-fulfilled lines post `RETURN_RESTOCK` / `RETURN_DAMAGED` to
inventory-service (`POST /v1/returns`, Idempotency-Key per RMA line). Passed lines are refunded; if every line fails
the RMA is rejected.

**Refund at first scan**: drop-off and driver-pickup returns with an estimate up to `first-scan-refund-max-minor`
(200.00) are refunded at the first scan when AfroTransact fulfilled them or the seller opted in
(`refundAtFirstScan`). Inspection still runs (stock posting; a failed inspection after the refund rejects the RMA and
the event carries `refundState=ISSUED` for trust-ops).

## Refunds (S115)

`DefaultRefundCalculator` works on the lines **as charged by payments-service** (`GET /v1/payments?orderId=`) minus
what earlier refunds took; money is minor units, proportions use `BigDecimal` and round **half up per line**:

- **items** = items charged × returned units / units; returning the last units refunds exactly what is left.
- **tax** = line tax × returned units / units (same "last units take the rest" rule), so repeated partial returns
  always add up to the tax charged. A restocking fee does not reduce the tax refund (it is treated as a separate,
  non-taxable charge).
- **shipping** only when the seller is at fault: the shipment's shipping × (value of the seller-fault units / items
  value of the shipment); all remaining shipping when the return empties the shipment. It is refunded on the line that
  carries it (orders charges a shipment's shipping on its first line).
- **restocking fee** on no-fault lines of seller-fulfilled shipments: seller policy (capped at 20 %), items × bps /
  10 000. Never on AfroTransact-fulfilled returns.

Orchestration (`RefundOrchestrator`): record the refund `PENDING` → `POST /v1/payments/{id}/refunds` with
`Idempotency-Key` = refund id → in one transaction mark it `ISSUED`, book the tax reversal (keyed by the payments
refund id), close the RMA and publish events. 5xx / 408 / 429 / I/O keep it `PENDING` for the job (same key);
4xx fail it (`returns.refund.failed`; staff retry with `POST /v1/refunds/{id}/retry`). payments-service reverses the
seller payable, tax and commission per its fee policy and takes back transferred seller funds with a transfer
reversal ("refund reverses the seller transfer portion and commission").

Partial refunds without return: `POST /v1/refunds` (staff, or a seller for its own lines, `refunds:issue`) refunds an
amount of a line's items and / or shipping; tax follows proportionally.

## Marketplace-facilitator sales tax (S116)

`TaxCalculator` port; the default adapter `RulesTaxCalculator` runs the domain `DefaultTaxRules` over the rate table
(no external calls). `at.taxreturns.tax.provider` is `RULES`; `STRIPE_TAX`, `TAXJAR` and `AVALARA` are reserved for
adapters of the same port (startup fails if one is selected without its adapter).

- **Rate** = state rate for the line's category (`GENERAL`, `GROCERY` — reduced or exempt, `PREPARED_FOOD`) + local
  rate for the ship-to ZIP (exact 5-digit ZIP wins over a 3-digit prefix; a local rate applies to grocery only when
  `appliesToGrocery`). Rates are fractions stored as `numeric(8,6)`.
- **Taxable** = unit price × quantity − discount, plus the line's shipping where the state taxes shipping and the item
  is taxable.
- **Tax** = taxable × rate with `BigDecimal`, **rounded half up to the minor unit once per line** (never per unit,
  never per invoice); the order's tax is the sum of its lines, which is what refunds reverse.
- **Facilitator**: only states with `facilitator=true` are collected (`collectedBy=MARKETPLACE`); other states,
  unknown states and non-US addresses get no tax (`NOT_COLLECTED`).
- **Category**: from the request (`taxCategory`), else catalog product type via
  the classification rules below (`tax_classification_rule`), else `GENERAL`.
- **Seed** (`V2__seed_tax_rates.sql`): TX, NY, NJ, GA, MD, CA, IL, MN (facilitator) and DE (no sales tax) with a few
  city ZIP prefixes. The seeded rates are illustrative: finance must confirm them before production and keep them
  current through `PUT /v1/tax/rates/**`.

**Ledger**: `orders.order.placed` books one SALE entry per line with the tax orders charged, the state jurisdiction
(`US-TX`), the local jurisdiction from the table (`US-TX-770`) and the seller. Refunds book negative REFUND entries in
the month of the refund — from this service's refunds and from every `payments.refunded` event (cancellations, staff
refunds), de-duplicated by payments refund id; a `FAILED` refund voids its entries. `GET /v1/tax/liability` gives per
jurisdiction and currency: orders, gross sales, taxable sales, tax collected, tax refunded and net to remit; `/export`
is the CSV. `GET /v1/tax/sellers/{orgId}/statement` is the same per seller ("monthly seller statements show tax
collected on their behalf").

## API

RFC 9457 problems with `code` `returns.*` / `tax-returns.*` (422 rule violations with `fieldErrors`, 409 invalid
transitions and stale versions, 403 permissions, 503 / 502 upstreams). Contract:
[`contracts/openapi/tax-returns.yaml`](../../../contracts/openapi/tax-returns.yaml). Reference pages
`/swagger-ui.html`, `/docs`, `/redoc` (off in prod).

| Method & path | Who |
|---|---|
| `GET /v1/returns/eligibility?orderId=` | the order's customer; staff `returns:manage` |
| `POST /v1/returns` (`Idempotency-Key`) | the order's customer; staff `returns:manage` on their behalf |
| `GET /v1/returns?orderId&status&sellerOrgId&limit`, `GET /v1/returns/{id}`, `/number/{number}` | customer (own), seller `returns:manage` (own org), staff `returns:manage` |
| `POST /v1/returns/{id}/cancel` | customer (own) / staff |
| `POST /v1/returns/{id}/approve`, `/reject` | seller `returns:manage` (seller-fulfilled), staff `returns:manage` |
| `POST /v1/returns/{id}/label` | staff `returns:manage` (retry label / code) |
| `POST /v1/returns/{id}/pickup {handoverCode}` | driver `delivery:operate` (code checked), staff `returns:manage` |
| `POST /v1/returns/{id}/receive`, `/inspect` | seller (seller-fulfilled) / staff `returns:manage` |
| `GET /v1/returns/{id}/refund-quote` | as for reading the return |
| `GET`/`PUT /v1/return-policies/{orgId}` | seller `returns:manage` (own org), staff |
| `POST /v1/refunds` (`Idempotency-Key`) | staff `refunds:issue`; sellers `refunds:issue` for their own lines |
| `GET /v1/refunds?orderId=` or `?rmaId=`, `GET /v1/refunds/{id}` | staff `refunds:issue` or `returns:manage` |
| `POST /v1/refunds/{id}/retry` | staff `refunds:issue` |
| `POST /v1/tax/quotes` | `tax:quote` (orders-service and gateway-bff client-credentials; not the shopper JWT) |
| `GET /v1/tax/rates` · `PUT /v1/tax/rates/states/{state}` · `PUT`/`DELETE /v1/tax/rates/local/{state}/{zip}` | `tax:read` · `tax:configure` |
| `GET /v1/tax/liability?month=YYYY-MM`, `/v1/tax/liability/export` (CSV) | staff `tax:read` |
| `GET /v1/tax/sellers/{orgId}/statement?month=`, `/statement/export` (CSV) | seller `payouts:read` (own org), staff `tax:read` |
| `GET /v1/tax/orders/{orderId}/transactions` | staff `tax:read` |
| `POST /v1/tax/reversals` | staff `refunds:issue` (refunds made outside payments events) |

## Permissions, roles and the service client

Reused keys (identity `V2__seed_rbac.sql`): `returns:manage` (platform super admin, operations manager, customer
support; seller owner, admin, fulfilment, support), `refunds:issue` (super admin, finance, customer support; seller
owner, admin), `delivery:operate` (drivers), `payouts:read` (seller statements: seller owner, admin, finance and the
platform roles that already have it).

New keys for identity to seed (this service does not edit identity):

| Key | Module | Roles |
|---|---|---|
| `tax:quote` | tax | client scope of `orders-service` (and `gateway-bff` if carts show tax); `platform.super_admin` |
| `tax:read` | tax | `platform.super_admin`, `platform.finance`, `platform.auditor` |
| `tax:configure` | tax | `platform.super_admin`, `platform.finance` |

New confidential client **`tax-returns-service`** (client credentials, secret `TAX_RETURNS_CLIENT_SECRET`), scopes
`payments:service` (payment lookup and refunds), `inventory:adjust` (`POST /v1/returns`), `shipping:label`
(carrier-gateway labels — carrier-gateway's key, not yet in identity's permission catalogue). Catalog reads are
public.

## Events

Published on `at.taxreturns.events` (contract
[`contracts/asyncapi/fragments/tax-returns.yaml`](../../../contracts/asyncapi/fragments/tax-returns.yaml)):
`returns.rma.requested|approved|rejected|cancelled|label_issued|in_transit|received|inspected|refunded` (key RMA id),
`returns.refund.issued|failed` (key refund id), `tax.sale.recorded`, `tax.refund.reversed` (key order id).

Consumed (group `tax-returns-service`, inbox-deduplicated; listeners tested by calling them directly):
`orders.order.placed`, `orders.shipment.delivered` (`at.order.events`), `payments.refunded` (`at.payment.events`),
`carrier.tracking.updated` (`at.carrier-shipment.events`, return labels only).

Jobs (`ReturnsJobs`, every 60 s, idempotent): seller-SLA auto-approval, label / handover-code retries, refunds that
could not be prepared or issued, inventory postings.

## Integration status (S235)

| Piece | State |
|---|---|
| Checkout tax | **Done.** orders-service calls `POST /v1/tax/quotes` in the checkout's prepare step (`orders-service` token, `tax:quote`) and stores `taxMinor`, `jurisdiction`, `localJurisdiction` and `shippingTaxMinor` per line; `dev` falls back to orders' interim table when this service is down, `uat` / `prod` retry instead |
| `orders.order.placed` lines | **Done.** Product type, perishable, discount, tax jurisdictions, shipping tax, the shipment's shipping and the ship-to street (V3 columns); `GET /v1/lookup/orders/{id}` (`orders:lookup`, `OrderLookup` port) backfills orders placed before this service consumed them |
| Eligibility from order data | **Done.** Eligibility and labels use the order snapshot (or the lookup) — the app sends no address; `returnFrom` only overrides it |
| Refunded amounts per line | **Done.** payments `GET /v1/payments?orderId=` lines carry `refundedItemsMinor`, `refundedShippingMinor`, `refundedTaxMinor` and `refundableMinor`; refunds made elsewhere (cancellations) are subtracted |
| Return pickups | **This side done.** DRIVER_PICKUP returns are scheduled with fulfilment-service `POST /v1/return-pickups` (`ReturnPickupScheduler`, client scope `delivery:returns`, retried by the job, cancelled with the RMA); fulfilment's endpoint and the driver app belong to the fulfilment / mobile teams |
| Order timeline | orders may consume `returns.rma.refunded` / `returns.refund.issued` ("Refunded Oct 6") |

Remaining outside this service:

- **inventory**: `POST /v1/returns` with a receiving node / service scope (inventory team).
- **carrier-gateway**: label-less QR drop-off and return rate choice; `shipping:label` / `shipping:rate` keys.
- **storefront / mobile**: return screens (pick items, reason, method; return number, drop-off QR, refund amount,
  cancel before drop-off; food shows "report a problem").
- **media**: evidence asset ids are stored as given (no ownership check).
- **catalog**: product types carry no tax category; mapped here by configuration.
- **Finance**: confirm the seeded rates (`V2__seed_tax_rates.sql`) before production (S235 acceptance criterion).
- **contracts**: `contracts/asyncapi/events.yaml` is regenerated from the fragments by the platform tooling.

## Configuration

`application.yml` + `application-{local,dev,uat,prod}.yml`. Secrets only from the environment: `DB_URL`, `DB_USER`,
`DB_PASSWORD`, `KAFKA_BOOTSTRAP`, `TAX_RETURNS_CLIENT_SECRET` (blank in `local`: upstreams run without security).

| Env var | Purpose |
|---|---|
| `AUTH_ISSUER_URL`, `AUTH_TOKEN_URL`, `TAX_RETURNS_CLIENT_ID` | JWT validation; client-credentials token |
| `PAYMENTS_URL`, `INVENTORY_URL`, `CARRIER_GATEWAY_URL`, `CATALOG_URL`, `UPSTREAM_TIMEOUT` | upstreams |
| `TAX_PROVIDER` | `RULES` (default) |
| `RETURN_WINDOW_DAYS`, `RETURN_LOW_VALUE_MINOR`, `RETURN_FIRST_SCAN_MAX_MINOR`, `RETURN_SELLER_SLA`, `RETURN_DROP_OFF_DAYS` | return policy |
| `RETURN_CENTRE_NODE`, `RETURN_CENTRE_NAME`, `RETURN_CENTRE_LINE1` … `RETURN_CENTRE_COUNTRY` | AfroTransact return centre (the Helm values carry a placeholder address to confirm with fulfilment) |
| `RETURN_LABEL_CARRIER`, `RETURN_LABEL_SERVICE`, `RETURN_LABEL_WEIGHT_LB` | return label service |
| `TAX_RETURNS_JOBS_ENABLED`, `TAX_RETURNS_JOBS_INTERVAL`, `CONSUMERS_ENABLED` | background work |
| `OTEL_EXPORTER_OTLP_ENDPOINT`, `TRACE_SAMPLING` | tracing |

Product-type windows, non-returnable types and the product type → tax category map are under `at.taxreturns.*`.

## Run and test

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
make up                                                       # Postgres, Kafka … from infra/local
cd java && ./mvnw -B -f services/tax-returns/pom.xml verify    # checkstyle, unit, ArchUnit, Testcontainers ITs
./mvnw -f services/tax-returns/pom.xml spring-boot:run         # http://localhost:8111/docs
tools/contracts/export_java_openapi.sh tax-returns taxreturns 8111 --at.consumers.enabled=false \
  --at.taxreturns.jobs.enabled=false
```

Tests: tax math (`DefaultTaxRulesTest`: rates, ZIP lookup, categories, shipping, discounts, half-up per line,
facilitator), refund math (`DefaultRefundCalculatorTest`: partial returns that add up exactly, fault shipping,
restocking fees, adjustments), eligibility (`DefaultReturnPolicyTest`), the RMA state machine (`RmaTest`), listeners
called directly, the HTTP adapters against a local server, and Testcontainers ITs with in-memory upstreams
(`ReturnsFlowIT`: drop-off with refund at first scan and restock, perishable problem report, seller-fulfilled driver
pickup with seller decision and failed inspection, seller SLA auto-approval, partial refunds with retry;
`TaxLedgerIT`: quotes from the seeded table, rate edits, sale / refund ledger, liability and seller statements).

Deploy: `deploy/helm` (depends on `infra/helm/library/at-service`), one Argo CD Application per environment.

## Tax classification (what an item is for tax)

Design: [`docs/engineering/tax-classification-design.md`](../../../docs/engineering/tax-classification-design.md).

- `tax_code` (our codes, each with a rates column `GENERAL | GROCERY | PREPARED_FOOD` and an optional provider code such as Stripe's
  `txcd_...`) and `tax_classification_rule` (catalog **product type**, optionally narrowed by one attribute answer, to a tax code) are
  staff-maintained: `GET/PUT /v1/tax/codes`, `GET/PUT/DELETE /v1/tax/classification-rules`, `POST /v1/tax/classification-test`.
- A quote line is classified from the product's type and the seller's answers read from catalog (`GET /v1/public/variants/{atin}`,
  cached five minutes). An answer rule beats a type rule. **Anything not positively classified is `GENERAL_GOODS` (general taxable).**
  A caller's explicit `taxCategory` still wins (no tax code is recorded then).
- Catalog asks the seller one plain question (`tax_class`) for grocery, perishable and beauty types (catalog V9). The option texts must
  match the rule rows (tax-returns V5). The question is not required yet; unanswered items are taxed as general goods.
- Placed orders are stamped with the code each line was taxed under (`order_line_snapshot.tax_code`), and lines classified by default
  are counted in `tax_classification_seen`: the staff review queue (`GET /v1/tax/review-queue`, `POST /v1/tax/review-queue/reviewed`).
  `GET /v1/tax/liability/by-code?month=YYYY-MM` is tax by code and jurisdiction (sales minus refunds).
- Staff screens: Fees & commissions › Tax classification (review queue, rules, codes).
- Provider `STRIPE_TAX` (`TAX_PROVIDER=STRIPE_TAX` + `STRIPE_SECRET_KEY`): `POST /v1/tax/calculations` per quote; the rules engine answers
  (`provider` = `RULES-FALLBACK`) when Stripe is unreachable, the ship-to is not US, or a line's tax code has no provider code yet.
  It only calculates: creating Stripe tax transactions for captured payments and refunds, and registrations, are not built.
  The provider codes in `tax_code` are empty except general goods: copy them from Stripe's list, with the CPA, before switching.

### Items known not to be taxable

`tax_code.taxable = false` (seeded: `NON_TAXABLE`, for gift cards, donations and other items staff mark exempt everywhere) and
`tax_code.exempt_states` (e.g. `TX,NJ`, for an item known exempt in those states) are staff decisions made with the CPA. A quote
prices such lines at zero (`collectedBy = NOT_COLLECTED`) **without sending them to the calculator**: the customer is not charged and a
paid provider is not called for them. An order made only of such lines makes no provider call at all (`provider = EXEMPT`). The built-in
rate table's zero rates are not used for this, because they are not a statement that an item is exempt.
