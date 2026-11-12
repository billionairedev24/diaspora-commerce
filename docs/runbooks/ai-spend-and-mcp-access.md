# AI spend ceiling and private MCP access

Owner decisions, enforced in code:

1. **AI spend is capped at USD 2.00 per UTC day in total**, on the cheapest suitable model, with a clear 429 when reached
   and a per-caller limit so one caller cannot use up the day for everyone.
2. **The MCP server is private**: no public hostname and no route through the Cloudflare Tunnel or Envoy, reachable only
   inside the cluster (staff through an authenticated path later if needed). Every tool call needs authentication and a scoped token. No staff tool is reachable with a shopper token. AI-calling tools count against the
   same budget and per-caller limit.

## 1. AI spend ceiling (ai-gateway)

### What is enforced, in order, before every model call

| Check | Default | 429 code | Scope in the problem |
|---|---|---|---|
| Call rate per caller | 20 calls/min for a user token, 120/min for a service client (media-service) | `ai.rate-limited` (+ `Retry-After` seconds) | n/a |
| Whole-gateway daily total | USD 2.00 per UTC day, all features and callers | `ai.budget-exceeded` (+ `Retry-After` until UTC midnight) | `global` |
| One caller's daily share | USD 0.50 per UTC day, all features | `ai.budget-exceeded` | `caller` |
| Per-feature and per-user budgets | `at.ai.budgets.*` (unchanged; the USD 2 total is the binding one) | `ai.budget-exceeded` | `feature-cost`, `user-cost`, `user-tokens` |

Both 429s are RFC 9457 `application/problem+json`, for example:

```json
{"type":".../ai.budget-exceeded","title":"AI budget exceeded","status":429,"code":"ai.budget-exceeded",
 "detail":"The daily AI spend limit has been reached; try again tomorrow","feature":"lola","scope":"global"}
```

Callers must treat 429 as "AI is off until the next UTC day (or for N seconds)": Lola, listing writer and the others
already show the problem detail; media-service holds or publishes images per its `failMode` (see media config).

### Cheapest model

Every text task (intent, planning, chat, listing writer, translation, triage, image moderation) defaults to
`google/gemini-2.5-flash-lite` (USD 0.10 in / 0.40 out per million tokens), falling back to `openai/gpt-5-nano`; the Spring AI
default chat model is also `google/gemini-2.5-flash-lite`. Embeddings use `openai/text-embedding-3-small`. Premium models are only
used when someone sets `AI_MODELS_<TASK>` (see `docs/runbooks/cost.md`); a model missing from the price table is costed at the
conservative `default-price` (USD 15 / 75), so it uses the ceiling up quickly.

### Settings (environment variables on the ai-gateway deployment)

| Variable | Default | Meaning |
|---|---|---|
| `AI_GLOBAL_DAILY_COST_USD` | `2.00` | the owner's daily ceiling; `0` = unlimited (never in prod) |
| `AI_CALLER_DAILY_COST_USD` | `0.50` | one caller's share of the day |
| `AI_USER_CALLS_PER_MINUTE` | `20` | per user token (one Lola message can take several model rounds) |
| `AI_SERVICE_CALLS_PER_MINUTE` | `120` | per service client |

To raise the ceiling for a day, set `AI_GLOBAL_DAILY_COST_USD` and restart ai-gateway; change it back afterwards.

### Accuracy and limits (read this)

* Cost is **estimated from provider usage after each call** (`at.ai.prices`), so the day can overshoot by the cost of the
  calls in flight at the moment the ceiling is crossed. With the cheapest models that is a fraction of a cent per call.
* Totals live in Postgres (`ai_usage_daily`, scopes `GLOBAL` and `CALLER`), shared by all replicas, reset at 00:00 UTC.
* The per-minute rate limit is kept **in memory per replica**; with N replicas the effective limit is up to N times the
  setting. The daily dollar caps are exact across replicas.
* **The OpenRouter key credit limit is set separately by the owner** (OpenRouter dashboard, Keys, the key, Credit limit)
  and is the real backstop if the gateway is bypassed or the price table is wrong. Set it to a few dollars.
* Check usage: `select * from ai_usage_daily where usage_day = current_date and scope = 'GLOBAL';` (cost in micro-dollars,
  2,000,000 = USD 2). Metrics: `ai_budget_decisions_total{verdict=~"deny_global|deny_caller|rate_limited"}`.

## 2. Private MCP server

The service is `ClusterIP` only. Consumers (ai-gateway or any other in-cluster caller) use its service name,
`http://mcp-server.afrotransact-<env>.svc.cluster.local` (port 80, or `http://mcp-server` from the same namespace). Do not add an
HTTPRoute, tunnel ingress rule or `mcp.<domain>` DNS name for it; that would reverse this owner decision.

### Which paths exist and how they are protected

| Path | Access |
|---|---|
| `POST /mcp` (all JSON-RPC: `initialize`, `tools/list`, `tools/call`) | **bearer token required** (OAuth 2.1 from the identity service); no anonymous tool |
| `GET /.well-known/oauth-protected-resource` | open inside the cluster (RFC 9728 metadata, so clients find the issuer) |
| `GET /v1/tools` | open inside the cluster, but without a token it lists the **shopper toolset only**; seller and staff tools appear only for a token that may call them (`?mine=true` filters further) |
| `/actuator/health`, `/actuator/prometheus`, `/actuator/info` | cluster-internal only (no edge route exists) |

No tool is anonymous. Even the read-only shopper catalogue tools need a token, so that rate limits, audit and the AI
budget attach to an identity.

### How a call is authorised (`tools/list` and `tools/call`)

1. The JWT is verified (issuer, signature, expiry). Missing or invalid: HTTP 401 with `WWW-Authenticate: Bearer resource_metadata=...`.
2. Toolset scope first: `mcp:shopper`, `mcp:seller`, `mcp:staff` must be in the token.
3. Toolset rule: seller tools need a seller/provider organisation; **staff tools need a staff principal** (a shopper token can
   never call them, even if it carries `mcp:staff`).
4. The tool's own platform permission (for example `orders:update-status`).
5. Per client and subject token bucket (prod: 30 calls capacity, 30/min), shared across replicas through Valkey.
6. Every call, allowed or refused, is audited (`mcp.tool.invoked`).
7. `tools/list` only returns the tools steps 2 to 4 allow.

Tools call the platform APIs with the caller's own relayed token, so each downstream service applies its own checks again.

### Safety nets

* **Startup guard** (`McpExposureGuard`, every profile except `local`): the service refuses to start if `at.security.enabled` is
  false, if `MCP_PUBLIC_URL` is neither https nor an in-cluster service URL (`http://mcp-server.<ns>.svc.cluster.local`), or if
  `AUTH_ISSUER_URL` is not an https non-local address. The in-cluster URL is accepted only with auth on. So a stray
  `AT_SECURITY_ENABLED=false` cannot make production anonymous.
* **AI budget:** the MCP server has no model client and a test (`ArchitectureTest.toolsNeverCallModelProvidersDirectly`) keeps it that
  way. A tool that needs a model must call the ai-gateway with the caller's relayed token; the gateway then applies the
  USD 2 ceiling, the caller's USD 0.50 share and the per-minute rate to that caller like any other.
* **Browser origins:** `MCP_ALLOWED_ORIGINS` is the allow-list for browser-based MCP clients; server-side agents send no Origin.

### Operating it

* There is no public exposure to switch off. If a route or `mcp.` DNS name ever appears, remove it (Cloudflare tunnel ingress,
  HTTPRoute, `infra/cloudflare`); the service itself keeps running.
* To block one client or user, revoke their token / client in identity; tokens are short-lived.
* Watch `mcp_*` metrics and the audit topic for `denied` and `rate_limited` outcomes; a spike from one client is the signal.
* MCP clients need an identity client with the scopes they should have; give shoppers `mcp:shopper` only. Staff-only
  scopes are granted to staff accounts only.
