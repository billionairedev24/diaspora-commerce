# notifications

Go service (port **8208** locally, Postgres database `notifications`) that sends templated, consent-checked
email, SMS, push and WhatsApp messages with per-market provider failover, and records delivery receipts.
Architecture: section 18 of `design/AfroTransact Architecture.dc.html`. Backlog: E19 (S121–S125), S211 (back-in-stock alerts), S217 (rules aligned with real producer events).

## How a message flows

```
Kafka event ──► Ingest: rule → recipients ──────────┐  (payload, or identity by user/org id, TTL-cached)
inventory.availability.changed ──► StockAlerts ─────┤  (claim active alerts for the SKU)
POST /v1/notifications (Idempotency-Key) ───────────┴► Notifier: render template ─► notification table (outbox)
                                                         (events: inbox de-dup in the same transaction)  │
                                                                         SELECT … FOR UPDATE SKIP LOCKED │
Dispatcher ◄─────────────────────────────────────────────────────────────────────────────────────────────┘
  ├─ ConsentChecker: suppression, TCPA SMS consent, preferences, quiet hours ─► suppressed / rescheduled
  ├─ Router: providers for (channel, market) in order, circuit breaker each, 3 s per attempt,
  │          fail over on timeout / 5xx / retryable; stop on permanent errors
  └─ sent ─► provider webhooks ─► delivered / bounced / complained / failed (never backwards)
     all failed ─► re-queue with exponential backoff ─► dead_letter (POST /v1/dead-letters/{id}/replay)
```

## Layout

| Path | What |
|---|---|
| `internal/domain` | channels, categories, statuses, `Decide` consent rules, quiet hours, SMS keywords, circuit breakers, retry policy, `ProviderError` (retryable vs permanent) |
| `internal/port` | `EmailSender`, `SmsSender`, `PushSender`, `ChatSender`, `ConsentChecker`, `ConsentStore`, `TemplateStore`, `NotificationStore`, `ContactResolver`, `RecipientResolver`, `StockAlertStore` |
| `internal/app` | `Router`, `Notifier`, `Dispatcher`, `Ingest` (+ `rules.go`: topics and event rules), `StockAlerts`, `Receipts`, `SmsCompliance`, `Templates`, `Preferences`, `NotificationLog`, `Unsubscriber`, `Renderer` |
| `internal/adapter/{resend,sendgrid,ses,smtp}` | email adapters (Resend uses `Idempotency-Key`) |
| `internal/adapter/{twilio,vonage,awssms}` | SMS adapters (AWS = End User Messaging `SendTextMessage`) |
| `internal/adapter/{fcm,apns}` | push: FCM HTTP v1 (OAuth2 service account), APNs HTTP/2 with ES256 provider token |
| `internal/adapter/whatsapp` | WhatsApp Cloud API (text, or approved template + parameters) |
| `internal/adapter/logsender` | log provider for every channel (local) |
| `internal/adapter/webhooksig` | Twilio HMAC-SHA1, Resend/Svix HMAC-SHA256, SendGrid ECDSA verifiers |
| `internal/adapter/postgres` | pgx/v5 stores, embedded migrations (`migrations/*.sql`, applied at startup), consent checker |
| `internal/adapter/httpapi` | REST API, webhooks, one-click unsubscribe |
| `internal/adapter/jwtcontact` | caller's own email/phone for test-send |
| `internal/adapter/identity` | `RecipientResolver` over identity (client credentials `notifications-service`, scope `users:read`) |
| `internal/adapter/contactcache` | TTL cache in front of the resolver (not-found cached briefly, errors never) |
| `cmd/notifications` | explicit wiring (`main.go`) and provider construction from config (`providers.go`) |

## Rules

* **Categories come from the template**: `security` (OTP, verification) always sends; `transactional` sends
  unless the recipient turned the channel off; `reminder` respects preferences and quiet hours; `marketing` needs
  explicit opt-in, respects quiet hours and carries `List-Unsubscribe` + `List-Unsubscribe-Post: List-Unsubscribe=One-Click`.
* **SMS** needs a TCPA consent record (`POST /v1/consents/sms`: source + disclosure text version, audit trail kept).
  Inbound STOP, END, CANCEL, UNSUBSCRIBE, QUIT (and STOPALL/OPTOUT/REVOKE) revoke it; START/UNSTOP/YES restore it;
  HELP/INFO get the program help text (TwiML reply).
* **Quiet hours** default 21:00–08:00 in the recipient's time zone (request `recipient.timeZone`, else the market's
  zone from `marketTimeZones`); held messages are rescheduled to the end of the window, not counted as failures.
* **Suppression**: permanent bounces and complaints from Resend/SendGrid add the address; everything except
  security messages is then suppressed.
* **Errors**: adapters return `*domain.ProviderError`. Retryable = transport errors, timeouts, 408/425/429/5xx,
  401/403 (provider-account problems another provider avoids) and provider-specific throttling codes.
  Permanent = other 4xx such as invalid numbers (Twilio 21211), unsubscribed numbers (21610), unregistered
  device tokens. Permanent errors are not failed over and go straight to the dead-letter table.
* **Breakers**: per provider; `failureThreshold` consecutive failures open it for `coolDown`, then one half-open
  probe decides.

## API

All `/v1` endpoints except webhooks and unsubscribe need a bearer token (`/docs`, `/redoc`, `/openapi.yaml`;
contract copy in `contracts/openapi/notifications.yaml`).

| Endpoint | Permission |
|---|---|
| `POST /v1/notifications` (header `Idempotency-Key`) | `notifications:send` |
| `GET /v1/notifications?recipient=&channel=&status=&before=&limit=`, `GET /v1/notifications/{id}` | `notifications:read` |
| `GET /v1/dead-letters`, `POST /v1/dead-letters/{id}/replay` | read / `notifications:manage` |
| `GET /v1/templates`, `POST /v1/templates/{key}/preview` | `notifications:read` |
| `POST /v1/templates` (new version), `POST /v1/templates/{key}/test-send` (caller's own email/phone only) | `notifications:templates:write` |
| `GET/PUT /v1/preferences/{recipientId}` | the recipient, or `notifications:manage` |
| `POST /v1/consents/sms`, `GET /v1/consents/sms/{phone}` | `notifications:send` / read |
| `GET /v1/suppressions`, `DELETE /v1/suppressions/{address}` | read / `notifications:manage` |
| `POST /v1/webhooks/resend`, `/sendgrid`, `/twilio/status`, `/twilio/inbound` | provider signature |
| `GET/POST /v1/unsubscribe?t=…` | signed token (RFC 8058) |
| `POST /v1/stock-alerts`, `GET /v1/stock-alerts`, `DELETE /v1/stock-alerts/{atin}` | the shopper's own token (service tokens get 403) |

### Back-in-stock alerts (S211)

`POST /v1/stock-alerts` `{"atin": "ATGARI0001", "email"?, "market"?, "locale"?, "productTitle"?}` arms one alert per
shopper and variant ATIN (201; 200 with the existing alert when already active; 201 again re-arms one that was sent).
`GET` lists the caller's alerts (`active` / `notified`), `DELETE /v1/stock-alerts/{atin}` removes one (204 / 404).
On `inventory.availability.changed` (`at.inventory.events`, SKU = variant ATIN) with `available > 0`, up to 500 active
alerts for the SKU are claimed (`notified_event_id` = the event, so a redelivery re-claims the same rows and the inbox
prevents a second send) and `stock.back_in_stock` goes out by email (the alert's `email`, else identity's) and push.
Table `stock_alert` (migration `0004`).

## Events consumed

Consumer group `notifications`. Topics are configurable per environment (`kafka.topics.<name>` in
`config/<env>.yaml`) and default to the AsyncAPI channel addresses (`contracts/asyncapi/events.yaml`; consumer
fragment `contracts/asyncapi/fragments/notifications.yaml`):

| Name | Default address |
|---|---|
| `identity` | `at.identity.events` |
| `orders` | `at.order.events` |
| `sellerApplications` | `at.seller-application.events` |
| `sellerCompliance` | `at.seller-compliance.events` |
| `payouts` | `at.payout.events` |
| `bookings` | `at.bookings.events` |
| `content` | `at.content.events` |
| `inventory` | `at.inventory.events` |

Rules (`internal/app/rules.go`) map an event type to one or more templates. Recipients come from the payload's
`recipient` block (or top-level `email`/`phone`), from an identity user id (`customerId`, `applicantUserId`), or from
every owner of an organisation (`orgId`). Identity fills in missing email/phone/push tokens, first name, locale,
time zone and market, and channels the user switched off in their account are dropped (except security messages).

| Event type | Recipient | Template(s) | Channels |
|---|---|---|---|
| `identity.verification.requested` | payload (never looked up) | `identity.verification` | email, or the payload's `channel` |
| `identity.user.registered` (not STAFF) | payload `email` | `account.welcome` | email |
| `orders.order.placed` | `customerId` | `order.confirmed` | email, sms, push |
| `orders.order.cancelled` (only `wasPlaced`) | `customerId` | `order.cancelled` | email, push |
| `orders.shipment.status_changed` → `picked_up` (or `in_transit` without a pickup scan) | `customerId` | `shipment.shipped` | email, push |
| … → `out_for_delivery` | `customerId` | `shipment.out_for_delivery` | sms, push, email |
| … → `ready` with `deliveryMethod=PICKUP` | `customerId` | `shipment.ready_for_pickup` | email, sms, push |
| … → `delivery_failed` | `customerId` | `shipment.delivery_failed` | email, sms, push |
| `orders.shipment.delivered` | `customerId` | `shipment.delivered` | email, push |
| `orders.shipment.cancelled` | `customerId` | `shipment.cancelled` (refund note from `captured`) | email, push |
| `seller.application.submitted` / `info_requested` / `approved` / `rejected` | `applicantUserId` | `seller.application.*` | email (+ sms for info requested / approved, + push for approved) |
| `seller.compliance.reminder` | org owners | `seller.compliance.reminder` | payload `channels` (`in_app` → push), default email, sms |
| `seller.compliance.changed` (new items, or → RESTRICTED_SOON / RESTRICTED) | org owners | `seller.compliance.action_needed` | as above |
| `seller.payouts.paused` / `resumed`, `seller.listings.hidden` / `restored` | org owners | same name | email (+ sms for paused / hidden) |
| `payments.payout.paid` / `failed` | org owners | `payout.paid` / `payout.failed` | email + push / email + sms |
| `booking.confirmed` / `cancelled` / `rescheduled` | payload `recipient` (+ identity) | same name | email, sms, push |
| `booking.declined` | payload `recipient` (+ identity) | `booking.declined` | email, push |
| `content.career-application.received` | payload `email` | `careers.application_received` | email |
| `content.newsletter.confirmation_requested` | payload `email` | `newsletter.confirm` | email |
| `inventory.availability.changed` | stock alerts | `stock.back_in_stock` | email, push |
| `notification.requested` (any topic) | payload `recipient` (+ identity) | `templateKey` | `channels` |

`TestRulesMatchAsyncAPIContract` parses `contracts/asyncapi/events.yaml` and fails when a mapped event type or topic
is missing (or published on another topic); `TestEveryRuleHasSeededTemplates` checks every rule has a seeded template
per channel; the Postgres integration test renders a contract-shaped fixture of every mapped event.

Each event id is recorded in the `inbox` table in the same transaction as its notifications, so redeliveries
are ignored. A recipient that cannot be reached (no address, unknown user) is skipped; other recipients of the same
event still go out. Identity being unavailable fails the handler, and the consumer restarts from the last committed
offset (with backoff).

### Identity endpoints (planned)

`contracts/openapi/identity.yaml` has no service-callable user lookup yet, so the adapter targets:

* `GET /v1/internal/users/{userId}/contact` → `{userId, firstName, email, phone, phoneVerified, locale, timeZone,
  market, pushTokens: [{token, platform}], preferences: {email, sms, push}}` (404 = unknown user; `phoneVerified:
  false` drops the phone; `preferences.<channel>: false` drops the channel);
* `GET /v1/internal/orgs/{orgId}/owners` → `{orgId, ownerUserIds: [...]}`.

Both with a client-credentials token (`notifications-service`, scope `users:read`, secret `NOTIFICATIONS_CLIENT_SECRET`).
Until they exist, lookups return 404 and only payloads with addresses (OTP, bookings, content, generic) are delivered.

## Templates

Rows of `template(key, locale, channel, version)`; the latest version is used, with locale fallback
`fr-CA → fr → en`. Email HTML is Go `html/template` — compiled React Email or MJML output can be stored as-is with
`{{.field}}` placeholders; subjects and text are `text/template`. Missing fields fail rendering. Seeded in English (other
locales fall back to `en` until translated versions are added with `POST /v1/templates`): `0002` has the original v1
set, `0003` adds a template for every rule above plus `booking.reminder` (for `notification.requested` until bookings
publishes a reminder event) and `stock.back_in_stock`, each as the next version of its key so it lands on top of any
staff-created version. `order.confirmed` and `shipment.out_for_delivery` get a new version because the real payloads
carry no first name, formatted total or handover code: the ingest derives `firstName` (identity, else "there"),
`total`/`amount` (minor units + currency), `itemCount`, `estimatedDelivery`, `trackingLine`, `refundNote`,
`itemsText`, `deadline` and links (`orderUrl`, `portalUrl`, `actionUrl`, `productUrl`) from `links.*` config.

## Configuration

`config/{local,dev,uat,prod}.yaml` with env overrides (`APP_ENV` selects the file). `kafka.topics`, `identity.*` (base URL, token URL, client id, timeout, cache TTLs) and `links.*`
(storefront, seller center, provider hub) are per environment. Routing is an ordered
provider list per market (`default` is the fallback), e.g. `routing.email.us: [resend, sendgrid, ses]`,
`routing.sms.us: [twilio, aws, vonage]`. A provider whose credentials are missing is skipped with a warning.

Secrets (env only): `DB_URL`, `RESEND_API_KEY`, `SENDGRID_API_KEY`, `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`,
`VONAGE_API_KEY`, `VONAGE_API_SECRET`, `FCM_SERVICE_ACCOUNT_JSON`, `APNS_KEY_P8`, `WHATSAPP_TOKEN`,
`RESEND_WEBHOOK_SECRET`, `SENDGRID_WEBHOOK_PUBLIC_KEY`, `UNSUBSCRIBE_SIGNING_KEY`, `SMTP_PASSWORD`,
`NOTIFICATIONS_CLIENT_SECRET` (identity client; local defaults to identity's local secret; when missing elsewhere
identity lookups are disabled with an error log rather than stopping OTP delivery). AWS (SES, End User
Messaging) uses the default credential chain / workload identity. Other env overrides: `PUBLIC_BASE_URL`,
`KAFKA_ENABLED`, `KAFKA_BROKERS`, `IDENTITY_ENABLED`, `IDENTITY_URL`, `AUTH_TOKEN_URI`, `LINKS_*`, `DISPATCHER_*`, `BREAKER_*`, `QUIET_HOURS_START/END`, `SEND_TIMEOUT`.

Local: email → SMTP to Mailpit (`localhost:1025`, UI http://localhost:8025), SMS/push/WhatsApp → log provider,
auth off (local super admin), webhook signatures not checked, test-send goes to `testSend.localEmail/localPhone`.

## Run and test

```bash
make up                                        # repo root: Postgres, Kafka, Mailpit …
cd go/services/notifications
GOTOOLCHAIN=go1.27.1 GOWORK=off go run ./cmd/notifications       # http://localhost:8208/docs
GOTOOLCHAIN=go1.27.1 GOWORK=off go vet ./... && GOWORK=off go test ./...   # Docker needed for the Postgres test
GOWORK=off golangci-lint run --config ../../.golangci.yml ./...
```

The Postgres integration test (testcontainers) uses `postgis/postgis:17-3.5` (override with
`NOTIFICATIONS_TEST_PG_IMAGE`; skip with `-short` or `SKIP_DOCKER_TESTS=1`). It covers migrations, seeded templates
and versioning, idempotent submit, inbox de-duplication, `SKIP LOCKED` claiming across workers, retry with
backoff → dead letter → replay, TCPA consent with STOP/START, quiet hours, OTP bypass, receipts that never move
status backwards, suppression, one-click unsubscribe, every mapped producer event rendered with the seeded templates,
and stock alerts (subscribe once, claim once per restock, end-to-end send).

Container: `docker build -f services/notifications/Dockerfile go`. Helm: `deploy/helm` on the `at-service` library
chart.
