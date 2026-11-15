# Release 1 deploy: build, deploy, cut over per batch, roll back, watch

Status: **written, never run against a live cluster.** Everything here was checked offline (workflow YAML, shellcheck, the SQL on a scratch
PostgreSQL 16, the Argo CD emulation in `infra/argocd/scripts`); `helm`, `kubeconform`, `kustomize`, `actionlint` and `tofu` were not
available where this was written, so every `helm template` claim is read from the templates, not rendered. The first run of `core.yml`
and of the first cutover will find things: treat the trial (`ovh-production.md`, "Trial") as the rehearsal. Vendor facts are tagged
**UNVERIFIED** as elsewhere in this repository.

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
| Secrets rendering | `infra/ovh-vps/secrets.sh` with `DB_LAYOUT=schema` |
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
3. **Branch protection on `release-1`:** require the check of `release-1-guard` and the `verify`, `helm` and `image` jobs of the workflows `core` and `afrotransact-go` (GitHub names a check `<workflow> / <job name>`; copy the exact names from the first run).
   The GitOps app must be a bypass actor (it pushes pin commits), as it is on main today.
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
| `image` | one multi-arch image, image policy (non-root, port 8080, `SPRING_PROFILES_ACTIVE=prod`, size budget **420 MB: a guard rail to tighten**), Trivy image scan, SBOM, **cosign keyless sign** | pushes only on a push to release-1; pull requests build without pushing |
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

## 6. Cutover, one batch at a time

A batch is a few modules that move from their own Deployments into a deployable (batches: `release-1.yaml`, `batches`). **Nothing is deleted**: the old Deployment is scaled to 0 and its Service keeps its name and
now selects the deployable's pods, so no caller's URL (`http://catalog`, `http://geo`) changes and gateway-bff does not notice.

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
| Dashboard `at-release-1-modules` | modules ready, RSS against the 2 GiB line, heap, one Hikari pool per module, pending threads, requests / 5xx / p95 by module | all modules UP; RSS under 1.8 GiB; no pool above 90 %; Sum of pool maximums 48 |
| `ModuleNotReady` (page) | `at_module_up{module}` is 0 for 3 minutes | silent (series UNCONFIRMED until the Java side exports it) |
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

`tools/release/sync-main.sh` (never pushes), then `git push origin HEAD:release-1`. Delivery files that main also edits and where a merge will conflict: the **generated workflows** (resolve by re-running
`python3 tools/ci/gen_workflows.py`, never by hand), `java/Dockerfile` (keep both: the `PROJECT` and `JAR_GLOB` arguments), `infra/argocd/applicationsets/*.yaml`, the env kustomizations and platform-values
(keep both sides), `infra/k8s/platform/lean/backup/pg-backup.sh` (release-1 default list is the 4 databases), `Makefile`. The guard workflow turns red when release-1 falls behind.

## 11. Known gaps (honest list)

- Nothing ran on a cluster. `helm template`, `kubeconform`, `kustomize`, `actionlint` and `tofu validate` were **not run** (not installed in the authoring environment); the SQL, the shell scripts (shellcheck), the route-diff tests,
  the Argo CD emulation and the secrets rendering (with a stub `kubectl`) were.
- `core.yml` needs the Java side's final environment names; it uses `CORE_DB_URL`, `CATALOG_DB_USER`, `TAX_RETURNS_DB_USER` as `application.yml` has them today and the per-module test databases `catalog` and `taxreturns` that `CoreRun` still uses.
- `at_module_up` and the per-module Spring health groups are the Java side's to export; the rules and panels read them and stay silent until they exist.
- The Go pool cap is in code; the chart's `pool` is a budget figure. A lower value goes in `pool_max_conns` of the module's URL.
- Pre-existing, found on the way: `infra/ovh-vps/secrets.sh` splits a multi-line value on newlines (`SIGNING_KEY_PEM` is a PEM): `service_keys` builds `KEY=VALUE` lines and `mapfile` re-reads them per line, which
  yields junk keys for identity's Secret. Check the identity Secret's keys before the first apply (this also affects `afrotransact-core-secrets`, which is the union).
- `generate_rules.py --check` reports `r2-backup.yaml` as a missing generated file on this branch too: run `make rules` once on main.
