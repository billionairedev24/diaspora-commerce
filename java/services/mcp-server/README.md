# mcp-server-service

Model Context Protocol server for AfroTransact: shopper, seller and staff toolsets over the platform's public APIs,
for Lola and partner agents (Claude, ChatGPT, custom agents). Spring Boot 4.1 · Java 25 · **Spring AI 2.0 MCP server
(WebMVC)** · stateless (no database) · local port **8114**.

## MCP endpoint

- **Transport**: Streamable HTTP in **stateless** mode — `POST /mcp` with one JSON-RPC 2.0 message per request
  (`Accept: application/json, text/event-stream`). No server-side sessions, so any pod can answer and the service
  scales horizontally. Spring AI property `spring.ai.mcp.server.protocol=STATELESS`.
- **Auth (OAuth 2.1)**: bearer access tokens issued by the identity service. Unauthenticated calls get `401` with
  `WWW-Authenticate: Bearer resource_metadata="<public-url>/.well-known/oauth-protected-resource"`; that RFC 9728
  document names the identity issuer and the scopes, and MCP clients then run **Authorization Code + PKCE** against
  identity (public client, see "Identity setup").
- **Tool filtering**: `tools/list` only returns tools the token allows; `tools/call` of anything else is refused with
  JSON-RPC error `-32003`. Rules (`DefaultToolAccessPolicy`): the toolset scope, then seller tools need an
  organisation, staff tools a staff principal, then the tool's own permission.
- **Rate limits (global)**: token bucket per OAuth client + subject (`at.mcp.rate-limit`, default 60 burst / 60 per
  minute; 30/30 in prod), stored in **Valkey** so the limit holds across every replica. One atomic Lua script
  (`src/main/resources/valkey/token-bucket.lua`) refills and takes a token using the Valkey server clock (no pod clock
  skew); idle buckets expire once they would be full. Exhausted buckets get JSON-RPC error `-32029` with
  `data.retryAfterMs`.
  - **Failure mode** (`at.mcp.rate-limit.failure-mode`, env `MCP_RATE_FAILURE_MODE`): **`open` is the default** —
    when Valkey cannot be reached (250 ms command timeout) each pod falls back to its own in-memory bucket with the
    same settings, so an outage never takes MCP down and a runaway client is still capped per pod (worst case
    `replicas ×` the limit). `closed` refuses every call with `-32029` until Valkey is back, for environments that
    prefer strictness over availability.
  - `store: memory` (env `MCP_RATE_STORE`) keeps the old per-pod buckets (single-instance tools only).
- **Audit (durable)**: every call (allowed, denied, rate-limited or failed) emits `mcp.tool.invoked` on
  `at.mcp-tool.events` (who, OAuth client, organisation, tool, SHA-256 of the canonical arguments — never the arguments
  themselves —, outcome, duration) and the same line on the `com.afrotransact.mcp.audit` logger. The service has no
  database (no outbox table), so `AuditRecorderService` publishes straight to Kafka and, when Kafka refuses an event
  (send throws or is not acknowledged), appends it to a **Valkey stream spool** (`mcp:audit:spool`). While a pod
  knows the spool has a backlog, its new events are spooled too so they queue behind it instead of overtaking it.
  `AuditReplayJob` (every 5 s) drains the spool **in stream order** to Kafka, one replica at a time (lease key
  `SET NX PX`), stopping at the first refusal so order is kept, and deleting each entry only after Kafka acknowledged
  it. Replayed events keep their `eventId`/`at`; consumers de-duplicate on `eventId`. The stream is capped
  (approximate `MAXLEN`, default 1,000,000); if Kafka **and** Valkey are both down, the audit log line is the
  backstop.

  *Why a Valkey stream and not a local file spool*: the MCP server is a stateless Deployment that autoscales (3–12
  pods in prod). A per-pod append-only file needs either an emptyDir (lost exactly when an outage coincides with a
  scale-down, eviction or node drain) or a per-pod PVC (turns the Deployment into a StatefulSet and blocks scaling).
  A Valkey stream is shared, so any surviving replica replays what a vanished pod spooled; stream ids give a single
  global order for free; and Valkey is already a dependency for the global rate limits (AOF persistence in the
  platform Valkey). The trade-off — the spool is not available when Valkey is down too — is covered by the audit log.

The guard is a decorator around the Spring AI stateless transport (`GuardedMcpTransport`), so listing, admission and
audit apply to every tool without touching the tool code; tools also re-check their permission in the application
layer.

## Toolsets

| Toolset (scope) | Tool | Extra permission | Calls |
|---|---|---|---|
| shopper (`mcp:shopper`) | `search_products` | — | search `GET /v1/search` |
| | `get_product` | — | catalog `GET /v1/public/products/{atin}` |
| | `check_delivery` | — | geo `GET /v1/zip/{zip}` + promise `GET /v1/promises` |
| | `get_cart` | — | cart `GET /v1/cart` |
| | `propose_add_to_cart` | — | catalog lookup; returns a **PROPOSED** cart call, never executes it |
| | `track_order` | — | orders `GET /v1/orders/{id}`: status, shipments, tracking, whether Cancel is still offered (`cancellable`, `cancellableUntil`: before packing and inside the cancellation window), and a delivered shipment's proof (how it was handed over; the photo and signature are media asset ids, not links) |
| | `start_return_proposal` | — | returns a **PROPOSED** return request |
| | `find_service` | — | bookings `GET /v1/services/search` |
| | `my_order_history` | — | orders `GET /v1/orders` — the signed-in customer's own orders, newest first; page with `cursor` |
| seller (`mcp:seller` + org) | `list_my_products`, `product_status` | `catalog:write` | catalog `GET /v1/products?orgId=…` |
| | `list_my_orders` | `orders:read` | orders `GET /v1/seller/shipments` — the organisation's shipments, newest first; page with `cursor` (the previous `nextCursor`) |
| | `update_shipment_status` | `orders:update-status` | seller-fulfilled, one step forward: CONFIRMED → PACKED → READY → SHIPPED (carrier shipments; needs a carrier and tracking number, recorded with `POST /v1/shipments/{id}/tracking`, unless a label is already bought) or DELIVERED (pickup orders only; needs the buyer's 4-digit `handoverCode`); the step is `POST /v1/shipments/{id}/transitions` |
| | `check_inventory` | `inventory:read` | inventory `GET /v1/positions?ownerOrg=…` |
| | `request_category` | `catalog:write` | catalog `POST /v1/category-requests` |
| | `get_my_order` | `orders:read` | orders `GET /v1/orders/{id}` — only the organisation's own shipments, whitelisted fields, no buyer details |
| | `payout_balance` | — (payments decides per member role) | payments `GET /v1/payouts/balance?orgId=` — available now, held orders with the date each is released (7 days after delivery), in transit, paid, minimum, pauses; the answer to "when will I get my payout" |
| | `list_payouts` | — | payments `GET /v1/payouts/requests?orgId=` |
| | `account_health` | — | seller `GET /v1/orgs/{orgId}/account-health` — what Stripe still needs, whether payouts or charges are off |
| staff (`mcp:staff` + staff) | `applications_queue`, `application_detail` | `applications:review` | seller `GET /v1/applications…` — PII masked |
| | `moderation_queue` | `catalog:moderate` | catalog `GET /v1/products?status=IN_REVIEW` |
| | `category_requests_queue` | `catalog:taxonomy` | catalog `GET /v1/category-requests?status=` — sellers' requests for a missing category with the products waiting; read-only (staff decide in the console under Catalog) |
| | `order_payment_split` | `ledger:read` | payments `GET /v1/orders/{id}/payment-split` |
| | `order_lookup` | `orders:read` | orders `GET /v1/orders/{id}` — PII masked |
| | `seller_payout_status` | `ledger:read` | payments `GET /v1/payouts/balance?orgId=` — why a seller has not been paid |
| | `seller_account_health` | `applications:review` | seller `GET /v1/orgs/{orgId}/account-health` — PII masked |

> **Changed:** `update_shipment_status` and `list_my_orders` now call orders-service's real routes (they called ones
> that do not exist). `list_my_orders` takes `cursor` instead of `page`; `update_shipment_status` takes `handoverCode`
> and follows orders' steps (CONFIRMED → PACKED → READY → SHIPPED, or DELIVERED for a pickup order).

Every tool returns `{status, data, message}` where status is `OK`, `PROPOSED` (show to the user; only their
confirmation performs it), `NOT_FOUND`, `FORBIDDEN`, `REJECTED` or `UNAVAILABLE` (the owning service is not deployed
yet or did not answer — agents are told not to guess). Each platform API is a port with a `RestClient` adapter that
relays the caller's bearer token; the MCP server never reads a database.

## REST

| Method | Path | Who |
|---|---|---|
| GET | `/v1/tools` (`?mine=true` for the caller's own) | public — developer portal catalogue with JSON schemas |
| GET | `/.well-known/oauth-protected-resource` | public — RFC 9728 metadata |
| POST | `/mcp` | MCP clients (bearer token) |

Contract: [`contracts/openapi/mcp-server.yaml`](../../../contracts/openapi/mcp-server.yaml) (REST only; the MCP
protocol is described here).

## Identity setup (shared change)

Add permissions/scopes `mcp:shopper`, `mcp:seller`, `mcp:staff` to identity and grant them to the matching roles, and
register a public MCP client, for example:

```yaml
- client-id: mcp-client
  name: AI agents (MCP)
  type: public
  grant-types: [authorization_code, refresh_token]
  redirect-uris: ["http://127.0.0.1:33418/callback", "https://claude.ai/api/mcp/auth_callback"]
  scopes: [openid, mcp:shopper, mcp:seller, mcp:staff]
  require-consent: true
```

## Configuration

| Env var | Purpose |
|---|---|
| `AUTH_ISSUER_URL` | identity issuer (JWT validation, advertised authorization server) |
| `MCP_PUBLIC_URL` | external URL of this server (resource indicator) |
| `MCP_ALLOWED_ORIGINS` | browser origins allowed to call `/mcp` (DNS-rebinding protection) |
| `MCP_RATE_CAPACITY`, `MCP_RATE_PER_MINUTE` | token bucket |
| `MCP_RATE_STORE` | `valkey` (default, global) or `memory` (per pod) |
| `MCP_RATE_FAILURE_MODE` | `open` (default: per-pod fallback while Valkey is down) or `closed` (refuse calls) |
| `REDIS_URL` | Valkey (`redis://host:6379`): rate-limit buckets and the audit spool |
| `MCP_AUDIT_SPOOL_MAX`, `MCP_AUDIT_REPLAY_INTERVAL` | audit spool cap (default 1,000,000) and replay delay (5 s) |
| `KAFKA_BOOTSTRAP` | audit events |
| `SEARCH_URL`, `CATALOG_URL`, `CART_URL`, `INVENTORY_URL`, `GEO_URL`, `PROMISE_URL`, `ORDERS_URL`, `SELLER_URL`, `PAYMENTS_URL`, `BOOKINGS_URL` | platform APIs |

Profiles: `local` (security off; `X-MCP-Client` header simulates OAuth clients), `dev`, `uat`, `prod`.

Metrics: `mcp_tool_calls_seconds{tool,toolset,outcome}`. Ops: `XLEN mcp:audit:spool` on Valkey shows the audit
backlog during a Kafka outage.

## Run and test

```bash
cd java && ./mvnw -B -f services/mcp-server/pom.xml verify   # unit, ArchUnit, MCP-client ITs (WireMock + Kafka + Valkey)
./mvnw -f services/mcp-server/pom.xml spring-boot:run          # then: npx @modelcontextprotocol/inspector → http://localhost:8114/mcp
```

The ITs drive the endpoint with the official MCP Java client (`HttpClientStreamableHttpTransport`): tool lists per
scope, a tool call against WireMock-backed APIs with the token relayed, refusal of out-of-scope tools, rate limiting,
and the audit event read back from a Kafka Testcontainer. `GlobalRateLimitIT` is the 3-replica load test: three
limiter instances, each with its own Valkey connection, fire 300 concurrent calls at one key and exactly `capacity`
are admitted (per-pod buckets admit 3×); it also covers refill, key expiry and both failure modes. `DurableAuditIT`
drives the real Kafka adapter and Valkey spool through a simulated broker outage and checks the replayed events
arrive in order; `AuditRecorderServiceTest` covers ordering, partial replay, the lease and the both-down path.
Testcontainers use the local `valkey/valkey:8.1-alpine` and `apache/kafka:4.1.0` images.

Deploy: `deploy/helm` (depends on `infra/helm/library/at-service`), secrets `KAFKA_BOOTSTRAP` and `REDIS_URL`.
