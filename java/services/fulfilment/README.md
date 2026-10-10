# fulfilment-service

AfroTransact's own fulfilment and delivery network: fulfilment centres, pick/pack of FBA and Retail 1P shipments,
delivery dispatch (routes per zone, drivers, reassignment, SOS, live stream) and the server side of the offline-first
driver app (access check, vehicle inspection, load-out and stop scans, seller pickups, proof of delivery, delivery
problems, ordered and idempotent sync).
Spring Boot 4.1 · Java 25 · PostgreSQL `fulfilment` · local port **8108**.
Epic E12, stories S081–S088 (server side). Design: `design/AfroTransact Driver.dc.html` and the Backoffice
"Delivery dispatch" module.

**orders-service owns shipment status.** Fulfilment never sets it: pick/pack and staging call orders'
`POST /v1/shipments/{id}/transitions` as fulfilment ops, and driver actions are forwarded to
`POST /v1/shipments/{id}/courier-events` (orders checks the photo, the customer's handover code, safe place for
perishables, and applies its forward-only state machine).

## What it owns

| Table | What it holds |
|---|---|
| `fulfilment_centre` | Centres / nodes (same id as inventory's node, e.g. `HOU-FC1`): address, position, cold chain |
| `fc_task` | Pick/pack task per FBA_AFROTRANSACT / RETAIL_1P shipment: node, lines (picked qty, lots), status `PICK_PENDING → PICKING → PICKED → PACKED → READY` (+ `PICK_EXCEPTION`, `CANCELLED`), ATS label, weight, carrier label, `version` |
| `delivery_shipment` | Dispatch pool: every AFROTRANSACT_DELIVERY shipment, origin `FC` (load-out) or `SELLER` (pickup stop), ZIP, perishable, handover code required, ATS label, status `AWAITING_ROUTE · ROUTED · DELIVERED · FAILED · CANCELLED` |
| `delivery_route` · `route_stop` · `route_package` | Route aggregate: zone, day, depot, driver, vehicle, status `PLANNED → ASSIGNED → IN_PROGRESS → COMPLETED` (`CANCELLED`), inspection result, blocked flag, odometers; stops (`PICKUP`/`DROPOFF`, status `PENDING · ARRIVED · DONE · FAILED · RETRY · SKIPPED · REASSIGNED · CANCELLED`, contact log, reattempts, handover method, POD distance); packages (label, origin, status `EXPECTED · LOADED · PICKED_UP · NOT_PICKED_UP · DELIVERED · FAILED · TO_PICKUP_POINT · MISSING · RETURNED · REASSIGNED · CANCELLED`, scan flags, `manual_entry`) |
| `vehicle_inspection` · `vehicle_odometer` · `vehicle_defect` | Inspections (report + outcome), odometer history per vehicle, defects raised to dispatch |
| `driver_position` | Last known position per driver (S255, V3): PostGIS `geography(Point)`, time, accuracy, source `SYNC` / `PING`; GiST index for KNN |
| `pickup_assignment` | Who got a seller pickup and why: `AUTO_NEAREST` (distances) or `DISPATCH_OVERRIDE` (reason, previous route, actor) |
| `driver_access` | Last known access per driver (`ACTIVE`/`REVOKED`, when, why, source `IDENTITY · TOKEN · DISPATCH`) |
| `driver_device` · `driver_event` | Per-device sync cursor (`last_sequence`) and every driver event received with its outcome (idempotency by event id; handover codes redacted) |
| `sos_alert` | SOS alerts (one per app event id) until dispatch acknowledges |
| `orders_command` | Durable queue of orders-service calls retried with backoff, in order per shipment |
| `platform_outbox` / `platform_inbox` | Events out (`at.fulfilment.events`); consumed orders / identity events de-duplicated |

## Fulfilment centres and pick/pack (FC basics)

`orders.order.placed` (topic `at.order.events`) creates, per shipment:

- **FBA_AFROTRANSACT / RETAIL_1P** → an `fc_task` at the node of inventory's reservation (`GET /v1/reservations/{orderId}`,
  SKU = variant ATIN; inventory down ⇒ `at.fulfilment.default-node` or assign by hand).
- **AFROTRANSACT_DELIVERY** → a dispatch-pool entry (`FC` origin when AfroTransact fulfils it, `SELLER` otherwise).
- Each gets one **ATS label** (`ATS` + 9 base-36, Code 128) used from pack to doorstep.

| Step | Effect in orders-service (service token, `Idempotency-Key fulfilment:<task>:<step>`) |
|---|---|
| `POST /v1/fc/tasks/{id}/pick/start` | `confirmed` |
| `POST /v1/fc/tasks/{id}/pick` (all units, or `shortReason` ⇒ `PICK_EXCEPTION`) | — |
| `POST /v1/fc/tasks/{id}/pack` (weight; carrier shipments need the carrier-gateway label) | `packed` (+ `POST /v1/shipments/{id}/tracking` for carrier shipments) |
| `POST /v1/fc/tasks/{id}/ready` | `ready` |

`orders.shipment.cancelled` cancels the task and takes the shipment off a route that has not started (started routes
get a `shipment.cancelled` stream alert).

## Dispatch console (S088, `delivery:dispatch`)

- **Routes CRUD**: `POST /v1/dispatch/routes` (optionally with pool shipments), `GET /v1/dispatch/routes?date&status&driverId`,
  `GET …/{routeId}`, `POST …/{routeId}/shipments`, `DELETE …/{routeId}/stops/{stopId}`, `POST …/{routeId}/cancel`.
  One drop-off per order; one pickup per seller for seller-origin packages, visited before the drop-offs.
- **Route builder**: `POST /v1/dispatch/routes/build {serviceDate, depotNodeId, maxDropoffs, window}` groups the
  depot's waiting shipments by **delivery zone** — geo-service ZIP centroid (`GET /v1/zip/{zip}`, or the order's
  position when orders sends it) → active `DELIVERY` zone covering it (`GET /v1/zones/covering`); no zone ⇒ grouped by
  ZIP. Drop-offs sorted by ZIP, capped per route, an order never split.
- **Assign**: `POST …/{routeId}/assign {driverId, vehicleId}` (a new vehicle needs a new inspection).
- **Reassign failed stops**: `POST …/{routeId}/stops/{stopId}/reassign {targetRouteId}` for failed, retry, skipped or
  pending drop-offs. Packages still on the old van are marked to be scanned back into the FC; the new route loads them
  at the FC (or picks them up at the seller when they never left).
- **Defects**: `GET /v1/dispatch/defects`, `POST /v1/dispatch/defects/{id}/resolve {routeId}` unblocks the route
  (the driver re-inspects before starting). **SOS**: `GET /v1/dispatch/sos`, `POST /v1/dispatch/sos/{id}/acknowledge`.
- **Driver events**: `GET /v1/dispatch/events?routeId&driverId`, and the **live stream** `GET /v1/dispatch/stream`
  (server-sent events): `driver.event` (every synced event with its outcome), `driver.sos` (HIGH), `vehicle.defect`,
  `route.updated`, `stop.reassigned`, `shipment.cancelled`, `orders.forward_failed`, `session.revoked`,
  `driver.sos_acknowledged`. Pushed right after commit; in-process per replica (consoles reconnect and re-read).
- **Drivers**: `POST /v1/dispatch/drivers/{id}/suspend|reinstate` (immediate sign-out, see S081).
- `GET /v1/dispatch/shipments?nodeId` (pool, with `shipBy`, `pickupLocationId` and the pickup position),
  `GET /v1/dispatch/forward-failures` (orders calls that failed).
- **Seller pickups (S259)**: `GET /v1/dispatch/pickups?routed=&sellerOrgId=&pickupLocationId=&serviceDate=&limit=` lists
  SELLER-origin shipments waiting for a route **and** already routed, each with its pickup location and, once routed,
  route, driver, day, route status, pickup stop and package status (`routed=true|false` narrows; `serviceDate` keeps
  routed pickups of that day's routes, unrouted ones always show). Pickup stops are **one per seller ship-from
  location** (`route_stop.pickup_location_id`, V4) and route stops return `pickupLocationId`.

## Nearest driver for seller pickups (S255)

AfroTransact Delivery from the seller's store: the seller packs, an AfroTransact driver collects at the seller's
pickup location (`orders.order.placed` `shipments[].pickup {locationId, lat, lng}`, stored on the dispatch-pool entry
and as the position of the route's PICKUP stop).

- **Positions.** Every synced driver event carries the device GPS: the newest fix of each `POST /v1/driver/sync` batch
  (captured before any revocation) becomes the driver's last known position; the app can also ping
  `POST /v1/driver/location {deviceId, lat, lon, accuracyM, capturedAt}` (`delivery:operate`). Stored as PostGIS
  geography with its time; an older fix never overwrites a newer one; fixes more than 2 minutes in the future are
  refused. Positions older than `DRIVER_POSITION_STALE_AFTER` (10 min) are ignored.
- **Candidates.** For a SELLER-origin pickup: its delivery zone (geo `GET /v1/zones/covering`); the 200 nearest fresh
  positions by **KNN** (`position <-> pickup` on the GiST index); kept when the driver's access is not revoked and they
  drive an `ASSIGNED` or `IN_PROGRESS` route **today** (`DISPATCH_ZONE`, America/Chicago) **in that zone**, not blocked,
  with a **passed vehicle inspection** (`PASSED` / `PASSED_WITH_DEFECTS`) and **room** (fewer than
  `ROUTE_MAX_PACKAGES` = 40 packages expected / loaded / picked up). One row per driver (their route with most room),
  the nearest `NEAREST_DRIVER_CANDIDATES` (20) by air.
- **Ranking.** The candidates are re-ranked by **road distance** from geo (`POST /v1/distance/batch`, pickup → each
  driver position: geo's routing providers, Google Routes API / OSRM, with PostGIS geodesic × detour as geo's last
  resort); when geo is down, by straight-line distance (`STRAIGHT_LINE`). Ties: straight line, then driver id.
- **Auto-assignment.** When the pickup is created (`orders.order.placed`, `NEAREST_DRIVER_ENABLED`), it joins the best
  candidate's route (pickup stop at the store, before its drop-offs), is `ROUTED`, logged (`AUTO_NEAREST` with the
  distances) and published as `fulfilment.pickup.auto_assigned`; the driver gets `route.updated` on their stream and
  dispatch `pickup.auto_assigned`. No position, no zone or no candidate ⇒ it stays `AWAITING_ROUTE` and the **zone
  route builder** remains the fallback.
- **Dispatch.** `GET /v1/dispatch/pickups/{id}/driver-suggestions` → top 5 `{driverId, routeId, routeName, zone,
  straightLineMiles, roadMiles, durationMinutes, distanceMethod, positionAt, packagesOnRoute, capacityLeft}`.
  `POST /v1/dispatch/pickups/{id}/assign {routeId, reason (5+ chars)}` overrides — from the pool, or from another route
  while the package was not collected and its drop-off carries only this shipment (`409 fulfilment.pickup-not-movable`)
  — logged as `DISPATCH_OVERRIDE` (reason, previous route, dispatcher) and published as
  `fulfilment.pickup.dispatch_assigned`. `GET /v1/dispatch/pickups/{id}/assignments` lists the history.

## Driver app integration

The Expo driver app is built against this API (`contracts/openapi/fulfilment.yaml`, tag **Driver app**).

### Auth and access (S081)

- Sign in with identity-service (email + password + 6-digit TOTP or passkey; staff principal). The access token must
  carry **`delivery:operate`** (Driver role `platform.driver`, or granted directly, e.g. to an ops admin).
- `GET /v1/driver/me` right after sign-in: `200 {driverId, access, permissions, routeIds}`, or `403` problem
  - `fulfilment.no-driver-access` → show "No driver access" with `grantHint` ("A developer or super admin can grant
    delivery:operate in Backoffice → Access control, either by giving you the Driver role or as a direct permission.")
  - `fulfilment.driver-access-revoked` (`signOut: true`, `revokedAt`) → sign out, **keep the outbox**.
- Revocation sources: identity events (`identity.role.revoked` of `platform.driver`, `identity.permission.revoked` of
  `delivery:operate`, `identity.user.disabled`), a token that no longer carries the permission, or a dispatcher
  suspension. The app learns it from `session.revoked` on `GET /v1/driver/stream`, from `signOut: true` on the next
  sync, or from a 403 on `/me`. **Events captured on the device up to `revokedAt` are still accepted** (sync with the
  last token, or a new token without the permission); later ones are `REJECTED fulfilment.driver-access-revoked`.
  A token issued after a re-grant works again; a dispatch suspension is lifted only by `reinstate`.

### Offline data

`GET /v1/driver/routes` (or `/v1/driver/routes/{id}`) returns each open route with stops (order, kind, address/ZIP,
window, `handoverRequired`, `safePlaceAllowed`) and packages (label, origin FC/SELLER, pickup and drop-off stop,
perishable, status, scan flags). Customer address details, instructions and contact come from orders-service
`GET /v1/shipments/{id}/delivery-brief` with the driver's token. Cache both for offline work.

### Sync protocol (S087)

Every action is an event in the on-device outbox with a **client-generated `eventId`** (UUID) and a **per-device
`sequence`** (1, 2, 3 … never reused; persist the counter with the outbox). Send batches (≤ 200) when online:

```
POST /v1/driver/sync
{ "deviceId": "ios-7f3a…", "events": [ { "eventId": "…", "sequence": 42, "type": "delivery.completed",
  "routeId": "RT-…", "stopId": "…", "capturedAt": "2026-10-10T19:31:02Z", "lat": 29.7376, "lon": -95.5134,
  "delivery": { … } } ] }
→ { "deviceId": "…", "lastSequence": 42, "expectedSequence": 43, "signOut": false, "revokedAt": null,
    "results": [ { "eventId": "…", "sequence": 42, "outcome": "APPLIED", "code": null, "message": null,
                   "replayed": false, "forwardPending": false } ] }
```

- Events are applied **strictly in sequence order**, each in its own transaction, each **at most once**.
- Outcomes: `APPLIED`; `REJECTED` (a rule failed — show `message`, fix, queue a **new** event); `CONFLICT` (the route
  or stop moved on, e.g. reassigned by dispatch, or a sequence number was reused); `DEFERRED` (an earlier sequence is
  missing — resend from `expectedSequence`). REJECTED / CONFLICT events are consumed (the queue never blocks).
- Drop everything up to `lastSequence` from the outbox. Resending a batch is safe: known event ids return the stored
  outcome with `replayed: true`.
- `forwardPending: true` (code `fulfilment.forward-pending`): applied here, orders-service unreachable — forwarded by
  the retry queue in order. Proof of delivery is forwarded live with the driver's token so a wrong handover code comes
  back as `REJECTED orders.handover-code-invalid`.
- **SOS**: `POST /v1/driver/sos {deviceId, event}` immediately and keep the same event in the outbox; SOS events in a
  batch are raised before anything else, even behind a sequence gap (`fulfilment.driver.sos_raised` priority HIGH +
  `driver.sos` on the dispatch stream). One alert per event id.
- Photos: capture with the live in-app camera only, upload to media-service (`POST /v1/uploads`), then reference the
  asset id with capture metadata `{assetId, source: LIVE_CAMERA, capturedAt, lat, lon, accuracyM, stampRouteId,
  sha256}`. Gallery photos are rejected (`fulfilment.photo-not-live`).

### Event types

| `type` | Payload | Rules (codes) |
|---|---|---|
| `session.signed_in` | — | — |
| `inspection.submitted` | `inspection {vehicleId, checks{TYRES,BRAKES,LIGHTS,MIRRORS,HORN_WIPERS,NO_LEAKS,CARGO_AREA,COLD_BOX,SAFETY_KIT,PHONE_MOUNT: PASS/FAIL}, odometerMiles, fuelPercent, coldBoxF, photos{FRONT,BACK,LEFT,RIGHT}, defectNote, certified, signatureAssetId}` | all 10 items; odometer ≥ vehicle's last; fuel 0–100; cold box **33–40 °F when perishables are on the route**; 4 live photos; defect note when anything fails; certified + signature. **Brakes, tyres or lights FAIL ⇒ route blocked + critical defect to dispatch** (`route.started` → `fulfilment.route-blocked`); other failures report a defect without blocking |
| `loadout.scanned` | `scan {label, method: CAMERA\|MANUAL}` | label `ATS`+9; must be on the route's FC manifest (`fulfilment.label-unknown`); not already scanned (`fulfilment.label-already-scanned`); MANUAL is accepted and **flagged** (`manualEntry`) |
| `loadout.completed` | — | every FC package scanned → orders `picked_up` |
| `route.started` | — | assigned, inspection passed, not blocked, load-out complete → orders `out_for_delivery` |
| `stop.arrived` | `stopId` | drop-off packages must be on board ("pick up from the seller first") |
| `stop.scanned` | `stopId`, `scan` | label expected at this stop, not already scanned |
| `pickup.confirmed` | `pickup {handedOverBy, missingReason}` | name of seller staff; **every expected label scanned or a reason**; collected → orders `picked_up`, `out_for_delivery`; uncollected go back to the pool |
| `pickup.problem` | `pickupProblem {type: NOT_READY\|CLOSED\|WRONG_COUNT\|DAMAGED, resolution: WAIT\|SKIP\|PARTIAL, note, photo}` | not ready: wait / skip; closed: skip; wrong count: partial / skip; damaged: skip / partial; note required; photo for wrong count and damaged |
| `customer.contacted` | `contact: CALL\|TEXT\|WAIT_STARTED` | logged per delivery attempt |
| `delivery.completed` | `delivery {handoverMethod: CUSTOMER\|SOMEONE_ELSE\|SAFE_PLACE, handoverCode, recipientName, relationship, signatureAssetId, safePlace, photo}` | all packages scanned; **photo always**, live, stamped with time, GPS and the route id, within **150 m** of the address; customer ⇒ 4-digit code (verified by orders-service); someone else ⇒ name, relationship, signature; **safe place refused for perishables** and when the customer did not allow it → orders `delivered` |
| `delivery.problem` | `problem {type: CUSTOMER_UNAVAILABLE\|ACCESS_ISSUE\|DAMAGED\|REFUSED\|MISSING\|UNSAFE, resolution: REATTEMPT\|PICKUP_POINT\|RETURN_TO_FC\|ACCEPTED_WITH_DAMAGE\|REPORT_MISSING, note, photo}` | **customer unavailable needs a call, a text and a 5-minute wait** (`customer.contacted` before the report) before reattempt / pickup point / return; notes (5+ chars) for the others; photos for access, damage, unsafe; at most 2 reattempts; failed ⇒ orders `delivery_failed` |
| `return.scanned` | `scan` | failed / pickup-point packages scanned back into the FC |
| `shift.ended` | `odometer` | no stop pending, arrived or **retry** (reattempts resolved first); **failed packages scanned back**; odometer > start |
| `sos.raised` | `sos: BREAKDOWN\|ACCIDENT\|UNSAFE\|MEDICAL`, `note`, position | dispatch alerted immediately |

## Events (transactional outbox → `at.fulfilment.events`)

`fulfilment.task.created|picked|pick_exception|packed|ready|cancelled`,
`fulfilment.route.planned|assigned|started|completed|cancelled`, `fulfilment.stop.delivered|failed|skipped|reassigned`,
`fulfilment.vehicle.defect_reported`, `fulfilment.driver.sos_raised` (priority HIGH),
`fulfilment.pickup.auto_assigned|dispatch_assigned` (S255),
`fulfilment.driver.access_revoked`, `fulfilment.orders.forward_failed` — see
[`contracts/asyncapi/fragments/fulfilment.yaml`](../../../contracts/asyncapi/fragments/fulfilment.yaml).

Consumed (group `fulfilment-service`, inbox-deduplicated): `orders.order.placed`, `orders.shipment.cancelled`
(`at.order.events`); `identity.role.granted|revoked`, `identity.permission.granted|revoked`,
`identity.user.disabled|enabled` (`at.identity.events`).

## Permissions and upstreams

| Key | Who | Used for |
|---|---|---|
| `fulfilment:operate` | platform.fulfilment_ops, operations_manager | centres, pick/pack (`/v1/centres`, `/v1/fc/**`) |
| `delivery:dispatch` | platform.dispatcher, fulfilment_ops, operations_manager | dispatch console (`/v1/dispatch/**`) |
| `delivery:operate` | platform.driver (or direct grant) | driver app (`/v1/driver/**`) |

Service calls use the `fulfilment-service` client-credentials token (`/oauth2/token`, scopes
`orders:update-status inventory:reserve`, cached until a minute before expiry): orders transitions / tracking, inventory
reservations. Courier events are sent with the **driver's own token** during a live sync; queued retries use the
service token, which orders-service does not accept on courier events yet (see the gap below — such commands retry,
then show up in `forward-failures`). geo-service ZIP / zone lookups are public. 4xx = refusal, 5xx / 408 / 429 / I/O
(and 401 / 403 for queued relays) = retried.

Known gaps in other services (tracked in the hand-off report): orders-service should accept courier events relayed by
`fulfilment-service` on behalf of `driverId` (new scope, e.g. `delivery:relay`), and `orders.order.placed` should carry
per shipment `nodeId`, `perishable` and the ship-to position / street line; inventory has no pick-confirmation or bin
location API yet.

## Configuration

`application.yml` + `application-{local,dev,uat,prod}.yml`. Secrets only from the environment: `DB_URL`, `DB_USER`,
`DB_PASSWORD`, `KAFKA_BOOTSTRAP`, `FULFILMENT_CLIENT_SECRET`. Other knobs: `AUTH_ISSUER_URL`, `ORDERS_URL`,
`INVENTORY_URL`, `GEO_URL`, `UPSTREAM_TIMEOUT`, `FULFILMENT_DEFAULT_NODE`, `POD_MAX_DISTANCE_METERS` (150),
`REATTEMPT_WAIT` (5m), `MAX_REATTEMPTS` (2), `NEAREST_DRIVER_ENABLED` (true), `DRIVER_POSITION_STALE_AFTER` (10m),
`ROUTE_MAX_PACKAGES` (40), `NEAREST_DRIVER_CANDIDATES` (20), `DISPATCH_ZONE` (America/Chicago), `ORDERS_RELAY_INTERVAL` (5s), `ORDERS_RELAY_MAX_ATTEMPTS` (20),
`FULFILMENT_JOBS_ENABLED`, `CONSUMERS_ENABLED`, `OTEL_EXPORTER_OTLP_ENDPOINT`, `TRACE_SAMPLING`.
Profiles: `local` (security off, local client secret), `dev`, `uat`, `prod` (API reference pages off).

## Run and test

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
make up                                                     # Postgres, Kafka … from infra/local
cd java && ./mvnw -B -f services/fulfilment/pom.xml verify  # checkstyle, unit, ArchUnit, Testcontainers ITs
./mvnw -f services/fulfilment/pom.xml spring-boot:run       # http://localhost:8108/docs
tools/contracts/export_java_openapi.sh fulfilment fulfilment 8108 --at.consumers.enabled=false \
  --at.fulfilment.jobs.enabled=false
```

Tests: domain rules — inspection (`DefaultInspectionPolicyTest`), proof of delivery (`DefaultProofOfDeliveryPolicyTest`),
reattempt policy (`DefaultReattemptPolicyTest`), sync ordering and access (`DefaultSequencePolicyTest`), the route
workflow (`DeliveryRouteTest`), zone planner; integration tests with PostgreSQL (PostGIS image) and WireMock for
identity, orders, inventory and geo: pick/pack ↔ orders (`PickPackIT`), a full driver day through `/v1/driver/sync`
incl. replay, gap and SOS on the live stream (`DriverDayIT`; S258: the "handover codes are never stored" check reads
`payload -> 'delivery' ->> 'handoverCode'` of the stored delivery events, which must all be `****`, instead of a
substring match on the whole payload that random ids could hit; 20 consecutive runs green), access and revocation (`DriverAccessIT`),
reassignment and forwarding while orders is down (`ReassignAndOfflineIT`), positions, KNN availability filters,
road-distance ranking, auto-assignment, override and builder fallback against PostGIS, plus routed / unrouted pickup
listing with one stop per pickup location (`NearestDriverIT`).

Deploy: `deploy/helm` (depends on `infra/helm/library/at-service`), one Argo CD Application per environment.
