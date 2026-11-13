# Tenant isolation without OpenFGA

Owner decision (ADR 0010): OpenFGA is not deployed or enabled in production from day 1. FGA stays off for catalog, offers, orders and
payments; OpenFGA and authz-sync are outside the launch set. This page states how tenants are kept apart in that mode, where it is
proven, and what is still weak. Findings context: `security-audit.md` F6 and section 5.

## 1. The model

A request for tenant data must pass, in order:

1. **RBAC permission** (`PermissionChecker.require`): the token's permissions, set by identity from roles.
2. **Org claim vs owner** (`requireForOrg`, `ResourceAccessChecker.requireOwned/requireForOrg`): non-staff, non-service callers must carry
   an `org` claim equal to the resource's owner org. The owner is read from the service's own database, never from the request.
3. **Subject match** for customers: the order/cart/address/booking `customerId` equals the token subject. Mismatch is a 404.
4. **SQL scoping**: lists, exports and reports filter by the caller's org (staff may name an org; for everyone else a client-supplied
   org is ignored or refused).
5. **Second layer in the RBAC fallback** (new): `ResourceAccessChecker.require(permission, relation, resource, tuples)` no longer
   falls back to the permission alone. For a non-staff, non-service caller it requires an `org` tuple for the resource whose
   organisation equals the caller's `org` claim, and **fails closed** (403 `auth.wrong-org`) when the owner is not stated.
   Staff and service principals remain platform-wide (their reach is decided by permissions).

In the Go gateway-bff the session never stores a client-chosen org: each seller request re-reads identity `/v1/me` with the user's
bearer, relays the user's token (so the Java checks run with the caller's identity), overwrites `ownerOrg`/`orgId` with the session
org where it relays them, and strips org and user keys from relayed FBA and size-chart bodies.

Startup: services without FGA log `FGA disabled: isolation by RBAC + org claim` (`RelationshipAutoConfiguration`). Prod configs for
catalog, offers, orders and payments keep `at.security.fga.enabled: ${FGA_ENABLED:false}` and point at ADR 0010.

### The principal-type guard (`@StaffOrServiceOnly`)

`com.afrotransact.platform.security.StaffOrServiceOnly` (platform-security) marks a controller or one handler. A Spring MVC interceptor
(`StaffOrServiceOnlyInterceptor`, registered by `PlatformSecurityAutoConfiguration`) refuses any principal that is not STAFF or SERVICE
(or the super-admin `*`) with 403 `auth.staff-only` before the handler runs. It sits on top of the permission check in the use case, so a
permission that is ever granted to a seller, provider, driver, shopper or agent role does not open a platform-wide route. Applied to:

- retail: every controller (9);
- delivery-runs: locations, economics, preorder settings, runs, preorders, `/v1/service/*` (service controller, carrier fee route, preorder
  eligibility and audience), advertorial and delivery-fee admin routes, attribution, pick lists and run progress;
- fulfilment: dispatch, centre, run dispatch, return pickups and the nearest-driver dispatch routes;
- orders: `PUT /v1/shipping-settings/{currency}` and its changes route;
- identity: `PUT /v1/orgs/{orgId}`;
- catalog: product approve, reject, takedown, restore and flag, change-request and GTIN-exemption queues and decisions, category-request
  admin, image-quality admin, brand, taxonomy, taxonomy import, catalog settings, review moderation, tax gate audit.

The matrix drift guard (`TenantIsolationMatrix.assertComplete`) checks both directions: a handler (or its controller) with the annotation
must carry `[principal-guard]` in its row, and a row with `[principal-guard]` must have the annotation. Staff console and service clients keep
working because their tokens have principal type STAFF (staff user token relayed by the BFF) or SERVICE (client credentials). Agent
(AI_AGENT, PARTNER_APP) and customer tokens on these routes are now refused.

## 2. Isolation classes and the matrix

| Class | Meaning | Enforcing code (typical) |
|---|---|---|
| PUBLIC | no tenant data | none needed |
| CUSTOMER_SELF | row customer == token subject | service `ensureOwner` / subject-keyed queries |
| ORG_OWNED | fetch by id, owner org == caller org | `requireOwned`, `requireForOrg`, `AccessGuard.orgRole`, `ownerFor(actor)` |
| ORG_SCOPED_LIST | lists, exports, reports filtered by caller org | org taken from `Actor`, not from the request |
| DRIVER_SELF | driver's own assignments | fulfilment route ownership |
| STAFF_ONLY / SERVICE_ONLY | principal type and permission | `staffOnly`, `service()`, scopes |
| TOKEN_LINK / WEBHOOK | signed token or provider signature is the credential | token verification, HMAC |
| UNVERIFIED | not proven; reason required | listed below |

Every service owns the matrix next to its tests; a drift guard fails the build when a route has no row or a row has no route.

| Stack | Matrix | Drift guard | Cross-tenant tests |
|---|---|---|---|
| Java (17 services) | `java/services/<svc>/src/test/resources/tenant-isolation-matrix.txt` | `TenantIsolationMatrixTest` via `platform-test` `TenantIsolationMatrix.assertComplete` (reflection over `@Controller` mappings; test classes named in the matrix must exist) | `*TenantIsolationTest` using `TenantFixture` and `CrossTenant` (kit in `java/libs/platform-test/.../tenant`) |
| Go gateway-bff | `internal/adapter/httpapi/tenant_isolation_matrix.txt` (585 rows) | `TestRouteDriftGuard` | `internal/testkit/tenant.go` (fake upstream recorder, two seller and provider orgs, two customers, spoof oracle) |
| Go other services | `tenant_isolation_matrix.txt` embedded per service | `go/pkg/tenanttest` route-drift check | `go/pkg/tenanttest` runner (carrier-gateway, media, notifications, cart, search, geo, promise, indexer, authz-sync) |

### Matrix by route group (summary)

| Route group | Class | Enforcing code | Test |
|---|---|---|---|
| orders: customer reads, cancel, returns | CUSTOMER_SELF | `OrdersAccess.viewerOf/ensureOwner` | `OrdersTenantIsolationTest` |
| orders: seller queue, shipments, transitions | ORG_OWNED / ORG_SCOPED_LIST | `sellerOrg`, `transitionActor` | `OrdersTenantIsolationTest` |
| payments: balances, payouts, statements, bank | ORG_OWNED / ORG_SCOPED_LIST | `PaymentsAccess.balanceViewer`, `orgOf` | `PaymentsTenantIsolationTest` |
| offers, coupons | ORG_OWNED | `requireOwned`, `customerOf` | `OffersTenantIsolationTest` |
| inventory lots, cycle counts, receipts, returns | ORG_OWNED, staff-gated platform ops | `InventoryAccess.requireOwnedLot/staffRequire` | `InventoryTenantIsolationTest` |
| catalog seller routes | ORG_OWNED / ORG_SCOPED_LIST | `owners.ownerFor(actor)`, `requireOwned` | catalog tenant tests |
| seller org, billing, team, compliance | ORG_OWNED | `AccessGuard.orgRole` | `SellerTenantIsolationTest` |
| bookings | ORG_OWNED / CUSTOMER_SELF | provider org path check, subject | `BookingsTenantIsolationTest` |
| tax-returns, returns | ORG_OWNED / CUSTOMER_SELF | seller ownership of every line | `TaxReturnsTenantIsolationTest` |
| identity team, memberships, active org | ORG_OWNED | `OrgTeamAccess.resolve` (store re-read) | `IdentityTenantIsolationTest` |
| trust-ops, content, ai-gateway, mcp | STAFF_ONLY / CUSTOMER_SELF / ORG_OWNED (ads) | `staffOnly`, tool policy | `TrustOpsTenantIsolationTest`, `ContentTenantIsolationTest`, `AiTenantIsolationTest`, `McpTenantIsolationTest` |
| retail | STAFF_ONLY (first party) | `retail:*` permissions on `platform.*` roles, plus `@StaffOrServiceOnly` on every controller | matrix `[principal-guard]` (drift guard) |
| delivery-runs, fulfilment | STAFF/SERVICE/TOKEN_LINK/DRIVER_SELF | permissions, route ownership, `@StaffOrServiceOnly` on the staff and `/v1/service/*` routes | `*TenantIsolationTest`, matrix `[principal-guard]` |
| orders courier events, delivery brief | SERVICE_ONLY | `OrdersAccess.courier/briefReader`: fulfilment relay only (`delivery:relay`), dispatch for the brief | `OrdersTenantIsolationTest` |
| gateway-bff seller, customer, staff | see Go matrix | session org, user token relay | `TestSellerMatrix`, `TestCustomerMatrix`, `TestStaffGate` |
| carrier-gateway labels, pickups, tracking | ORG_OWNED | org that bought the label, own origin node only | carrier-gateway httpapi tests |
| media assets, KYC, library | ORG_OWNED / ORG_SCOPED_LIST | owner check, org-prefixed keys, short signed URLs; staff need `kyc:read` for KYC documents and proof photos | media app tests (`kyc_read_test.go`) |

## 3. The org claim and its staleness window

- Identity re-resolves memberships on **every** token issuance, including refresh (`IdentityTokenCustomizer` -> `TokenClaimsServiceImpl`).
  A removed member's next refresh carries no `org` claim for that org and no org permissions; a disabled account cannot refresh.
- Access token default: **5 minutes** (no override in prod). Refresh token: 30 days (driver app 14 h), rotated.
- **Worst case after removal, suspension or role downgrade: up to 5 minutes** on downstream services; refresh does not extend it. There
  is no introspection, revocation list or per-request membership lookup in the resource servers.
- Immediate: identity team routes (store re-read per call), the BFF seller routes (live `/v1/me`; removal returns `NO_ORGANISATION`),
  org switching (membership verified server-side; the BFF now rotates session tokens after a switch), password change and reset
  (sessions revoked), account disable (authorisations deleted).
- Immediate (new): sensitive seller actions use `AccessGuard.liveOrgRole/liveOrgRoleOrStaff`, which ignore the token role and read the
  organisation's member list on every call: all billing and plan routes (invoices, payment methods, plan change, cancel, resume,
  plan-fee collection, usage, rewards), Stripe account session, account health, branding (`PUT business-page`) and org rename. A removed or
  downgraded member is refused on the next call (`seller.org-role-required`). Reads that are not sensitive (ship-from addresses, the org, catalog scope,
  selling status, business page read) keep the cheap `orgRole` (token role or member list).
- Still token-based (5 minute window): payments (payout requests, bank, balances, statements), media (KYC upload) and the other
  services read the `org` claim and role permissions from the token; they have no members table. Closing this needs either a short
  membership introspection call to identity or a much shorter access token for those routes (see section 5).

## 4. Findings from the pass (fixed)

| Sev | Where | Issue |
|---|---|---|
| High | carrier-gateway | any seller with label permissions could read, print or void another org's labels; origin node let a seller spend another org's or the platform's carrier account |
| High | inventory | lot routes, cycle counts, platform-wide runs (nodes, rates, reconciliation, storage) open to any seller with `inventory:adjust` |
| High | trust-ops | support-case queue readable and workable by seller and provider members |
| Medium | orders | payment-split returned whole-order charge to a seller on a shared order |
| Medium | payments | `balanceViewer` checked `payouts:read` only with FGA on; now in both modes |
| Medium | inventory returns, SKU dimensions; carrier tracking and pickup locations; search stock quantity | see commit messages |
| Medium | orders courier events and delivery brief open to any driver token | relay only (follow-up 2) |
| Low | seller billing reason codes unguarded; catalog GTIN prefill and identifier check named other sellers' non-live ATINs; inventory SKU dimensions readable by sellers; media `duplicateOf` named another seller's asset; carrier `ready` open to sellers; tax-returns pickup not bound to the assigned driver and unlimited code guesses | follow-ups 6 and 7 |
| Low | fulfilment, offers coupons, geo zones, cart guest ids, gateway-bff relayed bodies and org-switch token rotation, identity password change | see commit messages |

## 5. Not fixed, residual risk (owner decisions needed)

- Payout requests, bank details and KYC upload trust the token role for up to 5 minutes (see section 3); the members table lives in
  seller and identity, not in payments or media.
- `GtinAlreadyUsed` (409 on create, add variant) and `CatalogMatchService` still name the ATIN of an existing page whatever its status; only
  the GTIN check and prefill routes were scoped to live pages, the caller's own pages and staff.
- carrier-gateway `ready` cannot check `orderRef`/`customerRef` itself (no orders access); it now accepts only SERVICE or STAFF callers with
  `shipping:label`. A caller that serves sellers (orders or the BFF) must check the order first. No caller exists today.
- Handover-code limiting for tax-returns pickups is in memory (per instance): with several replicas the budget multiplies. Prod runs one.
- With FGA off, any staff member with `payments:read` or `payouts:approve` sees every org (no market scoping).
- Inventory idempotency keys are not scoped by actor. Repository SQL is not owner-scoped by id in most services; checks run after the
  fetch in the service layer (defence-in-depth SQL scoping not added: not trivially safe per service).
- Pay links for an already paid order never expire.
- Docker ITs were not run in this pass.

## 6. Enabling OpenFGA later (shadow, then enforce)

See ADR 0010. Deploy OpenFGA and authz-sync, add to the launch set and secrets; `FGA_ENABLED=true FGA_ENFORCE=false` (shadow, denials
logged as `FGA shadow:`); clean a full cycle; then `FGA_ENFORCE=true` per service. Keep the RBAC second layer.

## 7. Adding a route

The drift guard fails the build until the route has a matrix row. Pick the class, name the enforcing code, and for CUSTOMER_SELF,
ORG_OWNED, ORG_SCOPED_LIST or DRIVER_SELF name a cross-tenant test that exists.
