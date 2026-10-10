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
| `GET/PUT /v1/delivery-location` | geo `GET /v1/zip/{zip}` + content `GET /v1/markets/eligibility`; lat/lng → geo `GET /v1/reverse?lat=&lng=` (Google → Nominatim) | live |
| `GET /v1/geo/address-suggestions` | geo autocomplete + resolve | live |
| `GET /v1/search`, `/v1/search/suggestions` | search `/v1/search` (`ads=true` unless the client sends `ads=false`; sponsored hits keep their slot and map to `sponsor` with advertiser, reason and click token; pages with ads are `private, no-store`), `/v1/suggest`; home rails send `track=false`; card offer count and min price from one offers `GET /v1/public/buy-boxes?by=product` batch (≤ 50 ATINs) | live (offers down → cards without offer facts) |
| `POST /v1/search/clicks` | search `POST /v1/search/clicks` (`searchId`, `q`, `atin`, `position`, `adToken`, market) → 202 | live |
| `GET /v1/products/{atin}` | catalog public product/variant + categories; buy box and "Other sellers" from offers `GET /v1/public/products/{atin}/buy-boxes` + `GET /v1/public/offers?productAtin=`; inventory availability, promise `POST /v1/promises` | live (promise pending → estimate; no offers → catalog price fallback) |
| `POST /v1/products/{atin}/stock-alerts`, `GET /v1/account/stock-alerts`, `DELETE /v1/account/stock-alerts/{atin}` | catalog (exists check, `productTitle`) + notifications `POST/GET /v1/stock-alerts`, `DELETE /v1/stock-alerts/{atin}` (shopper token; guests get 401) | live |
| cart: `GET /v1/cart`, items add/patch/delete, selection, save-for-later, move-to-cart, saved delete | cart service (lines carry `offerId`; cart validates buyable/price via offers) + offers buy box for the default offer, catalog for enrichment, inventory for stock problems, Valkey for selection | live (`409 cart.offer-not-buyable`) |
| `GET /v1/me` | identity `GET /v1/me` (fallback: ID-token profile), counts from identity addresses + payments | live |
| `GET /v1/auth/login`, `/v1/auth/oidc/{provider}/authorize`, `/v1/auth/oidc/callback`, `POST /v1/auth/sign-out` | identity `/oauth2/authorize`, `/oauth2/token`, `/oauth2/revoke`, JWKS; cart merge | live |
| `POST /v1/auth/sign-up`, `/email-verification/verify`, `/email-verification/resend` | identity `POST /v1/accounts`, `/v1/accounts/verification`, `/resend` | live (verify → `redirectTo` hosted login; with `invitationToken` no code: straight to sign-in) |
| `POST /v1/auth/sign-in`, `/mfa/sms`, `/mfa/verify`, `/passkey/options`, `/passkey/verify` | identity `/v1/auth/password`, `/v1/auth/mfa/sms`, `/mfa/verify`, `/v1/auth/passkey/options`, `/verify` with a client-credentials token (`auth:first-party`); the `login_ticket` is redeemed at `/oauth2/authorize` (PKCE S256, redirect not followed) then `/oauth2/token`. Trusted device: HttpOnly `at_trusted_device` cookie, 30 days. Lockout 429 relays `Retry-After` | live |
| `GET/POST /v1/account/addresses`, `PATCH/DELETE …/{id}`, `POST …/{id}/default` | identity `/v1/me/addresses…` + geo `POST /v1/addresses/validate` | live |
| `POST /v1/account/phone/verification`, `…/confirm` | identity `POST /v1/me/phone/verification`, `…/confirm` (`GET /v1/me` carries the masked phone and `phoneVerified`) | live |
| `GET /v1/account/payment-methods`, `POST …/setup-intents`, `POST …/{id}/default`, `DELETE …/{id}` | payments `GET /v1/customers/me/payment-methods` (array of display data → label + MM/YY), `POST /v1/customers/me/setup-intents {types}` (+ `STRIPE_PUBLISHABLE_KEY`), `POST …/{id}/default`, `DELETE …/{id}` (`payments.default-method-required` → 409) | live |
| `POST /v1/account/payment-sheet` (customer app PaymentSheet) | payments `POST /v1/customers/me/ephemeral-keys` (planned) | **pending (payments: no ephemeral keys)** → 503 |
| `GET /v1/account/orders`, `GET …/{orderNumber}`, `GET …/{orderNumber}/tracking`, `POST …/shipments/{id}/cancel` | orders `GET /v1/orders?customer=me`, `GET /v1/orders/{number}` (shipments, lines, timeline, handover codes), `POST /v1/orders/{n}/shipments/{s}/cancel` (`orders.cancel-window-closed` → 409); tracking adds carrier-gateway `GET /v1/tracking/{carrier}/{number}` scans with a `shipping:rate` service token (best effort); order detail adds `shipments[].returnable/returnWindowEndsAt/problemReportOnly` (orders) and charged/refunded/refundable + shipping tax per line from payments `GET /v1/payments?orderId=` (best effort, read only after the order read proves ownership) | live |
| `GET /v1/account/orders/{orderNumber}/returnable`, `GET/POST /v1/account/returns` (`?orderNumber=`), `GET …/{rmaId}` (id or RMA number), `POST …/{rmaId}/cancel` | orders `GET /v1/orders/{number}` (order id) + tax-returns `GET /v1/returns/eligibility?orderId=`, `POST /v1/returns` (Idempotency-Key, client's or derived; no `returnFrom` — tax-returns defaults it to the order address), `GET /v1/returns[?orderId=]`, `GET /v1/returns/{id}` or `/number/{number}` + `/refund-quote`, `POST …/cancel`; the app's `{orderNumber, atin, reason}` maps to one unit with the first allowed method | live |
| `GET /v1/checkout/summary` | cart (selected lines) + identity addresses + payments; per package: carrier-gateway `GET /v1/pickup-locations?postalPrefix=` (platform points for AfroTransact packages, `ownerOrg=` for sellers), geo `GET /v1/zones/covering?type=DELIVERY` (AfroTransact Delivery only where the zone of the package's FC covers the address), promise `POST /v1/promises` (dates per option, FC origins) | live (each delivery fact best effort; seller packages keep the estimate — no ship-from ZIP); tax shown as "calculated when you place your order" |
| `POST /v1/checkout/orders`, `GET /v1/checkout/orders/{n}`, `POST …/{n}/confirm` | orders `POST /v1/checkout` (Idempotency-Key relayed, selections mapped to upper-case preferences, lines from the selected cart, optional `couponCode`), `GET /v1/checkout/{n}`, `POST /v1/checkout/{n}/confirm`; the PaymentIntent client secret is always returned (`awaiting_payment`) | live (`orders.coupon-not-applicable` → 422 field error, 402 → `PAYMENT_DECLINED`) |
| `GET/PUT /v1/account/notification-preferences`, `GET /v1/account/security`, `PUT /v1/account/password`, `POST/DELETE /v1/account/mfa/{totp,sms}`, `…/confirm`, `POST /v1/account/mfa/recovery-codes`, `DELETE /v1/account/{passkeys,sessions,devices,linked-accounts}/{id}`, `POST /v1/account/sessions/revoke-others` | identity `/v1/me/mfa…`, `/v1/me/passkeys`, `/v1/me/sessions`, `/v1/me/devices`, `/v1/me/linked-accounts`, `/v1/me/password`, `/v1/me/notification-preferences` (customer token); passkeys are registered on identity's hosted `/webauthn/register` (`passkeyRegistrationUrl`) | live |
| `GET /v1/services/search`, `/v1/services/{id}`, `…/days`, `…/slots`, `…/summary`, `GET /v1/service-providers/{orgId}` | bookings `GET /v1/public/services/search?zip=` (deliver-to ZIP by default), `/v1/public/services/{id}[/days,/slots,/summary]`, `/v1/public/providers/{orgId}` + storefront `GET /v1/storefronts/{slug}` (layout, theme tokens, approved links only, published posts) | live (storefront pages are keyed by slug only: without `?slug=` the org id is tried) |
| `POST /v1/services/bookings`, `GET /v1/account/bookings[/{id}]`, `…/cancellation-quote`, `POST …/{cancel,reschedule,review,payment/sync,new-time/{accept,decline}}` | bookings `POST /v1/bookings` (customer contact from identity `GET /v1/me`; deposit client secret returned), `GET /v1/bookings/mine`, `/v1/bookings/{id}[…]` (Idempotency-Key, client's or derived) | live |
| `POST /v1/lola/chat` (SSE), `GET /v1/lola/sessions/{id}/messages` | ai-gateway `POST /v1/lola/chat` streamed byte for byte (token relayed, deliver-to ZIP and language added, flushed per chunk; the route is a `httpserver.Stream` route, exempt from the shared server's 30 s write timeout), `GET /v1/lola/sessions/{id}/messages` | live (429 `ai.budget-exceeded` → `RATE_LIMITED` + Retry-After) |
| `GET /v1/careers/jobs`, `POST /v1/careers/jobs/{id}/applications` | content careers (open roles only; `resumeAssetId` required, `CAR-######` receipts) | live |
| `POST /v1/careers/uploads`, `POST /v1/careers/uploads/{assetId}/complete` | media `POST /v1/uploads`, `POST /v1/assets/{id}/complete` (PDF ≤ 5 MB; guests use the BFF's client-credentials token; purpose `kyc-document` until media adds a résumé purpose) | live |

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
| `GET /v1/seller/dashboard` | seller: orders `GET /v1/seller/analytics` (sales, orders, checkouts and conversion by day) + inventory `GET /v1/positions` + payments `GET /v1/payouts/balance` + account-health; provider: derived from bookings `GET /v1/providers/{orgId}/bookings` + `/earnings` | live (a failing upstream reports `available=false` for its chart) |
| `GET /v1/seller/orders`, `GET /v1/seller/orders/{orderNumber}` | orders `GET /v1/seller/shipments`, `GET /v1/orders/{number}` | live (orders serves the routes) |
| `POST …/shipments/{shipmentId}/status` | orders `POST /v1/shipments/{id}/transitions` (only `confirmed`, `packed`, `ready`) | live (orders serves the route) |
| `POST …/shipments/{shipmentId}/rates` | orders (ship-to) + carrier-gateway `POST /v1/rates` | live |
| `POST …/shipments/{shipmentId}/labels` | carrier-gateway `POST /v1/labels` (Idempotency-Key) + orders `POST /v1/shipments/{id}/tracking` | live |
| `GET /v1/seller/listings`, `POST …/listings/{productId}/status` | catalog `GET /v1/products`, `POST /v1/products/{id}/pause` / `/resume` | live |
| `POST /v1/seller/products` | catalog `POST /v1/products` (`categoryId` → IN_REVIEW, `categoryRequestId` → PENDING_CATEGORY_APPROVAL) | live |
| `GET /v1/seller/catalog/categories`, `…/product-types/{code}`, `…/gtin/{code}` | catalog `GET /v1/categories` (nested), `GET /v1/product-types/{code}`, `GET /v1/identifiers/gtin/{code}` | live (tree/types cached) |
| `GET/POST /v1/seller/category-requests` | catalog `GET /v1/category-requests/mine`, `POST /v1/category-requests` | live |
| `POST /v1/seller/uploads`, `POST /v1/seller/uploads/{assetId}/complete` | media `POST /v1/uploads`, `POST /v1/assets/{id}/complete` | live |
| `GET /v1/seller/inventory` | inventory `GET /v1/positions?ownerOrg=` | live |
| `POST /v1/seller/inventory/adjustments` | offers `PUT /v1/offers/stock/{sellerSku}` (ownership check; AfroTransact-fulfilled SKUs → 409) | live |
| `GET/POST /v1/seller/ship-from-addresses`, `PUT/DELETE …/{id}`, `POST …/{id}/default` | seller `/v1/orgs/{orgId}/ship-from-addresses` | live |
| `GET/POST /v1/seller/payouts` | payments `GET /v1/payouts/balance`, `GET /v1/payouts/requests?orgId=` (history; 403 → `historyAvailable=false`), `POST /v1/payouts/requests` | live |
| `GET /v1/seller/fees` | payments `GET /v1/fee-schedules` + `GET /v1/fee-schedules/effective?category=&orgId=&at=` per category, now and at the next scheduled version — read with the BFF's client-credentials token (scope `payments:service`; identity's `gateway-bff` client must be granted it), always for the active organisation | live |
| `GET /v1/seller/billing`, `POST …/billing/plan`, `POST …/payment-methods/{id}/default`, `DELETE …/payment-methods/{id}` | seller `GET /v1/orgs/{orgId}/billing`, `/billing/invoices`, `GET /v1/plans?kind=`, `POST /billing/plan`, payment-method routes (owner only) | live |
| `GET /v1/seller/team`, `PUT/DELETE …/team/members/{userId}`, `POST …/team/invitations`, `DELETE …/team/invitations/{id}` | identity `GET /v1/orgs/{orgId}/members` + `GET …/invitations` (INVITED rows), `POST …/invitations`, `DELETE …/invitations/{id}`, `PUT/DELETE …/members/{userId}`; module matrix (capability cards). Identity codes map to `LAST_OWNER`, `INVALID_ROLE` (422), `OWNER_ONLY`, `OWN_ROLE` (403), `ALREADY_MEMBER` (409) | live |
| `POST /v1/seller/invitations/lookup` (public), `POST /v1/seller/invitations/accept` | identity `POST /v1/invitations/lookup`, `POST /v1/invitations/accept` (then `PUT /v1/me/active-org`); `INVITATION_EXPIRED/USED/REVOKED/EMAIL_MISMATCH` (422) | live |
| `GET /v1/seller/bookings/calendar`, `…/requests`, `POST …/bookings/{id}/actions` | bookings `GET /v1/providers/{orgId}/calendar`, `GET /v1/providers/{orgId}/bookings?status=`, `POST /v1/providers/{orgId}/bookings/{id}/{action}` (active org from the session; default Idempotency-Key) | live |
| `GET/PUT /v1/seller/provider/hours`, `GET/POST/PATCH …/services`, `GET/PUT …/cancellation-policy`, `GET …/earnings` | bookings `/v1/providers/{orgId}/hours`, `/overrides`, `/services` (+ activate/deactivate), `/policy`, `/earnings`, `/settings`, `/team` | live |
| `GET/PUT /v1/seller/storefront`, `POST /v1/seller/storefront/publish` | storefront `GET/PUT /v1/storefronts/me/draft`, `POST /v1/storefronts/me/publish` | live |

Upstream gaps found while building the seller routes: the module matrix needs `access:read`; payments lists payout history only for `payouts:approve`. Ship-from addresses, seller analytics, seller stock and
the bookings provider routes are now served upstream.

## Configuration

`config/<APP_ENV>.yaml` (local, dev, uat, prod) with env overrides: `PORT`, `REDIS_URL`, `SESSION_SIGNING_KEY` (≥ 32
chars), `OIDC_CLIENT_SECRET`, `OIDC_ISSUER`, `OIDC_PUBLIC_URL`, `OIDC_CLIENT_ID`, `OIDC_REDIRECT_URI`, `OIDC_AUDIENCE`,
`WEB_ORIGINS`, `WEB_PUBLIC_ORIGIN`, `WEB_BFF_PATH`, `SESSION_COOKIE_SECURE`, `SESSION_COOKIE_DOMAIN`,
`<UPSTREAM>_URL` (`SEARCH_URL`, `CATALOG_URL`, `INVENTORY_URL`, `CART_URL`, `GEO_URL`, `IDENTITY_URL`, `CONTENT_URL`,
`PAYMENTS_URL`, `PROMISE_URL`, `ORDERS_URL`, `NOTIFICATIONS_URL`, `SELLER_URL`, `OFFERS_URL`, `BOOKINGS_URL`,
`STOREFRONT_URL`, `MEDIA_URL`, `CARRIER_URL`, `TAX_RETURNS_URL`, `AI_GATEWAY_URL`), `STRIPE_PUBLISHABLE_KEY`, `RATE_LIMIT_AUTH_PER_MINUTE`,
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
