# notifications

Go service (port **8208** locally, Postgres database `notifications`) that sends templated, consent-checked
email, SMS, push and WhatsApp messages with per-market provider failover, and records delivery receipts.
Architecture: section 18 of `design/AfroTransact Architecture.dc.html`. Backlog: E19 (S121–S125).

## How a message flows

```
Kafka event ──► Ingest (inbox de-dup) ─┐
POST /v1/notifications (Idempotency-Key)┴► Notifier: render template version ─► notification table (outbox)
                                                                                     │ SELECT … FOR UPDATE SKIP LOCKED
Dispatcher ◄─────────────────────────────────────────────────────────────────────────┘
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
| `internal/port` | `EmailSender`, `SmsSender`, `PushSender`, `ChatSender`, `ConsentChecker`, `ConsentStore`, `TemplateStore`, `NotificationStore`, `ContactResolver` |
| `internal/app` | `Router`, `Notifier`, `Dispatcher`, `Ingest`, `Receipts`, `SmsCompliance`, `Templates`, `Preferences`, `NotificationLog`, `Unsubscriber`, `Renderer` |
| `internal/adapter/{resend,sendgrid,ses,smtp}` | email adapters (Resend uses `Idempotency-Key`) |
| `internal/adapter/{twilio,vonage,awssms}` | SMS adapters (AWS = End User Messaging `SendTextMessage`) |
| `internal/adapter/{fcm,apns}` | push: FCM HTTP v1 (OAuth2 service account), APNs HTTP/2 with ES256 provider token |
| `internal/adapter/whatsapp` | WhatsApp Cloud API (text, or approved template + parameters) |
| `internal/adapter/logsender` | log provider for every channel (local) |
| `internal/adapter/webhooksig` | Twilio HMAC-SHA1, Resend/Svix HMAC-SHA256, SendGrid ECDSA verifiers |
| `internal/adapter/postgres` | pgx/v5 stores, embedded migrations (`migrations/*.sql`, applied at startup), consent checker |
| `internal/adapter/httpapi` | REST API, webhooks, one-click unsubscribe |
| `internal/adapter/jwtcontact` | caller's own email/phone for test-send |
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

## Events consumed

Consumer group `notifications` on `at.identity.events`, `at.orders.events`, `at.seller.events`,
`at.bookings.events` (fragment: `contracts/asyncapi/fragments/notifications.yaml`). Payloads carry a `recipient`
block (`id`, `email`, `phone`, `pushTokens`, `timeZone`, `locale`), optional `market`/`locale`/`channel`, and
template fields at the top level.

| Event type | Template | Channels |
|---|---|---|
| `identity.verification.requested` | `identity.verification` (6-digit code) | email, or the payload's `channel` |
| `order.placed` | `order.confirmed` | email, sms, push |
| `shipment.out_for_delivery` | `shipment.out_for_delivery` (handover code) | sms, push, email |
| `seller.payout.requirement_due` | `payout.requirement_due` (Stripe compliance) | email, sms |
| `booking.confirmed` | `booking.confirmed` | email, sms, push |
| `notification.requested` (any topic) | `templateKey` from the payload | `channels` from the payload |

Each event id is recorded in the `inbox` table in the same transaction as its notifications, so redeliveries
are ignored. A failed handler restarts the consumer from the last committed offset.

## Templates

Rows of `template(key, locale, channel, version)`; the latest version is used, with locale fallback
`fr-CA → fr → en`. Email HTML is Go `html/template` — compiled React Email or MJML output can be stored as-is with
`{{.field}}` placeholders; subjects and text are `text/template`. Missing fields fail rendering. Seeded (v1, en):
`identity.verification`, `order.confirmed`, `shipment.out_for_delivery`, `payout.requirement_due`,
`booking.confirmed`.

## Configuration

`config/{local,dev,uat,prod}.yaml` with env overrides (`APP_ENV` selects the file). Routing is an ordered
provider list per market (`default` is the fallback), e.g. `routing.email.us: [resend, sendgrid, ses]`,
`routing.sms.us: [twilio, aws, vonage]`. A provider whose credentials are missing is skipped with a warning.

Secrets (env only): `DB_URL`, `RESEND_API_KEY`, `SENDGRID_API_KEY`, `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`,
`VONAGE_API_KEY`, `VONAGE_API_SECRET`, `FCM_SERVICE_ACCOUNT_JSON`, `APNS_KEY_P8`, `WHATSAPP_TOKEN`,
`RESEND_WEBHOOK_SECRET`, `SENDGRID_WEBHOOK_PUBLIC_KEY`, `UNSUBSCRIBE_SIGNING_KEY`, `SMTP_PASSWORD`. AWS (SES, End User
Messaging) uses the default credential chain / workload identity. Other env overrides: `PUBLIC_BASE_URL`,
`KAFKA_ENABLED`, `KAFKA_BROKERS`, `DISPATCHER_*`, `BREAKER_*`, `QUIET_HOURS_START/END`, `SEND_TIMEOUT`.

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
status backwards, suppression, and one-click unsubscribe.

Container: `docker build -f services/notifications/Dockerfile go`. Helm: `deploy/helm` on the `at-service` library
chart.
