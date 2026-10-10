# ADR 0001 — Relationship-based authorisation with OpenFGA

- **Status:** Accepted (S022). Piloted in offers-service; rollout per service and per environment.
- **Date:** 2026-10-10
- **Deciders:** platform / identity owners
- **Related:** `contracts/openfga/model.fga`, `java/libs/platform-security`, `go/services/authz-sync`,
  `infra/k8s/platform/openfga`, [engineering guide](../engineering/authorisation.md)

## Context

Authorisation today is RBAC carried in identity-issued JWTs: `perms` (permission keys), `roles` and a single `org`
claim (the user's active seller / provider organisation). `PermissionChecker.requireForOrg(permission, orgId)` lets a
caller act on a resource when it holds the permission and its `org` claim equals the resource's owner; staff and
service principals skip the org comparison entirely.

That leaves three gaps the backlog (S022) asks us to close:

1. **The org claim is the only ownership proof.** Anything that puts the wrong `org` in a token — a bug in
   active-organisation switching, a stale token after a member is removed (tokens live up to 5 minutes), a
   mis-issued client token — gives full access to another organisation's data, because permissions are checked
   without asking "is this user actually a member of *that* organisation?".
2. **Staff access is all-or-nothing.** A support agent with `orders:read` sees every market's orders. We need grants
   scoped to a market ("support can view orders in GB").
3. **Roles inside an organisation are flattened into permission strings.** "A catalog member cannot see payouts" holds
   only as long as nobody adds `payouts:read` to the wrong role, and per-resource rules (a practitioner sees only the
   bookings assigned to them, seller_org → store → listing) cannot be expressed at all.

## Decision

Adopt **OpenFGA** (v1.10, Zanzibar-style ReBAC) as the relationship store, **in addition to** RBAC:

- **RBAC stays the coarse gate.** `PermissionChecker` is unchanged and still answers "may this kind of actor do this
  kind of thing" from the token.
- **Relationships answer "to this resource".** A new `ResourceAccessChecker` in `platform-security` requires the RBAC
  permission **and** a relation in OpenFGA (`can_edit` on `offer:<id>`, `can_manage_products` on
  `organization:<id>`, …). With FGA enabled the `org` claim is no longer trusted for ownership.
- **One model, versioned as a contract:** `contracts/openfga/model.fga` (DSL, source of truth), `model.json`
  (generated with `fga model transform`), `model.fga.yaml` (model tests run with `fga model test`). It covers users,
  OAuth clients, the platform, markets, organisations (SELLER / PROVIDER / BOTH via capability flags) and their roles,
  and org-owned resources: storefronts, products (listings), offers, orders and shipments, bookings, ads campaigns and
  payouts. Platform staff roles exist platform-wide or per market; service clients are trusted platform-wide
  (`platform#service`) or delegated by one organisation (`delegated_viewer` / `delegated_editor`).
- **Who writes tuples:**
  - **Memberships, staff roles, organisation market/kind** — `authz-sync`, a small Go consumer of
    `identity.role.granted|revoked` and `seller.org.approved|updated`. It also creates the store and deploys the model.
  - **Resource ownership** (offer → organisation, booking → organisation, …) — **not synced**. The owning service
    reads the owner from its own database and sends it as a *contextual tuple* on each check. Its database stays the
    single source of truth; there is no dual write and no window where a new resource is unknown to OpenFGA. A
    service that needs persisted resource tuples (ListObjects, cross-service checks) writes them with
    `RelationshipWriter` after commit.
- **Opt-in and safe by default.** Nothing FGA-related is created unless `at.security.fga.enabled=true`; without it
  `ResourceAccessChecker` behaves exactly like today's `requireForOrg`. `at.security.fga.enforce=false` is a shadow
  mode that logs would-be denials. Enforcing mode fails closed: an unreachable store answers HTTP 503.
- **No SDK.** The Java adapter calls OpenFGA's HTTP API through Spring's `RestClient` (check, write, store lookup), so
  `platform-security` adds no dependencies and dependency convergence is unaffected. Results are cached per instance
  for a short TTL (default 5 s; writes through `RelationshipWriter` clear the local cache).

### Why the sync lives in a new Go service, not in identity

| | In identity (Java) | `go/services/authz-sync` (chosen) |
|---|---|---|
| Blast radius | OpenFGA latency/outage would sit on identity's role-grant and sign-in paths, or need a second outbox | Isolated: identity keeps publishing events; an OpenFGA outage only delays tuples (consumer retries, never drops) |
| Inputs | identity owns memberships but consumes seller-org events only partially; future sources (bookings practitioners, delegated clients) are other services' events | Any topic can feed it; it is a projection like `indexer` |
| Rebuild / replay | Custom backfill code in identity | Reset the consumer group (idempotent writes, per-key ordering) |
| Model rollout | Identity would own an authorisation model it does not use | The service that writes tuples also deploys the model they need |
| Cost | No new deployable | One small stateless consumer (≈64 MiB), standard Go service checklist |

Identity remains the source of truth for memberships; authz-sync is a derived view and can be rebuilt.

## Consequences

**Positive**
- A member of organisation A cannot touch organisation B's data even with the right permission and a forged or stale
  `org` claim (proved by `OfferRelationshipsIT` against a real OpenFGA).
- Market-scoped staff grants, per-role resource rules ("catalog members never see payouts") and delegation for partner
  apps / AI agents are expressed once in the model and tested with `fga model test`.
- Revoking a membership takes effect within seconds (event lag + ≤ 5 s cache), not at token expiry.

**Negative / risks**
- An extra network hop per check (mitigated: per-instance TTL cache, OpenFGA's own check cache, 500 ms timeout).
- A new runtime dependency on the authorisation path: in enforce mode an OpenFGA outage returns 503 for protected
  writes. OpenFGA runs with ≥ 2 replicas and a PDB; services can drop to shadow mode by configuration.
- Tuples are eventually consistent with identity (event lag). New members may wait a moment before access works.
- Event-driven backfill depends on topic retention; if `at.identity.events` is time-limited, a one-off export from
  identity's `org_membership` table is needed when a store is first built.
- Market-scoped staff grants are modelled and enforced, but identity does not yet publish a `market` on role events;
  until it does, they are written by operators (fga CLI / `RelationshipWriter`).

## Rollout

1. Deploy OpenFGA (`infra/k8s/platform/openfga`) and authz-sync per environment; authz-sync backfills from the topics.
2. Per service: adopt `ResourceAccessChecker` (no behaviour change while FGA is off), then `FGA_ENABLED=true` with
   `FGA_ENFORCE=false` (shadow) and watch `FGA shadow` log lines; then `FGA_ENFORCE=true`.
3. offers-service is the pilot: enforced in dev, shadow in uat, off in prod until uat has run enforced.
4. Next candidates: catalog (listings), orders (shipments), payments (payouts), bookings (practitioner rules), content
   (ads campaigns).

## Alternatives considered

- **Keep RBAC and harden the `org` claim** (re-check membership in each service's DB): every service would need a
  copy of memberships and its own staff-scoping logic; nothing shared, nothing testable as one model.
- **Membership lookups against identity over HTTP:** central truth, but no relation graph (stores, markets, delegation),
  and identity becomes a hot dependency of every request.
- **OPA / Cedar policies over token claims:** good for attribute rules, but the facts (who belongs where) still have to
  reach the policy engine; that is the problem a relationship store solves.
- **OpenFGA Java SDK:** brings its own Jackson 2 / OpenTelemetry API dependency set into a library every service
  depends on (Spring Boot 4 is on Jackson 3), which would have to converge across every service; the three HTTP calls
  we need are simple.
