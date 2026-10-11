# trust-ops-service

Trust & safety for staff: the listing moderation queue, disputes and A-to-z claims, the immutable audit log, seller
health with suspend / reinstate, and the support-case queue for Lola hand-offs.
Spring Boot 4.1 · Java 25 · PostgreSQL `trustops` · local port **8110**. Epic E18 (stories S117–S120, plus S223,
S228, S237 and S243);
design source `design/AfroTransact Backoffice.dc.html` (Listing moderation, Disputes & returns, Audit log, Sellers &
providers) and `design/backoffice-data.js`.

## What it owns

- **Listing moderation queue (S117).** `catalog.product.created` with status `IN_REVIEW` queues the product. Each item
  has an **SLA timer** (24 h; 4 h when AI rates it high risk; 8 h once escalated) shown as `ON_TRACK`, `AT_RISK` (last
  quarter) or `BREACHED`, an **assignee**, and optional **AI triage hints** (ai-gateway `POST /v1/ai/triage`: risk level,
  summary, flags with cited fields). AI is **fail-open**: when it is off, slow (5 s timeout) or failing, the item is
  queued without a hint and nothing else changes. Reviewers **allow** (catalog `POST /v1/products/{id}/approve`) or
  **remove** with a **reason code** (catalog `POST /v1/products/{id}/reject`, note `"[CODE] label: note"`). The
  **escalation path** is manual (`/escalate`) or automatic: the SLA sweep escalates pending items past their due time.
  If catalog refuses (409/422/404) the item stays open (`409 trustops.catalog-refused`); if catalog decides a product
  directly, its `catalog.product.approved|rejected` closes the item (`CLOSED`). Every decision is audited.
- **Flagged live listings (S228).** `catalog.product.flagged` (a shopper's report, a staff flag or an automated flag
  on a `LIVE` listing) queues a `LIVE_LISTING` item (submissions are `NEW_LISTING`; filter with `?type=`). The item
  keeps the first report (source, reason code, details, reporter, risk level) and counts further reports for the same
  product while it is open; a `HIGH`-risk automated flag or AI hint raises the priority (4 h SLA). **Allow** keeps the
  listing live and dismisses the reports (no catalog call, audited as `trustops.listing.kept`); **remove** takes it
  down in catalog (`POST /v1/products/{id}/takedown` with the reason code and note, audited as
  `trustops.listing.taken_down`). If catalog refuses, the item stays open; a takedown made directly in catalog
  (`catalog.product.taken_down`) closes it.
- **Booking review moderation (S243).** bookings-service keeps every customer review `PENDING` until it is
  moderated. Its `booking.review.submitted` (on `at.bookings.events`) queues a `SERVICE_REVIEW` item (`?type=`)
  with the review (booking id = review id, provider organisation, service, rating, text, public reviewer name, and
  the author's id for conflict-of-interest checks). The item gets the listing SLA and an AI triage hint (subject
  type `REVIEW`; rating, text and service only, never the author; fail-open). There is one open item per review,
  and redeliveries are dropped by the inbox (consumer `trust-ops:booking-reviews`). **Allow** publishes the review
  (bookings `POST /v1/reviews/{bookingId}/approve {note}`) and **remove** rejects it with a review reason code
  (`POST /v1/reviews/{bookingId}/reject {reasonCode, note}`: `ABUSIVE`, `PERSONAL_INFORMATION`, `SPAM`,
  `OFF_TOPIC`, `CONFLICT_OF_INTEREST`, or `OTHER` with a note). Both calls use the `trust-ops-service` token. A
  listing reason code on a review, or a review code on a listing, is `422 trustops.reason-code-not-applicable`.
  Deciding a review also needs **`bookings:reviews:moderate`** (bookings' own key) on top of `catalog:moderate`, so
  the queue cannot bypass bookings' check. If bookings refuses (409/422/404) the item stays open
  (`409 trustops.bookings-refused`). Bookings' own `booking.review.approved|rejected` closes an item decided
  directly in bookings (`CLOSED`, decided by `bookings`), and is a no-op after a decision made here. Decisions are
  audited as `trustops.review.approved|rejected|escalated` (entity `REVIEW`, id = booking id). The
  `trustops.moderation.*` events carry `itemType: SERVICE_REVIEW`, `bookingId`, `serviceId` and `rating` (no
  `productId`), keyed by booking id. notifications only reacts to the listing item types.
- **Disputes and A-to-z claims (S118).** A customer opens a claim on an order or a shipment (one open claim per
  order + shipment) with **evidence = media asset ids**. The domain state machine (`ClaimStatus`):

  ```
  AWAITING_SELLER ──seller responds / 72 h window lapses──▶ UNDER_REVIEW ──decide──▶ RESOLVED_REFUND | RESOLVED_DENIED
         └──────────────escalate──────────▶ ESCALATED ◀──────┘ (staff, or 5-day decision SLA breached) ──decide──▶ …
  any open state ──customer withdraws──▶ WITHDRAWN
  ```

  Staff decide with **refund** — payments `POST /v1/payments/{paymentId}/refunds` (line-level, `Idempotency-Key:
  trustops-claim-<claimId>`, called *before* the claim is resolved so a failed refund leaves it open and retryable) —
  or **deny** (reason required). The refund may not exceed the claimed amount. **Both parties are notified** by
  notifications-service from the typed `trustops.claim.*` events, which carry `sellerOrgId` and `customerId`;
  `trustops.claim.decided` also carries the staff `decisionNote` (S237). trust-ops no longer publishes its own
  `notification.requested` for claims, so nobody gets the same notice twice. Every step is in the claim timeline
  (`/history`); decisions are audited.
- **Seller reminders and lapses (S237).** The SLA sweep publishes `trustops.claim.response_due_soon` once per claim
  when the seller's deadline is less than 24 h away (`TRUSTOPS_SLA_SELLER_RESPONSE_REMINDER`) and the claim still
  awaits the seller. `claim.response_reminder_sent_at` (V3) is set with a conditional update, so overlapping or
  repeated sweeps never send it twice. When the window passes, the claim moves to staff review and the sweep
  publishes `trustops.claim.response_lapsed` (`responseDueAt` = the missed deadline, `dueAt` = the decision deadline).
  Escalations, manual or by the sweep, publish the full claim payload (`trustops.claim.escalated`) and the full
  moderation payload (`trustops.moderation.escalated`, with `sellerOrgId` and, for a shopper-reported live listing,
  the reporter's `customerId`).
- **Immutable audit log (S119).** Audit-worthy events from other services (topics and event types configurable) plus
  every trust-ops decision are appended to `audit_entry` with a **SHA-256 hash chain**: each row stores the previous
  row's hash; `hash = SHA-256(prevHash, seq, content)` with length-prefixed fields. Appends are serialised with a
  PostgreSQL advisory lock, so `seq` is gap-free. The payload is stored as text, exactly as hashed.
  **Database-level append-only:** triggers reject every `UPDATE`, `DELETE` and `TRUNCATE` (`42501`, "audit_entry is
  append-only"), and those privileges are revoked from `PUBLIC`. `GET /v1/audit/verify` recomputes the chain and
  reports the first edited, re-hashed or missing entry. Every appended entry is streamed as `trustops.audit.recorded`.
  Query by actor, entity, action, organisation and date; CSV export (spreadsheet-injection safe, capped at 50 000 rows).
- **Seller health, suspend and reinstate (S120, S223).** Orders events build a per-shipment projection; over a rolling
  90-day window trust-ops computes the **late shipment rate** (delivered after `promisedBy`), **seller cancellation
  rate** (cancelled by `SELLER` or `SYSTEM`) and **order defect rate** (claims / shipments), with targets 4 %, 2.5 % and
  1 % (`GOOD`, `AT_RISK`, `UNKNOWN`). A daily job (03:15 UTC) computes every active seller and pushes
  `onTimeRate`, `defectRate` and `restricted` to offers `PUT /v1/sellers/{orgId}/performance` (the current rating is
  read from `/standing` and sent back unchanged). **Suspension** places seller-service's suspension hold
  (`POST /v1/orgs/{orgId}/suspend` with the reason code and note: payouts paused, listings hidden in catalog, offers,
  bookings and search through `seller.listings.hidden`, organisation `SUSPENDED`) and restricts the seller in the buy
  box (`restricted=true`); **reinstatement** lifts both (`POST /v1/orgs/{orgId}/reinstate`). seller-service keeps
  the suspension separate from Stripe compliance holds, so reinstating never releases a compliance payout hold placed
  for another reason. Each upstream step is tracked (`payoutsHeld` / `listingsHidden` for the seller hold,
  `buyBoxRestricted`); a failed step stays pending and calling suspend / reinstate again retries it (both seller
  endpoints are idempotent). Suspensions are audited and published (`trustops.seller.suspended|reinstated`).
  AfroTransact Retail (`RETAIL_ORG_ID`) is exempt. It is the merchant of record and has no payouts to hold, so
  suspending it is refused with `422 trustops.retail-exempt` and nothing is sent upstream. Its health is still
  computed and pushed to offers, because the buy box treats Retail like any offer. Claim refunds go through payments,
  which debits Retail sales for Retail lines and never attempts a transfer reversal.
- **Support cases.** Every Lola hand-off (`ai.handoff.requested`) opens one support case (deduplicated by
  `handoffId`) with a 4-hour first-response SLA; agents assign and resolve them.

## API

| Method | Path | Permission |
|---|---|---|
| GET | `/v1/moderation/items?status&assignee&mine&type` (`NEW_LISTING`, `LIVE_LISTING`, `SERVICE_REVIEW`), `/v1/moderation/items/{id}`, `/v1/moderation/reason-codes?type` (listing codes by default, review codes for `SERVICE_REVIEW`) | `catalog:moderate` |
| POST | `/v1/moderation/items/{id}/assign`, `/escalate`, `/approve`, `/reject`, `/triage` | `catalog:moderate` (`/approve` and `/reject` of a review also `bookings:reviews:moderate`) |
| POST | `/v1/claims` | customer (own); staff with `disputes:manage` on behalf (`customerId`) |
| GET | `/v1/claims/mine` | customer |
| GET | `/v1/claims/seller/{orgId}` | `support:respond` for that org (or staff) |
| GET | `/v1/claims?status&sellerOrgId&orderId` | `disputes:manage` (staff) |
| GET | `/v1/claims/{id}`, `/v1/claims/{id}/history` | the customer, the seller org, or staff with `disputes:manage` |
| POST | `/v1/claims/{id}/evidence` | any party |
| POST | `/v1/claims/{id}/seller-response` | `support:respond` for the claim's seller org |
| POST | `/v1/claims/{id}/withdraw` | the customer |
| POST | `/v1/claims/{id}/escalate`, `/v1/claims/{id}/decision` | `disputes:manage` (staff) |
| GET | `/v1/audit/entries?actorId&entityType&entityId&action&orgId&from&to`, `/v1/audit/entries/{seq}` | `audit:read` |
| GET | `/v1/audit/export.csv?…same filters`, `/v1/audit/verify?fromSeq&toSeq` | `audit:read` |
| GET | `/v1/seller-health/{orgId}`, `/v1/seller-enforcement`, `/v1/seller-enforcement/{orgId}` | `orgs:read` |
| POST | `/v1/seller-health/{orgId}/recompute`, `/v1/seller-enforcement/{orgId}/suspend`, `/reinstate` | `sellers:enforce` (new) |
| GET/POST | `/v1/support-cases?status`, `/{id}`, `/{id}/assign`, `/{id}/resolve` | `support:respond` |

Errors are RFC 9457 problems: `trustops.invalid-transition` (409), `trustops.*` rule violations with `fieldErrors`
(422), `trustops.catalog-refused` / `trustops.bookings-refused` / `trustops.refund-refused` /
`trustops.claim-already-open` (409),
`trustops.upstream-unavailable` (502, with `service` and `upstreamStatus`).

Contract: [`contracts/openapi/trust-ops.yaml`](../../../contracts/openapi/trust-ops.yaml).
Interactive docs when running: `/swagger-ui.html`, `/docs` (Scalar), `/redoc`.

## Permissions, roles and the service client

Existing identity keys reused: `catalog:moderate`, `disputes:manage`, `support:respond`, `audit:read`, `orgs:read`,
and bookings' `bookings:reviews:moderate` (S243: Trust & Safety staff who decide reviews need it).
**New key needed:** `sellers:enforce` (module `trust`, "Suspend and reinstate sellers and recompute seller health"),
suggested for `platform.super_admin`, `platform.trust_safety` and `platform.operations_manager`.

Service-to-service calls use the client-credentials client **`trust-ops-service`** (secret `TRUSTOPS_CLIENT_SECRET`)
with scopes `catalog:moderate` (catalog approve / reject / takedown), `bookings:reviews:moderate` (bookings review
approve / reject, S243), `payments:service` (refunds),
`sellers:enforce` (seller-service suspend / reinstate), `offers:write` (seller performance) and `ai:triage` (triage
hints). The token must carry principal type `SERVICE` (payments and offers check it). `compliance:hold` is no longer
needed.

## Events

Published (transactional outbox → `at.trustops.events`): `trustops.moderation.queued|escalated|decided`,
`trustops.claim.opened|response_due_soon|response_lapsed|seller_responded|escalated|decided|withdrawn`,
`trustops.seller.suspended|reinstated`, `trustops.seller.health_computed`, `trustops.support_case.opened` and
`trustops.audit.recorded` (key `audit-log`) — see [`contracts/asyncapi/fragments/trust-ops.yaml`](../../../contracts/asyncapi/fragments/trust-ops.yaml).

Consumed (each listener has its own consumer group and inbox consumer, so they never compete for partitions):

| Group suffix | Topic | Events |
|---|---|---|
| `-moderation` | `at.product.events` | `catalog.product.created` (IN_REVIEW), `catalog.product.flagged`, `catalog.product.approved\|rejected\|taken_down` |
| `-reviews` | `at.bookings.events` | `booking.review.submitted` (PENDING), `booking.review.approved\|rejected` (every other bookings event is ignored) |
| `-seller-health` | `at.order.events` | `orders.order.placed`, `orders.shipment.delivered\|cancelled\|status_changed` |
| `-support` | `at.ai-handoff.events` | `ai.handoff.requested` |
| `-audit` | `TRUSTOPS_AUDIT_TOPICS` (default identity, seller-application, seller-compliance, payout, mcp-tool, order) | `TRUSTOPS_AUDIT_EVENT_TYPES`: role / permission grants and revokes, user disable / enable, seller application decisions, payout holds and listing hides, payout lifecycle, `mcp.tool.invoked`, and shipment status changes / cancellations made by `PLATFORM_ADMIN` or `FULFILMENT_OPS` |

## Configuration

| Env var | Purpose |
|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | PostgreSQL (`trustops`) |
| `KAFKA_BOOTSTRAP` | Kafka for the outbox relay and the consumers |
| `AUTH_ISSUER_URL`, `AUTH_TOKEN_URL` | identity issuer (JWT validation) and token endpoint (client credentials) |
| `TRUSTOPS_CLIENT_SECRET` (secret), `TRUSTOPS_CLIENT_ID` | the `trust-ops-service` client; blank in `local` = no token |
| `CATALOG_URL`, `BOOKINGS_URL`, `PAYMENTS_URL`, `SELLER_URL`, `OFFERS_URL`, `AI_GATEWAY_URL`, `UPSTREAM_TIMEOUT` | upstream services |
| `AI_TRIAGE_ENABLED`, `AI_TRIAGE_TIMEOUT` | AI triage hints (default on, 5 s) |
| `TRUSTOPS_SLA_*` | SLA targets (`MODERATION` 24h, `MODERATION_HIGH_RISK` 4h, `MODERATION_ESCALATED` 8h, `SELLER_RESPONSE` 72h, `SELLER_RESPONSE_REMINDER` 24h before the deadline, `CLAIM_DECISION` 120h, `CLAIM_ESCALATED` 48h, `SUPPORT_FIRST_RESPONSE` 4h) |
| `TRUSTOPS_AUDIT_TOPICS`, `TRUSTOPS_AUDIT_EVENT_TYPES`, `TRUSTOPS_AUDIT_STREAM`, `TRUSTOPS_AUDIT_EXPORT_MAX_ROWS` | audit ingestion and export |
| `SELLER_HEALTH_WINDOW`, `SELLER_HEALTH_CRON`, `SELLER_HEALTH_PUSH` | seller-health job (90d, `0 15 3 * * *` UTC, push on) |
| `TRUSTOPS_JOBS_ENABLED`, `TRUSTOPS_SLA_SWEEP_INTERVAL` | SLA sweep (60s) |
| `OTEL_EXPORTER_OTLP_ENDPOINT`, `TRACE_SAMPLING` | tracing |

Profiles: `local` (security off, no service token, debug logs), `dev`, `uat`, `prod` (API reference pages off).
In production the application's database role should hold only `SELECT, INSERT` on `audit_entry`; the triggers block
changes even for the owner.

## Known upstream gaps

- **Taken-down listings in search and the buy box.** catalog hides a taken-down listing from its public API, but the
  indexer and offers-service do not consume `catalog.product.taken_down|reinstated` yet, so the search document and
  offers stay until they do (seller suspension is not affected: it goes through `seller.listings.hidden`).
- **Automated flags.** Nothing calls catalog's `POST /v1/products/{id}/flags` automatically yet (for example an AI
  sweep of live listings); staff flags and shopper reports work.
- **payments** refunds need the payment id and line ids; there is no "refund by order" endpoint, so staff supply them.
  Claims don't verify order ownership (no orders lookup for services yet).
- **notifications** maps the typed claim events, but has no rule yet for `trustops.claim.response_due_soon`
  (the seller reminder) or `trustops.claim.response_lapsed`, and still lists the retired `notification.requested`
  claim templates as superseded; `contracts/asyncapi/events.yaml` must be re-merged
  (`tools/contracts/merge_asyncapi.py`) to pick up the new events.
- **fulfilment** publishes no events yet, so seller health uses orders events only; there is no ship-by date, so
  "late" means delivered after `promisedBy`.

## Run and test

```bash
make up                                                      # Postgres, Kafka … from infra/local
cd java && ./mvnw -B -f services/trust-ops/pom.xml verify    # checkstyle, unit, ArchUnit, Testcontainers ITs
./mvnw -f services/trust-ops/pom.xml spring-boot:run         # http://localhost:8110/docs
```

Integration tests run against `imresamu/postgis:17-3.5.4-alpine3.22` with one WireMock standing in for identity's token endpoint,
catalog, bookings, payments, seller, offers and ai-gateway; Kafka listeners and jobs are called directly
(`ReviewModerationIT` and `ListenersIT` cover S243). Migrations:
`V1__trustops_schema.sql`, `V2__live_listings_and_seller_suspension.sql` (moderation item type and report fields;
`seller_enforcement.payouts_held` renamed `seller_suspended`), `V3__claim_response_reminder.sql`
(`claim.response_reminder_sent_at`), `V4__seller_shipment_ship_by.sql`, `V5__service_review_moderation.sql`
(`SERVICE_REVIEW` items: nullable `product_id`, review columns, one open item per `booking_id`).

Deploy: `deploy/helm` (depends on `infra/helm/library/at-service`), one Argo CD Application per environment.
