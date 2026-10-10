# carrier-gateway

Direct carrier integrations — **USPS, UPS, FedEx and DHL Express, no aggregator** — behind one `Carrier` port
(`Rate`, `BuyLabel`, `Void`, `Track`, `SchedulePickup`, `ValidateAddress`). Go 1.27.2 · PostgreSQL `carriers` ·
Valkey · local port **8209**.

| Capability | How |
|---|---|
| Rate shopping | `POST /v1/rates` fans out to every enabled carrier that has an account for the origin (FC or seller) in parallel with a **1.5 s overall budget**. Late carriers are reported `timeout`, failing ones `error`, and the result is flagged `partial`. Complete results are cached in Valkey for **10 minutes** per cart/shipment fingerprint (partial ones are not cached). Quotes carry negotiated vs list amounts; accounts can add an `adjustmentPct`. |
| Labels | `POST /v1/labels` with `Idempotency-Key` (scoped per caller org). A `purchasing` row reserves the key before the carrier is called; a retry returns the same label, a different body is rejected (422), a failed purchase frees the key. Documents (PDF/ZPL) go to the `ObjectStore` (S3-compatible via aws-sdk-go-v2, or the filesystem locally); if storing fails the label is voided at the carrier. `shipment_label` keeps carrier, service, tracking number, cost, label key and status. |
| Void | `POST /v1/labels/{id}/void` only while the tracking state is still `label_created` (before carrier acceptance). |
| Tracking | Push: `POST /v1/webhooks/{ups,fedex,dhl}` (credential/HMAC verified). Poll: USPS (config `polling.carriers`) for active, non-delivered numbers, claimed with `FOR UPDATE SKIP LOCKED`, every 2 h with exponential backoff up to 12 h on failures. Events are de-duplicated by (carrier, event id), applied in carrier-timestamp/sequence order, never regress (late scans are history only; delivered/returned are terminal) and mapped to `label_created, picked_up, in_transit, out_for_delivery, delivered, delivery_failed, returned, exception`. Numbers bought elsewhere (seller labels) are registered with `POST /v1/tracking`. |
| Events | `carrier.tracking.updated` and `carrier.pickup.ready` on **`at.carrier-shipment.events`** via a Postgres outbox written in the same transaction; the dispatcher (`SKIP LOCKED`, per-aggregate ordering, backoff on broker failure) publishes with `go/pkg/events`. Contract: [`contracts/asyncapi/fragments/carrier-gateway.yaml`](../../../contracts/asyncapi/fragments/carrier-gateway.yaml). |
| Pickups | `POST /v1/pickups` books a carrier pickup at an FC or seller. Customer pickup locations (S080): `GET/POST/PUT /v1/pickup-locations` for platform counters, lockers and seller pickup (address, timezone, weekly hours, typical ready time); sellers manage only their own org's `SELLER` locations, staff with `shipping:admin` manage all. `POST /v1/pickup-locations/{id}/ready` publishes `carrier.pickup.ready` for the customer notification. |
| Addresses | `POST /v1/addresses/validate` uses the carrier's own validation (USPS for US, DHL abroad by default). |

Permissions: `shipping:rate` (rates, tracking reads, address validation), `shipping:label` (labels, pickups,
tracking registration), `shipping:void`, `shipping:admin`. Errors are RFC 9457 with codes
`carrier-gateway.{invalid-request, not-found, conflict, idempotency-key-reused, carrier-disabled, carrier-rejected,
carrier-auth, carrier-unavailable, webhook-unverified, forbidden}`; `carrier-unavailable` (503 + `Retry-After`) is the
only retryable class. Contract: [`contracts/openapi/carrier-gateway.yaml`](../../../contracts/openapi/carrier-gateway.yaml).

## Carrier adapters and assumptions

Each adapter lives in `internal/adapter/carrier/<carrier>`, owns its auth, retries (2 retries, jittered exponential
backoff, `Retry-After` honoured but never past the request deadline) and error mapping (`429/5xx/timeouts` →
retryable; `400/422` → rejected with the carrier code; `401/403` → auth, after one token refresh). **All endpoint
paths and API versions are in `config/<env>.yaml`** (`usps.paths`, `ups.paths`, …).

| Carrier | Auth | Endpoints used | Assumptions |
|---|---|---|---|
| USPS (apis.usps.com) | OAuth 2.0 client credentials (JSON body) at `/oauth2/v3/token`, cached until 60 s before expiry | Prices v3 `base-rates/search` (one call per configured mail class; EPS account sent for contract pricing), Labels v3 `POST /labels/v3/label` + `DELETE …/{trackingNumber}` with `X-Payment-Authorization-Token`, Tracking v3 `?expand=DETAIL`, Addresses v3, Carrier Pickup v3 | `USPS_PAYMENT_TOKEN` is obtained out of band (Payments v3 payment-authorization with CRID/MID) and rotated via the secret store. Labels are single-piece; the response is parsed as `multipart/mixed` (labelMetadata + base64 labelImage) or the JSON form. Domestic only. Event ids are hashes of (number, code, GMT timestamp, ZIP). |
| UPS | OAuth client credentials (Basic auth + `x-merchant-id`, form body); `expires_in` arrives as a string | Rating `v2409/Shop`, Shipping `v2409/ship` and `void/cancel/{id}`, Track `v1/details`, Track Alert `v1/subscription/standard/package`, Pickup `v2409/pickup/oncall`, Address Validation `v2/3` | Labels are ZPL (UPS has no PDF). `NegotiatedRatesIndicator` when the account is negotiated; negotiated charges win over list. Objects-vs-arrays (`RatedShipment`, `PackageResults`, `Candidate`) are both accepted. Track Alert pushes are authenticated by the `credential` header we set on the subscription (`UPS_WEBHOOK_CREDENTIAL`); UPS offers no HMAC. Every UPS label and registered number is subscribed. |
| FedEx | OAuth client credentials (form body) at `/oauth/token` | Rate `v1/rates/quotes` (`ACCOUNT` + `LIST`), Ship `v1/shipments`, Cancel `PUT v1/shipments/cancel`, Track `v1/trackingnumbers`, Pickup `v1/pickups`, Address `v1/addresses/resolve` | Webhooks are verified with HMAC-SHA256 over the raw body using `FEDEX_WEBHOOK_SECRET`, header `fdx-signature` (base64; hex also accepted; header name configurable). Payload is the Track API result shape, bare or wrapped in `output`. Customs commodities are sent for international shipments. |
| DHL Express (MyDHL API) | HTTP Basic (`DHL_USERNAME`/`DHL_PASSWORD`), `Message-Reference` header per call | `POST /rates`, `POST /shipments` (customs: `exportDeclaration.lineItems` with HS codes, quantities, prices, origin; invoice number/date; declared value + currency; incoterm DAP/DDP), `GET /shipments/{n}/tracking`, `POST /pickups`, `GET /address-validate` | MyDHL has no shipment cancel; DHL bills only scanned waybills, so `Void` is local. Address validation is serviceability (city/postcode), not street level. Push tracking is verified with HMAC-SHA256 (`DHL_WEBHOOK_SECRET`, header `X-DHL-Signature`, configurable) and uses the tracking response shape. |

## Configuration

`config/<APP_ENV>.yaml` + env. Secrets only from env: `DB_URL`, `REDIS_URL`, `USPS_CLIENT_ID`, `USPS_CLIENT_SECRET`,
`USPS_PAYMENT_TOKEN`, `UPS_CLIENT_ID`, `UPS_CLIENT_SECRET`, `UPS_ACCOUNT`, `UPS_WEBHOOK_CREDENTIAL`, `FEDEX_CLIENT_ID`,
`FEDEX_CLIENT_SECRET`, `FEDEX_ACCOUNT`, `FEDEX_WEBHOOK_SECRET`, `DHL_USERNAME`, `DHL_PASSWORD`, `DHL_ACCOUNT`,
`DHL_WEBHOOK_SECRET`, `S3_ACCESS_KEY_ID`, `S3_SECRET_ACCESS_KEY` (empty → default chain / workload identity).
Other overrides: `PUBLIC_BASE_URL`, `KAFKA_BROKERS`, `RATE_BUDGET`, `QUOTE_TTL`, `LABEL_STORE`, `S3_BUCKET`,
`<CARRIER>_ENABLED`, `<CARRIER>_BASE_URL`, `AUTH_ENABLED`, `AUTH_ISSUER_URI`. Local/dev/uat use carrier sandboxes,
prod the production hosts. A carrier that is enabled but has no credentials is skipped at start-up with a warning.
Carrier accounts and negotiated-rate settings per FC/seller live under `accounts` (empty numbers use the platform
account from env).

## Run and test

```bash
cd go/services/carrier-gateway
export GOTOOLCHAIN=go1.27.2 GOWORK=off
go test ./...      # adapters vs httptest fakes, fan-out/budget, cache, idempotency, ingest, Postgres + Valkey Testcontainers
APP_ENV=local go run ./cmd/carrier-gateway   # needs the carriers database, Valkey and Kafka from infra/local
```
