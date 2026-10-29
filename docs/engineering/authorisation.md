# Authorisation: RBAC + relationships (OpenFGA)

How a service decides whether the caller may act on a resource, and how to add relationship checks to a service.
Decision record: [ADR 0001](../adr/0001-openfga-relationship-authorisation.md). Model:
[`contracts/openfga/model.fga`](../../contracts/openfga/model.fga).

## 1. Two questions, two mechanisms

| Question | Answered by | Where |
|---|---|---|
| May this kind of actor do this kind of thing? (`offers:write`) | **RBAC**: `perms` in the identity JWT | `PermissionChecker` (unchanged) |
| May **this** actor do it to **this** resource? (offer `o-1` of org B) | **Relationships** in OpenFGA | `ResourceAccessChecker` (RBAC permission **and** relation) |

With FGA enabled, the JWT `org` claim is only a hint for "which organisation am I working in"; it never proves
ownership. Membership comes from OpenFGA, synced from identity by `authz-sync`.

## 2. The model in one screen

```
platform:afrotransact      super_admin, support, catalog_manager, finance, … (platform-wide staff), service (clients)
market:<GB|US|…>           the same staff roles scoped to one market (inherits platform-wide grants)
organization:<org id>      platform, market, sells_products / offers_services flags (kind SELLER | PROVIDER | BOTH)
                           roles: owner > admin > catalog | fulfilment | ads | support;  owner > manager > practitioner;
                           owner > finance;  member = any role;  delegated_viewer / delegated_editor (clients)
                           derived: can_view, can_manage_products, can_view_products, can_manage_services,
                           can_view_payouts, can_request_payouts, can_manage_ads, can_fulfil
storefront → product       seller_org → store → listing (product#store, storefront#org)
offer, shipment, booking, ads_campaign, payout     #org → organization (+ order#market, booking#practitioner, …)
```

Relations that services check end in a verb: `can_view`, `can_edit`, `can_manage`, `can_request`,
`can_update_status`, `can_manage_products`, … Role relations (`catalog`, `finance`) are only for tuples.

**Identity role → organisation relation** (written by authz-sync): `seller.owner`, `provider.owner` → `owner`;
`seller.admin` → `admin`; `provider.manager` → `manager`; `seller.catalog` → `catalog`; `seller.fulfilment` →
`fulfilment`; `seller.finance`, `provider.finance` → `finance`; `seller.ads` → `ads`; `seller.support` → `support`;
`provider.practitioner` → `practitioner`. Platform roles map to `platform:afrotransact` relations (see the
authz-sync README).

**Subjects:** user tokens are `user:<sub>`; `SERVICE`, `PARTNER_APP` and `AI_AGENT` principals are `client:<sub>`
(`FgaSubjectResolver`; override the bean to change it).

## 3. Adding relationship checks to a service

### 3.1 Model the resource

1. Add the type to `contracts/openfga/model.fga`, owned by an organisation:

   ```
   type coupon
     relations
       define org: [organization]
       define can_view: can_view_products from org
       define can_edit: can_manage_ads from org
   ```

   Reuse the organisation's derived relations (`can_view_products`, `can_manage_products`, `can_view_payouts`, …)
   so staff scoping, delegation and kind flags come for free. Add a direct relation (`practitioner: [user]`) only
   when individual users relate to individual resources.
2. Add tests to `contracts/openfga/model.fga.yaml` — at least: own member allowed, other org's member denied, the
   role that must *not* have access denied, market-scoped staff allowed in-market and denied out-of-market.
3. Regenerate and test:

   ```bash
   go install github.com/openfga/cli/cmd/fga@v0.7.4
   fga model validate --file contracts/openfga/model.fga
   fga model transform --file contracts/openfga/model.fga > contracts/openfga/model.json   # then pretty-print
   fga model test --tests contracts/openfga/model.fga.yaml
   cp contracts/openfga/model.json go/services/authz-sync/model/model.json   # authz-sync deploys it on start
   ```

   Model changes must be **additive** (new types/relations). Renaming or removing a relation that a running service
   checks breaks that service; deploy the new service code first, then remove the relation.

### 3.2 Check it in the application layer

Inject `ResourceAccessChecker` next to (or instead of) `PermissionChecker`, and keep relation names as constants
(offers: `OffersRelations`).

```java
// Resource owned by an organisation: pass the owner from your own database as a contextual tuple.
Offer current = offers.findById(id).orElseThrow(...);
access.requireOwned(OffersPermissions.WRITE, OffersRelations.CAN_EDIT,
        FgaObject.of("offer", current.id()), current.sellerOrgId());

// Organisation-level action (create, list, settings): relation on organization:<orgId>.
access.requireForOrg(OffersPermissions.WRITE, OffersRelations.ORG_MANAGE_PRODUCTS, org);

// Platform-wide action (list every organisation's offers): relation on platform:afrotransact.
access.require(OffersPermissions.READ, OffersRelations.PLATFORM_VIEW_ALL, FgaObject.PLATFORM);
```

| Method | FGA enabled | FGA disabled (fallback) |
|---|---|---|
| `requireOwned(perm, rel, resource, ownerOrg)` | perm AND `rel` on resource, with `resource#org@organization:ownerOrg` contextual | `requireForOrg(perm, ownerOrg)` |
| `requireForOrg(perm, rel, orgId)` | perm AND `rel` on `organization:orgId` | `requireForOrg(perm, orgId)` |
| `require(perm, rel, resource, ctx…)` | perm AND `rel` on resource (stored + contextual tuples) | `require(perm)` |

Denials throw `ForbiddenActionException` with code `auth.relationship-denied` (HTTP 403, RFC 9457); RBAC failures keep
their codes (`auth.missing-permission`, `auth.wrong-org`). An unreachable store throws
`RelationshipCheckUnavailableException` (HTTP 503, `auth.relationship-unavailable`) in enforce mode.

Do the check **before** any side effect, and check reads too (a 403 on another org's resource is correct; we do not
hide existence with 404 today). Prefer `requireOwned` over persisting resource tuples: the owner always comes from
the service's database, so there is nothing to keep in sync.

### 3.3 Persisted tuples (only when you need them)

If other services must check your resource without knowing its owner, or you need ListObjects, write tuples with
`RelationshipWriter` **after commit** (never inside a transaction that can roll back), or publish an event and add a
mapping to authz-sync:

```java
TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
    @Override
    public void afterCommit() {
        tuples.grant(RelationshipTuple.ownedBy(FgaObject.of("coupon", id), orgId));
    }
});
```

Writes are idempotent (`on_duplicate` / `on_missing: ignore`), at most 100 tuples per call, and clear the local
check cache.

### 3.4 Test it

- Unit tests: nothing changes — with FGA disabled `ResourceAccessChecker` is the RBAC fallback.
- One IT per service against a real OpenFGA from the **local image** (Docker Hub is rate-limited): copy
  `java/services/offers/src/test/java/com/afrotransact/offers/it/{OpenFgaTestContainer,OfferRelationshipsIT}.java`.
  It starts `openfga/openfga:v1.10.0`, creates a store, loads `contracts/openfga/model.json`, sets
  `at.security.fga.*` with `@DynamicPropertySource` and seeds tuples through the `RelationshipWriter` bean. Cover:
  other organisation's member with the right permission and a forged `org` claim is denied; market-scoped staff
  allowed in market and denied outside; revocation takes effect.

## 4. Opting a service in

1. Code: use `ResourceAccessChecker` as above (safe to merge while FGA is off).
2. Configuration (`application.yml`, see offers-service):

   ```yaml
   at:
     security:
       fga:
         enabled: ${FGA_ENABLED:false}
         enforce: ${FGA_ENFORCE:true}
         api-url: ${FGA_API_URL:http://localhost:8091}
         store-id: ${FGA_STORE_ID:}          # blank: look up store-name once
         store-name: ${FGA_STORE_NAME:afrotransact}
         model-id: ${FGA_MODEL_ID:}          # blank: the store's latest model
         api-token: ${FGA_API_TOKEN:}        # OpenFGA pre-shared key (secret)
         timeout: ${FGA_TIMEOUT:500ms}
         cache-ttl: ${FGA_CACHE_TTL:5s}      # 0s disables the per-instance cache
   ```

   Profiles: `local` off (security is off locally), `dev` enforced, `uat` shadow then enforced, `prod` last.
   In-cluster URL: `http://openfga.openfga.svc:8080`.
3. Secret: add `FGA_API_TOKEN` (one of `afrotransact/openfga` `preshared-keys`) to the service's secret and to the key
   list comment in `deploy/helm/values.yaml`.
4. Roll out: `FGA_ENABLED=true FGA_ENFORCE=false` → watch for `FGA shadow:` warnings (each is a request RBAC allowed
   and relationships would deny: a missing tuple or a real cross-org access) → `FGA_ENFORCE=true`.

## 5. Operations

- **Local:** docker compose runs OpenFGA on `:8091` (no authn). Run authz-sync (`APP_ENV=local go run ./cmd/authz-sync`
  in `go/services/authz-sync`) to create the store and model and mirror memberships from Kafka; inspect with
  `fga tuple read --api-url http://localhost:8091 --store-id <id>` or
  `fga query check --api-url http://localhost:8091 --store-id <id> user:<id> can_edit offer:<id>`.
- **Clusters:** `infra/k8s/platform/openfga` (Deployment ≥ 2 replicas, PDB, migration Job, ExternalSecret
  `afrotransact/openfga` with `datastore-uri` and `preshared-keys`, NetworkPolicy admitting only `afrotransact-<env>`
  namespaces, ServiceMonitor).
- **Staff market grants:** until identity publishes a `market` on role events, write them directly, e.g.
  `fga tuple write user:<staff id> support market:GB`. Remove with `fga tuple delete`.
- **Delegation:** an organisation trusting a partner app or AI agent client:
  `fga tuple write client:<client id> delegated_editor organization:<org id>` (or `delegated_viewer`).
- **Rebuild:** tuples are a projection — see "Rebuild every tuple" in the authz-sync README. If topic retention does
  not cover every membership, export `org_membership` (user, org, role) from identity and replay it as
  `identity.role.granted` events or `fga tuple write --file`.
- **Caching and staleness:** each service instance caches answers for `cache-ttl` (5 s); OpenFGA caches checks for
  10 s. A revoked membership stops working everywhere within ≈ 15 s of authz-sync applying the event.

## 6. Shopper or organisation member: the acting context

Identity issues one token per person. A person who belongs to a seller or provider organisation gets
`pt=SELLER_MEMBER` / `PROVIDER_MEMBER` and the `org` of their active organisation, whichever app asked for the token.
That person is still a shopper. Requests from the storefront and the customer app therefore carry
`X-Acting-As: CUSTOMER` ([ADR 0002](../adr/0002-acting-as-a-shopper.md)), and platform-security's `ActorResolver`
and go/pkg/auth's `Middleware` turn the person into a `CUSTOMER` of their own subject, with no organisation:

- **gateway-bff** sends it on every upstream call of a storefront or customer-app route (everything except
  `/v1/seller/**` and `/v1/staff/**`). Seller Center and Provider Hub relay the token as issued.
- **A service relaying a shopper's token** forwards it (orders → cart / identity at checkout, ai-gateway's Lola
  tools). Code that checks `actor.type() == CUSTOMER` needs nothing else.
- **Staff, services, partner apps and AI agents** are never changed. The header can only narrow a person to a
  shopper.
