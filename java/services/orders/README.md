# orders-service

Checkout saga, orders split into shipments (sub-orders) and the shipment state machine with actor rules.
Spring Boot 4.1 · Java 25 · PostgreSQL `orders` · Temporal · port **8104**.
Architecture: §5 (checkout saga, idempotency, outbox, concurrency), §17b (one charge, many transfers) and §17c
(shipment state machine, "Who can do what", AfroTransact Delivery) of `design/AfroTransact Architecture.dc.html`.
Stories S046, S050 (server side), S058, S059, S060 (API side), S061 (server side).

## Model

| Table | What it holds |
|---|---|
| `customer_order` | Number `AT-2026-XXXXXX`, customer, currency, items / shipping / tax / discount / total (bigint minor units; DB check `total = items + shipping + tax − discount`), address snapshot, payment id, workflow id, status `PENDING_PAYMENT · PLACED · IN_PROGRESS · COMPLETED · CANCELLED`, `version` |
| `shipment` | One per seller + fulfilment type + fulfilment node: fulfilment `SELLER_FULFILLED · FBA_AFROTRANSACT · RETAIL_1P`, delivery method `CARRIER · AFROTRANSACT_DELIVERY · PICKUP` + service level / pickup location, preferences (handover hand/door/desk, safe place, instructions ≤ 240, contact), perishable, handover code (SHA-256 hash + AES-GCM sealed copy for the customer only), status, shipping, carrier + tracking, promised dates, capture, delivered at |
| `order_line` | Variant ATIN, title / image snapshot, qty, unit price, tax, discount, commission category, perishable, tax jurisdiction |
| `shipment_status_change` | Every applied transition: from, to, actor type / id / org, reason, source `api · carrier · driver · system`, proof (redacted), time. Unique `(shipment_id, event_id)` ⇒ idempotent per event |
| `checkout_request` | `(customer, Idempotency-Key)` → order id, number, workflow id, fingerprint, status, client secret, failure |
| `follow_up_task` | Capture / settle / cancel work queued in the transition's transaction, run with leases + backoff |
| `platform_outbox` / `platform_inbox` | Events out; consumed payment / carrier events de-duplicated |

Order totals always equal the sum of the shipments (domain invariant + DB check). Shipping of a shipment is charged
on its first payments line, so captures and refunds use the same allocation.

## Checkout saga (Temporal, S046)

`POST /v1/checkout` (alias `POST /v1/orders` for gateway-bff; `Idempotency-Key` required) claims
`(customer, key)` → order id, number and workflow id `checkout-<orderId>`, reads the cart (`GET /v1/cart`) and address
(`GET /v1/me/addresses`) **with the customer's token** and starts `CheckoutWorkflow` on task queue `orders-checkout`
(the token never enters workflow history):

1. **prepare** — cart non-empty, every line checkoutable, one currency; address state eligible
   (`GET /v1/markets/eligibility`, content — unreachable ⇒ allowed; `deliveryAllowed=false` ⇒ 422
   `orders.market-disabled`, S050); price snapshot from the cart view; tax per line from the `TaxCalculator` port
   (per-state table until the tax service exists); perishables from catalog `GET /v1/public/variants/{atin}`
   (product type `perishable`); delivery estimate from `POST /v1/promises` (unreachable ⇒ stored estimates).
2. **reserve** — AfroTransact-fulfilled lines, all or nothing, `POST /v1/reservations` with `Idempotency-Key` =
   order id; packages split into shipments per node; shipping fees; 4-digit handover codes.
3. **createPayment** — `POST /v1/payments` (manual capture, `Idempotency-Key` = order id, one line per order line,
   `fulfilment` FBA / SELF).
4. **persistPending** — order + shipments `PENDING_PAYMENT`; the HTTP call returns
   `{orderNumber, clientSecret, status: awaiting_payment, totalMinor, shipments[{deliveryCode}]}` (201; 202 if slow).
5. **wait** for `paymentAuthorized` / `paymentFailed` signals — from `payments.intent.succeeded|failed|canceled`
   (Kafka `at.payment.events`) or `POST /v1/checkout/{number}/confirm` (orders asks payments) — for 15 minutes
   (the reservation TTL).
   With a `couponCode` (behind `Workflow.getVersion("coupons-v1")`): **validateCoupon** (offers
   `POST /v1/coupons/validate`, lines by `offerId`; not applicable ⇒ `422 orders.coupon-not-applicable`) after pricing,
   and **redeemCoupon** (`POST /v1/coupons/redemptions`, `Idempotency-Key` = order id) before the payment; line
   discounts reduce items and tax, shipping discounts reduce shipments. Compensation **releaseCoupon**
   (`POST /v1/coupons/redemptions/{id}/release`); cancelling every live shipment also releases it.
6. **place** — `POST /v1/reservations/{id}/confirm`, shipments → `placed` (actor SYSTEM), order `PLACED`,
   `orders.order.placed`; **clearCart** `POST /v1/carts/{customerId}/ordered` (`cart:service`).

Failures run the compensations in reverse: cancel the PaymentIntent, release the reservation (skipped when the
reservation itself was refused), void the order (`CANCELLED` + reason, `orders.order.cancelled` with
`wasPlaced=false`). The same key replays the same result: the same order, or the same problem
(`409 orders.insufficient-stock`, `402 orders.payment-failed|payment-timeout`, `422 orders.cart-*`,
`orders.market-disabled`, `503 orders.upstream-unavailable`). Same key with a different body ⇒
`409 orders.idempotency-key-reused`.

## Shipment state machine (S059)

`placed → confirmed → packed → ready → picked_up → in_transit → out_for_delivery → delivered`, plus
`delivery_failed`, `returned`, `cancelled` (and `pending_payment` before the payment is authorised). Enforced in the
domain (`DefaultShipmentTransitionPolicy`), never in a UI.

| Step | Seller-fulfilled | Fulfilled by AfroTransact / Retail 1P |
|---|---|---|
| placed → confirmed → packed → ready | **seller only** | platform admins (orders:manage) and fulfilment ops |
| ready → picked_up → in_transit → out_for_delivery | carrier webhooks (carrier shipments), couriers (AfroTransact Delivery) | admins, fulfilment ops, carriers, couriers |
| → delivered | carriers, couriers, platform admins (confirm delivery); seller hands over a pickup order at its shop | admins, fulfilment ops, carriers, couriers |
| → delivery_failed | carriers, couriers, platform admins (record failure) | same + fulfilment ops |
| → returned | carriers / couriers (return to sender), platform admins (process returns, incl. after delivery) | same + fulfilment ops |
| → cancelled (before pickup) | platform admins (reason required) | admins, fulfilment ops |
| → cancelled by the **customer** | until packed (from placed / confirmed) | until packed |

Platform admins can **never** set a seller-fulfilled shipment to confirmed, packed or ready ("platform can't set it to
packaged… only the seller can"). Not your step ⇒ `403 orders.transition-forbidden`; not reachable from the current
status ⇒ `409 orders.invalid-transition` (`orders.cancel-window-closed` for late customer cancels). Carriers and
couriers only move forward: an event for an earlier step, or a scan not newer than the last applied one, is
**ignored**, never applied backwards (a failed delivery can be re-attempted). Every transition records actor type,
id, organisation, reason, source and time, emits `orders.shipment.status_changed`, and is idempotent per
`(shipment, event id)` (`Idempotency-Key`, carrier event id, driver-app event id).

Actor mapping: `SELLER_MEMBER` with `orders:update-status` of the shipment's org → seller; `STAFF` with
`orders:manage` → platform admin, with `fulfilment:operate` → fulfilment ops, with only `orders:update-status` →
platform admin on seller-fulfilled shipments; `SERVICE` with `orders:update-status` (fulfilment-service) → fulfilment
ops; `DRIVER` with `delivery:operate` → courier (courier events only); Kafka carrier events → carrier.

## Money and stock follow-ups (§5, §17b)

Queued in the transition's transaction, executed by `FollowUpJobs` (every 5 s, SKIP LOCKED leases, exponential
backoff, parked as FAILED after 12 attempts):

- **Capture** when a shipment reaches picked_up (carrier acceptance / driver pickup) or ready at a pickup point:
  `POST /v1/payments/{id}/capture` (`Idempotency-Key` `capture-<shipmentId>`, the shipment's lines, items + shipping
  + tax, `finalCapture` when no other live shipment is uncaptured); AfroTransact-fulfilled ⇒
  `POST /v1/reservations/{orderId}/commit`.
- **Settle** when every live shipment is delivered: `POST /v1/settlements/{orderId}/delivered` (settlement hold).
- **Cancel**: captured ⇒ line refund `POST /v1/payments/{id}/refunds`; nothing left ⇒ `POST /v1/payments/{id}/cancel`;
  no AfroTransact shipment left and nothing shipped ⇒ `POST /v1/reservations/{orderId}/release`.

## Delivery preferences, handover codes, courier events (S061)

Checkout takes per package: option (`carrier | express | afrotransact_delivery | pickup`), pickup point, preferences
(handover `hand|door|desk`, safe place, instructions, contact). A 4-digit code is generated for AfroTransact Delivery
or pickup when the package is perishable or the customer wants hand delivery; only its hash and an AES-GCM sealed copy
are stored, and only the customer's own views reveal it. `POST /v1/shipments/{id}/courier-events` (driver app,
`eventId` for offline replays) requires a photo and handover method for delivered; handing to the customer checks the
code; safe place is refused for perishables and hand-delivery orders; someone else needs name, relationship and
signature; failed attempts need a reason. `GET /v1/shipments/{id}/delivery-brief` gives the driver address,
instructions, perishable / code-needed flags (never the code). `GET /v1/shipments/stream` pushes every transition
(staff) or the organisation's (sellers) as server-sent events right after commit.

## API

RFC 9457 problems with `code` `orders.*`. Reference pages `/swagger-ui.html`, `/docs`, `/redoc`; contract
`contracts/openapi/orders.yaml`.

| Method & path | Who | Notes |
|---|---|---|
| `POST /v1/checkout` (`/v1/orders`) | customer | `Idempotency-Key`; starts the saga |
| `GET /v1/checkout/{number}` · `POST …/confirm` | customer | status; client confirmed the payment |
| `GET /v1/orders?customer=me&cursor=&limit=` | customer | Your orders: shipments, timelines, tracking, codes |
| `GET /v1/orders/{number}` | customer / seller / staff `orders:read` | per-viewer view (sellers: own shipments only) |
| `POST /v1/orders/{number}/shipments/{id}/cancel` | customer | until packed |
| `GET /v1/orders/{number}/payment-split` | staff / seller (own row) | from payments `GET /v1/orders/{id}/split` |
| `GET /v1/seller/shipments?status=` | seller `orders:read` (staff: `org=`) | queue with next actions |
| `GET /v1/admin/orders?number&customer&seller&status&shipmentStatus&from&to` | staff `orders:read` | search |
| `POST /v1/shipments/{id}/transitions {to, reason}` | seller / staff / fulfilment service | actor rules above |
| `POST /v1/shipments/{id}/tracking {carrier, trackingNumber}` | seller / staff | carrier label for webhook matching |
| `POST /v1/shipments/{id}/courier-events` · `GET …/delivery-brief` | driver `delivery:operate`; fulfilment SERVICE with `delivery:relay` (actor = body `driverId`); brief also `delivery:dispatch` | proof of delivery |
| `GET /v1/seller/analytics?from=&to=` (`org=` for staff) | seller `orders:read` | sales, orders, checkouts and conversion by day, totals, top products |
| `GET /v1/shipments/stream` | staff / seller `orders:read` | SSE live feed |

## Events

Published on `at.order.events` (key = order id; `contracts/asyncapi/fragments/orders.yaml`):
`orders.order.placed`, `orders.order.cancelled`, `orders.shipment.status_changed`, `orders.shipment.delivered`,
`orders.shipment.cancelled`. `orders.order.placed` shipments carry `nodeId`, `perishable`, ship-to street lines and
`lat`/`lng` for dispatch. Consumed: `payments.intent.*` (`at.payment.events`), `carrier.tracking.updated`
(`at.carrier-shipment.events`, matched by label reference = shipment id or carrier + tracking number).
`carrier.pickup.ready` (same topic, S219): `orderRef` = order id, order number or shipment id; the order's pickup
shipments (matching `customerRef` and pickup location) that are confirmed or packed move to ready with actor
`PICKUP_POINT` (pickup capture follows); ready or later shipments are left alone.

**Live feed across replicas (S219).** `GET /v1/shipments/stream` subscribers live on one replica; every committed
transition is published on the Valkey pub/sub channel `at:orders:shipment-status` and every replica delivers it to
its own subscribers, so staff on any replica see every transition (well within 2 s). `at.orders.live-feed.fanout`
(`LIVE_FEED_FANOUT`): `valkey` (default; `REDIS_URL`) or `local` (single replica). If Valkey is unreachable the
update is still delivered on the replica that applied it; the Redis health indicator is off so Valkey never takes
pods out of service. `LiveFeedFanoutIT` runs two application contexts against one PostgreSQL and one Valkey.

## Upstreams (ports → RestClient adapters)

Service calls use the `orders-service` client-credentials token (`/oauth2/token`, scopes
`payments:service inventory:reserve cart:service`, cached until a minute before expiry). Cart and address book are
read with the customer's token. 4xx = refusal (not retried), 5xx / 408 / 429 / I/O = retried.

## Configuration

`application.yml` + `application-{local,dev,uat,prod}.yml`. Secrets only from the environment: `DB_URL`, `DB_USER`,
`DB_PASSWORD`, `KAFKA_BOOTSTRAP`, `ORDERS_CLIENT_SECRET`, `TEMPORAL_TLS_CERT`, `TEMPORAL_TLS_KEY`,
`HANDOVER_CODE_KEY`. Temporal: `TEMPORAL_TARGET` (local `localhost:7233`), `TEMPORAL_NAMESPACE` (one per
environment, `afrotransact-<env>` on Temporal Cloud with mTLS in dev / uat / prod), `TEMPORAL_START_WORKERS`.
Other knobs: `CHECKOUT_PAYMENT_TIMEOUT` (15m), upstream URLs (`CART_URL`, `INVENTORY_URL`, `PAYMENTS_URL`, `OFFERS_URL`, …),
`ORDERS_JOBS_ENABLED`, `ORDERS_CONSUMERS_ENABLED`, per-state tax table and shipping fees under `at.orders.*`.

## Run and test

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
docker compose -f infra/local/docker-compose.yml up -d postgres kafka temporal
cd java && ./mvnw -B -f services/orders/pom.xml verify     # unit + workflow + ArchUnit + Testcontainers ITs
./mvnw -f services/orders/pom.xml spring-boot:run           # http://localhost:8104/docs
tools/contracts/export_java_openapi.sh orders orders 8104 --spring.temporal.connection.target= \
  --at.orders.consumers.enabled=false --at.orders.jobs.enabled=false
```

Tests: the full actor × fulfilment-type × status matrix (`ShipmentTransitionPolicyTest`), out-of-order carrier
scans, handover / proof rules, totals; `CheckoutWorkflowTest` on Temporal's `TestWorkflowEnvironment` (happy path,
shortage, payment failure, 15-minute timeout with time skipping, idempotent start); integration tests with
PostgreSQL, WireMock upstreams and the in-JVM Temporal test server running the real worker
(`CheckoutIT`, `ShipmentRulesIT`, `CarrierFlowIT`, `CourierFlowIT`).
