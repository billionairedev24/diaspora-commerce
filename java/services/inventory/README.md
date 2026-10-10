# inventory-service

Source of truth for sellable stock: AfroTransact-owned (1P, AfroTransact Retail) products and seller stock held in
AfroTransact fulfilment centres (FBA). Spring Boot 4.1 · Java 25 · PostgreSQL `inventory` · port **8103**.
Architecture: §17b/§17c of `design/AfroTransact Architecture.dc.html` (Inventory model, Concurrency, Lifecycle,
Lots & landed cost, Availability & storefront, Jobs & alerts). Stories S062–S065, S066 (hub backend), S067 (seller FBA
inventory), S226 (retail integration), S257 (send stock to AfroTransact).

## Model

| Table | What it holds |
|---|---|
| `node` | Fulfilment centres / origin hubs (`HOU-FC`, `EWR-FC`, `ATL-FC`, `LAX-FC`, `TEM-HUB`, `LOS-HUB`) with time zone and temperature zones |
| `inventory_position` | `(sku, node_id)` → `owner_org, on_hand, reserved, inbound, damaged, version`; **available = on_hand − reserved**; DB check `reserved ≤ on_hand` |
| `lot` | Batch per `(sku, lot_no, node_id)` (unique): bin, qty on hand / reserved, mfg / exp dates, unit landed cost, status `SELLABLE · QUARANTINE · EXPIRED · WRITTEN_OFF · DAMAGED`, reason, markdown flag |
| `reservation` (+ `_line`, `_allocation`) | One per order id: lines, FEFO lot allocations with cost, status `HELD · COMMITTED · RELEASED · EXPIRED`, `expires_at` |
| `stock_movement` | **Append-only ledger** (trigger rejects UPDATE/DELETE/TRUNCATE): type, sku, node, lot, qty, deltas and before/after of every bucket, reference, actor, reason, correlation id |
| `idempotency_record` | Stored responses of receipts, returns, write-offs, cycle counts and transfers per `Idempotency-Key` |
| `reconciliation_run` | Nightly drift reports |
| `sku_dimension` | Unit dimensions (inches) and derived cubic feet per SKU (S206) |
| `storage_snapshot` | Per day × owner × fulfilment centre: units physically held (on hand + damaged), units without dimensions, cubic feet |
| `inbound_shipment` (+ `_line`, `inbound_box`) | Seller FBA plans (`FBA-IN-006600`) and retail ASNs (`RETAIL_ASN`, by retail shipment reference): owner, destination FC and why, status `WORKING → SHIPPED → RECEIVING → CLOSED` (`CANCELLED`), ship-from, carrier / tracking / bought label; lines (SKU, ATIN, AT-FN unit label, expected, received good / damaged, units still inbound); boxes (LPN, contents, weight, dimensions, received at) |
| `fba_offer_link` | Projection of offers' Fulfilled-by-AfroTransact offers by (owner, inventory SKU): stranded-stock flags |
| `node` address columns | FC street address (inbound labels and the plan's ship-to; `PUT /v1/nodes/{id}/address`) |

Invariants (checked by reconciliation): `on_hand = Σ SELLABLE lot qty = Σ ledger Δon_hand`, `reserved = Σ lot
qty_reserved = Σ ledger Δreserved`, `damaged = Σ QUARANTINE/DAMAGED/EXPIRED lot qty = Σ ledger Δdamaged`.

## Concurrency (S063)

`reserve(orderId, lines[])` runs in **one transaction**:

1. Claim the order: `INSERT … ON CONFLICT (order_id) DO NOTHING`. A concurrent retry of the same order blocks on
   the unique index, then returns the winner's reservation (`200`, `replayed=true`) — never a second hold.
2. `SELECT … FOR UPDATE` every position of the requested SKUs **ordered by (sku, node_id)**. Every write path
   (receipts, lot ops, returns, commit, release, the expiry sweep) locks in that order, so transactions queue
   instead of deadlocking.
3. Allocate each line FEFO (earliest expiry first, skipping lots that expire within
   `fefo-min-remaining-days`, node-local date) and check availability. Any shortfall throws
   `409 inventory.insufficient-stock` with per-SKU `shortfalls[{sku,nodeId,requested,available,missing}]` and the
   whole transaction — including the claim — rolls back: **all lines or none**.
4. Insert lines + allocations, update lots and positions (`version + 1`), write `RESERVE` ledger rows and outbox
   events (`inventory.reservation.held`, `inventory.availability.changed`).

Flash-sale test (`ReservationConcurrencyIT.flashSaleNeverOversells`): 20 units, 45 simultaneous HTTP checkouts ⇒
exactly 20 × `201`, 25 × `409`, `reserved = 20`, 20 `RESERVE` rows, reconciliation drift 0.

## Lifecycle (S064)

`RESERVE` (TTL 15 min) → `confirm` (payment authorised, no TTL) → `commit` on pickup = `SHIP` (on hand and reserved
down, lot decrements, **COGS = Σ lot landed cost × qty** in the response and event) → `RETURN_RESTOCK` /
`RETURN_DAMAGED` after inspection (never more than shipped). `release` on cancellation or payment failure. A sweep
(`FOR UPDATE SKIP LOCKED`) releases expired HELD reservations as `EXPIRED`. Cycle counts post `ADJUST` with a
reason and can **never take on hand below reserved** (`422 inventory.below-reserved`).

## Receipts & lots (S065)

`POST /v1/receipts` (retail-service after a goods receipt, or FBA inbound): one lot per batch line (`RECEIVE` +
`PUT_AWAY`); damaged units become a separate `<lot>-DMG` quarantine lot; lots whose remaining life is below
`max(90 days, 50 % of shelf life)` are quarantined (`QUARANTINE`); expired lots or impossible dates reject the
receipt (`422 inventory.lot-rejected`). Staff can quarantine, release, write off (partial or full), cycle-count and
transfer lots — all with reasons.

## Send stock to AfroTransact (S257, FBA inbound)

Extends the `POST /v1/inbound` ASN (still available for single-SKU expected units) into inbound shipments:

1. **Plan** — `POST /v1/inbound/shipments` (`inventory:inbound`, own org): lines `{atin, sku?, qty}`, optional boxes
   `{contents[{sku, qty}], weightLb, dimensions}`, ship-from, requested FC, storage zone. Each line's unit label is the
   seller's **AT-FN code** from catalog (`GET /v1/labels/units/{atin}?orgId=` through the `UnitLabelClient` port,
   inventory-service token), issued once per seller and variant; it is also the default inventory SKU, so units of
   different sellers never share a position. **FC assignment** (`DefaultFcAssignmentPolicy`): never an FC where
   another organisation stocks one of the SKUs; a requested FC must qualify (422 `inventory.fc-unavailable`);
   otherwise the FC already holding most of the seller's SKUs, then the least loaded, then by id. Boxes get an LPN
   (`LPN` + 8). `PUT /{id}` replaces lines / boxes while `WORKING` (If-Match → 409 `inventory.version-mismatch`).
2. **Labels** — `GET /{id}/labels?kind=UNIT|BOX&format=PDF|ZPL&lineNo=`: one AT-FN unit label per planned unit and one
   carton label per box (LPN, plan number, FC), rendered by catalog `POST /v1/labels/batch`.
3. **Tracking** — `PUT /{id}/tracking {carrier, trackingNumber}` (registered with carrier-gateway `POST /v1/tracking`
   when it can: `trackingRegistered`), or `POST /{id}/carrier-label {carrier, service, shipDate}` buys the label from
   carrier-gateway (`CarrierGateway` port, `POST /v1/labels`, fulfilment `FBA`, Idempotency-Key
   `inbound-<id>-label`, one parcel per box, ship-to = the FC address).
4. **Ship** — `POST /{id}/ship`: boxes (if any) must hold every planned unit; expected units become **inbound** at
   the FC ("Coming soon"), `inventory.inbound.shipped`.
5. **Receive** — `POST /{id}/receipts` (`inventory:receive`, Idempotency-Key): lines by SKU or **scanned AT-FN label**
   (another seller's label is refused), `boxesReceived` LPNs. Lots are created for the seller (cost 0: the stock is
   theirs), damaged units go to `-DMG` quarantine lots, `holdReason` quarantines; exactly the plan's inbound units
   are consumed; unplanned SKUs are recorded as UNEXPECTED. `inventory.inbound.received` lets offers-service create
   the seller's **Fulfilled-by-AfroTransact offer** next to their own-store offer.
6. **Close** — `POST /{id}/close`: units that never arrived stop counting as inbound; discrepancies `SHORT`, `OVER`,
   `DAMAGED`, `UNEXPECTED` are on the shipment and in `inventory.inbound.closed`. `POST /{id}/cancel` before
   receiving.

`POST /v1/receipts` takes `inboundRef` (plan number or retail shipment reference): the receipt is booked against that
shipment instead of taking units off the position's aggregate inbound.

**Retail ASNs (S226).** `retail.shipment.stage-changed` (topic `at.retail.events`): from `DEPARTED` the ASN lines become
a `RETAIL_ASN` inbound shipment at the PO's node (units inbound); retail's receipt (`inboundRef` = shipment reference)
consumes them; `RECEIVED` closes the notice. Receipt lines carry `holdReason` (QA holds without a separate call) and
reservation lines carry `stockOwnerOrgId` (S226, already in place).

## Seller FBA inventory (S067) and the inventory hub (S066)

- `GET /v1/fba/inventory?ownerOrg=&nodeId=&sku=&stranded=&page=&size=` — per SKU × FC: on hand, reserved, available,
  inbound, unfulfillable (quarantined, damaged, expired), **ageing** since receipt (`D0_90 … D365_PLUS`, node-local),
  volume (unit dimensions or the default unit volume), offers and **stranded** (`NO_OFFER` / `OFFER_INACTIVE`: no
  live Fulfilled-by-AfroTransact offer, from the `offers.offer.*` projection), plus totals. Sellers: their own org.
- `GET /v1/overview?nodeId=&ownerOrg=` — units and landed value per lot status, expiry buckets, per-node figures,
  open inbound shipments, held reservations and alerts (expired / expiring lots, low stock, inbound discrepancies).
- `GET /v1/lots/{id}/trace` — recall trace: the lot, its ledger rows, the same batch at other nodes / `-DMG`, and the
  orders whose reservations took its units. Lot quarantine / release / write-off with reasons and per-lot movements
  already existed.

## API

All writes return RFC 9457 problems with `code` `inventory.*`. Reference pages: `/swagger-ui.html`, `/docs`
(Scalar), `/redoc`; contract: `contracts/openapi/inventory.yaml`.

| Method & path | Permission | Notes |
|---|---|---|
| `GET /v1/availability?skus=A,B` | public | available per SKU and per node, inbound, `inStock` |
| `GET /v1/positions` | `inventory:read` | on hand / reserved / available / inbound / damaged; `lowStock=true` ≤ 10 |
| `GET /v1/movements` | `inventory:read` | ledger; filter by sku, node, `lotId` (recalls), reference, type |
| `GET /v1/nodes` · `POST /v1/nodes` | read · adjust | |
| `POST /v1/reservations` | SERVICE + `inventory:reserve` | `Idempotency-Key`; 201 new / 200 replay / 409 shortfall |
| `GET /v1/reservations/{orderId}` | service or staff read | |
| `POST /v1/reservations/{orderId}/confirm` · `/commit` · `/release` | SERVICE + `inventory:reserve` | `Idempotency-Key`; idempotent by state; commit returns COGS |
| `POST /v1/returns` | `inventory:adjust` | `Idempotency-Key`; RESTOCK or DAMAGED |
| `POST /v1/receipts` | `inventory:receive` | `Idempotency-Key`; 201 / 200 replay |
| `POST /v1/inbound` | `inventory:receive` | expected units (ASN) → "Coming soon" |
| `POST /v1/inbound/shipments` · `GET …` · `GET/PUT …/{id}` | `inventory:inbound` (own org) · read: inbound, read or receive | FBA plans (S257) |
| `GET …/{id}/labels` · `PUT …/{id}/tracking` · `POST …/{id}/carrier-label` · `POST …/{id}/ship` · `POST …/{id}/cancel` | `inventory:inbound` (cancel also FC staff) | AT-FN / LPN labels, tracking, ship |
| `POST /v1/inbound/shipments/{id}/receipts` · `…/close` | `inventory:receive` | FC receiving against the plan, discrepancies |
| `GET /v1/fba/inventory` | `inventory:read` (sellers: own) | S067 |
| `GET /v1/overview` · `GET /v1/lots/{id}/trace` | `inventory:read` | S066 |
| `GET /v1/nodes/{id}/address` · `PUT …` | read · `inventory:adjust` | FC street address |
| `GET /v1/lots` · `/v1/lots/{id}` · `/v1/lots/markdown?within=WITHIN_30` | `inventory:read` | FEFO order, markdown list |
| `POST /v1/lots/{id}/quarantine` · `/release` · `/write-off` · `/adjust` · `/transfer` | `inventory:adjust` | reason required; optional `Idempotency-Key` |
| `POST /v1/lots/expiry-runs` · `/v1/lots/markdown-runs` | `inventory:adjust` | run nightly jobs on demand |
| `POST /v1/reconciliation/runs` · `GET …/latest` | adjust · staff read | drift report |
| `PUT /v1/skus/{sku}/dimensions` · `GET …` | `inventory:adjust` or `inventory:receive` · `inventory:read` | unit dimensions for FBA storage billing (S206) |
| `POST /v1/storage/snapshots` | `inventory:adjust` | retake today's storage snapshot (idempotent) |
| `GET /v1/storage/usage?from=&to=&ownerOrg=` | SERVICE `inventory:storage.read` (seller-service), staff `inventory:read`; sellers: own org | cubic-feet-days per owner and UTC day (≤ 93 days) |

Sellers (`SELLER_MEMBER` with `inventory:read`) only ever see positions, lots and movements whose `owner_org` is
their organisation (other orgs' lots answer 404). Super admins (`*`) may call reservation endpoints for the
flash-sale drill; everyone else must be a SERVICE principal.

## Events (outbox → Kafka, `contracts/asyncapi/fragments/inventory.yaml`)

| Topic (key) | Types |
|---|---|
| `at.inventory.events` (SKU) | `inventory.availability.changed` {sku, nodeId, ownerOrg, available, onHand, reserved, inbound, damaged, version} — whenever available moves; `inventory.lot.created / quarantined / released / expired / written_off` |
| `at.inventory-reservation.events` (order id) | `inventory.reservation.held / committed (with cogs) / released (status RELEASED or EXPIRED)` |
| `at.inventory-reconciliation.events` (run id) | `inventory.reconciliation.drift_detected` |
| `at.inventory-inbound.events` (shipment id) | `inventory.inbound.created / shipped / received / closed / cancelled` |

Consumed (group `inventory-service`, inbox-deduplicated, `CONSUMERS_ENABLED`): `retail.shipment.stage-changed`
(`at.retail.events`, S226) and `offers.offer.created|updated|withdrawn` (`at.offers.events`, S067).

## Jobs (idempotent, safe on several replicas)

| Job | Default | What |
|---|---|---|
| Reservation sweep | every 30 s | release HELD reservations past TTL (`SKIP LOCKED`) |
| Lot expiry | hourly `0 5 * * * *` | lots past expiry at the node's **local** date → `EXPIRED`, moved to damaged, event; reserved lots deferred |
| Markdown flags | `0 20 2 * * *` | sellable lots ≤ 60 / ≤ 30 days → `WITHIN_60` / `WITHIN_30` |
| Storage snapshot | hourly `0 55 * * * *` (UTC) | retakes today's snapshot of stock in fulfilment centres; the last of the day stands; unmeasured SKUs count `storage.default-unit-cubic-feet` (0.25) |
| Reconciliation | `0 40 2 * * *` | positions vs ledger sums vs lot sums in one REPEATABLE READ snapshot; gauge `inventory_reconciliation_drift`; WARN log + event on drift |

## Configuration

`application.yml` + `application-{local,dev,uat,prod}.yml`. Secrets only from env: `DB_URL`, `DB_USER`,
`DB_PASSWORD`, `KAFKA_BOOTSTRAP`, `INVENTORY_CLIENT_SECRET` (`AUTH_ISSUER_URL` per env). Downstreams (S257):
`CATALOG_URL` (unit labels), `CARRIER_GATEWAY_URL` (labels, tracking), `AUTH_TOKEN_URL`, `INVENTORY_CLIENT_ID`
(`inventory-service`, scopes `catalog:read catalog:write shipping:label`). Tunables (`at.inventory.*`): `retail-org-id`
(`RETAIL_ORG_ID`), `reservation-ttl` (`RESERVATION_TTL`, 15m), `fefo-min-remaining-days` (7),
`shelf-life.min-days` (90), `shelf-life.min-fraction` (0.5), `jobs.enabled`, `jobs.*-cron`, `jobs.zone`.

## Needed from other services (S257 / S067)

- **catalog**: return the AT-FN code as data (e.g. header `X-Unit-Label-Code` on `GET /v1/labels/units/{atin}`, which
  inventory already reads; today it parses the SVG `<title>`), and accept service clients with `catalog:read` (or a
  `catalog:labels` scope) there instead of `catalog:write`.
- **identity**: client `inventory-service` (secret `INVENTORY_CLIENT_SECRET`) with `catalog:read catalog:write
  shipping:label`; grant `inventory:inbound` to the Seller Center inventory roles.
- **carrier-gateway**: inbound FBA labels bought by inventory are billed `PLATFORM_CALLER`; bill them to the seller
  (e.g. accept `billTo`/`orgId` from trusted services or a fulfilment value `FBA_INBOUND`).
- **orders**: reserves by variant ATIN; FBA stock is stocked under the offer's inventory SKU (AT-FN by default), so
  orders should reserve the offer's `inventorySku` with the seller as owner for Fulfilled-by-AfroTransact lines.

## Run & test

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
cd java && ./mvnw -B -f services/inventory/pom.xml verify     # checkstyle, unit, ArchUnit, Testcontainers ITs
docker compose -f infra/local/docker-compose.yml up -d postgres kafka   # then:
java -jar services/inventory/target/inventory-service-0.1.0-SNAPSHOT.jar   # local profile, port 8103, auth off
tools/contracts/export_java_openapi.sh inventory inventory 8103 --at.inventory.jobs.enabled=false --at.consumers.enabled=false
```

Helm: `deploy/helm` (library chart `at-service`); validate with
`helm dependency build java/services/inventory/deploy/helm && helm template x java/services/inventory/deploy/helm -f java/services/inventory/deploy/helm/values-prod.yaml | kubeconform -strict -ignore-missing-schemas`.
