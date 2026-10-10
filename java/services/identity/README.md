# identity-service

Spring Authorization Server (OAuth 2.1 / OpenID Connect) for the whole platform, plus the account, access-control,
MFA, passkey and social sign-in APIs. Port **8100**, PostgreSQL database **`identity`**.

Every other service is a resource server: it validates JWTs against `<issuer>/oauth2/jwks` and reads the platform
claims with `JwtActorResolver` (java/libs/platform-security) or `auth.Require` (Go).

## What it does

| Area | Summary |
|---|---|
| Authorization server | Discovery (`/.well-known/openid-configuration`), JWKS (`/oauth2/jwks`), Authorization Code + PKCE (public clients), Client Credentials (services), refresh-token rotation (refresh tokens are single-use; reuse → `invalid_grant`), OIDC userinfo / logout, revocation, introspection. JDBC-backed clients, authorizations and consent (official schemas). |
| Tokens | Access tokens **5 minutes**, RS256, key from `SIGNING_KEY_PEM` (PKCS#8; an ephemeral key is generated in `local`). Previous public keys can stay in the JWKS during rotation (`at.identity.signing.previous-public-keys-pem`). |
| Claims | `sub` = account id for every sign-in method; `pt` principal type; `org` active seller/provider org; `roles`; `perms` = role permissions ∪ direct grants. Recomputed on every issuance and refresh, so a removed permission is gone from the next token (≤ 5 min). Disabled accounts cannot obtain or refresh tokens. |
| Sign-up | One form for customers, sellers and providers (first/last name, email, E.164 mobile via libphonenumber, password + confirm, terms, optional SMS consent). `intent` (`shop`/`sell`/`provide`) adds `account.customer` and the matching applicant role server-side. Staff email domains cannot self-register. |
| Email verification | 6-digit code (hashed), 10-minute expiry, 5 wrong attempts, resend ≤ 1 per 30 s and ≤ 5 per hour (HTTP 429 + `Retry-After`). Signing up again with an unconfirmed email restarts the registration. |
| Landing | `GET /v1/me` → `landing` = `shop` / `seller-onboarding` / `seller-center` / `provider-onboarding` / `provider-hub` / `staff-console` / `driver-app`, from roles and org approval status. |
| RBAC | Permissions, roles per portal (account, platform, seller, provider), multiple roles + direct grants per user, enable/disable, module access matrix (none/view/edit/approve/full), append-only change log (DB trigger rejects UPDATE/DELETE). |
| MFA | TOTP (RFC 6238, otpauth URI for the QR code, replay-protected), SMS codes (outbox event), 10 hashed recovery codes, "trust this browser" for 30 days. Staff always need a second factor (SMS to the phone on file if nothing is enrolled) and cannot turn MFA off. Passkey sign-ins skip the prompt (phishing-resistant). |
| Passkeys | Spring Security 7 `webAuthn()` with JDBC repositories (`user_entities`, `user_credentials`): `/webauthn/register/options`, `/webauthn/register`, `/webauthn/authenticate/options`, `/login/webauthn`, management page `/webauthn/register`. `PasskeyService` lists/removes them over the API and refuses to remove the last sign-in method. |
| Social login | Google (OIDC) via its own consent page; Apple (OIDC, form_post) and Facebook registrations exist but are disabled by default. A provider is offered only when it has client credentials **and** is enabled in `identity_provider` (`PUT /v1/admin/identity-providers/{id}`). Accounts created from provider-verified emails skip email verification; a verified email matching an existing account links to it; signing in with a provider while already signed in links it. |
| Sessions | Login sessions live in Spring Session JDBC (works with several replicas). "Sessions" in Login & security are OAuth authorizations with a live refresh/access token; revoking deletes them. |

## Sign-in flow (browser)

```
app ──/oauth2/authorize (PKCE)──▶ /login ──password──▶ [/login/mfa: TOTP | SMS | recovery code, trust browser] ──▶ code ──▶ /oauth2/token
                                     ├─ Sign in with a passkey (WebAuthn)        (no second factor)
                                     └─ /oauth2/authorization/google ─▶ Google consent ─▶ /login/oauth2/code/google ─▶ [MFA if enrolled]
```

Pages are served by the authorization server itself (`/login`, `/login/mfa`, `/webauthn/register`); apps never see
passwords. Public clients (web apps, mobile) use PKCE; the client setting
`settings.client.afrotransact.public-refresh-tokens` lets a public client receive rotating refresh tokens
(Spring Authorization Server does not issue them to public clients by default).

## REST API (`/v1`, JSON, RFC 9457 problem details)

Full contract: [`contracts/openapi/identity.yaml`](../../../contracts/openapi/identity.yaml) (exported with
`tools/contracts/export_java_openapi.sh identity identity 8100`). Reference pages: `/swagger-ui.html`, `/docs`, `/redoc`
(off in prod).

| Method & path | Auth | Purpose |
|---|---|---|
| `POST /v1/accounts` | public | Unified sign-up (`intent` = shop/sell/provide) |
| `POST /v1/accounts/verification` | public | Confirm email with the 6-digit code |
| `POST /v1/accounts/verification/resend` | public | New code (rate limited) |
| `GET /v1/identity-providers` | public | Enabled social providers for sign-in screens |
| `GET /v1/me`, `PATCH /v1/me` | user | Profile, roles, permissions, orgs, `landing`; complete profile (phone, terms) |
| `POST /v1/me/roles/seller-applicant`, `…/provider-applicant` | user | Existing customer starts selling / offering services (same account, audited) |
| `PUT /v1/me/active-org` | user | Choose the organisation to act for (`org` claim) |
| `PUT /v1/me/password` | user | Change password (current password required) |
| `GET /v1/me/sessions`, `DELETE /v1/me/sessions/{id}`, `POST /v1/me/sessions/revoke-others` | user | Active sessions / sign out other devices |
| `GET /v1/me/linked-accounts`, `DELETE /v1/me/linked-accounts/{provider}` | user | Linked social accounts (cannot unlink the last sign-in method) |
| `GET /v1/me/passkeys`, `DELETE /v1/me/passkeys/{credentialId}` | user | Passkeys |
| `GET /v1/me/mfa`; `POST /v1/me/mfa/totp`, `…/totp/confirm`, `DELETE …/totp`; `POST /v1/me/mfa/sms`, `…/sms/confirm`, `DELETE …/sms`; `POST /v1/me/mfa/recovery-codes` | user | Two-step verification |
| `GET /v1/admin/permissions`, `GET /v1/admin/roles?portal=` | `access:read` | Permission catalogue, roles with permissions |
| `PUT/DELETE /v1/admin/roles/{role}/permissions/{perm}` | `access:admin` | Capabilities tab |
| `GET /v1/admin/users?q=`, `GET /v1/admin/users/{id}`, `POST /v1/admin/users` | read / admin | People tab, provision staff/driver accounts |
| `PUT/DELETE /v1/admin/users/{id}/roles/{role}` | `access:admin` | Assign/remove account and platform roles |
| `PUT/DELETE /v1/admin/users/{id}/permissions/{perm}` | `access:admin` | Direct grants (e.g. `delivery:operate`) |
| `POST /v1/admin/users/{id}/disable`, `…/enable` | `access:admin` | Disable ends every session immediately |
| `GET /v1/admin/module-access?portal=`, `PUT /v1/admin/module-access/{portal}/{module}/{role}` | read / admin | Module access matrix |
| `GET /v1/admin/access-log?userId=` | `access:read` | Append-only change log |
| `GET /v1/admin/identity-providers`, `PUT /v1/admin/identity-providers/{id}` | read / admin | Identity provider switches |
| `PUT /v1/orgs/{orgId}`, `PUT/DELETE /v1/orgs/{orgId}/members/{userId}` | `orgs:manage` (seller service) | Mirror seller/provider orgs, approval status and members |

### Token claims

| Claim | Example | Notes |
|---|---|---|
| `sub` | `5b0f…` (account UUID) | client id for client-credentials tokens |
| `pt` | `CUSTOMER`, `SELLER_MEMBER`, `PROVIDER_MEMBER`, `STAFF`, `DRIVER`, `SERVICE` | STAFF = any platform role other than driver; DRIVER = driver only; *_MEMBER while acting for an org |
| `org` | `0e2d…` | active organisation (selected, else the first approved) |
| `roles` | `["account.customer","seller.owner"]` | account/platform roles + the active org role |
| `perms` | `["catalog:write","orders:read"]` | org-role permissions count only while the org is APPROVED; client tokens: granted scopes |

## Events (transactional outbox → `at.identity.events`, key = user id)

`identity.user.registered`, `identity.user.email-verified`, `identity.verification.requested` (one-time code for the
notifications service: `recipient`, `channel` email/sms, `code`, `expiresInMinutes`, `purpose`),
`identity.role.granted` / `identity.role.revoked`, `identity.permission.granted` / `identity.permission.revoked`
(direct grant or role capability change, key = role key for the latter), `identity.user.disabled` /
`identity.user.enabled`. Schemas: [`contracts/asyncapi/fragments/identity.yaml`](../../../contracts/asyncapi/fragments/identity.yaml).

## Data (Flyway, `db/migration`)

- `V1__identity_schema.sql`: `user_account`, `permission`, `role`, `role_permission`, `user_role`, `user_permission`,
  `organization`, `org_membership`, `access_module`, `module_access`, `access_change_log` (append-only),
  `verification_code`, `mfa_totp`, `mfa_sms`, `mfa_recovery_code`, `trusted_browser`, `federated_identity`,
  `identity_provider`; official Spring Authorization Server (`oauth2_*`), WebAuthn (`user_entities`,
  `user_credentials`) and Spring Session (`spring_session*`) tables. Platform outbox/inbox from `db/platform`.
- `V2__seed_rbac.sql`: permission catalogue, roles, role permissions, back-office modules and the module access matrix
  (seeded from the design prototype), identity providers (Google on, Apple/Facebook off).

## Permission catalogue

| Permission | Module | Description |
|---|---|---|
| `applications:submit` | onboarding | Submit and track a seller or provider application |
| `applications:review` | onboarding | First review of seller and provider applications |
| `applications:approve` | onboarding | Final approval of seller and provider applications (second reviewer) |
| `orgs:read` | orgs | View sellers, providers and their teams |
| `orgs:manage` | orgs | Create and update organisations and memberships (seller service) |
| `catalog:write` | catalog | Create and edit products, variants and listings |
| `catalog:moderate` | catalog | Approve or reject listings |
| `catalog:taxonomy` | catalog | Manage categories, product types and category requests |
| `catalog:retail` | catalog | List products as AfroTransact Retail (1P) |
| `labels:print` | catalog | Print barcode labels |
| `orders:read` | orders | View orders and shipments |
| `orders:update-status` | orders | Update status of seller-fulfilled orders and shipments |
| `orders:manage` | orders | Update AfroTransact-fulfilled shipments, cancel and reroute orders |
| `refunds:issue` | orders | Issue refunds |
| `returns:manage` | orders | Approve and process returns (RMAs) |
| `disputes:manage` | trust | Investigate and decide disputes |
| `inventory:read` | inventory | View stock positions, lots, reservations and the ledger |
| `inventory:adjust` | inventory | Adjust stock and cycle count |
| `inventory:scan` | inventory | Warehouse scanner: receive, pick and pack |
| `inventory:receive` | inventory | Receive inbound shipments and post goods receipts |
| `fulfilment:operate` | fulfilment | Run fulfilment-centre pick, pack and dispatch |
| `delivery:operate` | delivery | Use the driver app |
| `delivery:dispatch` | delivery | Plan routes and assign drivers |
| `shipping:manage` | delivery | Carrier integrations, webhooks and status mapping |
| `pickup:manage` | delivery | Pickup points, delivery zones and cut-offs |
| `payouts:read` | payments | View payouts and statements |
| `payouts:approve` | payments | Approve and release payouts |
| `payouts:hold` | payments | Pause or release seller payouts |
| `ledger:read` | payments | View the double-entry ledger |
| `fees:manage` | payments | Fees, commissions and plan pricing |
| `compliance:manage` | payments | Stripe Connect requirements, reminders and payout holds |
| `retail:purchase` | retail | Suppliers, purchase orders and inbound shipments (1P) |
| `marketing:manage` | marketing | Announcements, placements and campaigns |
| `ads:manage` | marketing | Run sponsored product ads |
| `coupons:manage` | marketing | Create, pause and expire coupons |
| `notifications:manage` | marketing | Notification templates and providers |
| `content:manage` | platform | Site content, legal pages and social links |
| `bookings:read` | bookings | View bookings and calendars |
| `bookings:manage` | bookings | Create, reschedule and cancel bookings |
| `services:write` | bookings | Services, prices, deposits and cancellation policies |
| `availability:manage` | bookings | Working hours, breaks and time off |
| `store:design` | storefront | Design the storefront or public page |
| `reviews:respond` | storefront | Reply to reviews |
| `team:manage` | team | Invite members and assign organisation roles |
| `billing:manage` | team | Plan, subscription and billing |
| `support:respond` | support | Answer customer messages and tickets |
| `users:read` | support | Look up customer accounts |
| `config:edit` | platform | Platform configuration: plans, markets, storefront settings |
| `flags:manage` | platform | Feature flags |
| `markets:manage` | platform | Activate countries and states |
| `developer:manage` | platform | API keys, webhooks and OAuth clients |
| `ai:manage` | platform | AI routes, budgets and Lola settings |
| `audit:read` | platform | Read the audit log |
| `reports:read` | platform | Dashboards and reports |
| `access:read` | access | View access control: roles, capabilities, people and change log |
| `access:admin` | access | Grant and revoke roles, permissions, module access and identity providers |

| Role | Portal | Permissions |
|---|---|---|
| `account.customer` (Customer) | account | — |
| `account.seller_applicant` (Seller applicant) | account | `applications:submit` |
| `account.provider_applicant` (Provider applicant) | account | `applications:submit` |
| `platform.super_admin` (Super Admin) | platform | all |
| `platform.operations_manager` (Operations Manager) | platform | `reports:read`, `orders:read`, `orders:manage`, `applications:review`, `applications:approve`, `orgs:read`, `disputes:manage`, `returns:manage`, `catalog:moderate`, `inventory:read`, `fulfilment:operate`, `delivery:dispatch`, `pickup:manage`, `shipping:manage`, `payouts:read`, `users:read`, `audit:read`, `notifications:manage`, `markets:manage` |
| `platform.trust_safety` (Trust & Safety) | platform | `reports:read`, `applications:review`, `applications:approve`, `orgs:read`, `catalog:moderate`, `disputes:manage`, `payouts:hold`, `compliance:manage`, `users:read`, `audit:read`, `orders:read` |
| `platform.finance` (Finance) | platform | `reports:read`, `orders:read`, `orgs:read`, `payouts:read`, `payouts:approve`, `payouts:hold`, `ledger:read`, `fees:manage`, `refunds:issue`, `compliance:manage`, `audit:read`, `inventory:read`, `coupons:manage` |
| `platform.customer_support` (Customer Support) | platform | `reports:read`, `users:read`, `orders:read`, `orders:update-status`, `refunds:issue`, `returns:manage`, `disputes:manage`, `support:respond`, `orgs:read`, `catalog:moderate`, `inventory:read`, `delivery:dispatch`, `pickup:manage` |
| `platform.marketing` (Marketing) | platform | `reports:read`, `orgs:read`, `marketing:manage`, `ads:manage`, `coupons:manage`, `notifications:manage`, `content:manage`, `config:edit` |
| `platform.catalog_manager` (Catalog Manager) | platform | `reports:read`, `orgs:read`, `orders:read`, `catalog:write`, `catalog:moderate`, `catalog:taxonomy`, `labels:print`, `retail:purchase`, `config:edit` |
| `platform.retail_buyer` (Retail Buyer (1P)) | platform | `reports:read`, `orders:read`, `catalog:write`, `catalog:retail`, `retail:purchase`, `inventory:read`, `inventory:receive`, `inventory:scan`, `labels:print`, `pickup:manage` |
| `platform.fulfilment_ops` (Fulfilment Ops) | platform | `reports:read`, `orders:read`, `orders:manage`, `fulfilment:operate`, `inventory:read`, `inventory:adjust`, `inventory:scan`, `inventory:receive`, `delivery:dispatch`, `pickup:manage`, `labels:print`, `retail:purchase` |
| `platform.inventory_manager` (Inventory Manager) | platform | `reports:read`, `orders:read`, `inventory:read`, `inventory:adjust`, `inventory:scan`, `inventory:receive`, `retail:purchase`, `labels:print` |
| `platform.dispatcher` (Dispatcher) | platform | `reports:read`, `orders:read`, `orders:manage`, `delivery:dispatch`, `pickup:manage`, `inventory:read` |
| `platform.product_owner` (Product Owner) | platform | `reports:read`, `config:edit`, `flags:manage`, `markets:manage`, `fees:manage`, `coupons:manage`, `content:manage`, `catalog:moderate`, `orders:read`, `orgs:read`, `payouts:read`, `inventory:read`, `access:read`, `audit:read`, `ai:manage` |
| `platform.developer` (Developer) | platform | `reports:read`, `orders:read`, `config:edit`, `flags:manage`, `developer:manage`, `shipping:manage`, `labels:print`, `access:read`, `access:admin`, `audit:read`, `ai:manage`, `markets:manage`, `fees:manage`, `coupons:manage`, `pickup:manage` |
| `platform.auditor` (Auditor (read-only)) | platform | `reports:read`, `orders:read`, `orgs:read`, `payouts:read`, `ledger:read`, `inventory:read`, `audit:read`, `access:read`, `users:read` |
| `platform.driver` (Driver) | platform | `delivery:operate` |
| `seller.owner` (Owner) | seller | `catalog:write`, `labels:print`, `orders:read`, `orders:update-status`, `returns:manage`, `refunds:issue`, `inventory:read`, `inventory:adjust`, `ads:manage`, `store:design`, `reviews:respond`, `payouts:read`, `billing:manage`, `team:manage`, `compliance:manage`, `support:respond`, `reports:read` |
| `seller.admin` (Admin) | seller | `catalog:write`, `labels:print`, `orders:read`, `orders:update-status`, `returns:manage`, `refunds:issue`, `inventory:read`, `inventory:adjust`, `ads:manage`, `store:design`, `reviews:respond`, `payouts:read`, `team:manage`, `compliance:manage`, `support:respond`, `reports:read` |
| `seller.catalog` (Catalog manager) | seller | `catalog:write`, `labels:print`, `orders:read`, `inventory:read`, `inventory:adjust`, `store:design`, `reports:read` |
| `seller.fulfilment` (Fulfilment) | seller | `orders:read`, `orders:update-status`, `returns:manage`, `inventory:read`, `inventory:adjust`, `labels:print`, `reports:read` |
| `seller.finance` (Finance) | seller | `orders:read`, `payouts:read`, `billing:manage`, `compliance:manage`, `reports:read` |
| `seller.ads` (Marketing) | seller | `ads:manage`, `store:design`, `reviews:respond`, `reports:read` |
| `seller.support` (Support agent) | seller | `orders:read`, `orders:update-status`, `returns:manage`, `support:respond`, `reviews:respond` |
| `provider.owner` (Owner) | provider | `bookings:read`, `bookings:manage`, `services:write`, `availability:manage`, `store:design`, `reviews:respond`, `payouts:read`, `billing:manage`, `team:manage`, `compliance:manage`, `support:respond`, `reports:read` |
| `provider.manager` (Manager) | provider | `bookings:read`, `bookings:manage`, `services:write`, `availability:manage`, `store:design`, `reviews:respond`, `payouts:read`, `team:manage`, `compliance:manage`, `support:respond`, `reports:read` |
| `provider.practitioner` (Practitioner) | provider | `bookings:read`, `bookings:manage`, `availability:manage` |
| `provider.finance` (Finance) | provider | `bookings:read`, `payouts:read`, `billing:manage`, `compliance:manage`, `reports:read` |

Module access matrix: 27 platform modules × 15 platform roles, 10 Seller Center modules × 7 seller roles,
12 Provider Hub modules × 4 provider roles (`GET /v1/admin/module-access?portal=…`).

## Configuration

`application.yml` + `application-{local,dev,uat,prod}.yml`. Secrets come only from the environment
(ExternalSecret `afrotransact/<env>/identity`):

| Env var | Purpose |
|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | PostgreSQL `identity` |
| `KAFKA_BOOTSTRAP` | outbox relay |
| `SIGNING_KEY_PEM`, `SIGNING_KEY_ID` | RS256 private key (PKCS#8 PEM) and `kid`; required outside `local` |
| `AUTH_ISSUER`, `HOME_URL` | issuer URL (`http://localhost:8100` locally), post-login fallback |
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` (+ `APPLE_*`, `FACEBOOK_*`) | social sign-in; unset → provider not registered |
| `BFF_CLIENT_SECRET`, `SELLER_SERVICE_CLIENT_SECRET` | confidential client secrets (hashed with BCrypt before storage) |
| `BOOTSTRAP_ADMIN_EMAIL`, `BOOTSTRAP_ADMIN_PASSWORD`, `BOOTSTRAP_ADMIN_PHONE` | first super administrator (skipped when unset) |

Other settings (`at.identity.*`): `staff-email-domains`, `verification.sender` (`outbox` | `log`), `mfa.*`,
`webauthn.{rp-id,rp-name,allowed-origins}`, `cors-allowed-origins`, and `clients` — the OAuth clients seeded (upserted by
client id) at start-up per environment: `storefront`, `seller-center`, `staff-console`, `customer-app`, `driver-app`
(public, PKCE, rotating refresh tokens), `gateway-bff` (confidential) and `seller-service` (client credentials,
`orgs:manage users:read`). Access tokens 5 minutes; refresh tokens 30 days (12 h staff console, 14 h driver app).

## Run locally

```bash
docker compose -f infra/local/docker-compose.yml up -d postgres kafka   # database "identity"
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
cd java && ./mvnw -B -f services/identity/pom.xml spring-boot:run       # profile local, port 8100
```

The `local` profile generates a signing key, writes one-time codes to the log
(`[local] EMAIL_VERIFICATION code … : 123456`), and creates `admin@afrotransact.com` / `LocalAdmin2026!`
(super admin; its SMS sign-in code is logged too). Try it: open
`http://localhost:8100/oauth2/authorize?response_type=code&client_id=storefront&redirect_uri=http://localhost:3000/auth/callback&scope=openid&code_challenge=…&code_challenge_method=S256`.

## Tests

`./mvnw -B -f services/identity/pom.xml verify` runs Checkstyle, domain unit tests (password policy, phone
validation, sign-up validation, permission resolution, landing rules, code expiry and resend limits, TOTP RFC 6238
vectors, MFA rules), `ArchitectureTest`, and `IdentityFlowIT` (Testcontainers PostgreSQL, real HTTP): discovery/JWKS,
client credentials, sign-up → email code → authorization code + PKCE → custom claims, refresh rotation and reuse
rejection, start selling → org approval → landing and `org`/`perms`, staff sign-in with SMS second factor, role/grant
changes visible on the next token, disable revokes sessions, module matrix and identity-provider switches (real redirect
to Google's consent page), social sign-up/linking, WebAuthn ceremony endpoints, TOTP enrolment + recovery code +
trusted browser, resend rate limit.

## Not covered here

- Fine-grained object checks (seller_org → store → listing) belong to OpenFGA (backlog S022), not this service.
- TOTP secrets are stored unencrypted in `mfa_totp` (protected by database access controls); envelope encryption with
  a KMS key is a follow-up.
- Token Exchange (RFC 8693), DPoP for mobile and the device-code grant from the architecture document are not enabled yet.

## Address book

Customers keep up to 20 delivery addresses (`GET/POST /v1/me/addresses`, `PATCH/DELETE /v1/me/addresses/{id}`,
`POST /v1/me/addresses/{id}/default`). Addresses are filled from geo-service autocomplete and carry its `verified`
result and coordinates, the recipient's phone (validated and stored in E.164) and delivery instructions for drivers.
The first address becomes the default; exactly one default is enforced by a partial unique index, and deleting the
default promotes the most recently updated address. The gateway-bff serves these to checkout and the account pages.
