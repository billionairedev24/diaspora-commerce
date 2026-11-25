# Release 1 deploy: build, deploy, cut over per batch, roll back, watch

Status: **written, never run against a live cluster.** Checked offline: workflow YAML (`actionlint` 1.7.7 with shellcheck), shellcheck, the SQL on a scratch
PostgreSQL 16, the Argo CD emulation in `infra/argocd/scripts` with `kustomize` 5.6 (all four environments: `make launch-set-check`, `render_apps.py`), and
**`helm` 3 with `kubeconform` 0.7** (lint, template for dev, uat and prod, and `tools/release/test_cutover_render.sh`, which had never run with a real helm: both
`Chart.yaml` files were invalid YAML, so the helm jobs could not have passed until 2026-11-16). `tofu` was **not** run. The first run of `core.yml` and of the first
cutover will find things: treat the trial (`ovh-production.md`, "Trial") as the rehearsal. Vendor facts are tagged **UNVERIFIED** as elsewhere in this repository.

**Go-live (UAT dry run, then PROD, one parameterised ordered checklist): [release-1-go-live.md](release-1-go-live.md).**

Decision and success criteria: [ADR 0011](../adr/0011-modular-monolith-release-1.md). Shape and inventory:
[release-1-architecture.md](../engineering/release-1-architecture.md). Local run: [release-1-local.md](release-1-local.md).
Production from zero: [ovh-production.md](ovh-production.md). Sizing and cost: [cloud-cost.md](../engineering/cloud-cost.md).

## 1. What ships

| | `afrotransact-core` | `afrotransact-go` |
|---|---|---|
| Runs | every Java module in one Spring Boot JVM (`java/apps/core`) | every Go module in one binary (`go/apps/afrotransact-go`; gateway-bff stays the edge) |
| Image | `ghcr.io/<owner>/<repo>/afrotransact-core`, built from `java/Dockerfile` (`PROJECT=apps/core`) | `ghcr.io/<owner>/<repo>/afrotransact-go`, built from `go/Dockerfile.afrotransact-go` |
| Workflow | `.github/workflows/core.yml` | `.github/workflows/afrotransact-go.yml` |
| Chart | `java/apps/core/deploy/helm` (depends on `infra/helm/library/at-service`) | `go/apps/afrotransact-go/deploy/helm` |
| Argo CD Application | `afrotransact-core-<env>` | `afrotransact-go-<env>` |
| Secret | `afrotransact-core-secrets` | `afrotransact-go-secrets` |
| Database | `core` (one schema and one role per module) | `go` (one schema and one role per module) |
| Memory | request 768 Mi growing to 1,536 Mi with the batches (section 6), limit 2,048 Mi (= the S1 cap, 2.0 GiB RSS) | request 128 Mi, limit 384 Mi (**UNMEASURED estimate**) |
| Connections | pools add up to 48 per pod, 96 with a rolling-update surge | 4 x 3 = 12 per pod, 24 with a surge |

The per-service workflows (`java-<service>.yml`, `go-<service>.yml`), charts and Argo Applications still exist. The workflows trigger only on
`main` and `develop` (the generator `tools/ci/gen_workflows.py` says so); **release-1 builds the deployables**, so a per-service image of a
service that has not moved yet is the one last built on `main` and pinned in its `values-<env>.yaml`. A service whose module has moved stays
in git, scaled to 0, until the cutover phase of ADR 0011 removes it.

Where things are:

| Concern | File |
|---|---|
| Which modules each environment runs, batch order | `infra/argocd/launch-set/release-1.yaml` (reviewed list) and `cutover.services` in `infra/argocd/envs/<env>/platform-values.yaml` (what Argo CD reads) |
| The two Applications per environment | `infra/argocd/applicationsets/deployables.yaml` (and `lean/deployables.yaml`) |
| Scale an old Application to 0 and route its Service | `cutover.movedTo` parameter in `services.yaml`, implemented in `infra/helm/library/at-service` |
| Generators and root apps read the release-1 branch | `infra/argocd/release-branch/`, `infra/argocd/bootstrap/root-<env>.yaml` (`targetRevision: release-1`) |
| Database bootstrap, verify, restore | `infra/ops/postgres/release-1-bootstrap.{sql,sh}`, `release-1-verify.sql`, `infra/ops/backup/pg-restore.sh restore-schema` |
| Secrets rendering | `infra/ovh-vps/secrets.sh` with `DB_LAYOUT=schema` (multi-line values such as `SIGNING_KEY_PEM` are one key; test: `tools/ovh/test/secrets-multiline.test.sh`) |
| Parity with main, the sync, the hotfix port-back | `tools/release/parity.py` + `parity.yaml`, `release-1-sync.yml`, `tools/release/sync-main.sh`, `sync-open-pr.sh`, `merge_backlog.py`, `port-back.sh`; [release-1-parity.md](../engineering/release-1-parity.md) |
| Deployable settings from the service charts | `tools/release/chart_env_sync.py` (generated block in the deployable `values*.yaml`) |
| Does the plan fit the one node | `tools/release/check_capacity.py` |
| Alerts, dashboard, scrape | `infra/observability/rules/release-1.yaml`, `grafana/dashboards/release-1-modules.json`, `infra/k8s/platform/lean/alloy/config.alloy` |

Offline gate for all of it: `make release-1-check` (workflows generated, action pins, launch set vs cutover, pools vs SQL vs chart, rule labels,
route-diff tests, shellcheck), then `make launch-set-check` (needs `kustomize` or `kubectl`) and `tools/release/test_cutover_render.sh` (needs `helm`).

## 2. One-time set-up on your accounts (nothing here was done for you)

1. **GHCR.** The packages `afrotransact-core` and `afrotransact-go` are created by the first push of `core.yml` / `afrotransact-go.yml` from release-1,
   private by default. Link each package to the repository (Package settings, "Manage Actions access": this repository, role Write) so
   `GITHUB_TOKEN` can push. Create the read-only pull token (`read:packages`) that `GHCR_TOKEN` in `secrets.local.env` carries (the `ghcr` pull Secret; already part of
   `ovh-production.md` section 6). Names only: `GHCR_USER`, `GHCR_TOKEN`.
2. **GitHub environments** `dev`, `uat`, `trial`, `prod` (they already exist for the per-service deploys): `_deploy.yml` reads their variables `CLOUD` (= `ovh`) and
   `REGISTRY_URL` (= `ghcr.io/<owner>/<repo>`, so nothing is mirrored). `prod` keeps its required reviewers. Secrets `GITOPS_APP_ID` and `GITOPS_APP_PRIVATE_KEY` (the GitOps
   app) must be allowed to push to **release-1**: the deployables' image pins are committed there (`values-<env>.yaml`), not on main.
3. **Branch protection on `release-1`:** see section 10b for the exact list (the checks of `release-1-parity`, `core` and `afrotransact-go`; **not** `release-1-guard`). The GitOps app must be a bypass actor
   (it pushes pin commits), as it is on main today.
4. **Argo CD** (once per cluster): point the root Applications at the branch: `kubectl apply -n argocd -f infra/argocd/bootstrap/root-<env>.yaml` (they now say
   `targetRevision: release-1`). A running cluster that was bootstrapped from main: apply the new file over the old Application.
5. **Layerbase:** the plan now needs **4 databases** (`core`, `go`, `temporal`, `temporal_visibility`) instead of 19, and the admin needs `CREATEROLE` and `CREATEDB`, the ability
   to `GRANT` a role to itself (`WITH SET TRUE`, PostgreSQL 16 syntax), `CREATE EXTENSION postgis` and `btree_gist`, and `pg_read_all_data` (or superuser) for the dump. The `max_connections` of
   the plan: the owner says up to 500 (UNVERIFIED in writing; ask, `ovh-production.md` section 14). Roles carry `CONNECTION LIMIT`; check Layerbase accepts it.
6. **Secrets** (names only, values come from `secrets.local.env`, never from git): unchanged from `ovh-production.md` section 6; the only new inputs are `DB_LAYOUT=schema` and
   the two Secrets it renders.

## 3. Build

On a push or pull request to `release-1` touching `java/**` (or the shared inputs in its `paths`), **`core.yml`** runs:

| Job | What | Notes |
|---|---|---|
| `meta` | no duplicate Flyway versions, the app exists | skips everything when `java/apps/core` is missing |
| `verify` | `./mvnw verify` on the **whole Java reactor**: Checkstyle, every module's tests, ArchUnit (module boundaries and the existing per-service `ArchitectureTest`), Testcontainers ITs, core's context tests; then the **contract route diff** | Postgres 17 service container (`imresamu/postgis:17-3.5.4-alpine3.22`, the image the local stack uses) provisioned by `tools/ci/core_ci_db.sh` with the production SQL; Maven cache through `setup-java`; 75-minute timeout |
| `security` | gitleaks, Trivy filesystem (HIGH/CRITICAL, fixable) | Semgrep, CodeQL and the OWASP scan stay on `security.yml` |
| `helm` | `helm-check` (lint, template for dev/uat/prod and every cloud, kubeconform) and `tools/release/test_cutover_render.sh` | proves: a cutover entry turns a module on, scales the old service to 0 and routes its Service; the pool budget guard fails the render |
| `image` | one multi-arch image, image policy (non-root, port 8080, `SPRING_PROFILES_ACTIVE=prod`, size budget **385 MB, measured 369 MB** (go: 103 MB, measured 98.6 MB), amd64 only), Trivy image scan, SBOM, **cosign keyless sign** | pushes only on a push to release-1; pull requests build without pushing |
| `deploy-dev` | `_deploy.yml` verifies the signature, then pins `image.tag` and `image.digest` in `java/apps/core/deploy/helm/values-dev.yaml` **on release-1** | `dev` syncs by itself |

The **route diff** (`tools/release/route_diff.py`, success criterion S4) boots the built jar against the CI Postgres with the modules that are enabled by default, and fails when a route of
`contracts/openapi/<module>.yaml` is not served by `/v3/api-docs`. It uses `/v3/api-docs/<module>` when core serves one group per module and the aggregate document until then.
Unit tests: `python3 tools/release/test_route_diff.py`. Runs only for modules that have a contract file.

**`afrotransact-go.yml`** is the same shape: gofmt, `go vet`, golangci-lint (`go/.golangci.yml`) and `go test -race` for the deployable **and every module of `go/go.work`**, a Postgres 17
service container, govulncheck, gitleaks and Trivy, the same helm tests, one image (`go/Dockerfile.afrotransact-go`, budget 120 MB, to tighten) and the same signing and dev pin. Every job is skipped until
`go/apps/afrotransact-go/go.mod` exists.

Both workflows: concurrency group per ref (cancel in progress only for pull requests), `permissions: contents: read` at the top and `packages: write` / `id-token: write` only on the image job,
third-party actions pinned by commit SHA (`tools/ci/check_action_pins.py`), caches (Maven, Go modules, pip, the buildx GitHub cache).

**`release-1-guard.yml`** fails when release-1 is behind main (`tools/release/sync-main.sh --require-current`), on every push, every pull request, daily at 05:17 UTC and by hand.
It is an **alarm, not a required check**: it looks at the release-1 tip, so on the sync pull request itself it is red until that pull request merges.

**`release-1-parity.yml`** (jobs `parity` and `delivery`; no path filter, so it can be required) fails when main brought something the monolith does not have
(table of gaps with a fix hint in the job summary, [release-1-parity.md](../engineering/release-1-parity.md)) and runs `make release-1-check` plus the Argo CD render of
every environment. **`release-1-sync.yml`** opens the pull request that carries main (and develop on request) into release-1 every day (section 10).

`core.yml` and `afrotransact-go.yml` have **no `paths:` filter** on their triggers (a required check of a workflow that did not start stays pending and blocks the merge): their
`meta` job decides whether the change touches an input of the pipeline and the heavy jobs are skipped otherwise (a skipped job counts as passing).

**Every other workflow on release-1** (classified one by one in `tools/release/parity.yaml`, checked by `parity.py`):

| Workflow | On release-1 |
|---|---|
| `java-<service>.yml`, `go-<service>.yml`, `_java-service.yml`, `_go-service.yml` | per-service, trigger on **main and develop only**: release-1 builds the two images |
| `backlog`, `contracts`, `docs`, `env-urls`, `kafka-topics`, `legal-documents`, `observability-config`, `security`, `seed-guard`, `storybook`, `tofu`, `tool-kafka-replay`, `workflows` | validate release-1 pushes too (`branches: [main, release-1]`) and pull requests into it |
| `web-*` (generated) | preview on pull requests, dev on pushes to main **and** release-1 (one concurrency group), prod only by dispatch and only of a commit release-1 contains |
| `mobile` | OTA preview from release-1; a production store build only of a commit release-1 contains |
| `promote` (generated) | runs only from release-1; the tag's commit must be on release-1 (deployables) or main/release-1 (a per-service image) |
| `_deploy` | pins of the deployables **and of every uat, trial and prod promotion** are committed to release-1 (Argo CD tracks it); a per-service dev pin stays on the GitOps branch (main) and arrives with the sync |
| `uat-unverified-image`, `carrier-sandboxes` | manual only; `uat-unverified-image` builds an unverified image for UAT only (go-live section 4.3) |

Verify a published image by hand:

```bash
cosign verify ghcr.io/<owner>/<repo>/afrotransact-core:<full-sha> \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  --certificate-identity-regexp '^https://github\.com/<owner>/<repo>/\.github/workflows/core\.yml@refs/heads/release-1$'
```

## 4. Database and secrets for a new environment

1. Bootstrap the Postgres (once, then whenever a module is added): `ovh-production.md` section 5. In short:
   `PGHOST=... PGUSER=<admin> PGPASSWORD=... PGSSLMODE=require STATE_DIR=$HOME/.afrotransact-prod infra/ops/postgres/release-1-bootstrap.sh`
   (or `DB_LAYOUT=schema infra/tofu/scripts/postgres-bootstrap.sh`, or `DB_LAYOUT=schema ... infra/ovh-vps/secrets.sh databases`: all three run the same SQL). It creates the roles **without a password**, sets
   the passwords from `STATE_DIR/generated.env` (`DBPW_<schema>`, generated once, 0600, never printed) and runs `release-1-verify.sql`, which fails if a role is too powerful, a connection limit
   differs, a `search_path` is missing, **one module role has any right on another module's schema**, or an extension is absent.
2. Render the Secrets with `DB_LAYOUT=schema` (`PROFILE=prod ... infra/ovh-vps/secrets.sh services`). Besides the per-service Secrets (their `DB_URL` now points at the module's schema in `core` or `go`,
   so an old Application that has not moved, and a rollback, use the same tables) it renders `afrotransact-core-secrets` (`CORE_DB_URL`, `<MODULE>_DB_USER`, `<MODULE>_DB_PASSWORD`, every client secret and key)
   and `afrotransact-go-secrets` (`<MODULE>_DB_URL`, ...). Encrypt with SOPS and apply as before (`infra/ops/secrets/README.md`).
3. Local: a fresh `make up` volume creates both databases (`infra/local/postgres/20-release-1.sh`); an existing volume: `make db-release-1`. Roles are named after the module, password = the name.

Data that already lives in per-service databases (dev, uat, or the trial) is **not** moved by any of this. Prod has none (it was never deployed). To bring a dev database over: in a scratch copy of the old database run
`ALTER SCHEMA public RENAME TO <schema>`, `pg_dump -Fc --no-owner`, then `pg_restore --no-owner --role=<schema>_app -d core -n <schema>` into the empty schema made by the bootstrap and run `release-1-verify.sql`.
**Untested**: rehearse it on the trial before relying on it.

## 5. Deploy

**dev**: automatic. Every green push to release-1 pins the new digest in `values-dev.yaml` and Argo CD syncs `afrotransact-core-dev` / `afrotransact-go-dev`.

**uat and prod**: promote a signed build, then sync by hand (prod is manual, `argocd.autoSync: false`).

1. Actions > `promote` > service `afrotransact-core` (or `afrotransact-go`), environment `prod`, tag = the **full sha** of a green release-1 build. `prod` waits for its reviewers; the workflow verifies the signature
   and commits `image.tag` and `image.digest` to `values-prod.yaml` on release-1. (`trial` has its own pin file, `values-trial.yaml`.)
2. `python3 infra/argocd/scripts/check_launch_set.py --env prod --require-tags` (every deployable pinned by tag and digest).
3. `argocd app diff afrotransact-core-prod`, then `argocd app sync afrotransact-core-prod`. A rolling update runs two core pods for about a minute (`maxSurge 25 %`, `maxUnavailable 0`): 2 x 48 connections and 2 x the current memory request (fits while the request follows the batch schedule, `cloud-cost.md`).

A deployable whose `cutover.services` has no entry for it renders **replicas 0**: the Application is Healthy and costs nothing until its first batch.

## 5a. Upstream wiring (who calls whom, outside the local profile)

Only the **local** profile serves the old per-service ports (`at.core.module-ports`, 8100-8115). In dev, uat and prod nothing listens on them, and a module that moved into a deployable
has no per-service host unless an alias Service exists. So every module-to-module URL is rendered by the chart, from **one list per deployable**: values `upstreams` in
`java/apps/core/deploy/helm/values.yaml` (what core calls) and `go/apps/afrotransact-go/deploy/helm/values.yaml` (what the Go modules call), rendered by
`infra/helm/library/at-service/templates/_upstreams.tpl`. An entry is `ENV_NAME: <module>` (or `{module, suffix}`), and the value follows `cutover.services` of the environment:

| Target module | URL rendered | Why |
|---|---|---|
| Java module that moved into core, called from the **Go** deployable | `http://afrotransact-core.<ns>.svc/modules/<module>` | core serves each module under `/modules/<m>`; this also picks the right module for the three duplicate routes `POST /v1/returns`, `/v1/receipts`, `/v1/reconciliation/runs` (payments reconciliation must be `/modules/payments`) |
| Java module in core, called from **core** (the Java -> Java edges that are still HTTP) | `http://localhost:8080/modules/<module>` | loopback on core itself (`service.port`): never the Service, never an old port. **This list shrinks** as the edges become in-process: a name moves to `converted` in `tools/release/upstreams.yaml` once every consumer is in-process |
| Go module that moved into `afrotransact-go`, called from core | `http://afrotransact-go.<ns>.svc` (flat: geo, media, notifications, promise) or `.../modules/<m>` (prefixed: cart, search, carrier-gateway) | the Go host mounts those three only under `/modules/<m>` (their paths collide with gateway-bff's facade) |
| Go module called from another Go module | nothing rendered | in-process (`modcfg`), as long as the target runs in the same binary |
| A module that **has not moved yet** | `http://<module>` (its own per-service Service, port 80) | a batch must not break the modules still running standalone; the URL changes when the module's `cutover.services` line is added |

Nothing else changes a URL; there is no second place to edit. `AUTH_TOKEN_URL` (client-credentials token endpoint) follows identity the same way; `AUTH_ISSUER_URL` stays the
public https issuer (JWT validation and `McpExposureGuard` need https; the library derives it from `global.authHost`). `MCP_PUBLIC_URL` is core's own Service,
`http://afrotransact-core.<ns>.svc.cluster.local/modules/mcp-server` (an in-cluster URL the guard accepts, so it does not depend on an alias Service).
`extraEnv` can still override a name by hand; `make release-1-check` fails when a values file does.

**Alias Services (old names) are the rollback path only.** `cutover.movedTo` keeps `http://catalog` alive after the move by selecting the deployable's pods on port 80 (verified:
selector `app.kubernetes.io/name: <deployable>`, `targetPort: http` = the container's port 8080, management port 9090 named, the old chart's ServiceMonitor is no longer rendered so
nothing is scraped twice). They are **not** a valid upstream: a **flat** call through one works (geo, media, notifications, identity, payments for everything but the three duplicate routes), a call
through the alias of a **prefixed** Go module (`http://cart`, `http://search`) reaches gateway-bff's facade, not the module, and `http://payments` + `POST /v1/reconciliation/runs` reaches
the first module that maps it (inventory). The only things that still use alias names are the public HTTPRoutes (`api` -> `gateway-bff`, `/identity`, `/bookings`, `/fulfilment`, `/media`, `/notifications`,
`/payments/v1/webhooks`, `/seller/v1/webhooks/*`; `auth` -> `identity`), which keep working before and after a cutover for exactly that reason, so **a per-service Application must exist for every
Service the routes name until the cutover phase**. Rolling a batch back is therefore always: remove the `cutover.services` line (URLs go back to the standalone Services by themselves).

**Exposure.** A moved Service selects a deployable's pods, which serve **every** module of the deployable, not only the one the route was written for. The route files
(`infra/k8s/platform/edge/overlays/<env>/routes.yaml`) therefore deny `<prefix>/modules` on every prefix, `<prefix>/mcp`, `/v1/tools` and `/.well-known/oauth-protected-resource` on the prefixes that land on core,
and `/modules`, `/mcp`, `/v1/tools`, `/.well-known/oauth-protected-resource` on the auth host. `mcp-server`, core and the Go deployable have no route at all (private ClusterIP, OAuth bearer tokens). **Residual risk
(owner decision):** the other modules' routes (for example `GET /v1/orders` under `/bookings/...`) are still reachable on those prefixes with a valid token and the module's own permission checks; an allow-list per prefix
(from `contracts/openapi/<module>.yaml`) or a second listener for the public modules would close it.

**Checks.** `make release-1-check` runs `tools/release/upstreams.py` (unit tests: `test_upstreams.py`): it scans every `application*.yml` and Go config for URL settings whose default is a platform service
(`--scan` lists them with their consumers), renders both charts for uat and prod in three cutover states (committed, complete launch set, batch 1) and fails when a `*_URL` points at an old port
(81xx/82xx), at `:8080` of a Service, at a per-service host of a module that moved, at a deployable for a module that did not move there, without `/modules/<m>` where it is required, or is missing for an enabled module's
edge; it also checks the routes above (private Services, rule limit) and `MCP_PUBLIC_URL`. With `helm` on the PATH it renders with `helm template` (CI: the `helm` job of `core.yml` runs it through
`tools/release/test_cutover_render.sh`); without, it uses an emulation of the template.
`python3 tools/release/upstreams.py --render core prod` prints what the complete launch set renders.

**Set by the owner in the cluster:** nothing new for the URLs. Before the first cutover of a module that the Go deployable calls (everything but `geo`, `media`, `notifications`), check `kubectl -n afrotransact-<env> get svc afrotransact-core afrotransact-go`
(created by the deployable charts at replicas 0 already).

## 6. Cutover, one batch at a time

A batch is a few modules that move from their own Deployments into a deployable (batches: `release-1.yaml`, `batches`). **Nothing is deleted**: the old Deployment is scaled to 0 and its Service keeps its name and
now selects the deployable's pods (the rollback path and what the public HTTPRoutes use); callers do not rely on it: their URLs are rendered from `cutover.services` (section 5a).

**Before the batch (once per batch)**

1. The deployable's image is pinned and healthy in this environment (section 5), with the modules of earlier batches running. **Set core's memory request for this batch** in `java/apps/core/deploy/helm/values-prod.yaml`
   (`resources.requests.memory`: batch 1 **768Mi**, batch 2 1024Mi, batch 3 1280Mi, batch 4 on 1536Mi; the limit stays 2048Mi). With the old pods still up, 1,536 Mi twice (the rolling-update surge) does not fit the ~13 GiB node (`cloud-cost.md`).
2. The module's schema exists and is verified (section 4) and **its data is there**: in prod from the start (schema layout), in dev/uat after the move in section 4.
3. The module's secrets are in `afrotransact-core-secrets` (check the key names for the module: `<MODULE>_DB_USER`, `<MODULE>_DB_PASSWORD`, `<X>_CLIENT_SECRET`, third-party keys).
4. `tools/release/route_diff.py` is green in CI for the module and `make release-1-check` is green.

**The change (one pull request, reviewed)**

5. In `infra/argocd/launch-set/release-1.yaml` add the modules under `enabled.<env>.<deployable>` **and** add `service: deployable` lines to `cutover.services` in
   `infra/argocd/envs/<env>/platform-values.yaml` (for example `catalog: afrotransact-core`). Do one environment per pull request, dev first, then uat, then prod.
6. `make launch-set-check` must print, for the environment, `catalog-<env>   scaled to 0, Service routed to afrotransact-core` and `afrotransact-core-<env>: runs 1 module(s) catalog`, and end with `OK`
   (a mismatch between the two files fails it).

**Apply (the order matters: never two writers on one schema)**

7. dev and uat: merge; Argo CD syncs both Applications. Argo applies them in the same wave, so a few seconds of overlap are possible: the old pod's and core's schedulers and outbox relays may both run once, and the
   Kafka consumers share the old group ids (so they split partitions rather than duplicate). If you are not sure a module tolerates that, use the prod order below there too.
8. **prod (manual, in this order):** (a) `argocd app sync <module>-prod` for each moved module: its Deployment goes to 0 and its Service now selects core pods; (b) `argocd app sync afrotransact-core-prod`: the module's
   env switch (`MODULE_<NAME>_ENABLED=true`) is rendered and core rolls. The module is unavailable between (a) and core being Ready again (target under 60 s, ADR 0011 S2). For an urgent order
   path do it in a quiet hour: this is the same downtime class as any single-replica deploy on this cluster (ADR 0004).

**Verify (do not skip; 15 minutes)**

9. `kubectl -n afrotransact-<env> get deploy` shows the old Deployment at 0/0 and core 1/1; `kubectl -n afrotransact-<env> get svc catalog -o jsonpath='{.spec.selector}'` says `afrotransact-core`.
10. Readiness: `kubectl -n afrotransact-<env> port-forward deploy/afrotransact-core 9090` then `curl -s localhost:9090/actuator/health/readiness` lists the module UP; `/actuator/health/<module>` adds its database.
11. Dashboard **AfroTransact / Release 1 modules**: the module is `UP`, its pool is not saturated, p95 and 5xx by module are as before the cutover. Alerts `ModuleNotReady`, `CoreMemoryNearCap` quiet.
12. Kafka: consumer group lag of the module's groups (`<module>-service`) is 0 or falling (`kafka-consumer-lag` board): the group ids are the old ones, so offsets carried over.
13. Outbox: `outbox_pending_events` is not growing (the module's relay runs in core).
14. One real journey through gateway-bff that touches the module (`tools/smoke`, or by hand: the smoke test of `ovh-production.md` section 10 for the batch's modules).
15. Memory: `kubectl top pod -l app.kubernetes.io/name=afrotransact-core` after 30 minutes. **If a batch adds more than 80 MB RSS per module or core startup passes 60 s, stop and re-plan** (ADR 0011 review rule).

Keep a batch in place for at least a week (checkout SLO unchanged for the money path) before the next one. Do not delete the old chart, workflow or Application: only the cutover phase of ADR 0011, after two release cycles
without a rollback, may.

## 7. Roll back

**The normal way (git, reviewed):** remove the `service: deployable` line(s) from `cutover.services` and the module from `release-1.yaml`, run `make launch-set-check`, then **in this order**: (a) sync
`afrotransact-core-<env>` (the module switches off and core rolls), (b) sync `<module>-<env>` (replicas back to the chart's value and the Service selects its own pods again). Nothing is migrated back: the old
service and core use the **same schema, the same Flyway history table, the same outbox rows and the same consumer group ids**.

**Emergency, Argo CD out of the loop** (a bad core, prod is manual so Argo will not undo this, but it will show OutOfSync until git catches up):

```bash
NS=afrotransact-prod
kubectl -n $NS scale deploy/afrotransact-core --replicas=0                      # stop the writers first
kubectl -n $NS scale deploy/catalog --replicas=1                                # the old Deployment still exists
kubectl -n $NS patch svc catalog -p '{"spec":{"selector":{"app.kubernetes.io/name":"catalog"}}}'
kubectl -n $NS scale deploy/afrotransact-core --replicas=1                     # only if other modules must keep running,
kubectl -n $NS set env deploy/afrotransact-core MODULE_CATALOG_ENABLED=false  # with the moved module switched off
```

then make git say the same thing (the normal way above). A bad **image** (not a module) is the usual `argocd app rollback` or re-promoting the previous digest.

## 8. What to watch

| Where | What | Healthy |
|---|---|---|
| Dashboard `at-release-1-modules` | modules ready, RSS against the 2 GiB line, heap, one Hikari pool per module and its schema usage, connection budget against 500, Kafka lag per module, scheduled jobs per module, Temporal workflow outcomes, requests / 5xx / p95 by module | all modules UP; RSS under 1.8 GiB; no pool above 90 %; Sum of pool maximums 48; worst case far under 500 |
| `ModuleNotReady` (page) | `at_module_up{module}` is 0 for 3 minutes (Java and Go modules) | silent. `ModuleReadinessMetricMissing` (ticket) fires if a deployable is up but the series does not arrive |
| `ConnectionBudgetNearLimit` (ticket) | worst-case connections (Java pool maximums + 12 per Go pod + 20) above 400 of 500 | silent; the dashboard row "Connection budget vs the Layerbase limit" shows it |
| `ServiceDown` (page) | now also `afrotransact-core`, `afrotransact-go` | silent |
| `HighErrorRate`, `HighLatency`, `DatabasePoolSaturation`, `OutboxBacklogGrowing` | unchanged: they select on `service`, which stays the module's short name | silent |
| `CoreMemoryNearCap`, `CorePoolBudgetExceeded`, `DeployableRolloutOverlap` (ticket) | RSS above 1.8 GiB for 10 min; pools above 48; two pods for 20 min | silent |
| `ContainerOOMKilled`, `PodRestartingFrequently` | one OOM restarts **every** Java module at once | silent |
| Logs (Loki) | filter the core pod by the `module` field (MDC `module` on HTTP requests; Kafka and scheduler threads do not set it yet, see release-1-architecture.md 11.3) | |
| Postgres | `select usename, count(*) from pg_stat_activity group by 1 order by 2 desc` on `core` and `go`: each role below its `CONNECTION LIMIT` (2 x pool + 2); the sum for core stays under 48 per pod | |

A process-wide change in the rules: process series (`jvm_*`, `process_*`, `up`) carry `service=afrotransact-core`; the old JVM board lists `afrotransact-core` as one service and its Hikari panels filter on
`service`, so use the Release 1 board for per-module pools. Alloy scrapes the deployables with `honor_labels` (the app's `service` and `module` win) and a 40,000 sample limit per target.

## 9. Connections

Layerbase: **up to 500** (owner; UNVERIFIED in writing). One pod's pools (`values.yaml` `modules.<name>.pool`, budget guard in the chart template, `tools/release/check_delivery.py` keeps the SQL, the charts and this table equal):

| Deployable | Pools (maximum, per pod) | Sum | With a rolling-update surge (2 pods) | Budget |
|---|---|---|---|---|
| core | catalog 8, orders 6, payments 5, identity 4, tax_returns 4, offers 3, inventory 3, seller 3, fulfilment 3, bookings 3, trust_ops 2, content 2, ai_gateway 2 | **48** | **96** | under 100 |
| go | cart 3, geo 3, media 3, notifications 3 (capped in code, `maxPoolConns`) | **12** | **24** | under 50 |
| Temporal | 2 databases x 5 | 10 | 10 | |
| Backup job, operators | | 10 | 10 | |
| **Total** | | **80** | **140** | 500 (about 28 %); the old 19-database plan was 74 steady, about 90 at peak |

`minimum-idle` is 0 for Java (connections are opened on demand), so the real count is lower. Each role's `CONNECTION LIMIT` (2 x pool + 2) makes the budget a hard limit: a pool misconfigured upwards fails with
"too many connections for role" in that module only. To change a pool: edit `modules.<name>.pool` in the chart **and** the number in `release-1-bootstrap.sql` (and `release-1-verify.sql`), run `make release-1-check`,
re-run the bootstrap.

## 10. Keeping main in release-1

**Automatic.** `release-1-sync.yml` (daily 04:41 UTC and by hand; `include_develop` for develop) runs `tools/release/sync-open-pr.sh`: it cuts `sync/main-<sha>` from release-1, merges main with
`tools/release/sync-main.sh --ci`, runs the release-1 CI on it and opens a pull request titled with the commits it carries (a **draft** while parity has gaps). Mechanical conflicts are resolved by the script:
`docs/backlog/backlog.csv` by row (release-1 stories S600-S699, renumbered on an id collision), generated workflows by regenerating them. Anything else opens **one issue** (label `release-1-sync`) listing
the files, and nothing is pushed. It never pushes release-1, main or develop and never forces. Review and merge the pull request like any other; parity must be green.

**By hand** (a release-1 worktree whose branch tracks release-1): `tools/release/sync-main.sh --check`, then `tools/release/sync-main.sh` (`--ref origin/develop` for develop), then
`git push origin HEAD:release-1` (or push a branch and open the PR). `tools/release/setup-merge-driver.sh` (once per clone) makes plain `git merge`, `rebase` and `cherry-pick` merge the backlog by row too.
Delivery files that main also edits and where a merge can conflict: the **generated workflows** (regenerate with `python3 tools/ci/gen_workflows.py`, never by hand), `java/Dockerfile` (keep both: the `PROJECT` and
`JAR_GLOB` arguments), `infra/argocd/applicationsets/*.yaml`, the env kustomizations and platform-values (keep both sides), `infra/k8s/platform/lean/backup/pg-backup.sh` (release-1 default list is the 4 databases),
`Makefile`. The guard workflow turns red when release-1 falls behind.

## 10a. Branch model

* **release-1 is the production line** (the modular monolith, ADR 0011). Argo CD, the promote workflow, the pins, prod web and mobile releases all follow it. Make it the GitHub default branch (section 10b).
* **main and develop mirror it for the microservice option.** They keep the per-service build and are merged INTO release-1 (never the other way) by the sync pull request. Nothing on main or develop is
  done until release-1 parity is green ([release-1-parity.md](../engineering/release-1-parity.md)): that is what `release-1-parity.yml` and `tools/release/parity.py` verify.
* **Features** start on main or develop as before (per-service pipelines, per-service charts), reach release-1 by the sync, and are done when parity is green. A feature that only makes sense in the monolith
  (module wiring, core, `go/apps`) is made on release-1 directly.
* **A hotfix** is made **on release-1** (a branch from release-1, a PR into it, deployed by promote). Then it is ported back so the lines never drift:
  ```bash
  tools/release/port-back.sh                       # lists release-1 commits that are not on origin/main (skips merges, bot pins, release-1-only paths)
  tools/release/port-back.sh <sha>... [--onto origin/develop]   # a branch port/main-<sha> from origin/main with the commits cherry-picked (-x), never pushed
  git push -u origin port/main-<sha> && gh pr create --base main ...   # the script prints the exact commands
  ```
  A commit that touches only release-1-only paths (`java/apps/core`, `java/libs/module-*`, `go/apps`, the deployable charts, `tools/release`) is not ported; one that mixes both is listed with a warning.
  When the ported PR merges, the next sync finds the change on both sides (identical patch) and carries nothing for it.
* **Going back to microservices** (if the owner decides): remove `infra/argocd/release-branch` from the env kustomizations, set `argocd.targetRevision` and the root Applications back to `HEAD`/main, make main the default
  branch. Nothing is deleted on either line until the cutover phase of ADR 0011.

## 10b. Settings the owner changes in GitHub (nothing here was changed by the pipelines work)

| Where | Setting | Why |
|---|---|---|
| Settings > General > Default branch | **`release-1`** | scheduled workflows (`release-1-sync`, `release-1-guard`, `security`, `seo-probe`, `reproducible-build`) and the `workflow_dispatch` buttons use the default branch's copy of the file; new PRs, Dependabot and `CODEOWNERS` follow it. If you keep `main` as default instead, copy `release-1-sync.yml` and `release-1-guard.yml` to main |
| Settings > Actions > General > Workflow permissions | enable **Allow GitHub Actions to create and approve pull requests** (default token rights can stay read-only: the job asks for its own) | the sync workflow opens its pull request with `GITHUB_TOKEN` |
| Repository secrets | `GITOPS_APP_ID`, `GITOPS_APP_PRIVATE_KEY` of the GitOps app, with **Contents, Pull requests, Issues, Actions and Workflows: write** on the app | `release-1-sync` then uses a narrowed app token: needed whenever main changes `.github/workflows/**` (GITHUB_TOKEN cannot push workflow files; without the app the job opens an issue instead) and it makes the PR's checks start by themselves |
| Branch protection / ruleset on `release-1` | require a pull request; **required checks** (always report): `release-1-parity / parity (what main and develop have, the monolith has)` and `release-1-parity / delivery (workflows, charts, Argo CD, secrets renderer)`; and, now that the workflows always start, `core / verify (checkstyle, unit, ArchUnit, integration, route diff)`, `core / helm (lint, template, kubeconform, cutover and budget tests)`, `core / image (build)`, `core / security (gitleaks, trivy fs)`, `afrotransact-go / verify (lint, vet, unit, integration)`, `afrotransact-go / helm (lint, template, kubeconform, cutover and budget tests)`, `afrotransact-go / image (build)`, `afrotransact-go / security (gitleaks, trivy fs, govulncheck)` (copy the exact names from the first run; the `image` name changes on a push); **not** `release-1-guard` (red by design on the sync PR); the GitOps app is a bypass actor only for the pin commits | merge only green; the part after the slash is the job's `name:` |
| Branch protection on `main` and `develop` | keep as is; the sync never pushes there | |
| Environments `dev`, `uat`, `trial`, `prod` | `prod` keeps required reviewers; variables `CLOUD=ovh`, `REGISTRY_URL=ghcr.io/<owner>/<repo>`; `GITOPS_APP_*` secrets allowed on all four; deployment branch rule of `prod` and `trial`: **`release-1`** | promote and `_web-app` prod deploys come from release-1 only |
| Packages | link `afrotransact-core` and `afrotransact-go` to the repository (Write) after the first push | `GITHUB_TOKEN` pushes the images |

## 11. Known gaps (honest list)

- Upstream wiring (section 5a): the library template and both deployable charts were executed with Go's `text/template` + sprig (Helm's engine) and cross-checked against `tools/release/upstreams.py`'s emulation, never with a real `helm template`, `kubeconform` or a cluster. `AUTH_TOKEN_URL` now points at identity's Service/loopback instead of the public issuer hairpin (first deploy: confirm client-credentials tokens are issued).
- Nothing ran on a cluster. `helm` (lint, template, kubeconform), `kustomize` and `actionlint` were run offline on 2026-11-16 (see the status line); `tofu validate` was **not**. The SQL, the shell scripts (shellcheck),
  the route-diff tests, the Argo CD emulation and the secrets rendering (stub `kubectl`) were.
- `core.yml` needs the Java side's final environment names; it uses `CORE_DB_URL`, `CATALOG_DB_USER`, `TAX_RETURNS_DB_USER` as `application.yml` has them today and the per-module test databases `catalog` and `taxreturns` that `CoreRun` still uses.
- `at_module_up` is now exported by both deployables (module-core `ModuleUpMeter`, afrotransact-go `host/metrics.go`) and unit-tested, but it was never scraped from a running stack: confirm it with `count by (job) (at_module_up)` after the first deploy (section Monitoring below).
- The Go pool cap is in code; the chart's `pool` is a budget figure. A lower value goes in `pool_max_conns` of the module's URL.
- Fixed 2026-11-16: `infra/ovh-vps/secrets.sh` split a multi-line value (`SIGNING_KEY_PEM`) into one junk key per line (identity's Secret and `afrotransact-core-secrets`), and put every value in
  kubectl's argv. Records are now NUL-terminated and values go through 0600 files (`--from-file`); `tools/ovh/test/secrets-multiline.test.sh` proves it with a throwaway PEM and a stub kubectl.
  **A Secret already applied from the old script has junk keys: re-render and re-apply it before the first prod sync.**
- Open item for the orders batch: `uat` runs orders with the profile `uat,temporal-cloud`; a deployable cannot switch a profile for one module. Its `TEMPORAL_*` values are copied into the core chart; check that
  the settings of `application-temporal-cloud.yml` reach core in uat before orders moves there (`parity.yaml` `config.env_skip.SPRING_PROFILES_ACTIVE`).
- The non-secret settings of every module (peer URLs, zones, TTLs) were missing from both deployable charts until 2026-11-16 (core would have used the localhost defaults of each `application.yml`): they are generated
  into the charts now (`tools/release/chart_env_sync.py`) and `parity.py config` keeps them current.
- `generate_rules.py --check` used to report `r2-backup.yaml` as a missing generated file on this branch: the PrometheusRule CR is committed now (`make observability-check` is green).

## 12. Monitoring in production (Release 1)

Production monitoring is for the two deployables. What changed against the per-service layout, and what did not:

| Topic | Release 1 | Where |
|---|---|---|
| Scrape | **Alloy** (`infra/k8s/platform/lean/alloy/config.alloy`) scrapes the two pods through the job `deployable_pods`: one target each, `honor_labels = true` (the app's `service` and `module` win), 60 s, `sample_limit = 40000`; the old per-pod job drops them. Local Prometheus does the same (`infra/observability/prometheus.yml`) | Alloy -> Grafana Cloud hosted Prometheus (`remote_write`, `env` and `cluster` external labels) |
| Labels | HTTP, Hikari pool, Kafka client, business meters: `module` = `service` = the module's short name (`orders`, `cart`). Process series (`jvm_*`, `process_*`, `go_*`, `up`): `service` = `job` = `afrotransact-core` / `afrotransact-go` | `module-core` `ModuleMeterFilter`; Go `host/metrics.go` |
| Readiness | `at_module_up{module,service}` 1/0, both deployables; alert `ModuleNotReady` (page, 3 min) | allowlisted in Alloy as `at_module_(up|ready)` |
| Allowlist additions | `tasks_scheduled_execution_seconds_(count|sum|max)` (scheduled jobs per module, from the owning class's package) | Alloy `prometheus.relabel "guard"` |
| Alert routing | unchanged: `infra/observability/grafana-cloud/alertmanager.yaml` groups by `alertname, env, service`; module alerts carry `service` = module, so two modules down page separately. `page` severity goes to the paging contact point, everything else to tickets | Grafana Cloud contact points |
| SLO rules | valid with the merged series: they aggregate `by (env)` over meters whose names are unchanged (`checkout_attempts_total`, `payments_webhook_lag_seconds`, `payouts_completed_total`, `notifications_delivery_duration_seconds`) or select `service="search"` (the Go search module keeps its `service` label). `make observability-check` verifies every `service=` value in rules and dashboards is a real module | `infra/observability/rules/slo-*.yaml` |
| Traces | OTLP from both deployables to the collector gateway, then Grafana Cloud; `service.name` is `afrotransact-core` / `afrotransact-go`, the module is a span attribute | `opentelemetry-collector-gateway.yaml` |
| Logs | Alloy tails pod logs, labels only `namespace`, `app` (= the deployable), `pod`, `env`; the module is a JSON field: `{app="afrotransact-core"} \| json \| module="orders"` | `config.alloy` `loki.process "pods"` |
| Dashboards | `at-release-1-modules` (new panels: schema pools, connection budget vs 500, Kafka per module, scheduled jobs, Temporal), `at-jvm` (Hikari/producer/outbox filter by `module`), `at-platform-overview` (services = modules; fleet panels use the deployables) | `make dashboards` (generator `scripts/generate_dashboards.py`) |
| New alerts | `ModuleReadinessMetricMissing` (ticket), `ConnectionBudgetNearLimit` (ticket, above 400 of 500) | `infra/observability/rules/release-1.yaml` |

**Gaps (nothing below could be run without a stack):**

- Scheduler **pool** occupancy per module (queue length, active threads of `sched-<module>-n`) is not exported: the per-module `ThreadPoolTaskScheduler`s of `ModuleRoutingTaskScheduler` are not registered as executor meters. The dashboard shows job runs and duration per module instead. Closing the gap is a few lines (`ExecutorServiceMetrics` per pool, tag `module`); it was left out on purpose (the only code change allowed was `at_module_up`).
- **Go database pools** (pgx) export nothing; the connection budget uses the capped figure (4 modules x 3 per pod). Temporal worker (SDK) metrics are not exported by the Java side (no `temporal-micrometer`); the board shows the Temporal server's workflow counters.
- `tasks_scheduled_execution_seconds_*` assumes Spring's scheduled-task observation is on in core (it is on whenever an `ObservationRegistry` bean exists); unverified.
- Core's raw exposition (14 modules, histograms) may be larger than the 40,000-sample limit (a scrape above it fails and `up` becomes 0): check `scrape_samples_post_metric_relabeling{job="afrotransact-core"}` after the first deploy. `ScrapeTargetCardinalityHigh` now warns at 30,000 for the two deployables (3,000 for every other target).
- Postgres server metrics (`pg_stat_activity_count`) exist locally (postgres-exporter) but not on Layerbase; production connection counts are the sum of the Hikari series plus the static terms.
