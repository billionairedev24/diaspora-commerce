# bookings-service

Provider services and prices, team members and roles, working hours and live slot generation, the booking flow with
deposits through payments-service, booking requests, reschedules and cancellation policies.
Spring Boot 4.1 · Java 25 · PostgreSQL `bookings` · local port **8109**. Epic E15 (stories S105–S110, S221), long-dated
bookings S220; design source `design/AfroTransact Provider Hub.dc.html` and `design/at-bookings.js`.

## What it owns

- **Provider setup** per organisation: business name, IANA time zone, currency, location and the booking rules from
  the Working hours screen: minimum notice, booking horizon, buffer, slot interval, daily limit per member, instant
  booking, auto-accepting reschedules.
- **Reviews** (S221): the customer of a completed booking rates it once, 1–5 stars with an optional text; search shows
  each service's average and count.
- **Team** (S110): members with role Owner, Manager, Practitioner or Finance, linked to their identity user id, each
  with their own **weekly hours** (several ranges a day = breaks) and **date overrides** (time off or special hours).
- **Services & prices** (S106): duration, price, deposit, modes (`IN_PERSON`, `AT_HOME`, `VIRTUAL`), add-ons (price and
  extra time), who performs them, and the provider's **take-home** (price − commission − card fee on the deposit
  charge and on the balance charge) under the fees payments applies to the provider (see *Fees*). Bookings copy the
  service, so later price changes don't affect them.
- **Slots** (S105): computed live from hours, overrides, rules and existing appointments (buffer on both sides),
  in the provider's zone, skipping start times that don't exist on daylight-saving days. Changes are visible to
  customers immediately (`Cache-Control: no-store`).
- **Bookings** (S107/S108) with the design's statuses: `PENDING_PAYMENT → REQUESTED → CONFIRMED → COMPLETED | NO_SHOW`,
  `DECLINED`, `EXPIRED`, `RESCHEDULE_REQUESTED`, `RESCHEDULE_PROPOSED`, `CANCELLED_CUSTOMER`, `CANCELLED_PROVIDER`, and
  a history of who did what and which money moved.
- **Cancellation policies** (S109): Flexible (free until 24 h), Moderate (free until 48 h, half the deposit 24–48 h),
  Strict (free until 7 days), Custom (free window, optional partial refund, no-show rule, reschedule window and
  limit). Each booking keeps the policy it was made under; customers get the exact refund before cancelling.

### No double booking

Every active booking writes its time (appointment + buffer, plus a proposed new time while one is pending) to
`booking_time_block`, protected by
`EXCLUDE USING gist (member_id WITH =, period WITH &&, booking_id WITH <>)` (`btree_gist`). On top of that, creating
a booking locks the team member row (`SELECT … FOR UPDATE`), so the daily limit is also race-free. Overlaps surface as
`409 bookings.slot-taken`. Every booking write takes an `Idempotency-Key` (`idempotency_record`); a replay returns the
original booking, a reused key with a different request is `409 bookings.idempotency-key-reused`.

### Money (via payments-service)

One manual-capture PaymentIntent per booking (`POST /v1/payments`, `orderId` = booking reference `BK-…`, seller =
provider org, category `services`) with a `deposit` line and a `balance` line. It is created with
`shopperId` = the customer's token subject and `saveForOffSession: true`, so payments saves the confirmed card on the
shopper's Stripe Customer for a later off-session balance charge.

| Moment | bookings → payments | Idempotency key |
|---|---|---|
| Customer books | create intent; client secret returned to the app (deposit **held**) | `BK-…:create-intent` |
| Provider confirms / instant booking | capture `deposit` (deposit **charged**) | `BK-…:capture:deposit` |
| … appointment more than `BOOKING_AUTH_WINDOW` away | right after the deposit capture: `POST …/release` (balance hold **released**, balance `DUE`) | `BK-…:release` |
| `BOOKING_BALANCE_CHARGE_BEFORE` before a `DUE` booking (job) | `POST …/off-session-charges {customerId, lineIds:["balance"]}` on the saved card; new payment id kept on the booking | `BK-…:offsession:<n>` |
| Completed (or auto-completed 48 h after) | capture `balance` (final) on the original intent — or on the off-session charge when there is one — + `POST /v1/settlements/BK-…/delivered` kind SERVICE (settlement hold) | `BK-…:capture:balance` · `BK-…:offsession:<n>:capture` |
| No-show | deposit kept and `POST …/release` of the balance hold (or `balance` captured when the policy says full price) + settlement | `BK-…:release` |
| Declined / expired / cancelled before capture | cancel intent (hold **released**) | `BK-…:cancel` |
| Cancelled after capture | `POST …/release` of the balance hold (or cancel the open off-session charge); refund `deposit` line per policy; kept part goes to settlement | `BK-…:release` · `BK-…:offsession:<n>:cancel` · `BK-…:refund:deposit` |

Money moves inside the booking's database transaction: if payments refuses or is down, nothing changes
(`409 bookings.payment-rejected` / `502 bookings.payments-unavailable`) and the same request can be retried.
Commission is charged by payments per captured line and returned on refunds, so it applies only to what the
customer paid. Calls use the client-credentials client `bookings-service` (scope `payments:service`). Late
provider-cancellation fees travel in `booking.cancelled` (`providerFeeMinor`); payments records them and debits the
provider's connected account.

### Long-dated bookings (S220)

Card authorisations last about 7 days; bookings can be a year ahead. The booking's `payment.balance` tracks the
balance (`balance_*` columns, V3):

| `balance.state` | Meaning |
|---|---|
| `ON_AUTHORISATION` | balance still part of the original authorisation (captured on completion) |
| `DUE` | hold released after the deposit capture; the job charges it off-session `BOOKING_BALANCE_CHARGE_BEFORE` (2 d) before the start |
| `PROCESSING` / `AUTHORIZED` | off-session charge submitted / authorised (captured on completion) |
| `ACTION_REQUIRED` | the bank wants SCA: the customer reads `clientSecret` from `GET /v1/bookings/{id}/balance-payment`, confirms with Stripe.js / PaymentSheet and calls `POST /{id}/payment/sync` (payments' `payments.intent.action_required` lets notifications ask them) |
| `FAILED` | declined; retried after `BOOKING_BALANCE_RETRY_DELAY` (6 h) with a new key `BK-…:offsession:<n+1>` |
| `NEEDS_ATTENTION` | declined `BOOKING_BALANCE_MAX_ATTEMPTS` (3) times, or not authorised by completion: event `booking.balance_unpaid` for the provider and the customer |
| `CAPTURED` / `RELEASED` | collected / nothing more will be charged (cancelled, no-show without full price) |

`BOOKING_AUTH_WINDOW` defaults to 5 days, not 7: the authorisation must still be valid when the booking completes,
which can be up to 48 h after it ends. A charge still waiting for the customer at completion stays open and is
captured as soon as it is authorised. The job runs every `BOOKINGS_BALANCE_JOB_INTERVAL` (5 min) on every replica:
each booking is claimed with `SELECT … FOR UPDATE SKIP LOCKED` and re-checked under the lock; payments being down
rolls the attempt back and the same key is replayed next round, payments refusing it (4xx, e.g. no saved card)
counts as a declined attempt.

### Fees

Take-home and earnings use `GET /v1/fee-schedules/effective?category=services&orgId=<provider>` (commission with its
source — seller override, category, default, AfroTransact Retail — card fee estimate and fee-schedule version), cached
per organisation for `FEES_CACHE_TTL` (5 min). While payments cannot answer, the configured
`SERVICES_COMMISSION_BPS` / `CARD_PCT_BPS` / `CARD_FIXED_MINOR` apply (logged as a warning, retried after 30 s); the
service's `takeHome.feeSource` then reads `CONFIG`.

## API

RFC 9457 problems with `code` `bookings.*`. Contract: [`contracts/openapi/bookings.yaml`](../../../contracts/openapi/bookings.yaml).
Reference pages: `/swagger-ui.html`, `/docs` (Scalar), `/redoc` (off in prod).

| Method | Path | Who |
|---|---|---|
| GET | `/v1/public/services/search?q=&category=&zip=&size=` (alias `/v1/services/search` for ai-gateway / mcp-server) | public |
| GET | `/v1/public/providers/{orgId}`, `/v1/public/services/{id}`, `…/days?from=&days=`, `…/slots?date=&memberId=&addOnIds=`, `…/summary?addOnIds=&mode=` | public |
| POST | `/v1/bookings` (Idempotency-Key) | customer |
| GET | `/v1/bookings/mine` | customer |
| GET | `/v1/bookings/{id}`, `/{id}/history`, `/{id}/cancellation-quote`, `/{id}/balance-payment` (client secret for the customer only) · POST `/{id}/payment/sync` | its customer or the provider (`bookings:read`) |
| POST | `/v1/bookings/{id}/review` `{rating 1–5, text?}` (completed bookings, once) | its customer |
| POST | `/v1/bookings/{id}/cancel`, `/new-time/accept`, `/new-time/decline` | customer or provider (`bookings:manage`) |
| POST | `/v1/bookings/{id}/reschedule` | customer |
| POST | `/v1/bookings/{id}/confirm`, `/decline`, `/propose-time`, `/complete`, `/no-show` | provider (`bookings:manage`) |
| GET/PUT | `/v1/providers/{orgId}/settings` | `bookings:read` / `bookings:availability:write` (not practitioners) |
| PUT | `/v1/providers/{orgId}/cancellation-policy` | `bookings:services:write` |
| GET/POST/PUT | `/v1/providers/{orgId}/services[/{id}]`, POST `…/activate`, `…/deactivate` | `bookings:read` / `bookings:services:write` |
| PUT | `/v1/providers/{orgId}/services/{id}/members` | `bookings:team:manage` |
| GET/POST/PUT | `/v1/providers/{orgId}/team[/{memberId}]` | `bookings:read` / `bookings:team:manage` |
| PUT | `/v1/providers/{orgId}/team/{memberId}/hours` | `bookings:availability:write` (practitioners: own) |
| GET/PUT/DELETE | `/v1/providers/{orgId}/team/{memberId}/overrides[/{date}]` | `bookings:read` / `bookings:availability:write` (practitioners: own) |
| GET | `/v1/providers/{orgId}/calendar?from=&to=&memberId=` (≤ 62 days) | `bookings:read` (practitioners: own column) |
| GET | `/v1/providers/{orgId}/bookings?from=&to=&memberId=&status=`, `/earnings?from=&to=` | `bookings:read` (practitioners: own) |

### Roles (enforced server-side)

| Area | Owner | Manager | Practitioner | Finance |
|---|---|---|---|---|
| Calendar & bookings | full | full | own only | view |
| Working hours / time off | full | full | own only | — |
| Booking rules (settings) | full | full | — | view |
| Services & prices, policy | full | full | view | view |
| Team & service assignment | full | full | view | view |

Practitioner scoping: an org member with role `provider.practitioner` and without `bookings:team:manage` only reaches
the team member whose `userId` equals their token subject (`403 bookings.own-calendar-only`). Staff (STAFF / SERVICE)
may act for any organisation.

### Search by ZIP and ratings (S221)

`zip` (5 digits) goes through the `GeoClient` port (`HttpGeoClient`, `GEO_URL`): `GET /v1/zip/{zip}` for the ZIP's
coordinates, then `GET /v1/zones/covering?lat&lon&type=SERVICE_AREA`; zone owners are provider organisations. Search
keeps services of providers whose active service area covers the ZIP, plus `VIRTUAL` services (no travel); an unknown
ZIP finds only virtual services. `422 bookings.invalid-zip`, `502 bookings.geo-unavailable`. Each hit carries `rating`
(average, one decimal; 0 without reviews) and `ratingCount` from `booking_review`.

## Events

Published (outbox → `at.bookings.events`, key = booking id): `booking.requested`, `booking.confirmed`,
`booking.declined`, `booking.expired`, `booking.cancelled`, `booking.reschedule_requested`, `booking.time_proposed`,
`booking.rescheduled`, `booking.completed`, `booking.no_show`, `booking.balance_unpaid`; `booking.service.upserted`
(key = service id). Booking payloads carry notifications' `recipient` block and template fields (`booking.confirmed`
is mailed by notifications) plus `balanceMinor`, `balanceState`, `balanceFailure`.

Consumed (consumer group `bookings-service`, inbox de-duplication on `event-id`):
- `at.payment.events`: `payments.intent.succeeded` (deposit authorised → REQUESTED, or CONFIRMED for instant booking)
  and `payments.intent.failed`, for orders whose id is a booking reference. With `parentPaymentId` / `offSession`
  the event is about the booking's off-session balance charge instead (succeeded → balance AUTHORIZED, failed → a
  declined attempt, `payments.intent.action_required` → ACTION_REQUIRED); events of older charges are ignored.
- `at.seller-compliance.events`: `seller.listings.hidden` hides the organisation's services and slots from
  `/v1/public/**` and from new bookings; `seller.listings.restored` brings them back (older events than the last
  applied are ignored). Existing bookings are untouched.

See [`contracts/asyncapi/fragments/bookings.yaml`](../../../contracts/asyncapi/fragments/bookings.yaml).

## Jobs

Every `BOOKINGS_JOBS_INTERVAL` (60 s): expire unpaid slot holds (`BOOKING_HOLD_TTL`, 15 min), expire unanswered
requests (`BOOKING_REQUEST_SLA`, 4 h) releasing the deposit hold, auto-complete confirmed bookings
`BOOKING_AUTO_COMPLETE_AFTER` (48 h) after they end. Each booking is processed under its row lock; failures retry next
round. Every `BOOKINGS_BALANCE_JOB_INTERVAL` (5 min): charge due balances off-session (see *Long-dated bookings*).

## Configuration

| Env var | Purpose |
|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | PostgreSQL (needs the `btree_gist` extension; created by the first migration) |
| `KAFKA_BOOTSTRAP` | Kafka (outbox relay, consumers) |
| `AUTH_ISSUER_URI` | identity issuer (JWT validation) |
| `PAYMENTS_URL`, `AUTH_TOKEN_URI`, `BOOKINGS_CLIENT_ID`, `BOOKINGS_CLIENT_SECRET` | payments-service and the `bookings-service` client credentials (**secret only from the environment**; blank in `local`, where payments runs without security) |
| `BOOKING_HOLD_TTL`, `BOOKING_REQUEST_SLA`, `BOOKING_AUTO_COMPLETE_AFTER`, `LATE_PROVIDER_FEE_MINOR` | flow settings (15m, 4h, 48h, 1500) |
| `SERVICES_COMMISSION_BPS`, `CARD_PCT_BPS`, `CARD_FIXED_MINOR` | **fallback** take-home / earnings fees while payments' fee schedule is unavailable (1000, 290, 30) |
| `FEES_CACHE_TTL` | how long payments' effective fees are reused per provider (5m) |
| `BOOKING_AUTH_WINDOW`, `BOOKING_BALANCE_CHARGE_BEFORE`, `BOOKING_BALANCE_MAX_ATTEMPTS`, `BOOKING_BALANCE_RETRY_DELAY` | long-dated bookings (5d, 2d, 3, 6h) |
| `GEO_URL`, `GEO_TIMEOUT` | geo-service for ZIP search (public reads, no token) |
| `BOOKINGS_MANAGE_URL_BASE` | customer link in event payloads |
| `PAYMENT_EVENTS_ENABLED`, `SELLER_COMPLIANCE_EVENTS_ENABLED`, `BOOKINGS_JOBS_ENABLED` | switch consumers / jobs off |
| `BOOKINGS_JOBS_INTERVAL`, `BOOKINGS_BALANCE_JOB_INTERVAL` | job intervals (60s, 5m) |

Profiles: `local` (security off, debug logs), `dev`, `uat`, `prod` (API reference pages off).

## Run and test

```bash
make up                                                     # Postgres, Kafka … from infra/local
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
cd java && ./mvnw -B -f services/bookings/pom.xml verify    # checkstyle, unit, ArchUnit, Testcontainers ITs
./mvnw -f services/bookings/pom.xml spring-boot:run         # http://localhost:8109/docs
tools/contracts/export_java_openapi.sh bookings bookings 8109
```

Unit tests cover slot generation (breaks, notice, buffers, daily limit, overrides, horizon, DST), policy math,
take-home / earnings under payments' or the fallback fee schedule, the state machine with its money instructions, the
off-session job's decisions (`BalanceChargePolicyTest`) and long-dated outcomes (release, SCA, declines, flagging,
capture on completion). Integration tests run PostgreSQL (`postgis/postgis:17-3.5`) and in-JVM WireMocks standing in
for payments-service (with identity's token endpoint) and geo-service; they cover the full money flow, eight parallel
requests for one slot (exactly one wins), the exclusion constraint itself, request / hold expiry, policy refunds,
reschedules, price snapshots, role scoping, seller compliance hide/restore, release after the deposit capture
(far bookings, cancellations, no-shows), off-session charges (authorised, SCA, declined ×3 → `booking.balance_unpaid`,
four parallel job rounds → one charge), fees from payments and the fallback, ZIP search and reviews (listeners and
jobs called directly; Kafka is not needed).

Deploy: `deploy/helm` (depends on `infra/helm/library/at-service`), one Argo CD Application per environment.

## Known gaps

- Services without a deposit keep the whole price on the original authorisation (nothing is captured at confirmation,
  so there is no partial capture to release after); far-off appointments of such services can still outlive the card
  hold.
- `booking.balance_unpaid` has no notifications rule or template yet; the customer is asked to authenticate through
  payments' `payments.intent.action_required`.
- Reviews have no moderation, provider reply or public list endpoint; ratings are averaged per service only.
