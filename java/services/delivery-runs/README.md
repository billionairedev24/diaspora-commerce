# delivery-runs-service

Community delivery for towns outside the regular delivery areas (epic E34). The one customer-facing offer is the
**pre-order window** ([ADR 0005](../../../docs/adr/0005-delivery-runs-payment-fees-tax.md)): shoppers in a window's areas
order from AfroTransact's own stock between its opening and closing dates, **pay at checkout like any other order**, and
everything is delivered on the window's delivery day. At close the paid orders are handed to a **delivery run**, which
batches them: one drive, one sweep of the sellers, one drop session in the town. Windows and runs are announced through
WhatsApp communities, email, SMS, push, social and location-targeted advertorials.
Spring Boot 4.1 · Java 25 · PostgreSQL · local port **8115** · database `deliveryruns`.

AfroTransact delivers in some Texas cities for a flat $7.99; some orders come from towns about an hour away (e.g.
Bastrop from Austin). A run makes those orders economical by batching them.

Payment is not part of a run (ADR 0005 §1): nothing is authorised for later, no capture waits for a minimum, and there
are no card holds that expire. If a window or a run is cancelled, **every order on it is refunded in full, delivery fee
included**, automatically and idempotently, and stock is released. The earlier idea of customers reserving a run slot
with a saved card (S261 as first written) is superseded by windows; the reservation API below remains for runs staff
schedule directly and is not used by checkout.

This pass covers S260 (service and run lifecycle), the pre-order windows (S270, S271) with the staff-set minimum, the
decision for a window that closes below it and cancellation with refunds (ADR 0005 §1 and §4), and the data and API
parts of S261–S265 that belong to this service. The other services' parts are listed under
[Integration plan](#integration-plan).

## Model

| Aggregate | What it holds |
|---|---|
| **Location** | Town / area name, slug (`bastrop-tx`), market, state, IANA time zone. **Area**: `POLYGON` (a geo-service zone id; GeoJSON sent on create is registered in geo as zone owner `delivery-runs:<slug>`) or `RADIUS` (centre + miles). The centre is always set: it is the circle centre and the drive destination for economics. Optional run **origin** (FC / hub; default `at.delivery-runs.origin`). Defaults for new runs: fee (minor units, 799), currency, minimum orders, capacity (orders and/or packages), drop mode (`DOORSTEP` or `PICKUP_POINT` with an address and hours), cutoff policy and extend days. WhatsApp community invite link, social links, `active`. Locations are deactivated, never deleted. |
| **Run** | Location, delivery window (≤ 24 h), order cutoff (before the window), optional `opensAt` (auto-open) and `sellerPickupAt` (sweep time), fee + currency, minimum orders, capacity, drop mode / pickup point, cutoff policy (`EXTEND` by N days once, or `CANCEL`), extensions, reserved orders / packages (counters kept on the row under a lock), route estimate (road miles, drive minutes, geo method), milestones per status, optimistic lock (`version` column). |
| **RunOrder** (slot) | Run ↔ orders-service order id: customer id, packages, status `RESERVED → CONFIRMED` or `RELEASED` / `CANCELLED`, six-digit handover code (pickup-point runs, issued at confirmation), `requestedRunId` when the order was moved to the next run. One active slot per order (partial unique index). The order itself stays in orders-service. |
| **Subscription** | Location + signed-in shopper (`userId`) **or** an email address. Per-channel consent (`EMAIL`, `SMS`, `PUSH`, `WHATSAPP`) with consent time, source and withdrawal time (`subscription_consent`), plus an append-only `consent_log` (`GRANTED`, `CONFIRMED`, `WITHDRAWN`). Email-only subscribers get email only and are `PENDING_CONFIRMATION` until the double opt-in link is used (token stored as SHA-256, 72 h). Every subscription has a one-click unsubscribe token. |
| **Economics** | `cost(n) = (fixedMinutes / 60 × perHour + miles × perMile) + n × (perOrderMinutes / 60 × perHour)`; fixed minutes = drive both ways (geo road minutes) + seller collection (+ one pickup-point stop), per-order minutes = a doorstep stop or a handover. Revenue = fees (+ a commission share when one is reported; not computed here). Break-even = fewest orders whose fees cover the cost (none when the fee does not cover a stop). The numbers are the **economics settings** below. |
| **Economics settings** (S265) | One versioned row (the driver cost model: cost per hour and per mile, collection / per-stop / pickup-point / handover minutes, round trip) edited by staff with a **required reason**, optimistic lock (`If-Match`) and an append-only `economics_settings_change` log (before, after, who, reason, time). Until staff save, version 0 serves the `at.delivery-runs.economics.*` defaults from configuration. Every calculation reads the current row (5 s cache, dropped on save). |
| **Local advertorial** (S263) | Words for the visitors of the place a pre-order window or a delivery run serves: variant (A, B...), weight, priority, headline / body / button label, optional schedule, status `DRAFT`, `LIVE`, `PAUSED`, `ENDED` (+ why: `SOURCE_CLOSED`, `CANCELLED`, `SCHEDULE`, `STAFF`), `generated` (the words follow the window or run until staff edit them) and per-day view and click counters. See *Advertorials*. |

| **Pre-order window** | Name and code (`LBK-7`), targets (country / region / city), opening, closing and delivery dates, door / pickup with their fees, creatives and comms, **`minOrders`** (staff-set; new windows start with `PREORDERS_DEFAULT_MIN_ORDERS`, default 10; 0 = none) and an **outcome** (`AWAITING_DECISION`, `PROCEEDED`, `CANCELLED`; empty while the window simply follows its dates). Status is derived from the dates and the outcome: `DRAFT`, `PAUSED`, `UPCOMING`, `OPEN`, `CLOSED`, `DELIVERED`, `AWAITING_DECISION`, `CANCELLED`. |
| **Delivery fee rule** | One row per method and currency (`AFROTRANSACT_DELIVERY`, `CARRIER`, `PICKUP`, `USD`...), versioned: distance bands (miles, contiguous from 0, at most 12 and 500 miles) or a pickup fee or the carrier handling / fallback / free-above numbers. Every change keeps a `delivery_fee_rule_change` row (before, after, who, reason, time). See *Delivery fees*. |
| **Window decision** | Append-only log per window: `AWAITING` (the system found it below the minimum), `PROCEED`, `CANCEL`, `EARLY_CLOSE`, each with who (staff subject or `system`), when, the reason, the orders that counted and the minimum. |
| **Window order** (projection) | An order's pre-order packages in a window, projected from orders-service events, with its **refund progress** (`PENDING`, `REFUNDED`, `VOIDED`, `FAILED`) once the order was cancelled. |

Domain rules live in `domain/` (no Spring): `RunStatus` transitions, `Run` (lifecycle, capacity, reminders, edits),
`RunTerms` validation, `DefaultCutoffDecisionPolicy`, `Capacity`, `Subscription`, `DefaultRunEconomicsCalculator`,
`PreorderWindow` (status, publish checks, minimum, `awaitingDecision` / `proceed` / `cancel` / `closeEarly`) and
`WindowDecision`.

### Pre-order windows: the minimum, the decision and cancellation (ADR 0005 §1, §4)

```
UPCOMING ─opens─▶ OPEN ─closes, minimum met (or none)─▶ CLOSED ─paid orders handed to a run─▶ DELIVERED
                    │ └─ minimum met early: staff "close early" (reason) ─▶ CLOSED
                    └─ closes below the minimum ─▶ AWAITING_DECISION ─┬─ PROCEED (reason) ─▶ CLOSED (hand-over as usual)
                                                                      └─ CANCEL  (reason) ─▶ CANCELLED
any published, undelivered window ── staff "cancel" (reason) ─▶ CANCELLED
```

- **Minimum.** Staff set `minOrders` on the window (create / edit in the staff console; blank takes the default). Orders
  that count are paid orders and late adds, not cancelled ones.
- **Close below the minimum.** At the closing date the scheduler does **not** cancel anything and hands nothing to a run:
  the window becomes `AWAITING_DECISION`, a `preorder.window.decision_needed` event goes out for staff (the console shows
  a *Needs a decision* tab and notice), and a `AWAITING` entry is logged. It stays there until staff decide, however long
  that takes; it cannot be edited meanwhile.
- **Decide.** `POST /v1/preorders/{id}/decision {decision: CANCEL | PROCEED, reason}` (`preorders:manage`; the reason is
  required, 422 otherwise). `PROCEED` lets the scheduler close the window and hand its paid orders to a run on its next
  tick (within the job interval). `CANCEL` is the same as cancelling (below). Deciding twice is 409.
- **Close early.** An `OPEN` window whose count has reached its minimum can be closed before its closing date
  (`POST …/close-early {reason}`; 422 `delivery-runs.preorder-minimum-not-met` before that). The closing date moves to
  just before now and the scheduler closes it on its next tick.
- **Cancel.** `POST …/cancel {reason}` on any published window that is not delivered: the window becomes `CANCELLED`, the
  run it was handed to (if any, and not yet collecting) is cancelled with it, each counting order is marked refund
  `PENDING`, and `preorder.window.cancelled` (with every order) is published. orders-service consumes it (and
  `delivery-runs.run.cancelled`), cancels the orders' PREORDER shipments through the same path a customer's own
  cancellation uses and reports each one back as `orders.preorder.refund_settled` (`REFUNDED` when a captured payment was
  refunded in full with the delivery fee, `VOIDED` when only an authorisation existed and it was released) or
  `orders.preorder.refund_failed`. delivery-runs projects those onto the window's orders, so staff see the progress per
  order (`GET /v1/preorders/{id}/orders`, `…/refunds`).
- **Audit.** Every decision is in `GET /v1/preorders/{id}/decisions` (who, when, why) and published as
  `preorder.window.decided`.

**Demo on a test environment.** In the staff console (Pre-orders): open a window for a covered city with a short order
window (it closes in a few minutes), set *Minimum orders* to 3, add the hero and publish. Place two orders from the
storefront (test card), or add them as late adds. When the closing time passes (the job runs every
`DELIVERY_RUNS_JOBS_INTERVAL`, 60 s) the window moves to *Needs a decision* and `preorder.window.decision_needed` is
published. Open it, choose *Cancel and refund everyone* with a reason: each order shows *Refunding*, then *Released, not
charged* (authorised only) or *Refunded* (already captured), and the customers get the cancellation email. Or choose *Go
ahead anyway*: the window closes and the run appears. With the minimum met before the closing date, *Close early* ends
ordering at once.

### Delivery fees (ADR 0005 §3)

Fees are configuration that staff manage; nothing about a fee is a constant in code or a payment-settings JSON any more
(`preorder_settings.payment` no longer has `doorFees` / `pickupFees`; migration `V6` carried the old values over as the
first rule versions). A window, a run and checkout resolve a fee the same way (`DeliveryFees`):

1. **AfroTransact delivery** (door, inside coverage): a window's own door-fee override wins and still names the rule.
   Otherwise the band of the location's road distance from the hub (`GeoWindowDistances`, geo, cached 6 h). A distance
   that cannot be measured uses the farthest band, so a fee is never understated. Beyond the last band there is no door
   fee: a door-only window is `on=false` with `routedTo=CARRIER`, and checkout ships the order by carrier.
2. **Carrier** (beyond coverage, shipped): the carrier-gateway quote plus handling, else the fallback; free from a goods
   subtotal. orders-service reads it from `POST /v1/service/delivery-fees/carrier` for standard packages (express keeps
   its flat rates; if this service cannot answer, orders-service keeps its flat rates too).
3. **Pickup**: the pickup fee (often 0; free when no rule exists), or the window's override.

The order records a snapshot (method, rule id and version, band, distance, amount, source) on its shipment, so a later edit
never changes a past order. Saves are optimistic (`If-Match` / `ETag` = version), a save that changes nothing makes no new
version, and a reason (at most 500 characters) is required.

| Method | Path | |
|---|---|---|
| GET | `/v1/delivery-fees` | all rules, `delivery-runs:read` |
| PUT | `/v1/delivery-fees/{method}/{currency}` | set a rule `{bands / feeMinor / handlingMinor / fallbackMinor / freeAboveMinor, reason}` with `If-Match`; `preorders:manage` |
| GET | `/v1/delivery-fees/changes` | audit trail, newest first (`method`, `currency`, `limit`) |
| POST | `/v1/delivery-fees/preview` | what a customer would pay: the same resolution checkout runs |
| POST | `/v1/service/delivery-fees/carrier` | service call from orders: the carrier rule for a quote and a subtotal |
| GET | `/v1/public/delivery-fees` | the rules the storefront estimate follows (carrier) |

The staff BFF exposes them as `/v1/staff/delivery-fees` (screen: Settings, Money, Delivery fees in the staff console).

### Advertorials (S263)

Advertorials live in delivery-runs, not in content-service. They are generated from, shown for, counted against and
ended by the pre-order windows and runs this service owns; keeping them here means the closing time that expires them,
the orders that are attributed to them (a pre-order's tracking tag carries `cr=adv-<id>`) and the audience that sees
them are all read from one database, with no copy that can outlive its window. (The storefront and the staff console
reach them through the gateway-bff; content-service keeps the generic home placements.)

- **Generated.** Publishing, opening or resuming a window, and announcing or opening a run, create variant A from the
  window's or run's own details (town, delivery day, closing countdown, fee) as `LIVE`; changing the window or extending
  the run re-writes the words of a generated variant that staff have not edited.
- **Authored.** Staff write further variants (B, C...) with a weight (share of visitors), a priority and an optional
  schedule; they stay `DRAFT` until published.
- **Served.** `GET /v1/public/advertorials` matches the visitor's ZIP or coordinates against run areas (geo coverage)
  and country / region / city against window targets, keeps only sources taking orders right now and inside the
  advertorial's own schedule, and picks one variant per source by weight, stably per `visitor` id.
- **Expired.** A window closing, being delivered or awaiting a decision ends its advertorials (`SOURCE_CLOSED`), a
  cancelled window or run ends them (`CANCELLED`), a run confirmed or on its way ends its advertorials, and an `endsAt`
  in the past ends one (`SCHEDULE`). An ended advertorial is never served again and cannot be edited.
- **Results.** Views and clicks are counted per day; a window's orders are the pre-orders whose tracking tag carries the
  advertorial's `cr=adv-<id>`. Runs' orders are not attributed.

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
  Cancelling a run publishes `delivery-runs.run.cancelled`; orders-service then refunds every order on it in full
  (delivery fee included) and releases stock, and customers are told.
- **Capacity**: a reservation that does not fit (orders or packages) or arrives after the cutoff goes to the
  location's next open run with room (up to 5 later runs, earliest cutoff first); `allowNextRun=false` refuses with
  409 instead. Capacity can never be lowered below what is reserved.
- Edits: `DRAFT` / `ANNOUNCED` runs change freely; `OPEN` / `EXTENDED` runs keep fee, currency, cutoff and drop mode.

### Seller pick lists and run progress (S264)

When a window closes into a run (`fromWindow`), the run gets a **seller pickup time** (`sellerPickupAt`): the cutoff plus the
packing time (2 h) at the earliest, `at.delivery-runs.preorders.seller-pickup-lead` (default `PT24H`) before the window opens
otherwise, and never later than an hour before the window (`SellerPickupSchedule`). `delivery-runs.run.confirmed` carries
`sellers[]` (organisation id and name) so notifications can tell each seller.

- **Pick list.** `GET /v1/seller/run-pickups?orgId=` and `/{runId}` return, for one organisation, the run's town and
  window, the pickup time, the total units **per product** across all orders (`items`) and **one bag per order**
  (`bags[]`, by order number, with the lines of that seller only and the bag's stage). Staff (`delivery-runs:read`) and
  service tokens pass `orgId`; a seller needs `orders:read` on the organisation (`ResourceAccessChecker`), the same check as
  the rest of Seller Center. `GET /v1/runs/{id}/pick-lists` gives staff every seller's list, and `GET /v1/runs/{id}/progress`
  the orders per stage. A dedicated permission would need an identity change and is a follow-up.
- **Progress from real events.** `RunProgressService` consumes orders' `orders.shipment.status_changed` and
  `orders.shipment.delivered` for PREORDER shipments (consumer `delivery-runs-progress`, deduplicated by event id, forward
  only) and moves each run order through `PENDING → COLLECTED → OUT_FOR_DELIVERY → DELIVERED` (or `FAILED` / `RETURNED`):
  `picked_up` collects, `out_for_delivery` puts it on the road, `delivery_failed` and `returned` are recorded, `delivered`
  stores `deliveredAt`. A cancelled shipment frees its slot. The run then follows by itself:
  first collection → `COLLECTING` (event `delivery-runs.run.collecting`), every order collected → `OUT_FOR_DELIVERY`,
  every order delivered or returned → `COMPLETED`. Replays and late events change nothing.
- **Delivered starts the payout hold.** The `delivered` event is orders' own: orders-service records it, then calls
  payments' `POST /v1/settlements/{order}/delivered` with `deliveredAt`, which starts the existing seller hold window. A
  run's `completed` never marks packages delivered; it is the result of them being delivered.
- **Staff override.** The `start-collecting`, `out-for-delivery` and `complete` transitions stay for the case where the
  drivers' events did not arrive (the dispatch console offers them with a confirmation).

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
| GET | `/v1/runs/{id}/pnl?commissionShareMinor=` | P&L with its phase: `BEFORE` (estimate at the orders reserved so far), `AFTER` (completed), `CANCELLED` (nothing earned or spent; `ordersRefunded`, `refundedFeesMinor`, `cancelReason`) |

#### Economics settings and window economics (S265; `delivery-runs:read`, writes `preorders:manage`)

| Method | Path | |
|---|---|---|
| GET / PUT | `/v1/economics-settings` | the cost model; `ETag` is the version (`0` = configuration defaults). PUT takes the seven numbers and a **required `reason`**, `If-Match`; 412 `delivery-runs.version-mismatch`, 422 `delivery-runs.invalid-economics` per field |
| GET | `/v1/economics-settings/changes?limit=` | the audit trail, newest first |
| POST | `/v1/preorders/economics-preview {locationId?, targets?, door?, pickup?, doorFeeMinor?, pickupFeeMinor?, minOrders?, expectedOrders?}` | break-even for the window being created, beside its minimum (`minimumCoversBreakEven`); the location is the one given or the one serving the first city target |
| GET | `/v1/preorders/{id}/economics` | a window's orders, revenue, delivery fees, estimated cost, contribution margin, break-even; for a cancelled window `ordersRefunded`, `refundsSettled`, `refundedMinor` and no revenue |

#### Advertorials (S263; `delivery-runs:read`, writes `preorders:manage`)

| Method | Path | |
|---|---|---|
| GET | `/v1/advertorials?source=&sourceId=&status=&limit=` | variants with their results (views, clicks, click rate, orders) and whether each is `serving` now |
| GET / PUT | `/v1/advertorials/{id}` | get / edit (words, weight, priority, schedule); `If-Match`; not an ended one; editing stops it following the window |
| POST | `/v1/advertorials/generate {source, sourceId}` | create the generated variant A from the window or run (or refresh its words); 409 for a cancelled source |
| POST | `/v1/advertorials` | write a variant (a `DRAFT` until published) |
| POST | `/v1/advertorials/{id}/publish`, `/pause`, `/end` | lifecycle, `If-Match` |

### Staff: pre-order windows (`delivery-runs:read` for reads, `preorders:manage` for writes)

Windows, creatives, orders, summary, exports, late adds, settings and coverage areas are under `/v1/preorders/**`,
`/v1/preorder-settings` and `/v1/preorder-areas` (see the contract). For ADR 0005 §4:

| Method | Path | |
|---|---|---|
| POST / PUT | `/v1/preorders`, `/v1/preorders/{id}` | body includes `minOrders` (null: the default on create, unchanged on edit) |
| GET | `/v1/preorders?status=decision` | list tabs: `all`, `open`, `upcoming`, `decision`, `closed`, `draft`; the page carries `defaultMinOrders` |
| POST | `/v1/preorders/{id}/decision {decision, reason}` | cancel and refund everyone, or proceed, for a window in `AWAITING_DECISION` |
| POST | `/v1/preorders/{id}/cancel {reason}` | cancel any published, undelivered window and refund every order |
| POST | `/v1/preorders/{id}/close-early {reason}` | close an open window whose minimum is met |
| GET | `/v1/preorders/{id}/decisions` | decision log |
| GET | `/v1/preorders/{id}/refunds` | refund progress: orders per `PENDING` / `REFUNDED` / `VOIDED` / `FAILED` |

### Public (no sign-in) and shopper

| Method | Path | |
|---|---|---|
| GET | `/v1/public/runs/available?zip=` or `?lat=&lng=` | open runs whose area covers the address (polygons via geo zone coverage, radii via geo distances), earliest cutoff first, with `ordersLeft`, `full`, `runUrl` |
| GET | `/v1/public/locations`, `/v1/public/locations/{slug}` | location page data: upcoming runs, WhatsApp community link, social links, UTM share links (Facebook, X, WhatsApp) |
| GET | `/v1/public/runs/{id}/advertorial` | advertorial data (S263): town, delivery date / day, window, cutoff, `countdownTarget`, `expiresAt` (= cutoff), fee and `feeDisplay`, drop mode, headline, CTA label, `ctaUrl` (`utm_medium=advertorial`), area targeting fields. 404 for drafts |
| GET | `/v1/public/advertorials?zip=` or `?lat=&lng=` or `?country=&region=&city=` (+ `visitor`, `limit`) | the advertorials a visitor sees: only windows and runs that take orders now, one variant each (stable per `visitor`), higher priority first, each with `countdownTarget`, `expiresAt` and a `ctaUrl` carrying UTM and, for windows, order attribution `src=page&cr=adv-<id>&po=<code>` |
| POST | `/v1/public/advertorials/{id}/events {type: VIEW\|CLICK}` | count a view or a click (204; best effort) |
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
| GET | `/v1/seller/run-pickups[/{runId}]?orgId=` | `delivery-runs:read`, or `orders:read` on the organisation | the organisation's pick lists (S264) |
| GET | `/v1/runs/{id}/pick-lists`, `/v1/runs/{id}/progress` | `delivery-runs:read` | every seller's pick list; orders per stage (S264) |
| GET | `/v1/service/locations/{id}/audience?channel=&after=&limit=` | `delivery-runs:audience.read` | opted-in subscribers on a channel (user id or email + unsubscribe URL), keyset-paged |

## Events (transactional outbox → `at.delivery-runs.events`)

Documented in [`contracts/asyncapi/fragments/delivery-runs.yaml`](../../../contracts/asyncapi/fragments/delivery-runs.yaml).
Key = run id or subscription id; headers `event-id`, `event-type`, `occurred-at`.

| Event | When | Extra |
|---|---|---|
| `delivery-runs.run.announced` | staff announce | |
| `delivery-runs.run.opened` | staff open, or `opensAt` | |
| `delivery-runs.run.cutoff_reminder` | `reminderLead` (24 h) before the cutoff, once per cutoff | |
| `delivery-runs.run.confirmed` | minimum met at cutoff, or staff | `orders[]` with handover codes, `sellers[]` (organisation id and name) |
| `delivery-runs.run.extended` | policy (once) or staff | `previousCutoffAt`, `reason` |
| `delivery-runs.run.cancelled` | policy or staff | `orders[]` (order and customer ids), `reason`; orders-service refunds every order in full |
| `delivery-runs.run.collecting` | first order collected, or staff | `sellers[]`, `orders[]` |
| `delivery-runs.run.out_for_delivery` / `.completed` | every order collected / delivered or returned (from orders' shipment events), or staff | `orders[]` |
| `preorder.window.decision_needed` | the window closed below its minimum (scheduler) | notifies staff; `decision` {AWAITING, orders, minimum}, `minOrders` |
| `preorder.window.decided` | staff proceeded, cancelled or closed early | `decision` {kind, reason, actor, at, orders, minimum} (audit) |
| `preorder.window.cancelled` | the window was cancelled | `orders[]` (every order to refund), `decision` |
| `delivery-runs.subscription.created` | sign-up or channels added | `confirmUrl` for pending email subscribers |
| `delivery-runs.subscription.confirmed` | double opt-in used | |
| `delivery-runs.subscription.cancelled` | channels withdrawn | `changedChannels`; `status` CANCELLED when none left |

Run payloads carry what notifications needs without calling back: location id / slug, town, market, state, time
zone, window, cutoff, seller pickup, pre-formatted `deliveryDay`, `windowDisplay`, `cutoffDisplay`, fee and
`feeDisplay`, drop mode and pickup point, minimum / reserved / capacity, `runUrl` and `locationUrl` built from
`PUBLIC_WEB_URL`, `links.{email,sms,push,whatsapp,social}` (same link with UTM per channel, `utm_campaign =
delivery-run-<slug>-<yyyymmdd>`, `utm_content = <event>`) and the WhatsApp community link.

## Integration plan

What the other services do. **Done for ADR 0005**: orders (window checkout, pay at order, cancel before close with a full
refund, and the cancellation consumer below), payments (settlement at delivery), notifications (window comms, the
cancelled-pre-order wording), the BFF and the staff console (minimum, decision, cancel, refund progress). **Still to do**:
the run-level items for fulfilment, content, analytics and the storefront location pages, and the rows marked *(not
built)*.

| Service | Work |
|---|---|
| **orders** (S261, S272) | Checkout turns cart lines in an open window into a `PREORDER` package (door or pickup, fee from the window, charged like any order: one payment at checkout, no reservation of a run slot, no delayed capture). The customer may cancel for a full refund until the window closes. **Cancellation consumer** (`DeliveryRunsEventsListener`, topic `at.delivery-runs.events`): `preorder.window.cancelled` cancels every placed PREORDER shipment of the window, `delivery-runs.run.cancelled` those of the orders it lists, by the same state machine and CANCEL follow-up as a customer's cancellation (refund the captured payment in full, delivery fee included, or release the authorisation; release stock held for the lines), idempotent per shipment, publishing `orders.preorder.refund_settled` / `refund_failed`. |
| **payments** (S261) | Orders' manual-capture intent is captured per shipment when the package leaves (existing flow); a cancel refunds a captured shipment in full or cancels the authorisation. Seller payout: the order's delivery starts the settlement hold (`settlement-hold`, 7 days) and the seller net is then transferred; the window's delivery fee stays with AfroTransact (ledger 4250). |
| **fulfilment** (S264) | Consume `delivery-runs.run.confirmed` → `GET /v1/service/runs/{id}/dispatch` (scope `delivery-runs:read`): one route per run (split by capacity) with seller pickups at `sellerPickupAt` (sellers from the orders' lines) and FC load-out before drops; doorstep drops ordered by road distance (geo `POST /v1/distance/batch`), or one stop at the pickup point with per-order handover codes; proof of delivery as for regular deliveries. Advance the run with `POST /v1/runs/{id}/start-collecting`, `/out-for-delivery`, `/complete` (scope `delivery-runs:manage` until a narrower dispatch scope exists). Seller Center shows run pickup times and pick lists from fulfilment. |
| **notifications** (S262) | Customers of a cancelled pre-order get the existing `shipment.cancelled` email / push, worded for pre-orders ("AfroTransact cancelled this pre-order. We're refunding the full …, delivery fee included" or "you haven't been charged"). Staff are told through `preorder.window.decision_needed` *(no staff e-mail or chat recipient exists in notifications yet; the console shows the tab and notice)*. Consume `at.delivery-runs.events`. `run.announced`, `run.opened`, `run.cutoff_reminder`, `run.out_for_delivery`: fan out to `GET /v1/service/locations/{locationId}/audience?channel=EMAIL|SMS|PUSH|WHATSAPP` (scope `delivery-runs:audience.read`), using `links.<channel>` and each member's `unsubscribeUrl`; WhatsApp only with approved templates to WHATSAPP-consented members. `run.confirmed` / `run.cancelled` / `run.out_for_delivery`: message the customers in `orders[]` (handover code for pickup-point runs). `subscription.created` with `confirmUrl`: double opt-in email; `subscription.confirmed`: welcome with `whatsappInviteUrl`; `subscription.cancelled`: stop those channels. Signed-in subscribers are resolved from `userId` (verified contacts in identity). |
| **content** (S263) | **Done inside delivery-runs** (see *Advertorials*): generation, targeting, expiry and results are here, so content-service needs nothing for them. Not built: featured products inside an advertorial (the card shows town, day, fee, countdown and the button). |
| **gateway-bff / storefront / apps** (S262, S263) | **Done for the web.** gateway-bff `Delivery runs` operations (`/v1/delivery-runs/locations[/{slug}]`, `/subscriptions[/confirm|/unsubscribe]`, `/advertorials[/{id}/events]`, `/v1/account/run-subscriptions`) relay the public endpoints and the shopper's token for `/v1/me/run-subscriptions`; the anonymous POSTs share the BFF's lookup rate limit. Storefront: `/delivery-runs`, `/delivery-runs/<slug>` (UTM on the link is kept on the sign-up `source`, e.g. `location-page.wa`), `/delivery-runs/subscriptions/confirm?token=` and `/unsubscribe?token=` (ask first, then POST, so mail scanners change nothing), Account, Delivery alerts, and the advertorial card on the home page and location pages. **Not built:** the mobile customer app (it has no pre-order or alert screens to extend), and a captcha at the edge. |
| **staff console** (S260, S263, S265, S270) | **Done for S263 and S265:** Settings, Money, *Run economics* (cost model with a reason and the change history, and what a draft changes for a location); the pre-order wizard shows the break-even beside the minimum order count; a window's overview shows its P&L (or the refunds when cancelled); Pre-orders, *Delivery runs* shows per location the orders of every run, subscriber counts by channel and monthly growth, and the P&L of a run before, after or cancelled; an *Advertorials* tab per window and a panel per run (generate, write, edit, publish, pause, end, results). Not part of this work: the screens that create and schedule locations and runs (S260), which is where the run economics preview (`POST /v1/runs/economics-preview`) would be shown. Pre-orders: minimum field in the wizard, *Needs a decision* tab, decision banner (cancel and refund everyone / go ahead), cancel and close-early with a required reason, decision log and refund progress per order. |
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
| `DRIVER_COST_PER_HOUR_MINOR`, `DRIVER_COST_PER_MILE_MINOR` | 2500, 70 | driver cost model: **defaults only**, used until staff save the cost model in Settings, Run economics (then the saved row wins) |
| `RUN_COLLECTION_MINUTES`, `RUN_PER_STOP_MINUTES`, `RUN_PICKUP_POINT_STOP_MINUTES`, `RUN_HANDOVER_MINUTES_PER_ORDER` | 45, 6, 30, 1 | time model: the same defaults |
| `DELIVERY_RUNS_REMINDER_LEAD`, `DELIVERY_RUNS_CONFIRMATION_TTL` | `24h`, `72h` | reminder lead, double opt-in link lifetime |
| `at.delivery-runs.preorders.seller-pickup-lead` (`AT_DELIVERY_RUNS_PREORDERS_SELLER_PICKUP_LEAD`) | `PT24H` | how long before the window opens the driver collects from sellers (S264) |
| `DELIVERY_RUNS_JOBS_ENABLED`, `DELIVERY_RUNS_JOBS_INTERVAL` | `true`, `60s` | cutoff job |
| `PREORDERS_DEFAULT_MIN_ORDERS` | `10` | minimum order count a new pre-order window starts with (staff change it per window; 0 = none) |
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
- Pre-order minimum and decisions: `PreorderMinimumTest` (domain rules), `PreorderDecisionIT` (default and set minimum,
  closing below it waits and publishes the staff event, proceed / cancel with a required, logged reason, cancel with its
  run, early close, refund progress per order; also runs without Docker against `AT_IT_POSTGRES_URL=jdbc:postgresql://…`),
  `PreorderIT` (the scheduler, hand-over to a run, projection).
- ITs (local `imresamu/postgis:17-3.5.4-alpine3.22` and `apache/kafka:4.1.0` images): `RunFlowIT` (slots, roll-over, cutoff job
  confirm / extend / cancel, concurrent replicas, outbox payloads), `SubscriptionIT` (double opt-in, consent log,
  counts, audience), `HttpApiIT` (HTTP API and problems), `OutboxRelayIT` (events on `at.delivery-runs.events`).

## Known gaps

- **Delivery fees.** Express carrier packages stay on orders-service's flat rates, and carrier-gateway live quotes are not
  yet passed from orders (the carrier rule uses its fallback fee). The payments `DeliveryPricing` for AfroTransact
  Delivery from a *seller's store* is a separate rule set and is unchanged. Distance is per location (hub to the location
  centre), not per address. Changing fees uses `preorders:manage` (no separate permission).

- **Charge timing.** ADR 0005 says customers are charged at checkout. orders-service still authorises at checkout and
  captures per shipment when the package leaves (existing, shared with every order). A window cancelled before pick-up
  therefore usually *releases* the authorisation (`VOIDED`: nothing was taken) rather than refunding a captured payment
  (`REFUNDED`); both leave the customer whole, delivery fee included. Moving to capture-at-order is an orders / payments
  change, not a delivery-runs one.
- **Stock.** PREORDER packages are not inventory-tracked in orders-service (they sell goods AfroTransact already holds),
  so cancelling releases a stock reservation only if inventory has one for the lines (best effort, 404 ignored).
- **Run-level automatic decisions.** `RunScheduleJob` still decides runs staff schedule *directly* at their cutoff
  (`CutoffPolicy` EXTEND / CANCEL, auto-cancel `MINIMUM_NOT_MET`). Runs created from windows are `CONFIRMED` at hand-over
  and never reach that job; the window's own minimum and the staff decision are what ADR 0005 §4 asks for.
- **No separate permission.** Deciding, cancelling and closing early use `preorders:manage` (identity is unchanged); a
  dedicated `preorders:decide` is a follow-up. The log shows the staff subject id, not a name.
- **Staff notification.** `preorder.window.decision_needed` is published, but notifications has no staff recipient list yet;
  staff see the *Needs a decision* tab and notice in the console.
- **Proceed is eventual.** After PROCEED the window reads `CLOSED` at once and the run appears on the scheduler's next tick.
- **Delivery status (closed, S264).** Orders' shipment events drive the run (see *Seller pick lists and run progress*);
  a run's `completed` still does not mark packages delivered, it follows them. Remaining: the override transitions are not
  audited with a reason or actor, and the progress follows PREORDER shipments only.
- **Seller pick lists.** Sellers cannot print bag labels; a seller's
  pickup has no coordinates, so there is no proof-of-delivery distance check at the seller's door, and the pickup point of a
  pickup-point run has no latitude and longitude either (no 150 m check there). Reading a pick list reuses `orders:read`;
  a dedicated permission needs identity. Seller notification rules (pickup time reminder) belong to notifications; the
  `run.confirmed` event carries the sellers for them.

- The local stack does not create the `deliveryruns` database yet (`infra/local/postgres/init-databases.sh`).
- Run ordering is not geographic inside the town (fulfilment orders drops); economics fold local driving into the
  per-stop minutes.
- **Economics.** The cost model is a staff-editable row (reason and audit); the commission share is still passed in by
  the caller because analytics does not report it yet (revenue is then delivery fees only). A staff edit applies to
  every later estimate, including runs already shown; nothing recalculates a stored result, because none is stored.
- **WhatsApp.** The location page shows the community invite and consent is recorded per channel, but WhatsApp
  *messages* are off until the templates are approved by Meta (notifications, `DELIVERY_RUNS_WHATSAPP_ENABLED`).
- **Advertorial attribution.** Window orders are attributed through `cr=adv-<id>`; UTM parameters are kept on alert
  sign-ups (`source`) but the storefront has no product-analytics sink to report them to (packages/telemetry is error
  tracking only).
- Rate limiting of anonymous sign-ups belongs to the edge / BFF.
