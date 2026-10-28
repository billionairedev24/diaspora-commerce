# ai-gateway-service

Lola, the AfroTransact AI shopping agent, plus AI utilities for search, sellers and staff — on **Spring AI 2.0**
with **OpenRouter** as the model provider (OpenAI-compatible API). Spring Boot 4.1 · Java 25 · PostgreSQL `ai` ·
local port **8113**.

## What it does

- **Model routing** (`ModelRouter` port, `SpringAiModelRouter` adapter): every call names a task — `INTENT`
  (fast/cheap), `PLANNING` and `CHAT` (strong), `LISTING_WRITER`, `TRANSLATION`, `TRIAGE`, `EMBEDDING`,
  `IMAGE_MODERATION` (vision models). The routing
  table in `at.ai.routes` gives each task its models in fallback order, a per-call timeout and retries. 429/5xx are
  retried with exponential backoff, then the next model is tried; timeouts and other 4xx fall back immediately. Our own
  loop (not OpenRouter's `models` array) keeps routing provider-agnostic. Model ids live only in configuration.
- **Budgets**: every call is written to the `ai_usage` ledger from the provider's usage metadata, with a cost estimated
  from the `at.ai.prices` table (micro-dollars), and rolled up per day per user×feature and per feature. Above a daily
  limit a feature can **degrade** to the cheapest model of its route until `hard-limit-factor × limit`; beyond that the
  API answers `429 ai.budget-exceeded` (RFC 9457, `Retry-After` = seconds to UTC midnight).
- **Lola** (`LolaService`): topic limits → PII redaction → memory (last N turns, redacted) → model rounds with tools
  (Spring AI tool definitions via `ToolCallback`; the agent executes tool calls itself so it can stream them, relay the
  shopper's token and ground the results) → grounded, moderated answer → `done`.
- **Guardrails** (framework-free domain, unit-tested):
  - `DefaultPiiRedactor`: emails, phone numbers (NANP + international), card numbers (Luhn-checked), SSNs and street
    addresses become `[EMAIL_1]`-style placeholders before any prompt or log line. The mapping lives only for the
    request and restores the text for the shopper's own display; stored memory stays redacted.
  - `DefaultInjectionFilter`: seller/provider text in tool results is stripped of instruction-like patterns
    ("ignore previous instructions", role markers, tool-call commands …) and quoted in `<untrusted>` envelopes.
  - `DefaultTopicPolicy`: no medical or legal advice; "talk to a person" and safety cases hand off to support.
  - `DefaultGroundingVerifier`: Lola may only present products and prices returned by tools in the turn. Streamed text
    is verified sentence by sentence: a sentence with an unknown ATIN or price is dropped before it is sent; product
    cards are built from tool results only.
  - `OutputModerator` port (rule-based adapter) reviews every sentence before it reaches the shopper.
- **Hand-off**: the model (`handoff_to_support`) or rules hand the chat to support — stored in `ai_handoff` and
  published as `ai.handoff.requested` through the outbox with a redacted summary.

## Image moderation (S030)

media-service's AI hook: before a product or storefront image is published, media sends a JPEG preview (≤ 768 px,
≤ 2 MB) with its own service token. The `image-moderation` route (vision models, OpenAI-compatible `image_url` data
URI) answers `ALLOW`, `REVIEW` or `BLOCK` with labels from a fixed vocabulary; `ImageModerationPolicy` (domain) makes
the labels imply a minimum decision (`WEAPONS`, `NUDITY` … block; `WATERMARK`, `CONTACT_INFO`, `TEXT_OVERLAY`,
`NOT_A_PRODUCT`, `THIRD_PARTY_LOGO`, `MISLEADING` review) and turns an unreadable or unknown answer into `REVIEW`,
never `ALLOW`. Text inside the image is treated as content, never as instructions. Billed to the `image-moderation`
budget (degrades to the cheapest vision model above the daily limit). Advisory: media holds or publishes, staff decide.

## Lola's tools

| Tool | Calls | Effect |
|---|---|---|
| `search_products` | search `GET /v1/search` | product cards (grounding facts) |
| `compare_offers` | catalog `GET /v1/public/{variants,products}/{atin}` + offers `GET /v1/public/products/{atin}/offers` | offers (empty until offers-service ships) |
| `check_delivery` | promise `GET /v1/promises?atin=&zip=` | promise, or "not available yet" |
| `add_to_cart` | — (catalog lookup only) | **action proposal**; never touches the cart |
| `build_cart_from_recipe` | search per ingredient | real in-stock hits only + "Add all to cart" proposal |
| `track_order` | orders `GET /v1/orders/{id}` | status, or "not available yet" |
| `start_return` | — | **proposal** |
| `find_service` | bookings `GET /v1/services/search?q=&zip=` | services (empty fallback) |
| `book_service`, `proceed_to_checkout` | — | **proposal** (spending money always needs the tap) |
| `handoff_to_support` | — | hand-off event |

Every downstream call is a port with a `RestClient` adapter that relays the caller's bearer token
(token exchange is future work).

## API

| Method | Path | Who |
|---|---|---|
| POST | `/v1/lola/chat` (SSE) | any signed-in user |
| POST | `/v1/lola/chat/complete` (JSON, same events) | any signed-in user |
| GET | `/v1/lola/sessions/{id}/messages` | owner of the session |
| POST | `/v1/embeddings` `{input, purpose}` → `{embedding, model, dimensions}` | `ai:use`: search-service (`search-query`, hybrid search S036) and indexer-service (`search-document`, product embeddings) |
| POST | `/v1/ai/moderate-image` `{image (base64), contentType, purpose}` → `{decision, labels, model}` | `ai:use` (media-service, S030) — see below |
| POST | `/v1/ai/listing-writer`, `/v1/ai/translate`, `/v1/ai/tagline` | `ai:use` — drafts only, never published |
| POST | `/v1/ai/seller-assistant/complete` | `ai:use`, seller and provider members only — one model turn for Lola in Seller Center: the gateway-bff sends the conversation and the tools, gets text or tool calls back and runs the tools itself. Billed to the organisation as AI credits; budget `seller-assistant` |
| POST | `/v1/ai/triage` | `ai:triage` — cited fields, decision always `HUMAN_REVIEW_REQUIRED` |
| GET | `/v1/ai/usage/orgs?from=&to=` | `ai:usage.read` (seller-service) or staff `ai:manage`: AI credits per organisation and UTC day for metered billing (S206). Calls by seller / provider members are attributed to their organisation (`ai_usage.org_id`); 1 credit = 10,000 micro-dollars of estimated cost, rounded up per day |

SSE events of a Lola turn, in order: `tool_call` (label such as "Searching for jollof seasoning") → `tool_result`
(cards, offers, promise, order, services) → `action_proposal` (add to cart, checkout, book, return; always
`requiresConfirmation: true`) → `token` … → `handoff`? → `done` (grounded message, cards, proposals, suggestion
chips, dropped claims, models, usage). Failures mid-stream send `error {code, detail}`.

Contract: [`contracts/openapi/ai-gateway.yaml`](../../../contracts/openapi/ai-gateway.yaml). Events:
[`contracts/asyncapi/fragments/ai-gateway.yaml`](../../../contracts/asyncapi/fragments/ai-gateway.yaml).

## Observability

Micrometer: `ai_model_latency_seconds{task,model,outcome}`, `ai_tokens_total{task,model,kind}`,
`ai_cost_usd_total{task,model}`, `ai_fallbacks_total{task,from,reason}`, `ai_budget_decisions_total{feature,verdict}`,
`ai_guardrail_total{kind}`, `ai_tool_calls_total{tool,outcome}`, plus Spring AI's `gen_ai_client_*` observations
(prompt/completion logging disabled). Logs never contain prompts; personal data is redacted first.

## Configuration

| Env var | Purpose |
|---|---|
| `OPENROUTER_API_KEY` | OpenRouter key (secret, env only) |
| `OPENROUTER_BASE_URL` | default `https://openrouter.ai/api/v1` (the OpenAI SDK appends `/chat/completions`) |
| `OPENROUTER_REFERER`, `OPENROUTER_TITLE` | OpenRouter attribution headers `HTTP-Referer` / `X-Title` |
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | PostgreSQL |
| `KAFKA_BOOTSTRAP` | outbox relay |
| `AUTH_ISSUER_URL` | identity issuer (JWT validation) |
| `SEARCH_URL`, `CATALOG_URL`, `OFFERS_URL`, `PROMISE_URL`, `ORDERS_URL`, `BOOKINGS_URL` | downstream APIs |
| `EMBEDDING_DIMENSIONS` | vector size (must match the search index) |

Routing table, prices and budgets: `at.ai.*` in `application.yml`, overridden per profile (`local`, `dev` uses
cheaper models, `uat`, `prod`).

## Run and test

```bash
cd java && ./mvnw -B -f services/ai-gateway/pom.xml verify   # checkstyle, unit, ArchUnit, Testcontainers + WireMock ITs
OPENROUTER_API_KEY=… ./mvnw -f services/ai-gateway/pom.xml spring-boot:run   # http://localhost:8113/docs
```

ITs run PostgreSQL in Testcontainers and an in-JVM WireMock that plays OpenRouter (chat completions, streamed SSE
chunks with tool calls, embeddings) and the downstream services.

Deploy: `deploy/helm` (depends on `infra/helm/library/at-service`), secrets `DB_URL`, `DB_USER`, `DB_PASSWORD`,
`KAFKA_BOOTSTRAP`, `OPENROUTER_API_KEY` from the cloud secret store.
