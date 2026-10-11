# delivery-runs-service

Community delivery runs (epic E34) for towns outside the regular delivery areas: customers in a town preorder for a
scheduled run, sellers' items are collected in one sweep and everything is delivered at once. Runs are announced
through WhatsApp communities, email, SMS, push, social and location-targeted advertorials.
Spring Boot 4.1 · Java 25 · PostgreSQL · local port **8115** · database `deliveryruns`.

AfroTransact delivers in some Texas cities for a flat $7.99; some orders come from towns about an hour away (e.g.
Bastrop from Austin). A run makes those orders economical by batching them: one drive, one sweep of the sellers, one
drop session in the town.

This pass covers S260 (service and run lifecycle) and the data and API parts of S261–S265 that belong to this service.
The other services' parts are listed under [Integration plan](#integration-plan).

## Model

| Aggregate | What it holds |
|---|---|
| **Location** | Town / area name, slug (`bastrop-tx`), market, state, IANA time zone. **Area**: `POLYGON` (a geo-service zone id; GeoJSON sent on create is registered in geo as zone owner `delivery-runs:<slug>`) or `RADIUS` (centre + miles). The centre is always set: it is the circle centre and the drive destination for economics. Optional run **origin** (FC / hub; default `at.delivery-runs.origin`). Defaults for new runs: fee (minor units, 799), currency, minimum orders, capacity (orders and/or packages), drop mode (`DOORSTEP` or `PICKUP_POINT` with an address and hours), cutoff policy and extend days. WhatsApp community invite link, social links, `active`. Locations are deactivated, never deleted. |
| **Run** | Location, delivery window (≤ 24 h), order cutoff (before the window), optional `opensAt` (auto-open) and `sellerPickupAt` (sweep time), fee + currency, minimum orders, capacity, drop mode / pickup point, cutoff policy (`EXTEND` by N days once, or `CANCEL`), extensions, reserved orders / packages (counters kept on the row under a lock), route estimate (road miles, drive minutes, geo method), milestones per status, optimistic lock (`version` column). |
| **RunOrder** (slot) | Run ↔ orders-service order id: customer id, packages, status `RESERVED → CONFIRMED` or `RELEASED` / `CANCELLED`, six-digit handover code (pickup-point runs, issued at confirmation), `requestedRunId` when the order was moved to the next run. One active slot per order (partial unique index). The order itself stays in orders-service. |
| **Subscription** | Location + signed-in shopper (`userId`) **or** an email address. Per-channel consent (`EMAIL`, `SMS`, `PUSH`, `WHATSAPP`) with consent time, source and withdrawal time (`subscription_consent`), plus an append-only `consent_log` (`GRANTED`, `CONFIRMED`, `WITHDRAWN`). Email-only subscribers get email only and are `PENDING_CONFIRMATION` until the double opt-in link is used (token stored as SHA-256, 72 h). Every subscription has a one-click unsubscribe token. |
| **Economics** | `cost(n) = (fixedMinutes / 60 × perHour + miles × perMile) + n × (perOrderMinutes / 60 × perHour)`; fixed minutes = drive both ways (geo road minutes) + seller collection (+ one pickup-point stop), per-order minutes = a doorstep stop or a handover. Revenue = fees (+ a commission share when one is reported; not computed here). Break-even = fewest orders whose fees cover the cost (none when the fee does not cover a stop). Settings `at.delivery-runs.economics.*`. |

Domain rules live in `domain/` (no Spring): `RunStatus` transitions, `Run` (lifecycle, capacity, reminders, edits),
`RunTerms` validation, `DefaultCutoffDecisionPolicy`, `Capacity`, `Subscription`, `DefaultRunEconomicsCalculator`.

### Lifecycle

```
DRAFT ─announce─▶ ANNOUNCED ─open (staff, or opensAt)─▶ OPEN ─cutoff─▶ CUT_OFF ─┬─ minimum met / staff ─▶ CONFIRMED
  └──────────── open ─────────────────────────────────▲                         ├─ EXTEND policy, once ─▶ EXTENDED ─cutoff─▶ CUT_OFF …
                                                                                └─ otherwise ───────────▶ CANCELLED
CONFIRMED ─start-collecting─▶ COLLECTING ─out-for-delivery─▶ OUT_FOR_DELIVERY ─complete─▶ COMPLETED
```

- `OPEN` and `EXTENDED` take preorders until the cutoff instant. Staff can extend `OPEN`, `EXTENDED` or `CUT_OFF` runs
  (new cutoff; the window and seller pickup move by as much unless given).
- **At cutoff** the job closes ordering and decides: minimum met → `CONFIRMED` (slots confirmed, handover codes
  issued); otherwise the run's policy: `EXTEND` moves cutoff, window and seller pickup by `extendDays` **once** (a run
  already extended by staff or policy, or one whose extended cutoff would already be past, is cancelled); `CANCEL`
  cancels with reason `MINIMUM_NOT_MET`.
- Staff can confirm early (closes ordering, overrides the minimum) and cancel anything up to `CONFIRMED` with a reason.
- **Capacity**: a reservation that does not fit (orders or packages) or arrives after the cutoff goes to the
  location's next open run with room (up to 5 later runs, earliest cutoff first); `allowNextRun=false` refuses with
  409 instead. Capacity can never be lowered below what is reserved.
- Edits: `DRAFT` / `ANNOUNCED` runs change freely; `OPEN` / `EXTENDED` runs keep fee, currency, cutoff and drop mode.

### Cutoff job

`RunScheduleJob` (every `at.delivery-runs.jobs.interval`, 60 s): auto-open (`opensAt`), the cutoff reminder
(`reminderLead`, 24 h; sent once per cutoff, re-armed by an extension) and the cutoff decision. Safe on every replica:
due run ids are listed without locks, then each run is processed in its own transaction under
`SELECT … FOR UPDATE SKIP LOCKED` and re-checked after locking, so a run is decided exactly once and one failing run
does not block the rest (`RunFlowIT.cutoffIsDecidedExactlyOnceAcrossConcurrentReplicas` runs four ticks concurrently).
Reservations lock the run row (`FOR UPDATE`, later runs in cutoff order) so capacity is never oversold.

## API

Contract: [`contracts/openapi/delivery-runs.yaml`](../../../contracts/openapi/delivery-runs.yaml) (exported with
`tools/contracts/export_java_openapi.sh delivery-runs deliveryruns 8115`). Interactive docs when running:
`/swagger-ui.html`, `/docs` (Scalar), `/redoc` (off in prod). Errors are RFC 9457 problems with stable codes
(`delivery-runs.invalid-transition` 409, `delivery-runs.run-full` / `run-closed` 409, rule violations 422,
`delivery-runs.geo-unavailable` 502, validation 400).

### Staff (`delivery-runs:read` for reads, `delivery-runs:manage` for writes)

| Method | Path | |
|---|---|---|
| POST / GET | `/v1/locations`, `/v1/locations?active=` | create / list |
| GET / PUT / DELETE | `/v1/locations/{id}` | get / replace / deactivate |
| GET | `/v1/locations/{id}/history` | runs with orders and fees, subscriber counts, 12 months of subscriber growth (S265) |
| GET | `/v1/locations/{id}/subscribers/counts`, `/v1/subscribers/counts` | total, active, pending, active per channel |
| POST / GET | `/v1/runs`, `/v1/runs?locationId=&status=&limit=` | schedule (DRAFT; response has the route estimate and `breakEvenOrders`) / list |
| GET / PUT / DELETE | `/v1/runs/{id}` | get (with `breakEvenOrders`) / reschedule / delete a DRAFT |
| POST | `/v1/runs/{id}/announce`, `/open`, `/extend {cutoffAt, windowStart?, windowEnd?, sellerPickupAt?, reason?}`, `/cancel {reason}`, `/confirm`, `/start-collecting`, `/out-for-delivery`, `/complete` | transitions |
| POST | `/v1/runs/economics-preview {locationId, feeMinor?, minOrders?, capacityOrders?, dropMode?, expectedOrders?, commissionShareMinor?}` | cost, break-even and P&L before creating a run (S265) |
| GET | `/v1/runs/{id}/economics?commissionShareMinor=` | P&L at the run's reserved / confirmed orders (before and after the run) |
| GET | `/v1/runs/{id}/orders?status=` | run slots |

### Public (no sign-in) and shopper

| Method | Path | |
|---|---|---|
| GET | `/v1/public/runs/available?zip=` or `?lat=&lng=` | open runs whose area covers the address (polygons via geo zone coverage, radii via geo distances), earliest cutoff first, with `ordersLeft`, `full`, `runUrl` |
| GET | `/v1/public/locations`, `/v1/public/locations/{slug}` | location page data: upcoming runs, WhatsApp community link, social links, UTM share links (Facebook, X, WhatsApp) |
| GET | `/v1/public/runs/{id}/advertorial` | advertorial data (S263): town, delivery date / day, window, cutoff, `countdownTarget`, `expiresAt` (= cutoff), fee and `feeDisplay`, drop mode, headline, CTA label, `ctaUrl` (`utm_medium=advertorial`), area targeting fields. 404 for drafts |
| POST | `/v1/public/subscriptions {locationId, email, source}` | email sign-up, double opt-in (always 202) |
| POST | `/v1/public/subscriptions/confirm {token}` | confirm (repeat clicks are harmless) |
| POST | `/v1/public/subscriptions/unsubscribe {token, channels?}` | one-click unsubscribe, per channel or all |
| GET / POST | `/v1/me/run-subscriptions`, `{locationId, channels, source}` | signed-in shopper: list / subscribe or add channels (active at once) |
| DELETE | `/v1/me/run-subscriptions/{locationId}?channel=` | withdraw channels (none = all) |

### Service clients

| Method | Path | Permission | |
|---|---|---|---|
| POST | `/v1/service/runs/{runId}/reservations {orderId, customerId, packages?, allowNextRun?}` | `delivery-runs:reserve` | take a slot; 201 new, 200 repeat (idempotent per order id); `moved` + `runId` when rolled over |
| DELETE | `/v1/service/runs/{runId}/reservations/{orderId}` | `delivery-runs:reserve` | release (idempotent; refused once out for delivery) |
| GET | `/v1/service/runs/{runId}/dispatch` | `delivery-runs:read` | confirmed orders with handover codes, pickup point, seller pickup time |
| GET | `/v1/service/runs/dispatchable` | `delivery-runs:read` | runs `CONFIRMED` / `COLLECTING`, earliest window first |
| GET | `/v1/service/locations/{id}/audience?channel=&after=&limit=` | `delivery-runs:audience.read` | opted-in subscribers on a channel (user id or email + unsubscribe URL), keyset-paged |

## Events (transactional outbox → `at.delivery-runs.events`)

Documented in [`contracts/asyncapi/fragments/delivery-runs.yaml`](../../../contracts/asyncapi/fragments/delivery-runs.yaml).
Key = run id or subscription id; headers `event-id`, `event-type`, `occurred-at`.

| Event | When | Extra |
|---|---|---|
| `delivery-runs.run.announced` | staff announce | |
| `delivery-runs.run.opened` | staff open, or `opensAt` | |
| `delivery-runs.run.cutoff_reminder` | `reminderLead` (24 h) before the cutoff, once per cutoff | |
| `delivery-runs.run.confirmed` | minimum met at cutoff, or staff | `orders[]` with handover codes |
| `delivery-runs.run.extended` | policy (once) or staff | `previousCutoffAt`, `reason` |
| `delivery-runs.run.cancelled` | policy or staff | `orders[]` (order and customer ids), `reason` |
| `delivery-runs.run.out_for_delivery` / `.completed` | staff (later fulfilment) | `orders[]` |
| `delivery-runs.subscription.created` | sign-up or channels added | `confirmUrl` for pending email subscribers |
| `delivery-runs.subscription.confirmed` | double opt-in used | |
| `delivery-runs.subscription.cancelled` | channels withdrawn | `changedChannels`; `status` CANCELLED when none left |

Run payloads carry what notifications needs without calling back: location id / slug, town, market, state, time
zone, window, cutoff, seller pickup, pre-formatted `deliveryDay`, `windowDisplay`, `cutoffDisplay`, fee and
`feeDisplay`, drop mode and pickup point, minimum / reserved / capacity, `runUrl` and `locationUrl` built from
`PUBLIC_WEB_URL`, `links.{email,sms,push,whatsapp,social}` (same link with UTM per channel, `utm_campaign =
delivery-run-<slug>-<yyyymmdd>`, `utm_content = <event>`) and the WhatsApp community link.

## Integration plan

What the other services do for S261–S265 (nothing below is implemented in them yet):

| Service | Work |
|---|---|
| **orders** (S261) | Checkout: when the address is outside regular delivery, call `GET /v1/public/runs/available?lat=&lng=` (or `zip=`) and offer the first run that is not `full` (date, cutoff, `feeMinor` as the delivery fee). Placing a preorder: `POST /v1/service/runs/{runId}/reservations` (client scope `delivery-runs:reserve`, order id as the idempotency key); use the returned `runId` / fee when `moved`; 409 `delivery-runs.run-full` / `run-closed` refuses the order. Hold stock and authorise the card (saved for off-session) without capture. Customer cancels before cutoff → `DELETE /v1/service/runs/{runId}/reservations/{orderId}` + void. Consume (inbox) `delivery-runs.run.confirmed` → capture each order in `orders[]` (off-session charge when the authorisation would have expired); `delivery-runs.run.cancelled` → release stock and payment holds, cancel the orders; `delivery-runs.run.extended` → keep holds, re-authorise if the new cutoff is past the hold expiry; `.out_for_delivery` / `.completed` → order status. |
| **payments** (S261) | Manual-capture intents with `setup_future_usage=off_session` for preorders; capture or off-session charge when orders asks at confirmation; cancel intents on run cancellation (driven by orders, no direct dependency). |
| **fulfilment** (S264) | Consume `delivery-runs.run.confirmed` → `GET /v1/service/runs/{id}/dispatch` (scope `delivery-runs:read`): one route per run (split by capacity) with seller pickups at `sellerPickupAt` (sellers from the orders' lines) and FC load-out before drops; doorstep drops ordered by road distance (geo `POST /v1/distance/batch`), or one stop at the pickup point with per-order handover codes; proof of delivery as for regular deliveries. Advance the run with `POST /v1/runs/{id}/start-collecting`, `/out-for-delivery`, `/complete` (scope `delivery-runs:manage` until a narrower dispatch scope exists). Seller Center shows run pickup times and pick lists from fulfilment. |
| **notifications** (S262) | Consume `at.delivery-runs.events`. `run.announced`, `run.opened`, `run.cutoff_reminder`, `run.out_for_delivery`: fan out to `GET /v1/service/locations/{locationId}/audience?channel=EMAIL|SMS|PUSH|WHATSAPP` (scope `delivery-runs:audience.read`), using `links.<channel>` and each member's `unsubscribeUrl`; WhatsApp only with approved templates to WHATSAPP-consented members. `run.confirmed` / `run.cancelled` / `run.out_for_delivery`: message the customers in `orders[]` (handover code for pickup-point runs). `subscription.created` with `confirmUrl`: double opt-in email; `subscription.confirmed`: welcome with `whatsappInviteUrl`; `subscription.cancelled`: stop those channels. Signed-in subscribers are resolved from `userId` (verified contacts in identity). |
| **content** (S263) | On `run.announced` / `opened` / `extended`, create or refresh an auto-generated advertorial from `GET /v1/public/runs/{id}/advertorial` (town, date, live countdown to `countdownTarget`, fee, `ctaUrl`; featured products from content), targeted by deliver-to ZIP / town (match with `GET /v1/public/runs/available?zip=` or the targeting fields `areaKind`, `geoZoneId`, centre, `radiusMiles`); expire at `expiresAt` (the cutoff) and on `run.cancelled`; results from views, clicks (`utm_medium=advertorial`) and attributed orders. Staff-authored variants stay in content. |
| **gateway-bff / storefront / apps** | Route `<API_BASE_URL>/delivery-runs`; screen APIs over the public endpoints (checkout run offer, location page `/delivery-runs/<slug>?run=<id>`, advertorial slot) and the shopper endpoints with token relay (`/v1/me/run-subscriptions`). Storefront pages `/delivery-runs/subscriptions/confirm?token=` and `/unsubscribe?token=` call `POST /v1/public/subscriptions/confirm` / `unsubscribe`. Rate-limit (and captcha) the anonymous `POST /v1/public/subscriptions` at the edge. |
| **staff console** (S260, S265) | Locations and runs screens (create, schedule, announce, open, extend, cancel, confirm), economics preview when creating a run (`breakEvenOrders`), run P&L (`/v1/runs/{id}/economics`), location history and subscriber counts. |
| **geo** | Works today with zone type `SERVICE_AREA` (owner `delivery-runs:<slug>`). Recommended: a dedicated `RUN_AREA` zone type, then set `DELIVERY_RUNS_GEO_ZONE_TYPE=RUN_AREA`. |
| **identity** | Permissions, roles and the `delivery-runs-service` client (see below). |
| **analytics** (S265) | Report the commission share per run; staff pass it as `commissionShareMinor` to the economics endpoints. |

### Permissions and clients (to add in identity)

- Permissions: `delivery-runs:read`, `delivery-runs:manage` (staff: operations managers, dispatchers; super admin),
  `delivery-runs:reserve` (orders-service client), `delivery-runs:audience.read` (notifications-service client).
- Client scopes: `orders-service` += `delivery-runs:reserve`; `notifications-service` += `delivery-runs:audience.read`;
  `fulfilment-service` += `delivery-runs:read delivery-runs:manage`; `content-service` needs none (public reads).
- New client `delivery-runs-service` (client credentials) with scope `geo:zones:write`, secret
  `DELIVERY_RUNS_CLIENT_SECRET`; only used to register polygon areas in geo. Geo's coverage, geocode and distance
  endpoints are public.

## Configuration

| Setting | Default | |
|---|---|---|
| `PUBLIC_WEB_URL` | `http://localhost:3000` (dev/uat/prod profiles fall back to their own storefront) | every link in events, pages and advertorials |
| `DELIVERY_RUNS_LOCATION_PATH` | `/delivery-runs` | storefront path of location pages |
| `GEO_URL`, `GEO_TIMEOUT` | `http://localhost:8205` (`http://geo` in clusters), `3s` | geo-service |
| `DELIVERY_RUNS_GEO_ZONE_TYPE` | `SERVICE_AREA` | zone type of run-area polygons |
| `AUTH_TOKEN_URL`, `DELIVERY_RUNS_CLIENT_ID`, `DELIVERY_RUNS_CLIENT_SECRET` | `<AUTH_ISSUER_URL>/oauth2/token`, `delivery-runs-service`, — | client credentials for polygon registration |
| `DELIVERY_RUNS_ORIGIN_LAT` / `_LNG` | Austin (30.2672, −97.7431) | default run origin |
| `DRIVER_COST_PER_HOUR_MINOR`, `DRIVER_COST_PER_MILE_MINOR` | 2500, 70 | driver cost model |
| `RUN_COLLECTION_MINUTES`, `RUN_PER_STOP_MINUTES`, `RUN_PICKUP_POINT_STOP_MINUTES`, `RUN_HANDOVER_MINUTES_PER_ORDER` | 45, 6, 30, 1 | time model |
| `DELIVERY_RUNS_REMINDER_LEAD`, `DELIVERY_RUNS_CONFIRMATION_TTL` | `24h`, `72h` | reminder lead, double opt-in link lifetime |
| `DELIVERY_RUNS_JOBS_ENABLED`, `DELIVERY_RUNS_JOBS_INTERVAL` | `true`, `60s` | cutoff job |
| `DB_URL`, `DB_USER`, `DB_PASSWORD`, `KAFKA_BOOTSTRAP` | local stack | secrets in `afrotransact/<env>/delivery-runs` |

Profiles: `local` (security off, every call is the local super admin), `dev`, `uat`, `prod` (reference pages off,
10 % trace sampling). Helm chart: `deploy/helm` (at-service library; Argo CD picks it up from
`java/services/*/deploy/helm`).

## Run and test

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
cd java && ./mvnw -B -f services/delivery-runs/pom.xml verify     # checkstyle, unit, ArchUnit, Testcontainers ITs
./mvnw -B -f services/delivery-runs/pom.xml spring-boot:run        # local profile on :8115 (needs DB deliveryruns)
```

- Unit: `RunLifecycleTest` (state machine, edits, reminders), `CutoffPolicyTest`, `CapacityTest`, `EconomicsTest`,
  `SubscriptionTest`, `HttpGeoClientTest` (geo contract on WireMock), `ArchitectureTest`.
- ITs (local `imresamu/postgis:17-3.5.4-alpine3.22` and `apache/kafka:4.1.0` images): `RunFlowIT` (slots, roll-over, cutoff job
  confirm / extend / cancel, concurrent replicas, outbox payloads), `SubscriptionIT` (double opt-in, consent log,
  counts, audience), `HttpApiIT` (HTTP API and problems), `OutboxRelayIT` (events on `at.delivery-runs.events`).

## Known gaps

- The local stack does not create the `deliveryruns` database yet (`infra/local/postgres/init-databases.sh`).
- Run ordering is not geographic inside the town (fulfilment orders drops); economics fold local driving into the
  per-stop minutes.
- Settings for economics are configuration, not a staff-editable table.
- Rate limiting of anonymous sign-ups belongs to the edge / BFF.
