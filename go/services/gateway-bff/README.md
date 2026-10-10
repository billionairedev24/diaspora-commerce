# gateway-bff

Screen-shaped API for the storefront (`web/apps/storefront`, through its same-origin `/api/bff` proxy), the customer
app, and the Seller Center & Provider Hub (`web/apps/seller-center`, same proxy pattern, `/v1/seller/...`). Go 1.27.1 ·
Valkey · local port **8200**. Contracts:
[`contracts/openapi/gateway-bff.yaml`](../../../contracts/openapi/gateway-bff.yaml) (embedded copy in `api/openapi.yaml`,
served at `/openapi.yaml`, `/docs`, `/redoc`) and
[`contracts/openapi/gateway-bff-seller.yaml`](../../../contracts/openapi/gateway-bff-seller.yaml) (embedded copy in
`api/openapi-seller.yaml`, served at `/openapi-seller.yaml`); tests fail when a copy differs from its contract.

## What it does

- **Token-mediating backend (web).** `GET /v1/auth/login` and `/v1/auth/oidc/{provider}/authorize` start the OAuth 2.1
  authorization-code flow with PKCE (S256), `state` and `nonce` against identity (confidential client `gateway-bff`).
  The callback (`/v1/auth/oidc/callback`, and `/login/oauth2/code/afrotransact`, the redirect URI registered at identity)
  exchanges the code, verifies the ID token (JWKS, audience, nonce), **rotates the session id**, carries the guest
  ZIP over, merges the guest cart (`POST /v1/cart/merge`) and redirects to `WEB_PUBLIC_ORIGIN + returnTo` (relative
  paths only). Tokens live in Valkey, **AES-256-GCM encrypted at rest** (key derived from `SESSION_SIGNING_KEY`, bound
  to the record key); the browser only holds `at_session` = opaque id + HMAC (HttpOnly, Secure, SameSite=Lax).
  Access tokens are refreshed 30 s before expiry under a Valkey lock (identity's refresh tokens are single use);
  `invalid_grant` signs the customer out but keeps guest state. Sign-out revokes refresh and access tokens.
- **App (bearer).** `Authorization: Bearer` tokens are verified with `go/pkg/auth` (identity JWKS) and relayed; the
  app keeps its deliver-to ZIP in a per-subject record and its guest cart in `X-Guest-Cart` (echoed when issued).
- **Aggregation.** Product page = catalog public product (product or variant ATIN) + inventory availability per
  variant + promise (estimate when promise is unavailable) + LOW_STOCK flags. Home = content placements (slot payloads
  → announcements, hero, tiles, banners; product rows resolved through search — `a OR b` queries run per term — or
  catalog for SKU lists); without content, default search rails. Search/typeahead map the search service's hits and
  facets (labels, price buckets, delivery promise, deliver-to label).
- **Cart.** `lineId`/`savedId`/`offerId` are variant ATINs (one seller per product until the offers service exists);
  a search card's product ATIN resolves to its first in-stock variant. Line **selection for checkout is BFF state**
  (Valkey set of deselected lines per cart owner — the cart service has no selection; new lines start selected,
  unavailable lines are never selected). Stock problems: `409 STOCK_LIMIT` / `OUT_OF_STOCK` with `available`
  (live inventory), `inCart`, `requested`, `maxQuantity`; "move to cart" returns the item with quantity 1.
- **Site config.** Content's public config mapped to the contract; `identityProviders` keeps content's display order
  and takes `enabled` from identity's `GET /v1/identity-providers`. ETag computed from the merged body (304 on
  `If-None-Match`); Cache-Control from content.
- **Delivery location.** ZIP → geo `GET /v1/zip/{zip}` (cached 10 min); `serviceable` = content
  `GET /v1/markets/eligibility?country=US&state=&zip=` `deliveryAllowed` (unknown when content is down → serviceable).
- **Security.** CSRF: unsafe methods need an allowed `Origin`; no Origin is only accepted without a cookie session;
  bearer requests are exempt. CORS for `WEB_ORIGINS` with credentials. Per-IP fixed-window rate limits in Valkey
  (auth endpoints; lookups such as address suggestions, newsletter, stock alerts, applications). Bodies ≤ 64 KiB (413).
  Tokens are never logged (upstream errors carry method + path only).
- **Resilience & observability.** One client per upstream: timeout, retries for GETs only, circuit breaker
  (5 consecutive failures → open 15 s → one half-open probe). Unreachable/timeout/5xx/open breaker, or a 404/405
  **without a problem code** (a route the upstream does not serve yet) → `503` problem `UPSTREAM_UNAVAILABLE`
  (`type …/bff.upstream-unavailable`, `Retry-After`). Metrics: `bff_upstream_request_duration_seconds`,
  `bff_upstream_requests_total{outcome}`, `bff_upstream_breaker_state`, plus the shared HTTP server metrics. OTel
  context propagates to upstreams (otelhttp transport). `/readyz` checks Valkey only — upstreams degrade on their own.
- **Caching** (in process, public data only, never per user): site config, placements per market/ZIP and eligibility
  (content max-age, ≤ 60 s), catalog products/variants (upstream max-age, ≤ 60 s), category tree, ZIP lookups and
  identity providers. Content publishes `content.*.changed/published` on `at.content.events`; the BFF does not consume
  Kafka, so the short TTLs bound staleness.

## Endpoints → upstreams

| BFF operation | Upstream(s) | Status |
|---|---|---|
| `GET /v1/site-config` | content `GET /v1/site-config` + identity `GET /v1/identity-providers` | live |
| `GET /v1/home` | content `GET /v1/placements/resolve` + search `/v1/search` + catalog/inventory (SKU rails) | live (default rails without content) |
| `POST /v1/newsletter/subscriptions` | content `POST /v1/newsletter/subscriptions` | live |
| `GET/PUT /v1/delivery-location` | geo `GET /v1/zip/{zip}` + content `GET /v1/markets/eligibility`; lat/lon → geo `GET /v1/reverse` | ZIP live; **coordinates pending (geo reverse geocoding)** |
| `GET /v1/geo/address-suggestions` | geo autocomplete + resolve | live |
| `GET /v1/search`, `/v1/search/suggestions` | search `/v1/search`, `/v1/suggest` | live |
| `GET /v1/products/{atin}` | catalog public product/variant + categories, inventory availability, promise `POST /v1/promises` | live (promise pending → estimate) |
| `POST /v1/products/{atin}/stock-alerts` | catalog (exists check) + notifications `POST /v1/stock-alerts` | **pending (notifications route)** |
| cart: `GET /v1/cart`, items add/patch/delete, selection, save-for-later, move-to-cart, saved delete | cart service (+ catalog for offer resolution/enrichment, inventory for stock problems, Valkey for selection) | live |
| `GET /v1/me` | identity `GET /v1/me` (fallback: ID-token profile), counts from identity addresses + payments | live |
| `GET /v1/auth/login`, `/v1/auth/oidc/{provider}/authorize`, `/v1/auth/oidc/callback`, `POST /v1/auth/sign-out` | identity `/oauth2/authorize`, `/oauth2/token`, `/oauth2/revoke`, JWKS; cart merge | live |
| `POST /v1/auth/sign-up`, `/email-verification/verify`, `/email-verification/resend` | identity `POST /v1/accounts`, `/v1/accounts/verification`, `/resend` | live (verify → `redirectTo` hosted login) |
| `POST /v1/auth/sign-in`, `/mfa/verify`, `/passkey/options`, `/passkey/verify` | identity has no first-party JSON API (hosted pages only) | **503 after validation; use hosted login** |
| `GET/POST /v1/account/addresses`, `PATCH/DELETE …/{id}`, `POST …/{id}/default` | identity `/v1/me/addresses…` + geo `POST /v1/addresses/validate` | live |
| `GET /v1/account/payment-methods`, `POST …/setup-intents`, `DELETE …/{id}` | payments `/v1/customers/me/payment-methods`, `/setup-intents` | **pending (payments)** |
| `GET /v1/account/orders`, `POST …/shipments/{id}/cancel`, `GET/POST /v1/account/returns` | orders `/v1/orders?customer=me`, `/v1/orders/{n}/shipments/{s}/cancel`, `/v1/returns` | **pending (orders)** |
| `GET /v1/checkout/summary` | cart (selected lines) + identity addresses + payments | **pending (payments)**; tax shown as "calculated when you place your order" |
| `POST /v1/checkout/orders` | orders `POST /v1/orders` (Idempotency-Key relayed) | **pending (orders)** |
| `GET /v1/careers/jobs`, `POST /v1/careers/jobs/{id}/applications` | content careers (open roles only; `resumeAssetId` required, `CAR-######` receipts) | live |

## Seller Center & Provider Hub endpoints → upstreams

Every route relays the member's access token, resolves the active organisation from identity `GET /v1/me` (`orgId`,
`orgs[].role`) and checks the role's module level (identity `GET /v1/admin/module-access?portal=`, cached per portal;
members without `access:read` get the seeded default matrix). Before approval only `/v1/seller/session` and
`/v1/seller/onboarding` answer; everything else is 403 `NOT_APPROVED`. Provider Hub routes need a PROVIDER organisation.

| BFF operation | Upstream(s) | Status |
|---|---|---|
| `GET /v1/seller/session` | identity `GET /v1/me` + module matrix, seller `GET /v1/orgs/{id}`; badges: seller account-health, orders `GET /v1/seller/shipments?status=placed`, bookings requests | live (badges best effort) |
| `PUT /v1/seller/session/active-org` | identity `PUT /v1/me/active-org` | live |
| `GET /v1/seller/onboarding` | seller `GET /v1/applications/me` | live |
| `GET /v1/seller/compliance` | seller `GET /v1/orgs/{orgId}/account-health` | live |
| `GET /v1/seller/dashboard` | seller: orders `GET /v1/seller/analytics` (planned) + inventory `GET /v1/positions` + payments `GET /v1/payouts/balance` + account-health; provider: bookings `GET /v1/providers/me/analytics` (planned) | partial: charts from planned upstreams report `available=false` |
| `GET /v1/seller/orders`, `GET /v1/seller/orders/{orderNumber}` | orders `GET /v1/seller/shipments`, `GET /v1/orders/{number}` | **pending (orders)** → 503 |
| `POST …/shipments/{shipmentId}/status` | orders `POST /v1/shipments/{id}/transitions` (only `confirmed`, `packed`, `ready`) | **pending (orders)** |
| `POST …/shipments/{shipmentId}/rates` | orders (ship-to) + carrier-gateway `POST /v1/rates` | **pending (orders)**; carrier live |
| `POST …/shipments/{shipmentId}/labels` | carrier-gateway `POST /v1/labels` (Idempotency-Key) + orders `POST /v1/shipments/{id}/tracking` | **pending (orders)**; carrier live |
| `GET /v1/seller/listings`, `POST …/listings/{productId}/status` | catalog `GET /v1/products`, `POST /v1/products/{id}/pause` / `/resume` | live |
| `POST /v1/seller/products` | catalog `POST /v1/products` (`categoryId` → IN_REVIEW, `categoryRequestId` → PENDING_CATEGORY_APPROVAL) | live |
| `GET /v1/seller/catalog/categories`, `…/product-types/{code}`, `…/gtin/{code}` | catalog `GET /v1/categories` (nested), `GET /v1/product-types/{code}`, `GET /v1/identifiers/gtin/{code}` | live (tree/types cached) |
| `GET/POST /v1/seller/category-requests` | catalog `GET /v1/category-requests/mine`, `POST /v1/category-requests` | live |
| `POST /v1/seller/uploads`, `POST /v1/seller/uploads/{assetId}/complete` | media `POST /v1/uploads`, `POST /v1/assets/{id}/complete` | live |
| `GET /v1/seller/inventory` | inventory `GET /v1/positions?ownerOrg=` | live |
| `POST /v1/seller/inventory/adjustments` | offers `PUT /v1/offers/stock/{sellerSku}` | **pending (offers)** → 503 |
| `GET/POST /v1/seller/payouts` | payments `GET /v1/payouts/balance`, `GET /v1/payouts/requests?orgId=` (history; 403 → `historyAvailable=false`), `POST /v1/payouts/requests` | live |
| `GET /v1/seller/billing`, `POST …/billing/plan`, `POST …/payment-methods/{id}/default`, `DELETE …/payment-methods/{id}` | seller `GET /v1/orgs/{orgId}/billing`, `/billing/invoices`, `GET /v1/plans?kind=`, `POST /billing/plan`, payment-method routes (owner only) | live |
| `GET /v1/seller/team`, `PUT …/team/members/{userId}` | seller `GET /v1/orgs/{id}` (members) + module matrix (capability cards); identity `PUT /v1/orgs/{orgId}/members/{userId}` | live (member names: see gaps) |
| `POST /v1/seller/team/invitations` | identity `POST /v1/orgs/{orgId}/invitations` (planned) | **pending (identity)** → 503 |
| `GET /v1/seller/bookings/calendar`, `…/requests`, `POST …/bookings/{id}/actions` | bookings `GET /v1/providers/me/calendar`, `GET /v1/providers/me/bookings?status=`, `POST /v1/bookings/{id}/{confirm\|decline\|propose\|complete\|no-show\|cancel}` | **pending (bookings)** → 503 |
| `GET/PUT /v1/seller/provider/hours`, `GET/POST/PATCH …/services`, `GET/PUT …/cancellation-policy`, `GET …/earnings` | bookings `/v1/providers/me/availability`, `/services`, `/cancellation-policy`, `/earnings` | **pending (bookings)** → 503 |
| `GET/PUT /v1/seller/storefront`, `POST /v1/seller/storefront/publish` | storefront `GET/PUT /v1/storefronts/me/draft`, `POST /v1/storefronts/me/publish` | live |

Upstream gaps found while building the seller routes: identity has no member directory for organisation admins
(the team list shows names only for the signed-in member), no invitation endpoint, and the module matrix needs
`access:read`; the seller service stores no ship-from address (the label form asks for it); payments lists payout
history only for `payouts:approve`; orders has no seller analytics endpoint; offers (seller stock) and bookings
provider endpoints are not served yet.

## Configuration

`config/<APP_ENV>.yaml` (local, dev, uat, prod) with env overrides: `PORT`, `REDIS_URL`, `SESSION_SIGNING_KEY` (≥ 32
chars), `OIDC_CLIENT_SECRET`, `OIDC_ISSUER`, `OIDC_PUBLIC_URL`, `OIDC_CLIENT_ID`, `OIDC_REDIRECT_URI`, `OIDC_AUDIENCE`,
`WEB_ORIGINS`, `WEB_PUBLIC_ORIGIN`, `WEB_BFF_PATH`, `SESSION_COOKIE_SECURE`, `SESSION_COOKIE_DOMAIN`,
`<UPSTREAM>_URL` (`SEARCH_URL`, `CATALOG_URL`, `INVENTORY_URL`, `CART_URL`, `GEO_URL`, `IDENTITY_URL`, `CONTENT_URL`,
`PAYMENTS_URL`, `PROMISE_URL`, `ORDERS_URL`, `NOTIFICATIONS_URL`, `SELLER_URL`, `OFFERS_URL`, `BOOKINGS_URL`,
`STOREFRONT_URL`, `MEDIA_URL`, `CARRIER_URL`), `RATE_LIMIT_AUTH_PER_MINUTE`,
`RATE_LIMIT_LOOKUP_PER_MINUTE`, `TRUST_FORWARDED_FOR`, `RETAIL_ORG_ID`, `OTEL_EXPORTER_OTLP_ENDPOINT_HOST`,
`TRACE_SAMPLING`, `DOCS_ENABLED`. Secrets (`REDIS_URL`, `OIDC_CLIENT_SECRET`, `SESSION_SIGNING_KEY`) come from the
cloud secret store via External Secrets (Helm chart in `deploy/helm`).

Local defaults: upstreams on their `infra/local/PORTS.md` ports, storefront origin `http://localhost:3000` (Seller Center
`http://localhost:3001` is also allowed for CORS/CSRF), identity
client secret `local-bff-secret`, cookie not `Secure` (plain-http localhost). In dev/uat/prod the OIDC redirect URI is
`https://<storefront>/api/bff/v1/auth/oidc/callback` so the cookie stays first-party — identity must register it for
`gateway-bff`.

## Run and test

```bash
cd go/services/gateway-bff
export GOTOOLCHAIN=go1.27.1 GOWORK=off
go test ./...      # unit, API flows, session/OIDC with an httptest identity, contract conformance (kin-openapi), Valkey Testcontainers
~/go/bin/golangci-lint run --config ../../.golangci.yml ./...
APP_ENV=local go run ./cmd/gateway-bff                      # http://localhost:8200/docs
BFF_ORIGIN=http://localhost:8200 pnpm --filter storefront dev   # storefront against the real BFF
docker build -f services/gateway-bff/Dockerfile -t gateway-bff ..   # from go/
```

Fake upstreams for manual runs (catalog, search, inventory, cart, geo, identity with JWKS/PKCE, payments):
`BFF_SERVE_FAKES=/tmp/fakes.url go test -run TestServeFakes -timeout 0 ./internal/testkit/` writes their base URL to
the file and stops when the file is deleted.
