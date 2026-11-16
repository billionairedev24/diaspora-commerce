# Branch diff audit: main / develop vs release-1

`main`, `develop` and `release-1` are functionally identical; only the architecture differs (CLAUDE.md, "One product, three branches").
This is the audit of that rule: every release-1 commit that main lacks, classified and ported, and every remaining file difference with
its class and reason. `main` and `develop` pointed at the same commit during the audit. Repeat it with the commands in section 5.

Classes: **architecture-only** (differs because release-1 runs the modules in two deployables), **intentional** (differs on purpose, same
behaviour), **sync-pending** (main is ahead of release-1: the next `release-1-sync` brings it; not a gap), **TODO** (a functional gap with a
fix or a backlog story).

## 1. release-1 commits not on main

`git log origin/main..origin/release-1 --no-merges` listed 126 commits; `git cherry` showed 3 of them already on main by patch id
(`3658350ba` identity first-party sign-in, `59293bd64` storefront `/sell` plain link, `2e07d545b` seed README). The other 123:

| Class | Commits | Result |
|---|---|---|
| FUNCTIONAL, ported | `0181c6f35` local checkout fault injection (`LocalCheckoutFaults` + test), `5e64e7934` (the `orders.yaml` hunk; `route_scan.py` is release-1 only), `fc3cee1c3` (the `r2-backup` PrometheusRule hunk; `at_module_up` is release-1 only), `fc3641685` secrets.sh multi-line values (the per-service half; the `afrotransact-*-secrets` half is architecture), `5cd94e648` (the optional channel, carrier and `OSRM_URL` secrets hunks of `secrets.sh`; the parity check and backlog merge are architecture) | cherry-picked with `-x` or split by hunk, see section 2 |
| FUNCTIONAL, already on main under another patch id | `86bbece6f` `2d1cbd66c` `06c633e10` staff invitations (main has `df27ada28`, `48faea70a`, `897368a9e`, and the gateway-bff routes), `fbb75246d` `66936307d` `48df8d7e5` `5a8779fc3` seed publish gate, smoke, seed ops, `7d0cd8723` seller contract (main `57c8840fa`), `cb228bf0f` identity matrix row (file identical), `d3a6e2faa` CLAUDE.md rule (main `9cb5df6a5`), `c667aa7bc` docs and CI (the `security.yml` and `backlog.yml` fixes were ported by the lead) | verified by file content (section 3): nothing to do |
| ARCHITECTURE-ONLY (107, grouped by subject keyword; every commit's non-architecture files were looked at one by one) | see the table below | kept out of main |

| Architecture-only theme | Count | Commits |
|---|---|---|
| `module-core`, `afrotransact-core` and `afrotransact-go` (host, mounting batches, schema-per-module, route probes), module contract in Go | 29 | 2dc8b95ca 72ae69796 693c96ebc adbfa4521 e4b125260 738723821 28375dd11 1123c8f2e 2c0f8b98c 5028e72cb 58def9445 755cc6fb4 700ee9fb1 140800c35 58c196eac 9d76c717e 427931480 216e1b354 15402b4cd 4c7212265 191c9b168 4d2b71c66 0d3947b5a 3f4a3df91 f6d1befab f8c5b2f06 03bf30458 07d298d44 f02a29159 |
| in-process edges, published module APIs, `ModuleEntry`, `ConditionalOnModuleMode`, the in-process failure tests | 41 | 14f27ff48 596f8d6a2 0ab61645c f662123cf 565d9da95 b4cf0c5e9 e965b0d65 ec87f6bb7 951f49296 607f79f50 7cdac65a4 fd46664dd 444f382fd db65e0253 18eb899f9 68f15835c 4c6d95a7e fb2dea9b2 103838085 a2902d339 554207776 d4089df05 2cdd898f8 789d6dab7 be2e3d131 a76d958a7 071e6f32f b2b888c16 9a4509ae3 cb02aafa9 2ef94b276 46caf0931 f250e76f5 40db888af 2fca2a0f4 38b729e31 176358a9a 7f7da210e 80a524b24 1ecc7fa71 4df5480ff |
| release-1 sync, parity, port-back and the backlog merge driver | 5 | a8e2b456d f3602cf54 af50ffe9f 3d8a03b5e c2ce2d71c |
| per-deployable charts, ApplicationSets, workflows, Dockerfiles, edge routes, upstreams, cutover | 20 | 9058d5a42 3dfa1734c a7b7e43d0 6b69744df 64b817e8d 0f16f9298 37dd9ea06 8350b6773 69b4e8d37 f29d4338f d75cd8e0c 2b99e6c4c 5146c88c2 5f3dfc1e7 18bfaf155 3d4275e8e 77344360b ebfd95851 b3b23ff20 e2e6d8600 |
| ops for the monolith (bootstrap, `DB_LAYOUT=schema`, backups), `make up STACK=monolith`, local init hook, `tools/dev` tests | 6 | 3648cf5b3 e887d0573 36d4e46cf 3eb3e0e75 a04b2fa41 147de33c0 |
| observability for the monolith (module-level scrape, rules, dashboards) | 1 | 7e9751a50 |
| release-1 docs, ADR 0011, runbooks, CLAUDE.md deployability rules | 5 | b09f7add6 4794be9e4 6228e89ea 2d8fa11a0 d0c9b07bb |

Known candidates from the task, verified:

* **Postgres init hook in POSIX sh (`3eb3e0e75`)**: architecture-only. main has no `20-release-1.sh` (no `core`/`go` databases locally); main's own `10-init-databases.sh`
  is unchanged and identical on release-1. Caveat: the commit says the local Postgres image has no `bash`, yet main's hook has a bash shebang and arrays on the same image
  (`imresamu/postgis:17-3.5.4-alpine3.22`); this was not run here (no Docker), so whether the Alpine image lacks bash is UNVERIFIED. If it does, main's hook has the same bug.
* **Identity first-party sign-in with security off (`3658350ba`)**: already on main.
* **Checkout/orders/fulfilment/trust-ops/tax-returns "crash text" and "404 is none" fixes (`2fca2a0f4`, `38b729e31`, `176358a9a`)**: the divergences were in the
  **in-process** adapters; the HTTP adapters are the reference those tests compare against. On main: orders `HttpPaymentsClient`, `HttpOfferInventory`, `HttpPickupLocations` and
  fulfilment `HttpInventoryClient` already read a callee 404 as "none"; `Upstream` puts an I/O error text into a retryable `UpstreamFailure`, and `CheckoutWorkflowImpl.message`
  turns every non-`orders.*` failure into a fixed sentence, so no callee or network text reaches the shopper. No bug on main.
* **`at_module_up`, dashboards/rules for the monolith, `HikariPoolExhausted` and `ScrapeTargetCardinalityHigh` text**: architecture-only (the text names the deployables and
  pool sizes of core). The generated `r2-backup` rule file was missing on main (`generate_rules.py --check` failed): ported.
* **`orders.yaml` invalid YAML**: also broken on main (unquoted `: ` in a 422 description): ported.
* **Identity tenant-isolation matrix**: identical on main.
* **Seeder, storefront `/sell`, seller routes contract**: already on main.
* **Fault injection `LocalCheckoutFaults`**: the saga code is identical on main; ported, 5 tests pass.
* **Checkout saga failure tests (`2fca2a0f4`)**: NOT portable: `InProcessCheckoutSagaFailureTest` calls the saga through orders' in-process adapters over the
  `*-api` published interfaces (`InProcessWiring`, `InProcessCallees`). Its HTTP twin is opened as a backlog story (section 4).

## 2. What was ported to main (and develop)

| release-1 commit | What | Result |
|---|---|---|
| `0181c6f35` | `LocalCheckoutFaults` (Temporal worker interceptor, `local` profile, default off) + `LocalCheckoutFaultsTest`; the orders README now documents it | `-x` cherry-pick, applies unchanged; 5 tests pass on JDK 25 |
| `5e64e7934` (hunk) | `contracts/openapi/orders.yaml`: the 422 descriptions are folded scalars, the file parses again | all `contracts/**` parse |
| `fc3cee1c3` (hunk) | generated `prometheus-rules/r2-backup.yaml` | `generate_rules.py --check` clean |
| `fc3641685` (split) | `secrets.sh` `service_keys` NUL-terminated records, `mapfile -d ''`; `lib.sh secret()` writes each value to a 0600 file and uses `--from-file` (identity's `SIGNING_KEY_PEM` was split into one junk key per line, and values were visible in `ps`) | `tools/ovh/test/secrets-multiline.test.sh` (core/go section removed) 22/22; wired into `make deploy-guide-test` |
| `5cd94e648` (hunks) | `secrets.sh` renders the optional `SENDGRID_*`, `TWILIO_*`, `VONAGE_*`, `FCM_*`/`APNS_*`/`WHATSAPP_*`, carrier credentials and `OSRM_URL` that the (identical) service charts document as secret keys, only when set; `secrets.local.env.example` lists them | test above still 22/22 |
| (comment only) | `AvalaraTaxCalculator`: the comment on the constructor Spring uses | no behaviour |

## 3. File-level diff audit (not only commits)

Method: the files release-1 changed since the merge base, minus the architecture globs (`java/apps/core`, `java/libs/module-*`, `go/apps`, release-1 docs/tools/workflows,
deployable charts and Argo, `adapter/**/module`, `*InProcess*`, `*-api`, poms, `go/pkg/module`, `module.go`, `cmd/**`, postgres bootstrap, observability, `infra/local`, `tools/dev`),
compared with the blob on main. 139 files differ; all are in the table. Every other tree is **identical** to main:

| Tree | Result |
|---|---|
| `web/` | identical on every file release-1 changed (the storefront Lola/checkout redesign is on main only: sync-pending, section 6) |
| `mobile/` | one difference: `apps/driver/README.md` media URL (`:8200` under `make up`): architecture-only |
| `contracts/` | identical after this port (only `orders.yaml` differed) |
| `db/migration` of every service | identical sets and contents (the only extra files are the two `module-core` test fixtures `modules/{alpha,beta}/db/migration/V1__widget.sql`: architecture-only) |
| `go/services/**` | `internal/**` identical. Differences are host code: `cmd/*/main.go` shrunk to a thin caller, `module.go` (new), `bffmodule`, `internal/wiring`, files moved out of `cmd/` (`providers.go`, `backend.go`), `go.mod`/`go.sum` tidy: architecture-only |
| `java/services/**` | only: `@RestControllerAdvice(basePackages=...)` scoping, `@ConditionalOnModuleMode` on `Http*` adapters, `SecurityPermits` beans and `core-service` conditions in identity/content/seller/mcp security, named `@Bean`s (delivery-runs, retail, payments), `@KafkaListener` group `${at.orders.kafka-group:orders-service}`, in-process wiring in catalog/seller/identity/payments/inventory config, `module-disabled.properties`, poms, `TaxGateArchitectureTest` (+`InProcessOfferWriter`), `LocalCheckoutFaults` (ported), `HttpWiring` test helper and `AddressBookForPersonTest` (in-process): all architecture-only |
| `java/libs/**` | `module-*`, `*-api`, `platform-web` `module/` + `ModuleFailures`, `platform-security` `ModuleEntry`/`EndUsers`, `platform-events` `EventRetentionServices`, `platform-test` module-boundary ArchUnit rules and fixtures: architecture-only |
| `docs/engineering`, `docs/runbooks` | release-1 docs and Release-1 notes in `cloud-cost`, `ovh-production`, `restore-drill`, `backups-backblaze`, `observability-grafana-cloud`, `new-service-checklist`, `environments`, `secrets-and-configuration` (local ports of afrotransact-go): architecture-only. `checkout-tax-estimate.md`: main is NEWER (`297d7811c`, plain "Estimated tax" row): sync-pending |
| `tools/seed`, `tools/smoke` | `DEFAULT_STACK`/`STACK` default (`services` on main, `monolith` on release-1) and the matching test, `carriers.sh` default URL: intentional (the STACK default is the one deliberate difference, set in `368e81ef2`) |
| `infra/local` | compose mounts `20-release-1.sh` and `../ops/postgres`, healthcheck waits for database `go`, `provision-core.sql`, `PORTS.md` monolith section, `secrets.env.example` notifications port: architecture-only (main has no `core`/`go` databases) |
| `infra/ovh-vps`, `infra/ops`, `infra/tofu` | `secrets.sh` `DB_LAYOUT=schema` and `deployable_secrets`, `postgres-bootstrap.sh`, `pg-backup.sh` (4 databases), `pg-restore.sh restore-schema`: architecture-only. The multi-line and optional-secret fixes are now on main |
| `infra/observability`, `infra/k8s/platform/observability`, `alloy`, `edge` | module/deployable labels, release-1 rules and dashboard, 40000 sample limit, edge deny rules for `/modules`: architecture-only |
| identity permissions, i18n files | identical (Java `*Permissions.java`, `Permission.java`, identity migrations, tenant-isolation matrices, every `i18n/*.json` key set of web and mobile) |
| `Makefile`, `CLAUDE.md`, `.github`, `.gitattributes`, `tools/ci` | release-1 targets and rules, parity checklist in the PR template, workflow generator: architecture-only |

## 4. TODO

| Gap | Class | Resolution |
|---|---|---|
| Checkout saga failure-path tests over the HTTP adapters: release-1 proves 22 failure cases and an in-process versus HTTP matrix; main has only the scripted `CheckoutWorkflowTest` | TODO | backlog story **S496** (E06, `To Do`): stateful stub callees over HTTP, same 22 cases, same assertions |
| `checkout-tax-estimate.md` on release-1 describes the older tax row | sync-pending | the next `release-1-sync` brings main's text |
| Is the Alpine local Postgres image really without bash (would break main's `10-init-databases.sh` too)? | UNVERIFIED | needs a Docker run; not done here (disk) |

## 5. Re-running the audit

```
git fetch origin main develop +refs/heads/release-1:refs/remotes/origin/release-1
git cherry origin/main origin/release-1 | grep '^+'                  # step 1: commits main lacks (by patch id)
python3 tools/release/parity.py --reverse --main origin/main         # on release-1: migrations, contracts, permissions, i18n, direction-labelled
git diff --name-only $(git merge-base origin/main origin/release-1) origin/release-1   # then compare each non-architecture file with main
```

On release-1 `parity.py --reverse` (`tools/release/reverse_parity.py`) automates the four functional comparisons and is a non-blocking step of `release-1-parity.yml`;
at the time of this audit it reported **0** differences to port back and the sync-pending ones listed in section 6.

## 6. Left to other agents (in flight while this audit ran)

Not ported here: Java timeouts and in-process edges (release-1 only, architecture), the AI spend cap (own agent), the storefront checkout/Lola redesign (on main only, brings 90
i18n keys: sync-pending; its agent ports/syncs), the admin delivery status (own agent). Re-check with `parity.py --reverse` after they land.
