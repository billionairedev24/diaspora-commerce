# bookings-service

Provider services and prices, team members and roles, working hours and live slot generation, the booking flow with
deposits through payments-service, booking requests, reschedules and cancellation policies.
Spring Boot 4.1 · Java 25 · PostgreSQL `bookings` · local port **8109**. Epic E15 (stories S105–S110); design source
`design/AfroTransact Provider Hub.dc.html` and `design/at-bookings.js`.

## What it owns

- **Provider setup** per organisation: business name, IANA time zone, currency, location and the booking rules from
  the Working hours screen: minimum notice, booking horizon, buffer, slot interval, daily limit per member, instant
  booking, auto-accepting reschedules.
- **Team** (S110): members with role Owner, Manager, Practitioner or Finance, linked to their identity user id, each
  with their own **weekly hours** (several ranges a day = breaks) and **date overrides** (time off or special hours).
- **Services & prices** (S106): duration, price, deposit, modes (`IN_PERSON`, `AT_HOME`, `VIRTUAL`), add-ons (price and
  extra time), who performs them, and the provider's **take-home** (price − commission − card fee on the deposit
  charge and on the balance charge). Bookings copy the service, so later price changes don't affect them.
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
provider org, category `services`) with a `deposit` line and a `balance` line:

| Moment | bookings → payments | Idempotency key |
|---|---|---|
| Customer books | create intent; client secret returned to the app (deposit **held**) | `BK-…:create-intent` |
| Provider confirms / instant booking | capture `deposit` (deposit **charged**) | `BK-…:capture:deposit` |
| Completed (or auto-completed 48 h after) | capture `balance` (final) + `POST /v1/settlements/BK-…/delivered` kind SERVICE (settlement hold) | `BK-…:capture:balance` |
| No-show | deposit kept (or `balance` captured when the policy says full price) + settlement | |
| Declined / expired / cancelled before capture | cancel intent (hold **released**) | `BK-…:cancel` |
| Cancelled after capture | refund `deposit` line per policy; kept part goes to settlement | `BK-…:refund:deposit` |

Money moves inside the booking's database transaction: if payments refuses or is down, nothing changes
(`409 bookings.payment-rejected` / `502 bookings.payments-unavailable`) and the same request can be retried.
Commission is charged by payments per captured line and returned on refunds, so it applies only to what the
customer paid. Calls use the client-credentials client `bookings-service` (scope `payments:service`).

## API

RFC 9457 problems with `code` `bookings.*`. Contract: [`contracts/openapi/bookings.yaml`](../../../contracts/openapi/bookings.yaml).
Reference pages: `/swagger-ui.html`, `/docs` (Scalar), `/redoc` (off in prod).

| Method | Path | Who |
|---|---|---|
| GET | `/v1/public/services/search?q=&category=&size=` (alias `/v1/services/search` for ai-gateway / mcp-server) | public |
| GET | `/v1/public/providers/{orgId}`, `/v1/public/services/{id}`, `…/days?from=&days=`, `…/slots?date=&memberId=&addOnIds=`, `…/summary?addOnIds=&mode=` | public |
| POST | `/v1/bookings` (Idempotency-Key) | customer |
| GET | `/v1/bookings/mine` | customer |
| GET | `/v1/bookings/{id}`, `/{id}/history`, `/{id}/cancellation-quote` · POST `/{id}/payment/sync` | its customer or the provider (`bookings:read`) |
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

## Events

Published (outbox → `at.bookings.events`, key = booking id): `booking.requested`, `booking.confirmed`,
`booking.declined`, `booking.expired`, `booking.cancelled`, `booking.reschedule_requested`, `booking.time_proposed`,
`booking.rescheduled`, `booking.completed`, `booking.no_show`; `booking.service.upserted` (key = service id). Booking
payloads carry notifications' `recipient` block and template fields (`booking.confirmed` is mailed by notifications).

Consumed (consumer group `bookings-service`, inbox de-duplication on `event-id`):
- `at.payment.events`: `payments.intent.succeeded` (deposit authorised → REQUESTED, or CONFIRMED for instant booking)
  and `payments.intent.failed`, for orders whose id is a booking reference.
- `at.seller-compliance.events`: `seller.listings.hidden` hides the organisation's services and slots from
  `/v1/public/**` and from new bookings; `seller.listings.restored` brings them back (older events than the last
  applied are ignored). Existing bookings are untouched.

See [`contracts/asyncapi/fragments/bookings.yaml`](../../../contracts/asyncapi/fragments/bookings.yaml).

## Jobs

Every `BOOKINGS_JOBS_INTERVAL` (60 s): expire unpaid slot holds (`BOOKING_HOLD_TTL`, 15 min), expire unanswered
requests (`BOOKING_REQUEST_SLA`, 4 h) releasing the deposit hold, auto-complete confirmed bookings
`BOOKING_AUTO_COMPLETE_AFTER` (48 h) after they end. Each booking is processed under its row lock; failures retry next
round.

## Configuration

| Env var | Purpose |
|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | PostgreSQL (needs the `btree_gist` extension; created by the first migration) |
| `KAFKA_BOOTSTRAP` | Kafka (outbox relay, consumers) |
| `AUTH_ISSUER_URI` | identity issuer (JWT validation) |
| `PAYMENTS_URL`, `AUTH_TOKEN_URI`, `BOOKINGS_CLIENT_ID`, `BOOKINGS_CLIENT_SECRET` | payments-service and the `bookings-service` client credentials (**secret only from the environment**; blank in `local`, where payments runs without security) |
| `BOOKING_HOLD_TTL`, `BOOKING_REQUEST_SLA`, `BOOKING_AUTO_COMPLETE_AFTER`, `LATE_PROVIDER_FEE_MINOR` | flow settings (15m, 4h, 48h, 1500) |
| `SERVICES_COMMISSION_BPS`, `CARD_PCT_BPS`, `CARD_FIXED_MINOR` | take-home / earnings fees (mirror payments' services fee schedule: 1000, 290, 30) |
| `BOOKINGS_MANAGE_URL_BASE` | customer link in event payloads |
| `PAYMENT_EVENTS_ENABLED`, `SELLER_COMPLIANCE_EVENTS_ENABLED`, `BOOKINGS_JOBS_ENABLED` | switch consumers / jobs off |

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
take-home / earnings and the state machine with its money instructions. Integration tests run PostgreSQL
(`postgis/postgis:17-3.5`) and an in-JVM WireMock standing in for payments-service and identity's token endpoint; they
cover the full money flow, eight parallel requests for one slot (exactly one wins), the exclusion constraint itself,
request / hold expiry, policy refunds, reschedules, price snapshots, role scoping and seller compliance hide/restore
(listeners called directly; Kafka is not needed).

Deploy: `deploy/helm` (depends on `infra/helm/library/at-service`), one Argo CD Application per environment.

## Known gaps

- After the deposit is captured, cancellations and no-shows leave the uncaptured balance authorisation to lapse:
  payments' cancel endpoint refuses intents with captures and has no "release remaining authorisation" call.
- Card authorisations expire after ~7 days, but bookings can be made up to 365 days ahead; charging the balance for
  far-off appointments needs off-session charges on a saved payment method in payments-service.
- The late provider-cancellation fee is recorded on the booking and in events, but nothing debits it yet.
- Search ignores `zip` (no service areas yet) and `rating` is always 0.
