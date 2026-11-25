# ADR 0015 — Release 1 ships two deployables: `afrotransact-core` (Java) and `afrotransact-go`, every service stays a module

- **Formerly numbered 0011** (renumbered 2026-11-25: `0011-two-fulfilment-modes.md` holds that number; "ADR 0011" in older text about the two deployables means this ADR).
- **Status:** Proposed (Phase 0, 2026-11-15). Applies to the `release-1` branch only. `main` and `develop` keep the per-service build.
- **Date:** 2026-11-15
- **Deciders:** platform owner (one developer)
- **Related:** ADR 0004 (lean single node), `docs/engineering/release-1-architecture.md` (inventory, call graph, risks),
  `docs/engineering/cloud-cost.md`, `infra/ovh-vps/README.md`, `tools/release/sync-main.sh`

## Context

Production is one 16 GB node (ADR 0004). It runs 14 Java services (each a JVM with a 384 Mi request, 640 Mi limit), 7 Go services,
one Helm chart, one workflow and one Argo CD Application per service. One developer operates all of it. The JVMs alone are
5.4 GiB of requests out of about 13 GiB allocatable (`cloud-cost.md`), each pays its own JIT, class loading, Spring context, Hikari
and Kafka client overhead, and every release is 21 rollouts.

The services are already hexagonal and already own their data (one database each, one Flyway history each). That is the property
that makes a **modular monolith** cheap here: the seams exist, only the process boundary is removed.

## Decision

1. **Two deployables on release-1**: `afrotransact-core` (all Java modules, one Spring Boot app, `java/apps/core`) and
   `afrotransact-go` (all Go modules, one binary `go/cmd/afrotransact-go`). Web apps stay separate (Cloudflare Workers).
   `gateway-bff` stays the edge, inside the Go deployable.
2. **Every service stays a module.** Nothing is merged: packages, databases, Flyway histories, Kafka consumer groups and
   API contracts are unchanged. Only the process is shared. Modules are switchable by `at.modules.<name>.enabled`.
3. **Per-module resources**: own `DataSource` (sized per module), own Flyway (a failed migration stops the start with a message that
   names the module), own outbox/inbox, own scheduler thread pool, own Kafka consumer groups (the old group ids, so offsets carry over
   at cutover).
4. **Calls between modules go through a published API package only** (`com.afrotransact.<module>.api`, in a small
   `java/libs/module-api/<module>-api` jar). Each outbound port that today calls another service over HTTP gets a second adapter that
   calls that API in-process. `at.<module>.mode=in-process|http` selects the adapter per consuming module; **in-process is the default
   in core, http stays the default for the standalone service build.** The HTTP adapter is kept, so a module can be detached by config.
5. **In-process calls carry a caller principal and run the same authorization.** The caller is a `SERVICE` actor with the scopes
   that identity grants that service's OAuth client today (taken from one registry in config, not self-asserted by the caller). The
   called module's application services run their existing `Access`/`PermissionChecker` checks against that actor. Scope checks
   are not dropped. (Finding: the controllers only require "authenticated"; the real checks are inside the application services, so
   the in-process path must go through them, not around them.)
6. **ArchUnit enforces the boundaries**: a module may depend on another module only through its `api` package; no module reads another
   module's tables (no other module's DataSource is injectable into it); module `@RestControllerAdvice` must be scoped by package.
7. **Monitoring is preserved**: Actuator health groups per module, Micrometer meters tagged `module` and `service` (the old short
   service name, so dashboards and alerts keep working), OTLP `service.name` per module as a span attribute and a log field.
8. **Old per-service builds stay buildable on `main` and `develop`** and are kept on release-1 until the final cutover phase.
9. **`main` flows into release-1 by regular merges** (`tools/release/sync-main.sh`), never cherry-picks.

## Success criteria (measured, written before building)

| # | Criterion | Target | How it is measured |
|---|---|---|---|
| S1 | Resident memory, Java | core with all 14 launch modules at most 55 % of the sum of the 14 separate JVMs at the same settings; hard cap 2.0 GiB RSS after warm-up | `ps -o rss` / `/proc/<pid>/status` after a fixed smoke load; separate JVMs started with the production flags |
| S2 | Startup | core ready in under 60 s with all launch modules on the lean node (cold), under 25 s with AppCDS; no module slower to become ready than its standalone start | time to `/actuator/health/readiness` UP |
| S3 | Tests | every existing module test that passes on `main` passes in release-1; module tests are not edited to pass | `./mvnw verify` per module, plus the core context tests |
| S4 | All features reachable | every route in `contracts/openapi/<service>.yaml` of an enabled module answers on core with the same status for the same request; route diff is empty | contract route diff script against `/v3/api-docs` per module group |
| S5 | Monitoring parity | every `service=` label value in `infra/observability` rules and dashboards still has series; JVM, Hikari, Kafka consumer and HTTP metrics per module; readiness shows each module | PromQL checks listed in the architecture doc, run against `/actuator/prometheus` |
| S6 | Authorization parity | every in-process call fails with 403 when the caller lacks the scope, exactly as the HTTP call does with a token without the scope | one parametrised test per in-process API method |
| S7 | Detachability | any enabled module can be switched to `mode=http` and `enabled=false` here and run as its own service with no code change | boot test with a module disabled; standalone service build stays green |
| S8 | Ops surface | Java: 1 image, 1 chart, 1 workflow, 1 Argo app; Go: the same | counts in the final report |

## Alternatives considered

- **Stay with 14 + 7 services, tune each JVM harder.** Cheapest to do, but the per-JVM floor (about 250 MB resident after start,
  measured in `java/Dockerfile`) stays, and release effort stays 21 rollouts.
- **Merge into one codebase, one database.** Rejected: loses the seams and the per-module data ownership that make detaching possible.
- **One JVM, in-process calls only (drop the HTTP adapters).** Rejected: the owner wants modules detachable later.
- **Spring Modulith.** Gives events and module verification, but assumes one `DataSource` and one package tree under one
  application class. Our modules each own a database and live in separate Maven modules, so we use plain ArchUnit and our own small
  module infrastructure (`java/libs/module-core`).

## Consequences

- One process failure takes every module with it. Mitigations: per-module health groups, bulkheads (own scheduler pools, own Hikari
  pools, own Kafka containers), optional heavy modules (media image processing is in the Go deployable; `ai-gateway`, `mcp-server`)
  detachable by config, a rollback to the per-service images (below).
- Dependency versions are now one set for all modules (Avalara SDK's gson and Apache HttpClient 4, Stripe's gson, POI, PDFBox,
  Temporal, Spring AI): see the conflict table in the architecture doc.
- A module that calls another module in-process shares the JVM's fate and no longer has a network timeout in between: in-process
  adapters keep the same timeout semantics (a bounded executor wait) so a hung dependency still fails the same way.

## Rollback plan

1. The per-service Dockerfile, Helm charts, workflows and Argo Applications are **not deleted until the cutover phase**; during
   release-1 development both exist and the per-service path is exercised by CI on `main`.
2. Cutover is per batch, behind Argo: core enables the batch's modules and the matching per-service Applications are scaled to 0, not
   deleted. Rolling back is scaling them up and flipping `at.modules.<m>.enabled=false` in core. The database, outbox rows and
   consumer group offsets are the same objects in both shapes (same group ids), so nothing is migrated back.
3. Flyway histories are per module and unchanged, so a module can move between core and standalone at any time.
4. Only after two release cycles on core with no rollback may release-1 drop the per-service deployables (cutover phase), and `main`
   keeps them regardless.

## Keeping `main` in release-1

`main` is merged into release-1 regularly (at least weekly, and before every release) with `tools/release/sync-main.sh`. It fetches,
predicts conflicts with `git merge-tree`, merges, and prints the push command (`git push origin HEAD:release-1`); it never pushes.
The module work is designed to merge cleanly: module code changes are additive (a second adapter, a `basePackages`
on an advice, `resources` entries in the service pom), release-1-only code lives in new directories (`java/apps/core`,
`java/libs/module-*`, `java/libs/module-api/*`, `go/apps`), and migrations are never moved in the source tree. Conflict rules are in the
script header and in the architecture doc.

## CLAUDE.md change (approved by the owner 2026-11-15; applied on release-1 only)

Replace in "Deployability":

> Every service is independently buildable, testable, containerised and deployable to Kubernetes: its own Dockerfile, its own Helm
> chart (`deploy/helm`, ...), its own GitHub Actions workflow (path-filtered) and its own Argo CD Application per environment.

with (release-1 only):

> **Each deployable** (`afrotransact-core`, `afrotransact-go`, each web app) is independently buildable, testable, containerised and
> deployable to Kubernetes: its own Dockerfile, Helm chart, workflow and Argo CD Application per environment. **Each module** inside a
> deployable keeps its own hexagonal packages, database, Flyway history, Kafka consumer group and OpenAPI contract, is enabled by
> `at.modules.<name>.enabled`, may call another module only through that module's `api` package (ArchUnit), and must still build and
> test on its own (`./mvnw -pl services/<name> -am verify`) until the owner approves removing the standalone build.

Also add to "Non-negotiable code rules / Java": "A module never injects another module's DataSource or repository; cross-module calls use
the published `api` package and run as an explicit caller principal."
