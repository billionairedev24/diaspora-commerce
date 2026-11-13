# ADR 0010 — No OpenFGA at launch

- **Status:** Accepted (owner decision)
- **Date:** 2026-11-13
- **Related:** `docs/engineering/tenant-isolation.md`; `docs/engineering/security-audit.md` F6 and section 5; `docs/engineering/authorisation.md`

## Context

Catalog, offers, orders and payments carry optional OpenFGA relationship checks (`at.security.fga.enabled` / `enforce`).
OpenFGA, its store and `authz-sync` are outside the launch set (`infra/argocd/launch-set`) and production renders no FGA token.
The owner decided that OpenFGA is not deployed or enabled in production from day 1: one node, one replica of everything, fewer
moving parts on the critical path of every order and payment.

## Decision

- Production runs with FGA off: `fga.enabled=false` (default of `FGA_ENABLED`), `FGA_ENFORCE` irrelevant. Every service logs at
  startup `FGA disabled: isolation by RBAC + org claim`.
- Tenant isolation rests on, in this order: (1) the RBAC permission, (2) the signed JWT `org` claim compared with the resource's
  owner (`requireForOrg`, `ResourceAccessChecker.requireOwned`), (3) per-resource owner checks (customer subject match), (4) SQL
  scoping by the caller's org on lists and, where cheap, on fetch-by-id.
- Second layer without OpenFGA: the RBAC fallback `ResourceAccessChecker.require(permission, relation, resource, tuples)` also
  verifies the owner organisation for non-staff callers (owner read from the contextual `org` tuple) and **fails closed** when the owner
  is unknown. Staff and service principals stay platform-wide, as before.
- Compensating controls: the tenant-isolation test kit (`platform-test` `tenant` package, `go/pkg/tenanttest`, the gateway-bff
  testkit) and route drift guards that fail the build when a route has no declared isolation class.

## Consequences

- The `org` claim is the only membership source. A removed member keeps it until the access token expires (5 minutes by default);
  see the staleness section of `docs/engineering/tenant-isolation.md` for mitigations and the actions that re-check the store.
- A bug in the identity `org` claim minting would affect every service at once; there is no independent relationship store to
  contradict it.
- No per-resource relations (platform view-all grants by market, delegated roles) in production: those authorise by permission and
  principal type only.

## Enabling OpenFGA later

1. Deploy OpenFGA and `authz-sync`, add both to the launch set, render the FGA secrets.
2. `FGA_ENABLED=true`, `FGA_ENFORCE=false` (shadow: denials are logged, calls are allowed). Watch `FGA shadow:` warnings for a full
   business cycle; every one is either a missing tuple or an isolation bug.
3. When shadow is clean, `FGA_ENFORCE=true` per service (catalog, offers, orders, payments), one at a time.
4. Keep the RBAC second layer: it is cheap and runs in front of the relationship check.
