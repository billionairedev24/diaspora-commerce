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
- **Rate limits**: token bucket per OAuth client + subject (`at.mcp.rate-limit`, default 60 burst / 60 per minute;
  30/30 in prod). Exhausted buckets get JSON-RPC error `-32029` with `data.retryAfterMs`.
- **Audit**: every call (allowed, denied, rate-limited or failed) emits `mcp.tool.invoked` on `at.mcp-tool.events`
  (who, OAuth client, organisation, tool, SHA-256 of the canonical arguments — never the arguments themselves —,
  outcome, duration) and the same line on the `com.afrotransact.mcp.audit` logger. The service is stateless, so the
  event is published straight to Kafka with the outbox wire format instead of through an outbox table.

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
| | `track_order` | — | orders `GET /v1/orders/{id}` |
| | `start_return_proposal` | — | returns a **PROPOSED** return request |
| | `find_service` | — | bookings `GET /v1/services/search` |
| seller (`mcp:seller` + org) | `list_my_products`, `product_status` | `catalog:write` | catalog `GET /v1/products?orgId=…` |
| | `list_my_orders` | `orders:read` | orders `GET /v1/orders?sellerOrgId=…` |
| | `update_shipment_status` | `orders:update-status` | seller-fulfilled, forward only (PACKED → SHIPPED with carrier + tracking → DELIVERED) |
| | `check_inventory` | `inventory:read` | inventory `GET /v1/positions?ownerOrg=…` |
| | `request_category` | `catalog:write` | catalog `POST /v1/category-requests` |
| staff (`mcp:staff` + staff) | `applications_queue`, `application_detail` | `applications:review` | seller `GET /v1/applications…` — PII masked |
| | `moderation_queue` | `catalog:moderate` | catalog `GET /v1/products?status=IN_REVIEW` |
| | `order_payment_split` | `ledger:read` | payments `GET /v1/orders/{id}/payment-split` |

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
| `AUTH_ISSUER_URI` | identity issuer (JWT validation, advertised authorization server) |
| `MCP_PUBLIC_URL` | external URL of this server (resource indicator) |
| `MCP_ALLOWED_ORIGINS` | browser origins allowed to call `/mcp` (DNS-rebinding protection) |
| `MCP_RATE_CAPACITY`, `MCP_RATE_PER_MINUTE` | token bucket |
| `KAFKA_BOOTSTRAP` | audit events |
| `SEARCH_URL`, `CATALOG_URL`, `CART_URL`, `INVENTORY_URL`, `GEO_URL`, `PROMISE_URL`, `ORDERS_URL`, `SELLER_URL`, `PAYMENTS_URL`, `BOOKINGS_URL` | platform APIs |

Profiles: `local` (security off; `X-MCP-Client` header simulates OAuth clients), `dev`, `uat`, `prod`.

Metrics: `mcp_tool_calls_seconds{tool,toolset,outcome}`.

## Run and test

```bash
cd java && ./mvnw -B -f services/mcp-server/pom.xml verify   # unit, ArchUnit, MCP-client ITs (WireMock + Kafka)
./mvnw -f services/mcp-server/pom.xml spring-boot:run          # then: npx @modelcontextprotocol/inspector → http://localhost:8114/mcp
```

The ITs drive the endpoint with the official MCP Java client (`HttpClientStreamableHttpTransport`): tool lists per
scope, a tool call against WireMock-backed APIs with the token relayed, refusal of out-of-scope tools, rate limiting,
and the audit event read back from a Kafka Testcontainer.

Deploy: `deploy/helm` (depends on `infra/helm/library/at-service`), secret `KAFKA_BOOTSTRAP`.
