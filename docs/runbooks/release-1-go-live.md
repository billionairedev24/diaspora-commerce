# Release 1 go-live: UAT first (the dry run), then PROD, the same steps

Status 2026-11-17: **written for the first run, never executed against a live cluster.** Checked offline: `helm lint` and `helm template` of both deployables with the
uat AND prod values, `kubeconform`, `kustomize build` of every Argo CD env and of the edge overlays, `make launch-set-check`, `tools/release/upstreams.py`, `parity.py`,
`check_capacity.py`, `make release-1-check`, the secrets renderer tests. Not run (no network or no tool): any OVH, Layerbase, Stripe, Cloudflare, GitHub-settings step, `tofu`,
`argocd`, a real image pull. Vendor facts are tagged **UNVERIFIED**.

**Rule of this page: UAT and PROD differ only by VALUES, never by structure.** One `ENV` variable (`uat` or `prod`) drives every command below. Do UAT end to end, run
section 8 (what the dry run must prove), then repeat every step for PROD using the value differences in section 3. Related: [release-1-deploy.md](release-1-deploy.md)
(mechanics, rollback), [stripe-webhooks.md](stripe-webhooks.md), [v1-to-v2-migration.md](v1-to-v2-migration.md) (v1 users, Stripe account, past orders),
[release-1-config-inventory.md](release-1-config-inventory.md) (every variable name, generated).

```bash
export ENV=uat            # or prod, for the repeat
cd <worktree of origin/release-1>
```

## 0. What changed in the repo for this go-live (so you know what you are deploying)

* **Both envs run the WHOLE launch set inside the two deployables** (14 Java modules in `afrotransact-core`, 7 Go modules in `afrotransact-go`). Nothing ran before in either env, so
  there is no standalone traffic to protect and no batches: `infra/argocd/launch-set/release-1.yaml` (`enabled.uat`, `enabled.prod`) and `cutover.services` in
  `infra/argocd/envs/<env>/platform-values.yaml` list all 21. The 21 old per-service Applications still exist, scaled to 0 (rollback path only; they have no image pins).
* **UAT is now the lean OVH shape, like prod** (`infra/argocd/envs/uat/kustomization.yaml`: the same lean ApplicationSets, `cloud: ovh`, namespace `afrotransact-uat`, Cloudflare Tunnel edge, no
  cert-manager, no in-cluster Prometheus/Loki/Tempo). Before this change `uat` assumed a full cloud platform. The deployables read `values-uat.yaml` (`env: uat`, profile `uat`).
* **Valkey is managed on Layerbase**: the in-cluster Valkey is out of `infra/k8s/platform/lean` and Alloy no longer scrapes it; `REDIS_URL` comes from `EXTERNAL_REDIS_URL` (`infra/ovh-vps/secrets.sh`).
* **Temporal is the self-hosted server** (`infra/k8s/platform/lean/temporal.yaml`, state in Layerbase, namespace `afrotransact`) in both envs; the uat `temporal-cloud` profile and the Cloud address are gone.
* `secrets.sh` takes `PROFILE=uat|prod`; `apply.sh` takes `ENV=uat|prod` and refuses empty mandatory keys of the two deployable Secrets too.
* New: `tools/smoke/go-live.sh` (read-only smoke of a deployed env), `tools/release/config_inventory.py`, `infra/ovh-vps/config.uat.env.example` and `secrets.uat.env.example`, `web/packages/ui/vercel.json`.

## 1. OWNER-ONLY list, ordered by lead time (start the first items NOW, they run while you do the rest)

| # | What only you can do | Lead time | Where | Names / exact values |
|---|---|---|---|---|
| 1 | **GitHub: make `release-1` the default branch.** Settings > General > Default branch | 1 min, but do it first | github.com/billionairedev24/diaspora-commerce/settings | Argo CD generators read `HEAD`; scheduled workflows and `workflow_dispatch` use the default branch's copy |
| 2 | **OVH US account + one Public Cloud project per env** (`uat` now, `prod` later), API credentials for tofu, ask about the monthly `b3-16` price and the free MKS plan in `US-EAST-VA-1` | hours to days (verification) | us.ovhcloud.com, `https://api.us.ovhcloud.com/createToken/` (rights `GET/POST/PUT/DELETE /cloud/project/<id>/*`, `GET /me`) | `OVH_APPLICATION_KEY`, `OVH_APPLICATION_SECRET`, `OVH_CONSUMER_KEY` (shell only) |
| 3 | **Layerbase: one Postgres server per env** (never shared: the role and database names are fixed) with 4 databases, admin with CREATEROLE/CREATEDB; **one Valkey per env**. PostGIS and btree_gist are confirmed available (owner). Ask in writing: `max_connections` (need >= 150, plan says 500), TLS/CA, `CONNECTION LIMIT` on roles, Valkey `maxmemory-policy` | hours to a day | layerbase.com | Postgres admin: `EXTERNAL_PG_HOST`, `EXTERNAL_PG_ADMIN_USER`, `EXTERNAL_PG_ADMIN_PASSWORD`; Valkey: `EXTERNAL_REDIS_URL` (`rediss://...`) |
| 4 | **Stripe** (test mode for uat, live for prod): enable Connect, create keys and the three webhook endpoints AFTER the first deploy (section 6) | 1 h; **live-mode activation can take days** (business verification), start it now for prod | dashboard.stripe.com | `STRIPE_SECRET_KEY` (restricted key), `STRIPE_PUBLISHABLE_KEY`, `STRIPE_WEBHOOK_SECRET`, `STRIPE_CONNECT_WEBHOOK_SECRET`, `STRIPE_BILLING_WEBHOOK_SECRET` (optional) |
| 5 | **Resend** (email: OVH blocks SMTP; there is no SendGrid or Twilio in the launch path): verify the domain `afrotransact.com` (DNS records TXT/CNAME at Cloudflare), API key, webhook | DNS propagation 1 h | resend.com, `docs/runbooks/resend-email.md` | `RESEND_API_KEY`, `RESEND_WEBHOOK_SECRET`; no Resend = no sign-up codes, no staff MFA email |
| 6 | **Cloudflare**: zone `afrotransact.com` is already active (nameservers at Cloudflare). Create the API token, R2 (2 buckets per env, 2 tokens), the Tunnel is made by tofu (section 5) | 1 h | dash.cloudflare.com | `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID` (GitHub environment secrets), `R2_*` (5 names) |
| 7 | **GitHub**: environments `uat` and `prod` (prod: required reviewers, deployment branch `release-1`), variables `CLOUD=ovh`, `REGISTRY_URL=ghcr.io/billionairedev24/diaspora-commerce`; secrets `GITOPS_APP_ID`, `GITOPS_APP_PRIVATE_KEY`; Actions > General > **Allow GitHub Actions to create and approve pull requests**; branch ruleset on `release-1` (required checks in release-1-deploy.md section 10b); repo variable `STORYBOOK_HOST=vercel` | 30 min | repo Settings | the GitOps app must be a bypass actor (it commits the image pins) |
| 8 | **GHCR**: after the first image push link both packages to the repo (Write) and create a read-only PAT with only `read:packages` | after the first image exists | github.com > Packages | `GHCR_USER`, `GHCR_TOKEN` (becomes the pull Secret `ghcr`) |
| 9 | **OpenRouter** key with a spend limit set in its dashboard (second guard behind the in-code USD 2/day cap) | 10 min | openrouter.ai | `OPENROUTER_API_KEY` |
| 10 | **Grafana Cloud** stack (free) + one access-policy token per env, **Backblaze B2** bucket + bucket-scoped key per env, **age** key pair for backups (private key offline) | 1 h | grafana.com, backblaze.com | `GRAFANA_*`, `BACKUP_*` (see `config.uat.env.example`) |
| 11 | **Vercel** (free Hobby) for Storybook (section 7.4) | 20 min | vercel.com | none (no secrets in Storybook) |
| 12 | **Route 53: change NOTHING tonight.** The registrar and DNS-provider move is next month (section 9) | - | - | - |

## 2. The blockers found while preparing this (read before you start)

1. **No image of `afrotransact-core` or `afrotransact-go` has ever been published.** `core.yml` and `afrotransact-go.yml` publish only after `verify` is green on a `release-1` push, and
   `verify` has failed on every full run (main fails the same way: it is accumulated test and lint debt, not a deployment problem). State at 2026-11-17 02:40 UTC:
   **core**: the Maven reactor stops at the first red module. `bookings-service` `ServiceAreasIT` is fixed on release-1 (the test placed a catering provider with a 5-mile radius; `AreaPolicy` requires 10) and now passes; the
   next red module is **catalog**: `ListingModerationIT` (3 tests) and `ProductRelationshipsIT` (3) fail with "This product is waiting for its barcode exemption to be decided" (their `liveProduct` helpers predate the barcode-exemption rule),
   and every module after catalog (offers ... trust-ops, core's own context tests) has not been reached, so more may follow. **afrotransact-go**: golangci-lint v2.14.0 (installable locally: `GOTOOLCHAIN=go1.27.4 go install github.com/golangci/golangci-lint/v2/cmd/golangci-lint@v2.14.0`)
   is clean for the deployable itself (fixed on release-1); the per-service Go modules still carry about 90 findings (wrapcheck, unparam, revive, noctx), `tools/ops/kafka-replay` 2, `pkg/httpserver` 2, and `services/gateway-bff` about 145 that CI never reached because `gofmt` failed first (gofmt is fixed now, so they will show).
   **Until both `verify` jobs are green there is no image, so nothing can be deployed.** Decide: (a) fix the red tests and the lint debt (mechanical, several CI cycles of ~15 minutes, each cycle reveals the next module), or (b) an owner-approved one-time manual image build that does not wait for `verify`
   (for example a `workflow_dispatch` input on `core.yml` / `afrotransact-go.yml` that lets the `image` job run when `verify` failed; the image is still Trivy-scanned and cosign-signed). (b) is a policy decision and is **not** implemented here.
2. **Tag strategy is deterministic**: images are tagged with the full 40-character commit sha and signed (cosign keyless); `promote` (Actions) writes `image.tag` and `image.digest` into
   `java/apps/core/deploy/helm/values-<env>.yaml` and `go/apps/afrotransact-go/deploy/helm/values-<env>.yaml` on release-1 after verifying the signature; Argo CD pulls
   `ghcr.io/billionairedev24/diaspora-commerce/afrotransact-core:<sha>@sha256:<digest>`. There is no `latest`. Today `values-uat.yaml` and `values-prod.yaml` have **no pin** (the chart default tag is the sentinel `dev`): `check_launch_set.py --env <env> --require-tags` fails until you promote.
3. Demo data is **not** seeded anywhere in this plan (section 8, item 12).
4. The Cloudflare, Stripe, Layerbase, OVH and Resend steps are all unverified against live accounts.

## 3. UAT versus PROD: the values that differ (everything else is identical)

| | UAT (dry run) | PROD |
|---|---|---|
| `ENV` / Argo env / namespace | `uat` / `*-uat` / `afrotransact-uat` | `prod` / `*-prod` / `afrotransact-prod` |
| Spring / Go profile (`SPRING_PROFILES_ACTIVE`, `APP_ENV`) | `uat` (API reference pages on, payments accepts test keys, trace sampling 0.25) | `prod` (API docs off, payments **requires live keys**, sampling 0.1, strict startup checks) |
| Hosts | `uat.` `seller-uat.` `admin-uat.` `api-uat.` `auth-uat.` `media-uat.` `developers-uat.` + `.afrotransact.com` | `afrotransact.com` `seller.` `admin.` `api.` `auth.` `media.` `developers.` |
| Cloudflare Workers | `storefront-uat`, `seller-center-uat`, `staff-console-uat` (`wrangler --env uat`) | `storefront-prod`, `seller-center-prod`, `staff-console-prod` (`--env prod`) |
| Stripe | **test mode**: `sk_test_`/`rk_test_`, `pk_test_`, 3 test-mode endpoints at `api-uat` | **live mode** `rk_live_`/`sk_live_`, `pk_live_`, 3 live endpoints at `api` |
| Demo seed | **never** (the seeder refuses uat; staff creates real test data by hand) | never |
| Postgres | its own Layerbase server: databases `core`, `go`, `temporal`, `temporal_visibility`; 17 module roles `<schema>_app` + `temporal_app` | a second Layerbase server, same names |
| Connection budget (per server) | core 48 + go 12 + Temporal 10 + backup/ops 10 = **80**; **140** with a rolling-update surge; limit 500 (needs >= 150) | identical |
| Valkey | its own Layerbase Valkey (`EXTERNAL_REDIS_URL`) | its own |
| Node | 1 x `b3-16` (minimum that fits, see capacity), disposable project, deletion protection off, KYC retention 30 d | 1 x `b3-16`, deletion protection on, KYC retention 7 y |
| Replicas | 1 of everything | 1 of everything |
| Resources | core request 1536Mi / limit 2048Mi, go 128Mi / 384Mi, Temporal 512Mi / 768Mi, Kafka 640Mi, Meilisearch 384Mi (steady 7.3 GiB, surge 8.9 GiB of 13 GiB allocatable) | identical |
| Argo sync | manual during the soak (`autoSync: false`), then flip | manual always |
| Observability | Grafana Cloud free, `GRAFANA_ENV=uat`; alerts route for `env=prod` only (uat alerts are silent) | `GRAFANA_ENV=prod`; all severities mail the owner list |
| R2 buckets | `afrotransact-uat-media`, `afrotransact-uat-kyc-evidence` | `afrotransact-prod-media`, `afrotransact-prod-kyc-evidence` |
| Backups | B2 `afrotransact-uat-backups`, 7 days | B2 `afrotransact-prod-backups`, 14 days |
| AI | USD 2/day global cap, USD 0.50 per caller | same |
| Google/OpenRouter keys | optional / limited | real, with quotas |
| `DRIVER_APP_ENABLED` | unset (default `false`) | unset (default `false`) |
| Edge overlay | `infra/k8s/platform/edge/overlays/uat` (prod's edge, uat hosts and namespace) | `.../overlays/prod` |
| State dir of secrets | `$HOME/.afrotransact-uat` | `$HOME/.afrotransact-prod` (never reuse) |

## 4. Pre-flight (GitHub, local tools)

1. Items 1, 7, 8 of section 1. **One private file for every value:** `tools/release/uat-env.sh init` creates `~/.afrotransact/uat.all.env` (mode 600) from `infra/ovh-vps/uat.all.env.example`; `uat-env.sh fill` asks for each value one at a time (hidden for secrets, checked, saved at once; Enter skips; re-run it later for what is still blank; `fill github|tofu|cluster` or `fill --only NAME` to narrow), or edit the file by hand; `uat-env.sh check` names blanks and bad formats (never values), `uat-env.sh github` sets the GitHub items, `uat-env.sh split` writes `config.env` and `secrets.local.env` for `secrets.sh`, `eval "$(tools/release/uat-env.sh tofu-env)"` exports the tofu credentials into your shell. Master copy in your password manager. The GitHub variables and secrets for UAT can also be set on their own with `tools/release/uat-github-setup.sh list` (explains where to get each) then `set` (hidden prompts, values go to `gh` on stdin, never printed) then `verify`; the cluster secrets (Stripe, Resend, R2, Layerbase, tunnel) are NOT GitHub secrets, see section 6. Verify: `gh api repos/billionairedev24/diaspora-commerce --jq .default_branch` prints `release-1`.
2. Tools: `tofu >= 1.9 kubectl sops age psql helm kustomize kubeconform argocd gh python3 rclone`. Offline gate (expect every line OK):
   ```bash
   python3 infra/argocd/scripts/check_launch_set.py            # [uat] and [prod]: afrotransact-core runs 14 modules, afrotransact-go runs 7, "OK"
   make release-1-check && python3 tools/release/check_capacity.py
   python3 tools/release/config_inventory.py --check
   for e in uat prod; do for d in java/apps/core go/apps/afrotransact-go; do ( cd $d/deploy/helm && helm dependency build . >/dev/null && \
     helm template x . -n afrotransact-$e -f values.yaml -f values-$e.yaml -f ../../../../../infra/argocd/envs/$e/platform-values.yaml --set image.tag=$(git rev-parse HEAD) | kubeconform -ignore-missing-schemas -summary - ); done; done
   ```
   Rollback: nothing is changed by this step.
3. **Build the images** (blocker 1): push to release-1 and wait for `core` and `afrotransact-go` to be green incl. `image`. Check:
   `gh run list --workflow core.yml --branch release-1 --limit 3` and in GHCR `afrotransact-core:<sha>`. Then `Actions > promote > afrotransact-core, environment $ENV, tag <full sha>`, same for `afrotransact-go`;
   `python3 infra/argocd/scripts/check_launch_set.py --env $ENV --require-tags` must print OK. Rollback: re-promote the previous sha.

   **Interim: unverified UAT images (owner decision, while `verify` is red; UAT ONLY).** `.github/workflows/uat-unverified-image.yml` builds, scans (Trivy), SBOMs and cosign-signs the image **without running verify**. It is manual only, runs only from release-1, waits for the rules of the GitHub environment `uat`, and needs `skip_verify=true` plus `confirm_uat_only=UAT-ONLY-UNVERIFIED`.
   ```bash
   gh workflow run uat-unverified-image.yml --ref release-1 -f service=afrotransact-core -f skip_verify=true -f confirm_uat_only=UAT-ONLY-UNVERIFIED
   gh workflow run uat-unverified-image.yml --ref release-1 -f service=afrotransact-go   -f skip_verify=true -f confirm_uat_only=UAT-ONLY-UNVERIFIED
   gh run watch   # approve the `uat` environment prompt if it has reviewers
   ```
   Read the result: the run summary prints `ghcr.io/billionairedev24/diaspora-commerce/<service>:<sha>@sha256:<digest>`; or `gh run view <run-id> --log | grep 'UNVERIFIED image'`. Check the marking (all four must hold):
   `cosign verify ghcr.io/billionairedev24/diaspora-commerce/afrotransact-core:<sha> --certificate-oidc-issuer https://token.actions.githubusercontent.com --certificate-identity-regexp '^https://github\.com/billionairedev24/diaspora-commerce/\.github/workflows/uat-unverified-image\.yml@refs/heads/release-1$' | jq '.[].optional'`
   shows `"org.afrotransact.verified": "false"`; the same image also has the tag `<sha>-unverified` and the OCI label `org.afrotransact.verified=false`.
   Then promote to **uat only** (the `<sha>` is the full 40-character sha of the run, which is the tag the summary prints):
   ```bash
   gh workflow run promote.yml --ref release-1 -f service=afrotransact-core -f environment=uat -f tag=<full sha>
   gh workflow run promote.yml --ref release-1 -f service=afrotransact-go   -f environment=uat -f tag=<full sha>
   python3 infra/argocd/scripts/check_launch_set.py --env uat --require-tags     # OK; values-uat.yaml now has image.unverified: true
   ```
   Design: the tag stays the full sha because promote and `_deploy` accept nothing else; the marking is therefore NOT in the tag name alone but in (1) the extra tag `<sha>-unverified` (visible, not a valid promote input), (2) the OCI label, and (3) the **cosign signature annotation** `org.afrotransact.verified` (signed, so it cannot be edited without a certificate of an allowed workflow; this is what the guard reads). A normal green `core` / `afrotransact-go` run signs with `org.afrotransact.verified=true`.
   Guard: `_deploy.yml` (used by promote) runs `tools/release/image_verdict.py` in a job BEFORE the environment's approval and again in the deploy job: for `prod` an `unverified` digest, or an unannotated afrotransact-core / afrotransact-go digest, is refused; `uat` accepts it and writes `image.unverified: true` into `values-uat.yaml`. `check_launch_set.py --require-tags` fails if that marker is in `values-prod.yaml`. Tests: `python3 tools/release/test_image_verdict.py` (part of `make release-1-check`).
   **Production needs an image from a fully green verify.** When verify is green, the normal pipeline publishes `<sha>` (replacing the unverified tag of the same sha only if you re-run uat-unverified-image on the same commit: do not) and promote `prod` with a sha built by `core` / `afrotransact-go`. Remove the unverified UAT pin by promoting a verified sha to uat. Remove `uat-unverified-image.yml` once verify is green.

### Image size and build time (measured 2026-11-17; amd64 only for UAT and prod)

| | afrotransact-core | afrotransact-go |
|---|---|---|
| Artifact | fat jar 293 MB, now 285 MB; extracted libraries 272 MB after stripping foreign natives | static binary 94 MB (45 MB code, 31 MB function tables) |
| JRE (jlink) | 68 MB layer (61 MB `lib/modules` + libjvm) | none |
| Base | distroless cc 37 MB | distroless static 4.8 MB |
| **Image (layer sum, uncompressed)** | **369 MB**, budget 385 MB | **98.6 MB**, budget 103 MB |
| Pull size (compressed) | about 300 MB cold; a code change re-pulls about 13 MB (own jars + app layer) | about 40 MB |

Why the Java image is not smaller: the jars are already compressed, so gzip gains nothing, and the weight is real dependencies. Top of the core jar:
`openai-java-core` 68.5 MB (178 MB of classes: 34,000 classes of the OpenAI SDK, pulled by Spring AI for `ai-gateway`), `stripe-java` 21.6 MB, Temporal
client 12.8 MB, `grpc-netty-shaded` 10.9 MB, `kafka-clients` 10.2 MB, `bcprov` 7.2 MB (identity), POI 12 MB (catalog imports). **Owner decisions, not taken here:**
(1) build the UAT core without the AI modules (`-DnoAi`, or drop the `ai-modules` profile): about 85 MB less jar and a faster start (estimate from the
jar listing, not measured); the modules stay switchable by `MODULE_AI_GATEWAY_ENABLED`; (2) `snappy-java`, `lz4-java` and `zstd-jni` (Kafka compression)
stay: nothing in the repo sets a compression type, but a broker-side `compression.type` would make consumers need them; (3) Go: the 94 MB comes from
seven modules with the AWS, Azure, Elasticsearch, Redis, excelize and arrow libraries; build tags per `SEARCH_PROVIDER` / storage provider would cut
an estimated 10-15 MB but is a code change.

Removed (safe, behaviour on linux amd64/arm64 identical): macOS, Windows, BSD and other-CPU native libraries inside `zstd-jni`, `snappy-java`, `lz4-java`,
`grpc-netty-shaded`, the netty QUIC/epoll jars (`java/tools/StripNatives.java` in the Dockerfile, plus `spring-boot-maven-plugin` excludes in
`apps/core/pom.xml`), GraalVM `META-INF/native-image` metadata, and the Temporal test server (`temporal-testing`, `temporal-test-server`, `cron-utils`,
pulled in by `temporal-spring-boot-starter`; only an optional, class-conditional auto-configuration uses it). Total: 293 MB to 272 MB of libraries.

Build: the workflows compile on the runner (`.github/actions/java-jar`, `.github/actions/go-binary`: setup-java / setup-go caches) and the Dockerfiles only
package (`--build-arg JAR_FROM=prebuilt` / `BIN_FROM=prebuilt`); local `docker build` without the arg still compiles inside Docker. Locally the Docker part
(jdeps, jlink, layers) takes about 45 s with no cache; with a warm cache a code change only rebuilds the own-jars and app layers. Reproducible: two
`--no-cache` builds with `rewrite-timestamp` give identical layer digests (core without AppCDS).

AppCDS (optional, **off**): `--build-arg APPCDS=true` adds a class-data archive made by a start-up training run (the build has no database, so it
archives what loads up to the first connection). Measured in the real distroless image, time to the first database connect: 8.2 s to 5.6 s (-31%); cost
+115 MB image (100 MB archive + 14.5 MB JDK base archive), re-pulled on every build, and the image is no longer byte-reproducible. A stale archive only loses
the speed-up (the JVM prints a warning and starts normally). Turn it on in `build-args` if the 60 s startup budget proves tight on the OVH node, and raise `max-size-mb` to 500.
Not verified: a `docker build` with the in-Docker Maven/Go compile path (needs network in the build; GitHub Actions is the first real run), the arm64 leg.

## 5. OVH cluster, Layerbase, Cloudflare edge (who: you; where: your machine)

1. **OVH cluster**: `cd infra/tofu/bootstrap/ovh && tofu init && tofu apply ...` then `cd ../../envs/$ENV` (`cp terraform.tfvars.example terraform.tfvars`: `cloud = "ovh"`, `region = "US-EAST-VA-1"`, `ovh_endpoint = "ovh-us"`, `ovh_service_name = <project id>`, `admin_cidrs = [<your IP>/32]`, uat: `disposable = true`), `tofu plan -out plan.out && tofu apply plan.out`.
   Expected: one MKS cluster with ONE `b3-16` node, no load balancer, no database. Rollback: `tofu destroy` (uat); prod has deletion protection.
2. **Kubeconfig**: OVH panel > Managed Kubernetes > kubeconfig. `export CONFIRM_CONTEXT=$(kubectl config current-context)`. Check: `kubectl get nodes` (1 Ready), `kubectl describe node | grep -A8 Allocatable` (**memory must be >= ~13 GiB**; if it is lower the plan does not fit), `kubectl get storageclass` (a default class exists; test a 1 GiB PVC).
3. **Ingress controller / TLS**: nothing to install. Traffic path: Cloudflare (TLS, WAF) -> Tunnel -> `cloudflared` pod -> Envoy Gateway -> HTTPRoutes. No load balancer, no cert-manager, no origin certificate; the zone SSL mode is "Full (strict)" for the proxied hosts and the origin is never reachable from outside.
4. **Layerbase Postgres** (own server per env). Bootstrap (idempotent, prints no password; creates the 4 databases, 17 roles, schemas, `CONNECTION LIMIT`s, search_path):
   ```bash
   export PGHOST=<layerbase host> PGUSER=<admin> PGPASSWORD=<admin password> PGSSLMODE=require STATE_DIR=$HOME/.afrotransact-$ENV
   infra/ops/postgres/release-1-bootstrap.sh
   ```
   Expected last lines: the verify script passes (no module role can reach another module's schema, every connection limit matches the pools, extensions present).
   **Extensions** (PostGIS and btree_gist are available on Layerbase: owner confirmed): the bootstrap runs, as the ADMIN, `CREATE EXTENSION IF NOT EXISTS postgis` and `btree_gist` in `public` of `core` and of `go`. The app roles never run `CREATE EXTENSION`:
   the three migrations that contain `create extension if not exists` (fulfilment `V3__nearest_driver.sql` postgis, bookings `V1__bookings_schema.sql` btree_gist, Go geo `001_zones.sql` postgis) then find it present and do nothing. If the bootstrap fails on an extension, enable it in the Layerbase panel for **both** `core` and `go` and re-run.
   Check: `psql "host=$PGHOST dbname=core user=$PGUSER sslmode=require" -c "select extname from pg_extension"` lists `postgis` and `btree_gist` (same for `go`).
   Latency: from a pod, `select 1` x 50 with `\timing` should be a few ms (UNVERIFIED). TLS: `sslmode=require` is encrypted but does not verify the server; ask Layerbase for the CA and switch to `verify-full` (`docs/runbooks/postgres-tls.md`) before prod. Back up `STATE_DIR` offline (`infra/ops/secrets/escrow.sh create`).
   Rollback: the bootstrap only creates; to start over drop the 4 databases and the roles in Layerbase.
5. **Layerbase Valkey**: create the instance; URL form `rediss://<user>:<password>@<host>:<port>` into `EXTERNAL_REDIS_URL` (a secret value, never committed). Required settings (ask Layerbase, UNVERIFIED): TLS on, password/ACL user, **`maxmemory-policy volatile-lru` (or `allkeys-lru`), never `noeviction`**, >= 256 MB, >= 100 client connections (use is small: cart, gateway-bff sessions and rate limits, mcp-server; Lola's cost-cap cache is in-process, `AI_SPEND_CAP_CACHE_SECONDS`=10, not Valkey). Smoke: `redis-cli -u "$EXTERNAL_REDIS_URL" ping` -> `PONG`. The in-cluster Valkey is no longer part of the path.
6. **Cloudflare (tofu)**: `cd infra/cloudflare && tofu init && tofu apply -var-file=envs/$ENV.tfvars` after setting `account_id` in `envs/$ENV.tfvars` and exporting `CLOUDFLARE_API_TOKEN`. Prod run owns the zone-wide settings (`manage_zone = true`); uat has `manage_zone = false`. It creates: the Tunnel, DNS for `api-*`/`auth-*` (CNAME to `<tunnel id>.cfargotunnel.com`, proxied), the Workers routes, the R2 buckets, the Pages domain for `developers-*`. Then `cloudflared tunnel token <tunnel id>` -> `CLOUDFLARED_TUNNEL_TOKEN`.
   **DNS records to exist after this step (uat; prod drops the suffix)**:

   | Name | Type | Target | Proxy |
   |---|---|---|---|
   | `api-uat` | CNAME | `<tunnel id>.cfargotunnel.com` | proxied |
   | `auth-uat` | CNAME | `<tunnel id>.cfargotunnel.com` | proxied |
   | `uat`, `seller-uat`, `admin-uat` | A/AAAA placeholder (Workers route host, tofu creates) | `192.0.2.1` | proxied |
   | `media-uat` | R2 custom domain (tofu `r2_buckets`) | bucket `afrotransact-uat-media` | proxied |
   | `developers-uat` | CNAME | `afrotransact-docs.pages.dev` | proxied |
   | `storybook` (optional) | CNAME | `cname.vercel-dns.com` (the value Vercel shows) | **DNS only (grey)** |
   | Resend records | TXT/CNAME/MX as Resend shows | | DNS only |

   Cloudflare API token, names of the permissions (UNVERIFIED, adjust to the dashboard's wording): Account: Workers Scripts Edit, Workers R2 Storage Edit, Cloudflare Tunnel Edit, Cloudflare Pages Edit, Account Settings Read, Turnstile Edit; Zone: Zone Read, DNS Edit, Workers Routes Edit, Zone Settings Edit, Zone WAF Edit (prod run only), SSL and Certificates Edit.
   **Dashboard steps tofu does not do** (once per account/zone):
   * Account: two-factor sign-in on (Profile > Authentication); **Account ID** is on *Workers & Pages* (right-hand column, 32 hex characters) -> `CLOUDFLARE_ACCOUNT_ID`.
   * Zone: *Rules > Settings > Managed Transforms* > **Add visitor location headers** ON. The home page reads the visitor's city from `CF-IPCity`, `CF-IPPostalCode` and friends (`docs/engineering/delivery-location.md`); without it the deliver-to city stays empty. Not in `infra/cloudflare` yet. (`LOCATION_DETECT_ZIP` is local-only: never set it here.)
   * **Workers plan**: the free plan limits the size of a Worker and the OpenNext builds of the three apps are often too big; if the first `web-*` deploy fails with "Script startup/size limit exceeded" or "The Worker is too large", upgrade the account to **Workers Paid** (about USD 5/month, *Workers & Pages > Plans*) and re-run.
   * Troubleshooting: authentication error 10000 = the token lacks a permission above or its Zone Resources do not include the zone; a route or custom domain refused because "a DNS record already exists" = delete the old A/AAAA/CNAME on that host (tofu owns the records); old content after a deploy = hard refresh (Worker versions switch at once, assets are cached by hash); roll a Worker back in *Workers & Pages > the Worker > Deployments > Rollback* (or `wrangler rollback`).
   Rollback: `tofu destroy -var-file=envs/$ENV.tfvars` (uat).
7. **Argo CD** (once per cluster): `kubectl create ns argocd && kubectl apply -n argocd --server-side -f https://raw.githubusercontent.com/argoproj/argo-cd/<pinned 3.x tag>/manifests/install.yaml`, scale `argocd-dex-server` and `argocd-notifications-controller` to 0; private repo: `argocd repo add ... --password <PAT>`. Then `kubectl apply -n argocd -f infra/argocd/bootstrap/root-$ENV.yaml`.
   Expected: AppProjects `afrotransact-$ENV`, `platform-$ENV`, four ApplicationSets, 21 + 2 + 3 Applications, all OutOfSync; none syncs by itself (`autoSync: false`; the uat root app itself is automated, which only creates the Applications).

## 6. Secrets and Stripe (who: you; never paste a value in chat or git)

1. Render (names only in git; values from your shell files):
   ```bash
   cd infra/ovh-vps && cp config.$ENV.env.example config.env && cp secrets.$ENV.env.example secrets.local.env     # prod: config.env.example / secrets.local.env.example
   # fill the empty names in both files (they are .gitignored); EXTERNAL_PG_*, EXTERNAL_REDIS_URL, STRIPE_*, RESEND_*, R2_*, CLOUDFLARED_TUNNEL_TOKEN, GRAFANA_*, BACKUP_*, GHCR_*, ADMIN_*
   export PROFILE=$ENV DB_LAYOUT=schema STATE_DIR=$HOME/.afrotransact-$ENV SECRETS_OUT=$(mktemp -d)
   ./secrets.sh platform && ./secrets.sh services
   ```
   Expected: `afrotransact-core: N keys, afrotransact-go: M keys`. Multi-line `SIGNING_KEY_PEM` is handled (tested). Generated values (JWT signing key, `IDENTITY_DATA_KEY`, every client secret) live in `STATE_DIR`: **run `infra/ops/secrets/escrow.sh create` now and store it offline**; those two cannot be recovered.

   **What else goes into `secrets.local.env`, and what is not mandatory** (all names; `apply.sh` refuses empty mandatory keys):
   `STRIPE_PUBLISHABLE_KEY` (gateway-bff refuses to start without it); `RESEND_API_KEY` and `RESEND_WEBHOOK_SECRET` (notifications refuses to start in prod without both);
   `OPENROUTER_API_KEY` **and a credit limit on that key in the OpenRouter dashboard** (the code-enforced USD 2 per UTC day cap is the first guard, the key limit the second);
   the R2 account id and the two bucket tokens (media refuses to start in prod without them); the tunnel token; Grafana Cloud OTLP and Alloy credentials (one stack,
   `GRAFANA_ENV=$ENV`); the Backblaze B2 bucket, its bucket-scoped key and endpoint, `BACKUP_RETENTION_DAYS` and the **age public key** (never Cloudflare R2 for backups);
   `GHCR_USER` / `GHCR_TOKEN`; `ADMIN_EMAIL` / `ADMIN_PASSWORD` / `ADMIN_PHONE`. **Geo and tax keys**: `GOOGLE_MAPS_API_KEY` is required in uat and prod (geo refuses to start without a Google key: Google Maps is the only address validator; set an API restriction and a quota);
   tax-returns runs `TAX_PROVIDER=RULES` (no vendor key; Stripe Tax and Avalara are the later cut-over in `tax-provider-cutover.md`). What IS mandatory is tax **data**: `ORDERS_TAX_FALLBACK_TO_TABLE=false` means the tax-returns profiles must be `ACTIVE` or checkout fails.
   Generate everything fresh per environment (`STATE_DIR` must be a new directory; never reuse uat or dev values in prod).

2. Encrypt and apply: `cd ../ops/secrets && mkdir -p $ENV && cp -r "$SECRETS_OUT"/. $ENV/ && sops --encrypt --in-place $ENV/*.yaml && ENV=$ENV SOPS_AGE_KEY_FILE=~/.config/sops/age/keys.txt ./apply.sh`.
   Expected: it refuses empty mandatory keys (Stripe, Resend, R2, signing key, tunnel, backup); otherwise `done`. Check names only: `kubectl -n afrotransact-$ENV get secret` lists `afrotransact-core-secrets`, `afrotransact-go-secrets`, `ghcr` and the per-service ones.
3. **Stripe, end to end** (mode: **test for uat, live for prod**; click paths and screenshots are in [stripe-webhooks.md](stripe-webhooks.md); this is the order):
   1. Account: dashboard.stripe.com, toggle Test mode (uat). For prod: activate the account (business details, bank account, identity): lead time days.
   2. **Connect**: Settings > Connect > Get started. **Our code uses Connect with sellers as connected accounts, with the platform liable for negative balances and Stripe collecting verification** (see `java/services/seller`, `docs/engineering/payments-idempotency.md`, "Seller accounts and liability"); payouts are separate payouts from the platform to the seller (events `transfer.reversed`, `payout.paid`, `payout.failed`). Prod: complete the platform profile, branding (name, icon, colours, support email/URL), Connect onboarding branding, payout schedule and statement descriptor under Settings > Connect and Settings > Business.
   3. Payment methods: Settings > Payment methods: enable cards (and US bank debit only if plan fees by ACH are wanted; that is what endpoint 3 is for). Turn on Radar defaults (Radar > Rules: block high-risk, 3D Secure when requested).
   4. Tax: **our tax design is `TAX_PROVIDER=RULES`** (own rate table, no Stripe Tax or Avalara at launch; `docs/runbooks/tax-provider-cutover.md` is the later cut-over). Do **not** enable Stripe Tax for this go-live. Checkout fails unless tax-returns profiles are `ACTIVE` (`ORDERS_TAX_FALLBACK_TO_TABLE=false`): load the tax data per `CheckoutTaxEstimate.md`.
   5. Keys (Developers > API keys): create a **restricted key** (names of permissions to grant: PaymentIntents Write, Charges Read, Refunds Write, Customers Write, PaymentMethods Write, SetupIntents Write, Transfers Write, Payouts Read, Balance Read, Accounts Write (Connect), Account Links Write, Persons Read, External Accounts Read, Disputes Read, Webhook Endpoints Read) into `STRIPE_SECRET_KEY`; publishable key into `STRIPE_PUBLISHABLE_KEY`. Run `make stripe-setup` from a laptop with the key in `.local/secrets.env` to check Connect and the key mode (uat: the key must be a test key; payments refuses live keys outside prod).
   6. **API version**: leave the account's default for the endpoints (snapshot payload); the SDK pins its own version. Do not pick an older one.
   7. **Webhook endpoints (create AFTER the first deploy, the route must answer)**: three, Snapshot payload:
      * `https://api-$SUFFIX.afrotransact.com/payments/v1/webhooks/stripe` (prod: `https://api.afrotransact.com/...`), Your account, 15 events: `payment_intent.succeeded`, `payment_intent.amount_capturable_updated`, `payment_intent.payment_failed`, `payment_intent.requires_action`, `payment_intent.canceled`, `charge.refunded`, `charge.dispute.created`, `charge.dispute.closed`, `transfer.reversed`, `payout.paid`, `payout.failed`, `setup_intent.succeeded`, `setup_intent.requires_action`, `payment_method.attached`, `payment_method.detached` -> secret name `STRIPE_WEBHOOK_SECRET`
      * `.../seller/v1/webhooks/stripe-connect`, **Connected accounts**, 9 events: `account.updated`, `capability.updated`, `person.created`, `person.updated`, `account.external_account.created`, `account.external_account.updated`, `account.external_account.deleted`, `payout.failed`, `account.application.deauthorized` -> `STRIPE_CONNECT_WEBHOOK_SECRET`
      * `.../seller/v1/webhooks/stripe-billing`, Your account, 2 events: `payment_intent.succeeded`, `payment_intent.payment_failed` -> `STRIPE_BILLING_WEBHOOK_SECRET` (optional)
      (derived from `StripeWebhookAdapter` / `StripeWebhookServiceImpl` in `java/services/payments` and `java/services/seller`.)
   8. Put each `whsec_` into `secrets.local.env`, re-run `./secrets.sh services`, re-encrypt, `apply.sh`, then `kubectl -n afrotransact-$ENV rollout restart deploy/afrotransact-core` (the secret is read at start).
   9. **Verify**: unsigned POST must be 400 (`ENV=$ENV tools/smoke/go-live.sh` does it). With the Stripe CLI: `stripe listen --forward-to https://api-$SUFFIX.afrotransact.com/payments/v1/webhooks/stripe --events payment_intent.succeeded` and `stripe trigger payment_intent.succeeded` (test mode only), or Dashboard > Webhooks > the endpoint > Send test event: expect `2xx`. A real test order with card `4242 4242 4242 4242` must move to paid.
   10. Delivery fails: Dashboard > Webhooks > endpoint > Event deliveries shows the status; 400 = wrong or missing secret (restart core after changing it), 404 = route or Argo not synced (`kubectl -n afrotransact-$ENV get httproute`), 502/503 = core not Ready; Stripe retries for up to 3 days, then use **Resend** on the event.
   11. **Test mode rehearsal first (uat), then the live switch (prod)**: repeat steps 5-9 in live mode with new keys and endpoints; never reuse a test secret in prod.
   Rollback: remove or disable the endpoint in Stripe; revert the Secret and restart.

## 7. Deploy: Argo CD sync order, Temporal, web, docs

1. **Platform** (wait Healthy between): `argocd app sync namespaces-$ENV envoy-gateway-$ENV opentelemetry-collector-gateway-$ENV`, then `edge-$ENV` (needs the `cloudflared` Secret), then `lean-$ENV` (Kafka, Temporal, Meilisearch, ClamAV, backup CronJob, Alloy).
   Expected: `kubectl -n kafka get pods` (kafka-0 Ready), `kubectl -n temporal get pods`, `kubectl -n ingress get pods` (cloudflared, envoy Running), `kubectl -n data get pods` (meilisearch, clamav; **no valkey**).
   **Kafka**: single-node KRaft in the cluster (`lean/kafka.yaml`, 640Mi request, 10Gi PVC), topics created by a Job; the prod and uat profile expect `KAFKA_BOOTSTRAP=kafka-0.kafka.kafka.svc.cluster.local:9092`; no Confluent. **Search**: Meilisearch in the cluster (`SEARCH_PROVIDER=meilisearch`).
2. **Temporal (self-hosted, both envs)**: `kubectl -n temporal logs deploy/temporal | tail` shows the namespace `afrotransact` created (retention 7d, `DEFAULT_NAMESPACE_RETENTION`), 4 history shards, state in Layerbase `temporal` and `temporal_visibility` (role `temporal_app`, schema created by the auto-setup on first start, 5 connections per database = 10). Service `temporal.temporal.svc.cluster.local:7233` is a ClusterIP, a NetworkPolicy admits only namespaces labelled `part-of=afrotransact` and Alloy; no public route; UI is not deployed (port-forward the CLI instead).
   Verify: `kubectl -n temporal exec deploy/temporal -- temporal operator namespace describe --namespace afrotransact --address 127.0.0.1:7233` (or `tctl --ns afrotransact namespace describe`; the exact CLI in the image is UNVERIFIED for 1.28.1). List workflows: `kubectl -n temporal port-forward svc/temporal 7233 &` then `temporal workflow list --namespace afrotransact --address 127.0.0.1:7233`.
   **If Temporal is down**: `POST /v1/checkout` answers **503 `orders.checkout-unavailable`** (`TemporalCheckoutWorkflows`); carts and browsing keep working; workflows already running are durable in Postgres and resume when it returns (a single replica pauses them during a restart). Backup: Temporal's databases are in the nightly `pg-backup` (B2); restore like any database (`restore-drill.md`).
3. **Deployables**: `argocd app diff afrotransact-core-$ENV` then `argocd app sync afrotransact-core-$ENV`; then `afrotransact-go-$ENV`. **First-deploy migrations**: core's start runs Flyway for each module on its own schema with its own role (`flyway_schema_history` per schema); a failed migration stops the start with a message naming the module (CrashLoop, nothing else is touched); nothing runs in parallel because there is one pod; startup budget 60 s (S2), the probes are `startupProbe` on `/actuator/health/liveness` (management port 9090). Fix forward, never edit an applied migration; rollback is the previous image (forward-only schema, expand then contract).
   Expected: `kubectl -n afrotransact-$ENV rollout status deploy/afrotransact-core` Ready, `deploy/afrotransact-go` Ready.
   The 21 old Applications must show `scaled to 0` (they are not synced for the first time; do not sync them).
4. **Web (three Workers) and docs, end to end**:
   * One Worker per app per env, defined in `web/apps/<app>/wrangler.jsonc` under `env.uat` / `env.prod` (name `storefront-uat`, route `uat.afrotransact.com/*`, vars `APP_ENV`, `CSP_MODE`, `MEDIA_PUBLIC_URL`, `PUBLIC_WEB_URL`, `BFF_ORIGIN` = `https://api-uat.afrotransact.com/bff`, `SELLER_CENTER_URL`, `STAFF_CONSOLE_URL`, `API_MOCKS=0`); **no Worker secrets**, one build serves every env. The host map check: `python3 tools/ci/env_hosts.py check`.
   * GitHub environment secrets `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID` on `uat` and `prod`. Deploy: Actions > `web-storefront` / `web-seller-center` / `web-staff-console` > Run workflow > `environment: $ENV` (prod needs approval; workflow `_web-app.yml` runs `wrangler deploy --env $ENV`). Preview environment = the PR preview (`preview` env, mock BFF), never pointed at uat or prod.
   * Routes come from tofu (step 5.6); do not create custom domains in the Worker UI (they refuse to overwrite the DNS record). Cache: Next.js/OpenNext sets cache headers; add no Cache Rule that caches `/bff/*` or authenticated HTML. WAF and rate limits (prod run, `manage_zone = true`): managed ruleset, custom WAF (staff console `admin*` challenge), rate limits on auth and checkout paths. **Route denies** are in the Envoy edge (`HTTPRouteFilter deny-operational-paths`): `/modules`, `/mcp`, `/v1/tools`, `/.well-known/oauth-protected-resource` and every `<prefix>/modules` answer **404** on `api` and `auth`; there is no `mcp.` and no `grafana.` host.
   * SSL/TLS mode **Full (strict)**, Always HTTPS, min TLS 1.2, HSTS: set by tofu in the prod run (`zone.tf`); the uat run reuses the zone.
   * **Docs portal** (Docusaurus, `docs/portal`) deploys to **Cloudflare Pages** project `afrotransact-docs` by `.github/workflows/docs.yml` (main and release-1 pushes, needs the two Cloudflare secrets at repo level); every env's `developers-<env>` host serves the same production deployment (tofu attaches the domain).
   * **Storybook on Vercel (free Hobby)**: Vercel > Add New > Project > import the GitHub repo > **Root Directory** `web/packages/ui` (tick "Include source files outside of the Root Directory"); framework "Other"; the repo's `web/packages/ui/vercel.json` sets Install `pnpm install --frozen-lockfile`, Build `pnpm --filter @afrotransact/ui build-storybook`, Output `storybook-static`, `X-Robots-Tag: noindex`; **no environment variables** and no secrets (`STORYBOOK_*` variables are inlined into the public bundle). Settings > Domains > add `storybook.afrotransact.com`, then in Cloudflare DNS create `storybook` CNAME to the value Vercel shows, **DNS only (grey cloud)**. Settings > Deployment Protection: enable Vercel Authentication for Preview deployments (Hobby cannot protect production: it is a public design reference, noindex). Set the GitHub repository variable `STORYBOOK_HOST=vercel` so `storybook.yml` stops deploying to Pages.
5. **Probes and metrics**: `kubectl -n afrotransact-$ENV port-forward deploy/afrotransact-core 9090` then `curl -s localhost:9090/actuator/health/readiness` (UP, lists each module) and `curl -s localhost:9090/actuator/prometheus | grep -c '^at_module_up'` (14); go: `kubectl -n afrotransact-$ENV port-forward deploy/afrotransact-go 8080` then `/readyz` and `at_module_up` (7). Traces: OTLP to the collector (`otel-collector.observability:4318`); metrics and logs to Grafana Cloud through Alloy.
6. **Search index** once catalog data exists: `kubectl -n afrotransact-$ENV create job reindex-1 --from=cronjob/indexer-reindex` (that CronJob belongs to the per-service indexer chart; whether the deployable chart renders it is UNVERIFIED: if it is absent, run the indexer's reindex command in the `afrotransact-go` pod, `tools/release` and `release-1-architecture.md`).
7. Backups: B2 key test first (`backups-backblaze.md`), then `kubectl -n ops create job --from=cronjob/pg-backup first-backup`.

## 8. What the UAT dry run must prove before PROD (tick every line, with the date)

1. Offline gate (section 4.2) green for **both** `uat` and `prod`.
2. Images built for the head sha, signed, pulled by the cluster (`kubectl -n afrotransact-uat get pods -o jsonpath='{..image}'` shows `@sha256:`).
3. `ENV=uat tools/smoke/go-live.sh` exits 0 (web, api, auth, three webhook routes 400, route denies 404, CORS, noindex).
4. Probes: core readiness UP with 14 modules, `at_module_up` 14 + 7, no CrashLoop, startup under 60 s (`kubectl get pod -w`).
5. **Resource headroom**: `kubectl top node` after 30 minutes; node memory requests < 70 %; core RSS < 1.8 GiB (`CoreMemoryNearCap` quiet); `kubectl describe node | grep -A12 "Allocated resources"`.
6. **Stripe test order end to end**: sign up, add address, buy a product with `4242 4242 4242 4242`, the order becomes paid (webhook 2xx in the Stripe dashboard), refund it, onboard a test seller (Connect Express-style onboarding with Stripe test data), payout events recorded.
7. **Temporal saga**: `temporal workflow list` shows the checkout workflow Completed; stop Temporal (`kubectl -n temporal scale deploy/temporal --replicas=0`), confirm checkout answers 503 `orders.checkout-unavailable` and browsing works, scale back, confirm a workflow started before the stop completes.
8. **Rollback rehearsal**: promote the previous sha and `argocd app sync`; also the emergency path of release-1-deploy.md section 7 (scale core to 0 and back) and `argocd app rollback afrotransact-core-uat <id>`.
9. **Restore-from-backup rehearsal on Layerbase**: `first-backup` job succeeds, then `restore-drill.md` into a scratch database on the Layerbase server; record the time (RTO) and that PostGIS-dependent tables restore.
10. **Cloudflare**: route denies and webhook routes (item 3), the three Workers answer on their hosts, `admin-uat` is challenged by the WAF, no origin port is open (`nmap` the node IP: nothing).
11. Kafka outbox: sign-up mail arrives (identity -> outbox -> Kafka -> notifications -> Resend); `outbox_pending_events` not growing; consumer lag 0.
12. **No demo data in prod, and none in uat**: the seeder (`tools/seed/seed.py`) refuses `APP_ENV`/`SPRING_PROFILES_ACTIVE` naming uat, prod, production or staging and any host under `afrotransact.com` other than dev (`tools/seed/README.md`, "Guards"; `seed-guard.yml` tests it). Do not set `SEED_ALLOW_REMOTE`. Verify after deploy: `select count(*) from catalog.products` is 0 in prod, no user `*.afrotransact.test`, no `src=demo-seed`. UAT test data is created by hand (staff console, seller center) or by `make smoke`-style scripts against the public API, never by the seeder. Changing that guard would be a code decision for the owner.
13. AI cap: `AI_GLOBAL_DAILY_COST_USD=2.00`, `AI_GLOBAL_DAILY_COST_MAX_USD=20.00`, `AI_CALLER_DAILY_COST_USD=0.50`, `AI_SPEND_CAP_CACHE_SECONDS` default 10 (in `java/apps/core/deploy/helm/values.yaml`); one Lola message works and the daily usage row is below USD 2. `DRIVER_APP_ENABLED` is unset (false): the staff console shows driver modules as dormant.
14. Connection budget: `select usename, count(*) from pg_stat_activity group by 1` on `core` and `go` stays under each role's limit; total <= 80 steady.
15. Alerts: the `env=uat` alerts are silent by design; trigger one test alert in prod's Grafana contact point before prod goes live.

## 9. PROD: repeat sections 4-7 with the section 3 values, then

1. Prod-only items: `ENV=prod`, a new OVH project, a second Layerbase (Postgres + Valkey), **live** Stripe keys and endpoints, the prod Cloudflare run (`envs/prod.tfvars`, `manage_zone = true`), prod GitHub environment reviewers, `GRAFANA_ENV=prod`, real `ADMIN_EMAIL`/`ADMIN_PASSWORD` (16+ characters; identity refuses a weak bootstrap admin), `EXTERNAL_PG_SSLMODE=verify-full` with the Layerbase CA.
2. Promote with `environment: prod` (reviewers approve), `python3 infra/argocd/scripts/check_launch_set.py --env prod --require-tags`, sync in the order of section 7.
3. `ENV=prod tools/smoke/go-live.sh` (read-only; safe against production), one small real payment, one refund, one payout test.
4. **Rollback plan**: image: re-promote the previous sha and sync (rolling update keeps the old pod until the new one is Ready); platform component: `argocd app rollback`; module off: `kubectl -n afrotransact-prod set env deploy/afrotransact-core MODULE_<NAME>_ENABLED=false` (git must then say the same); database: forward only, take `pg_dump -Fc` before a destructive migration; whole cluster: section 14 (RTO unproven until the drill in section 8.9).
5. **Same account or separate?** Recommendation: **separate OVH Public Cloud projects (same OVH account is fine, billed separately per project) and separate Layerbase servers.** The reasons: the role and database names are fixed (`core`, `go`, `catalog_app`, ...) so two envs cannot share a server; the uat project is disposable (`tofu destroy` after the dry run) and a shared project cannot be; the OVH US credit applies to the account, so check it can be used in the project you create.

## 10. Cost: UAT on the USD 200 credit, and PROD

**OVH public pricing could not be fetched from the session that wrote this (host not reachable): every OVH price below is UNVERIFIED and is a formula, not a quote. Get the numbers from the OVH US
price page and the console before you start, and put them in `monthly_budget` in `infra/tofu/envs/<env>/sizing.tf`.**

| Line | Assumption (UNVERIFIED) | Note |
|---|---|---|
| MKS control plane | free plan | repo assumption (`cloud-cost.md`); confirm in `US-EAST-VA-1` |
| Node `b3-16` (4 vCPU, 16 GB) | **hourly** for uat (monthly billing cannot be undone and is about half the hourly rate only if kept all month); price `P` USD/hour | the plan needs 7.3 GiB of requests (8.9 GiB with a surge) of ~13 GiB allocatable: **`b3-16` is the cheapest flavour that fits** (an 8 GB node has ~6 GiB; removing ClamAV, 1.25 GiB, still does not fit) |
| Block storage | Kafka 10 GiB + Meilisearch 5 GiB + boot disk, `S` USD/GiB-month | small |
| Load balancer | **none created** (Cloudflare Tunnel) | keep it that way: a `LoadBalancer` Service creates a paid LB |
| Egress | OVH US public cloud traffic: assumed included/low for UAT | verify |
| Layerbase (Postgres + Valkey), Resend, Grafana Cloud, Backblaze B2, Cloudflare Workers Free/Paid $5, R2, Vercel Hobby, Stripe fees | outside the OVH credit; Layerbase 4 GB plan about USD 35 (UNVERIFIED), the rest free tiers | the USD 200 is only for OVH |

**Will the USD 200 last 4 weeks?** 4 weeks = 672 h. All-in OVH spend per hour must stay below `200 / 672 = USD 0.298/h` (node + storage). If `P` plus storage is under about USD 0.25/h it lasts the 4 weeks; if `P` is higher,
use the pause below. Check after 24 h in the OVH console (consumption) and multiply. Set the OVH consumption alerts at 50/80/100 % of 200.
**Save money**: no in-cluster Prometheus/Loki/Tempo (already off: Grafana Cloud free), web and docs on Cloudflare (not the cluster), Postgres and Valkey on Layerbase (not in the cluster), no load balancer, no vRack gateway, 1 replica of everything, ClamAV can be removed for uat if a malware scanner is not needed (`lean/kustomization.yaml`, 1.25 GiB).
**Pause / scale to zero when idle** (hourly billing: a stopped node pool stops compute billing, volumes and the control plane keep their cost, UNVERIFIED for MKS): `kubectl -n afrotransact-uat scale deploy --all --replicas=0; kubectl -n kafka scale sts --all --replicas=0; kubectl -n temporal scale deploy --all --replicas=0`
(Argo CD `autoSync` is off, so it will not undo it), then in the OVH panel scale the node pool to 0 (or `tofu apply -var node_count=0` if the module supports it; otherwise delete the node pool and recreate it). To resume: scale the pool to 1, wait for the node, scale the workloads back (core last). Data is safe in Layerbase and the PVCs.
**PROD estimate** (same shape, monthly billing): `monthly node price + storage + Layerbase (Postgres + Valkey)`: UNVERIFIED, about USD 150 to 200 per month all-in per `cloud-cost.md` (placeholder `monthly_budget = 150` for OVH only); replace with the quote.

## 11. DNS: tonight and next month (clearly separated)

**Tonight (Cloudflare DNS is authoritative: the nameservers are already at Cloudflare).** Create only the records of the table in step 5.6 (tofu creates them) plus the Resend and Vercel records. **Do not touch Route 53 tonight**: the registrar and any Route 53 hosted zone stay as they are; with Cloudflare as the authoritative nameserver Route 53 DNS records are not used. Check: `dig +short NS afrotransact.com` returns Cloudflare nameservers.

**Next month: registrar transfer from Route 53 (separate task, not part of go-live).**
1. Before: export the Cloudflare zone and compare with the Route 53 zone (MX, SPF/DKIM/DMARC TXT, any other TXT) so no email record is lost; lower the TTL of those records to 300 s a day ahead.
2. DNSSEC: if DNSSEC is enabled at the registry for the domain, remove the DS record at the registrar (Route 53) **before** the transfer if Cloudflare is not signing, or keep Cloudflare's DS at the registrar after the transfer; do not leave a DS that no longer matches the serving nameservers (the domain goes dark).
3. Route 53 console > Registered domains > the domain: disable **Transfer lock**, request the **authorization code**, confirm the admin contact email works (the transfer approval email goes there).
4. Cloudflare > Domain Registration > Transfer Domains: enter the code, pay the 1-year renewal; approve the email; the transfer takes up to 5-7 days (UNVERIFIED).
5. After: verify `whois`/registrar shows Cloudflare, nameservers unchanged, `ENV=prod tools/smoke/go-live.sh` green, MX/SPF/DKIM/DMARC resolve (`dig TXT`), Resend domain still verified, then delete the unused Route 53 hosted zone (it is billed per month) after a week.

## 12. Go / no-go (as of this commit)

* **UAT: NO-GO until blocker 1 is cleared** (no image can be built). Everything else in the repo renders and validates offline; the first live run will find vendor surprises (that is its purpose).
* **PROD: NO-GO** until UAT has passed section 8, the Layerbase CA/`verify-full`, the Stripe live activation and a measured restore.

## 13. Image trust in production: signatures, digests, and the admission control that is NOT deployed

What protects production from a wrong or tampered image today:

1. **Signature verification happens at promote time.** `_deploy.yml` runs `cosign verify` (keyless; the certificate identity must be this repository's `_go-service.yml` / `_java-service.yml` on `main`) on the GHCR image, reads the manifest digest from the verification result, verifies again **by digest**, and only then writes `image.tag` and `image.digest` into `values-<env>.yaml` on release-1 (`_deploy.yml`). The prod job also waits for the `prod` environment reviewers.
2. **Deploy is by digest.** The pod spec carries `repo:tag@sha256:<digest>`. A tag that is later moved or re-pushed in GHCR cannot change what runs; a changed image needs a new promote (new verify, new reviewed commit). `python3 infra/argocd/scripts/check_launch_set.py --env prod --require-tags` fails if any launch-set service has no digest pin.
3. **Registry push rights** are limited to the CI workflows (GITHUB_TOKEN with `packages: write` on image jobs only); the cluster pulls with a read-only secret.

What is **not** in place: **admission control (Kyverno `signed-images`, `infra/k8s/platform/policies/signed-images.yaml`) is deferred and excluded from the lean OVH platform** (`infra/argocd/applicationsets/lean/platform-charts.yaml`). Nothing in the cluster re-checks signatures when a pod is created, so someone with `kubectl`/Argo CD write access could still run an arbitrary image; the controls above stop a bad image arriving through the pipeline, not through a stolen cluster credential. Cost to add it later: about **0.3-0.5 GiB RAM** for Kyverno (admission controller, plus background/cleanup controllers if enabled) on the single prod node. Revisit when the node is upsized or before the first untrusted-operator access; until then keep cluster-admin credentials to the owner only. This supersedes the older wording that said Kyverno admits only signed images: that holds for dev/UAT clusters that run the full platform, not for the lean production platform.

## 14. Rollback in detail (and the whole cluster)

- **Application:** images are immutable sha tags. Re-promote the previous sha (or revert the commit that bumped `image.tag` in `values-<env>.yaml`) and `argocd app sync afrotransact-core-<env>`,
  or `argocd app rollback afrotransact-core-<env> <history id>` (history is kept, `revisionHistoryLimit: 10`; auto-sync is off so this is allowed). The old pod keeps
  serving until the new one is Ready (`maxUnavailable 0`).
- **Database:** forward only. A schema change ships expand-then-contract, so the previous version still runs against the new schema for one release.
  Take `pg_dump -Fc <db>` of the affected database by hand before any release with a destructive migration. Never run a down-migration.
- **Platform component:** `argocd app rollback` the Application, or `git revert` and sync.
- **Whole cluster lost:** tofu apply in a new project (or the same after destroy), restore secrets from the SOPS files and `STATE_DIR` escrow, bootstrap Argo, sync in
  the order of section 7, reconnect to Layerbase (no data lost), `reindex-search`, republish the outbox if events were lost (`docs/runbooks/kafka-replay.md`).
  Target RTO 4 hours for the database, 2 to 4 for the cluster (**not proven**: the drill measures it).
- **Maintenance mode:** `docs/runbooks/maintenance-mode.md`.

## 15. Measure in UAT (decision data) and day 2

`kubectl top pods` RSS per JVM at idle and under a k6 run (`tools/perf`, 50 concurrent shoppers) against the requests in cloud-cost.md; node
requests vs allocatable; CPU during a 3-service sync; downtime of a rolling update and of a node reboot (time to all-Ready, PVCs reattach, Kafka/
Temporal resume); `pg_stat_activity` peak vs the plan (80 steady / 140 with a rolling-update surge, per role below its `CONNECTION LIMIT`, `cloud-cost.md`); migration times; Layerbase RTT; backup run time and size; **restore time**;
Kafka disk growth per day; Grafana Cloud series and log volume against the free limits (`docs/runbooks/observability-grafana-cloud.md` section 10; also `kubectl top pods -n observability` for Alloy); cost per day x 30 for node, disks and buckets.

**Day 2**

- Monthly: restore drill, cost review, `kubectl describe node` headroom, certificate/tunnel check, rotate nothing unless needed.
- OVH node upgrades need a maintenance window (downtime). Tell users.
- Alerts that must exist (all but the uptime check are coded in `infra/observability/rules/launch.yaml` and loaded into Grafana Cloud, see the table in `docs/runbooks/observability-grafana-cloud.md` section 9; plus an external uptime check on the storefront, `/bff/healthz` and the OIDC discovery URL): site/API
  down, 5xx rate on gateway-bff, Stripe webhook failures, Kafka consumer lag / outbox backlog / DLQ above 0, Postgres connections near the limit,
  node memory above 85 %, OOMKilled/CrashLoop, PVC above 80 %, **missed backup** (alert `BackupStale`, 26 hours, and the dead-man's-switch), backup size (`BackupSizeHigh` above 600 MB per night, `BackupBucketSizeHigh` above 8 GB; `infra/observability/rules/backup.yaml`), Layerbase storage near the limit.
- **Optional: a dead-man's switch (heartbeat).** A nightly `pg-backup` that never starts raises no alert by itself, and an alert path that is down stays silent. Add ONE external heartbeat:
  either a Grafana Cloud Synthetic Monitoring check on the storefront and `/bff/healthz`, or a heartbeat URL of a ping service ("healthchecks"-style) that the backup job calls
  (`BACKUP_HEALTHCHECK_URL` in `secrets.local.env`; the job pings it after a successful run and the service alerts you when the ping is missing). No vendor is chosen here and none is required for UAT;
  the in-cluster `BackupStale` alert (26 hours) covers the common case.
- Pre-orders, community, retail and delivery-runs are **not launched**: hide their navigation in the storefront (delivery-runs is not deployed).

## 16. Questions for vendors, in writing (all answers currently unknown)

**Layerbase**

1. Exact plan and price for 4 databases (`core`, `go`, `temporal`, `temporal_visibility`) with 17 login roles, each with a `CONNECTION LIMIT`, and the admin must `GRANT` roles to itself `WITH SET TRUE`); is Dedicated 4 GB right? Per-database or per-role limits? Extension list: PostGIS version, `btree_gist`,
   and whether the admin may `CREATE EXTENSION postgis` in a database (the release-1 bootstrap pre-creates `postgis` and `btree_gist` in `core` and `go`).
2. Which cloud, region and data centre hosts the Virginia server; private connectivity or peering options toward OVH US East; expected round-trip time.
3. `max_connections` per plan, any pooler (PgBouncer mode), idle-connection reaping. (Our plan: 80 steady, 140 with a rolling-update surge; need >= 150.)
4. Is TLS with a password enough, or is mTLS mandatory? CA certificate and server name for `verify-full`.
5. Backups: frequency, retention, point-in-time recovery (window and granularity), restore method (new instance / in place / per database), restore time for
   our size, who runs it, cost of extra copies, and may we run our own `pg_dump` and `pg_dumpall --globals-only` (superuser or equivalent role)?
6. High availability and failover, maintenance windows, minor/major upgrade policy (PostgreSQL 17), SLA, status page, support channel and response time in an incident.
7. Behaviour at the storage limit (overage price, hard stop or read-only), alerts, resizing without downtime.
8. Egress and ingress charges, in writing; data location and a DPA for KYC-adjacent data; startup-credit programme eligibility.
9. Can the superuser create roles and databases, set ownership (`ALTER DATABASE ... OWNER TO`) and `GRANT` a role to the admin (the bootstrap scripts need all of these)?
10. Is `pg_dump -Fc` from outside their network allowed (IP allow-listing: from the node's public IP and from backup/operator addresses)?

**Others**

**OVH:** MKS free plan in US East; Free vs Standard SLA; `b3-16` monthly and hourly price; node upgrade windows and what happens to PVCs; block storage and CSI
snapshot support; Object Storage price, **object lock** in the US; whether the US MKS API supports IP restrictions that keep node-to-API traffic working;
outbound traffic charges. **Cloudflare:** free-plan WAF and rate-limit rule quotas, Tunnel limits, Workers Paid need, R2 versioning/lifecycle (media only; backups are on Backblaze B2). **Backblaze B2:** free-tier storage and transaction limits, what an S3 delete does in a versioned bucket, lifecycle rule behaviour, Object Lock, the capabilities of a bucket-scoped key (all UNVERIFIED in `backups-backblaze.md`).
**GitHub:** GHCR private package storage and transfer limits. **Grafana Cloud:** free-tier limits and alert-rule quotas.

## 17. Known gaps (not done, do not forget)

- No admission control in prod (Kyverno deferred, ~0.3-0.5 GiB): image trust rests on signature verification at promote plus digest pinning (section 13).
- Nothing was applied, rendered by tofu, or tested against OVH, Layerbase or Cloudflare. Argo CD, `kustomize build` and `helm template` were run for the
  repository's own manifests only.
- The backup CronJobs run from the pre-built `backup-tools` image (non-root, read-only root; build it first: `read-only-root-first-boot.md`). Every lean workload now runs with a read-only root filesystem and the mounts listed there are UNVERIFIED until the UAT dry run.
- The OTLP collector only has an OTLP receiver: service metrics (`/actuator/prometheus`, `/metrics`) are not scraped and logs are not shipped; decide between
  a Prometheus receiver, OTLP metrics or `kubectl logs` + traces only at launch.
- The tax, legal, Stripe Connect, Resend domain and counsel items of section 19 are external lead-time gates.
- v1 -> v2 migration (Keycloak users, the Stripe account, past orders): [v1-to-v2-migration.md](v1-to-v2-migration.md) and `docs/engineering/keycloak-migration.md`.
- **Argo CD version**: nothing in the repository pins or installs it except the command of section 5.7; choose the latest stable 3.x tag on the day, write it in your
  notes and install exactly that tag in UAT and later in PROD (the manifests rely on `goTemplate`, multi-source Applications and ApplicationSet matrix generators). UNVERIFIED.
- Optional OKMS + External Secrets path (not verified): `ovh_okms = true` in tofu is refused for US regions; see `infra/k8s/platform/lean/external-secrets/externalsecrets.yaml`. Do not mix it with SOPS.
- Keep two people able to operate: two age keys, two OVH users. Set the cluster **maintenance window** to a quiet hour in the OVH panel: one node means every node upgrade is downtime.

## 18. Seller self-shipping switch must be off at launch

`select seller_self_shipping_enabled from shipping_settings;` on the orders database must return `false` in uat and prod (the column default is false;
orders `V15`). Staff turn it on only in Staff console > Fees > Flat delivery fee, with a reason, after the owner picks a rule in
`docs/engineering/seller-self-shipping-options.md`. Fulfilled by AfroTransact costs sellers nothing: there is no per-unit FBA fee to configure.

## 19. Launch readiness: owner, vendor, legal and blocker items

Folded in from the former production-readiness checklist (owner, vendor and legal items, and every **blocker**; non-blocking engineering
checks are covered by sections 4 and 8 and CI). Work through it before the first real customer and again before every major release.

**Who** (first tag on each line)
- `OWNER`: a decision, an account, a signature or a payment only the business owner can give.
- `ENGINEERING`: work or verification in this repository or on the cluster.
- `VENDOR`: waits on an outside provider (Stripe, OVH, Layerbase, Cloudflare, Resend, OpenRouter, counsel's firm, an accountant). `OWNER/internal` means the owner handles it inside the business (tax professional, counsel); it does not block engineering work.

**Status** (second tag): `Open` (not started), `In progress`, `Blocked: <on what>`, `Checked in code` (read in the source on
2026-11-12; says nothing about the live system), `Done` (checked on the live system, with date and name written on the line).
Nothing is `Done` until it has been checked where it runs.

### Top risk: existing connected accounts differ from v2's

- [ ] **blocker** `OWNER · Open` **Existing connected accounts: keep or re-onboard.** The owner's Stripe Connect Platform setup screen
  shows v1's connected accounts were created with the direct charges business model, losses collector Stripe, fees collector the
  account, dashboard **Full** (the seller's own Stripe dashboard) and Stripe-hosted onboarding, and Stripe shows an integration
  warning that the platform currently creates destination charges. v2 (`StripeConnectAdapter.createAccount`, lines 39 to 66)
  creates accounts with fees payer ACCOUNT, losses STRIPE, requirement collection STRIPE, Stripe dashboard **NONE**, capabilities
  `card_payments` and `transfers`, a platform-set manual payout schedule, and uses separate charges and transfers (the platform owns
  the charge, refunds and disputes). Controller properties of an existing account cannot be changed (UNVERIFIED), so v1's accounts
  keep a Full dashboard. UNVERIFIED consequences: the platform may be unable to set the payout schedule or request `transfers` on
  them; sellers keep their own dashboard and can change payout settings or disconnect; v2's embedded onboarding may not accept them.
  Evidence is the owner's screen, not an account read: the real type of each account is still to be read from its page. Options A
  (keep and test in Stripe test mode), B (re-onboard sellers to v2-style accounts) and C (hybrid), the test list and the exact
  questions for Stripe support are in [v1-to-v2-migration.md](v1-to-v2-migration.md), "Existing connected accounts: keep or
  re-onboard". Nothing else in the cutover starts until this is decided.
- [ ] **blocker** `VENDOR · Open` Stripe support's written answers to the question list in the cutover runbook (Full dashboard with separate
  charges and transfers, transfers capability, payout schedule, controller changes, the destination-charge warning), with the case id.
- [ ] **blocker** `ENGINEERING · Open` Option A test run in Stripe **test mode** against an account created like v1's (full dashboard): separate charge, transfer,
  refund with reversal, payout-schedule update, capability request, embedded onboarding, deauthorisation event; results recorded.
  Backlog story S379 (the test) and, if a flow is refused, S378 (tolerate both account shapes).

### 19.1 Environment and configuration

- [ ] **blocker** `ENGINEERING · Open` `APP_ENV=prod` on every service and every web app (the Helm library chart sets it for
  services; check the Worker vars of the web apps). The web apps turn the mock backend off when it is `prod`, `production` or `uat`.
- [ ] **blocker** `ENGINEERING · Open` `API_MOCKS` and `NEXT_PUBLIC_API_MOCKS` are not set anywhere in production.
- [ ] **blocker** `ENGINEERING · Open` Spring profile `prod` (Java) and `APP_ENV=prod` config files (Go) are the ones loaded; no `local` profile.
- [ ] **blocker** `ENGINEERING · Open` Security is on: no service runs with the local "security off" mode or the `local-dev` actor.
- [ ] **blocker** `ENGINEERING · Open` The payments service's local test stand-in for Stripe is off (allowed on the `local` profile only).
- [ ] **blocker** `ENGINEERING · Open` OVH production path exists and is rehearsed in UAT: Managed Kubernetes cluster, the Cloudflare **Tunnel**
  (cloudflared + Envoy Gateway; there is **no load balancer and no ingress certificate**, Cloudflare terminates TLS), Argo CD with manual sync, SOPS/age secret
  delivery, and the Helm values for the launch stack (21 services, FGA off, no native-app services). The path is this runbook (sections 4-9).
- [ ] **blocker** `ENGINEERING · Open` Services that are off at launch are confirmed off and not routed: FGA (`FGA_ENABLED=false`
  explicitly set on catalog, offers, orders, payments and the others that read it), authz-sync not deployed or idle, native-app
  push, WhatsApp. With FGA off, access control is RBAC plus organisation checks; run the cross-organisation access tests once
  with FGA off (a seller must not read another seller's orders).

### 19.2 Data

- [ ] **blocker** `ENGINEERING · Open` No demo data: the production databases contain no rows from `tools/seed` (demo sellers, products, orders,
  test users such as `customer.houston`, `driver@afrotransact.test`, `seller.owner`). Guards that keep it out, all enforced
  in code or CI: `tools/seed/seed.py` refuses `APP_ENV`/`ENVIRONMENT`/`SPRING_PROFILES_ACTIVE` naming prod, uat or staging, refuses any
  service URL under afrotransact.com except the dev hosts (even with `SEED_ALLOW_REMOTE=1`), parses hostnames (no
  `localhost` look-alikes) and needs `SEED_ALLOW_REMOTE=1` for any non-local target (`make seed-test`, `tools/seed/tests/test_guard.py`);
  identity creates test users only on local (dev by opt-in with its own password) and refuses to start on uat/prod with the
  published bootstrap-admin password, a `.test` admin address or a password under 16 characters; delivery-runs' demo areas are
  local-only; `python3 tools/ci/check_env_config.py` fails the build when a uat/prod config enables any of it or contains
  `afrotransact.test`, `LocalAdmin2026`, `Test1234!Local`, `demo-seed` or `SEED_ALLOW_REMOTE`. Still check the data once before
  launch: search for the `src=demo-seed` order attribution and `*.test` email addresses.
- [ ] **blocker** `OWNER · Open` Reference data is present and reviewed: product taxonomy, plans, review reason codes, markets, fee schedule,
  sales tax rate tables and tax codes, legal documents, cookie banner config.
- [ ] `OWNER · Open` Markets, states and cities for launch are switched on in Settings → Markets & locations (nothing outside them is offered a
  run); pre-order areas match.
- [ ] `OWNER · Open` Super Admin and first staff accounts exist; every staff role has only the permissions it needs (Users & roles).
  Fulfilment staff hold `orders:update-status` or `orders:manage` only if they are trained on the pickup step (19.5).
- [ ] `OWNER · Open` Retail (AfroTransact Retail) organisation, suppliers and warehouse centres are configured; exchange rates for the currencies
  you source in are entered (Settings → Exchange rates).
- [ ] **blocker** `OWNER · Open` v1 data migrated and verified: users and passwords, the seller, order history ([v1-to-v2-migration.md](v1-to-v2-migration.md), Part B),
  with answers to the open questions in [v1-to-v2-migration.md](v1-to-v2-migration.md).
- [ ] **blocker** `ENGINEERING · Open` Every migrated v1 user can sign in on v2 with no invite: there is **no sign-up gate** (v1 sign-ups stay open until the
  freeze; v1 registrations are blocked from the freeze; v2 sign-up is open from the switch). Story S355 is closed as not needed.

### 19.3 Secrets and identity

- [ ] **blocker** `ENGINEERING · Open` Every secret comes from the secret store, none from a file in the repo or an image. Rotate anything that was ever
  used in a test environment.
- [ ] **blocker** `ENGINEERING · Open` Identity signing key is **RSA** (3072); `SIGNING_KEY_PEM` is set; the identity data key (`IDENTITY_DATA_KEY`) is set
  and backed up separately from the database.

### 19.4 Security

- [ ] **blocker** `ENGINEERING · Open` Zero known vulnerabilities in what you ship: CI `security` workflow green on `main` (CodeQL, Trivy repo and image,
  Semgrep, govulncheck, osv-scanner, gitleaks); `pnpm audit` clean apart from documented exceptions.
- [ ] `OWNER · Open` Penetration test or an equivalent review done for sign-in, payments, uploads and the staff console.
- [ ] **blocker** `ENGINEERING · Open` **MCP exposure.** Owner decision: the MCP server is **private** (cluster-internal, no public hostname, authenticated tools: OAuth 2.1 bearer tokens from
  identity, tool lists filtered by scope and permission, a global rate limit of 30 burst and 30 per minute in prod, a durable audit
  of every call: `java/services/mcp-server/README.md`). Before launch: (a) **no public route exists**: no `mcp.` DNS name or tunnel rule, no HTTPRoute, the Service is `ClusterIP`; from inside the cluster
  an unauthenticated `POST /mcp` gets `401`; (b) the OAuth client's redirect URIs
  are the allowed MCP clients only; (c) **no tool works without a token**, staff tools need a staff principal, and tools that
  change data need an explicit scope: test with a no-scope token (`-32003`) and a seller token against another seller's data;
  (d) Valkey is reachable (the rate limit fails open to a per-pod bucket otherwise; decide on `MCP_RATE_FAILURE_MODE=closed`);
  (e) `mcp.tool.invoked` audit events reach Kafka and someone reads them; (f) tool descriptions and errors leak no internal
  detail. Story S377.
- [ ] `OWNER · Open` PWA: the three web apps install to the home screen (manifest, icons, service worker, offline page), and the install
  instructions for iPhone and Android are on the sign-in help page. No native apps ship at launch. Backlog story S373.

### 19.5 Payments and money

- [ ] **blocker** `OWNER · Open` Stripe cutover on the **same account** done per [v1-to-v2-migration.md](v1-to-v2-migration.md): inventory, test-mode rehearsal, freeze
  window, three v2 webhook endpoints (payments, seller Connect, seller plan fees) created fresh by the owner with the SDK API version and
  separate secrets, v1's endpoints stopped and deleted at the freeze step (not before; their URLs and events are written down
  first), the connected-account decision above applied, customer ids and saved cards migrated by the owner. v1 captures immediately,
  so no authorised-but-uncaptured v1 PaymentIntents are expected; keep a one-line check that none exist. v1 has no Stripe Billing
  subscriptions.
- [ ] **blocker** `ENGINEERING · Open` Stripe is in **live** mode with live keys and the live webhook secrets; each endpoint verified with **Send test event** (`2xx`);
  Connect settings, payout schedule and the platform's branding reviewed; connected accounts created by v1 are compatible with
  v2's onboarding (account type and who collects verification).
- [ ] **blocker** `OWNER · Open` A real small payment (and a refund) was made end to end on production, including a staff pickup that captured it and, later, the seller payout after the
  complaint hold window.
- [ ] **blocker** `ENGINEERING · Open` **Admin status changes audited and confirmed.** At launch staff set shipment statuses by hand, and **marking a shipment picked up
  captures the customer's money** (the capture task is queued when a shipment first reaches picked up, or ready for a pickup order).
  Checked in code on 2026-11-12:
  - Each change is recorded in the orders service's `shipment_status_change` table with the actor type, actor id, optional reason,
    source and time, and shown as the order's history in the staff console. That is the audit today. It is **not** copied to the
    central audit log (trust-ops) and orders emits no audit entry; history rows are only reachable through the order.
  - The staff console shows a confirmation dialog (from status, to status, order, seller) before any change. It does **not** say that the
    step will charge the customer, and a note is **optional** for picked up (required only for cancelled, delivery failed, and delivered
    where our own driver would normally prove it).
  - Gap: a mandatory reason and an explicit "this will capture the payment" warning on picked up, and an audit-log entry per manual status
    change. Backlog story S353. Until it is done, staff are told to
    write a note on every pickup and a second person reviews the daily list of captures against the shipments.
- [ ] **blocker** `ENGINEERING · Open` **Authorisation expiry of manual capture.** Each order is authorised at checkout and captured when the first shipment is picked up. A
  card authorisation lapses after about 7 days (the payments code says "cards hold ~7 days"). For ordinary orders this is **not handled**:
  if staff pick up on day 8 the capture is refused, the capture task retries 12 times, then parks as FAILED and the order is shipped but
  unpaid (`docs/engineering/inventory-rules.md`). Before launch: (a) the owner sets the rule that fulfilment must pick up within 5 days of
  the order, shown to staff; (b) engineering adds a daily list "authorised, not captured, older than 3 days" and a staff alert for FAILED
  captures; (c) the plan for an expired authorisation (re-authorise the customer's saved card off-session, or cancel and refund) is written;
  (d) the long-dated off-session path exists for bookings and pre-orders only. Backlog story S354.
- [ ] `OWNER · Open` **Stripe's fee is not returned on a refund after capture** (2.9 percent plus 30 cents of the refunded share; known in
  `docs/engineering/inventory-rules.md`, "Stripe's fee share is not returned"; verify against Stripe's current fee terms). Cancel before capture
  costs nothing. Decide who bears the fee (platform, seller, or customer's refund net of it), put it in the seller terms and refund policy, and
  tell staff to cancel before pickup where possible.
- [ ] `OWNER · Open` Reconciliation runs daily with a named person and has no unexplained differences (Payouts & ledger → Reconciliation); maker-checker is on for
  ledger adjustments.
- [ ] `OWNER · Open` Delivery fee rules (Settings → Delivery fees) and fee schedule (Fees & commissions) are final; run economics settings
  reviewed.

### 19.6 Tax

- [ ] **blocker** `OWNER · Open` Sales tax method chosen (our own rules or Stripe Tax) and rates loaded for every state you ship to.
- [ ] `OWNER/internal · Open` **Texas tax professional sign-off (handled internally by the owner, not blocking engineering) on the classification rules, the always-review list and the sample rate.** `taxReview.mode` is `RISK_BASED` by default (owner decision, 13 Nov): the rules decide and the system approves a variant when its category is active and the rules resolve, with a 5 percent audit sample (`docs/runbooks/tax-provider-cutover.md`); only conflicts, no matching rule, missing proof, gated and regulated categories wait for a person. Record the sign-off reference in a settings change reason if the list or the rate changes, and do not change the mode in the same window as the tax method. Staff capacity is needed for the exceptions and the audit sample only; choose `ALL` in *Review settings* if the owner wants a person on everything until the sign-off.
- [ ] `OWNER · Open` Nexus tracker reviewed; registrations in states over the threshold are filed.
- [ ] `OWNER · Open` Stored value (AfroTransact credit, gift cards): terms reviewed by counsel for each launch state (no cash-out, expiry rules,
  escheat), liability reports signed off by finance, gift card codes rate limited.

### 19.7 Delivery, inventory and operations

- [ ] `OWNER · Open` Hubs, delivery zones, pickup points and carrier accounts are real (carrier sandbox credentials replaced with production
  credentials; labels print). Launch shipping and delivery quote rules are final.
- [ ] `OWNER · Open` Staff fulfilment procedure written and trained: who sets which status, the 5-day pickup rule (19.5), what to write in the
  note, what to do when a status was set by mistake (who may cancel or reverse, and the refund consequence).
- [ ] `OWNER · Open` A pre-order window ran end to end (if runs are in launch scope): orders → minimum met or staff decision → run → pick lists → route → delivery proof →
  payout hold started.
- [ ] `OWNER · Open` Inventory: opening stock received with the right currency and exchange rate; expiry dates and lots correct. The inventory rules
  (`docs/engineering/inventory-rules.md`) are waiting on owner decisions; the build is blocked until they are made.
- [ ] `OWNER · Open` Staff on call know the maintenance-mode runbook (`docs/runbooks/maintenance-mode.md`) and where to turn it on.
- [ ] `OWNER · Open` Delivery run and pre-order announcements (S262): email goes out in the customer's language (`en`, `fr`, `es`, `ar`, `yo`) and the translations in
  `go/services/notifications/.../0023_run_preorder_locales.sql` have been read by a native speaker of each language; every announcement link
  carries UTM tags (check one in a received email). A window staff cancel tells each customer by email that the order is refunded in full,
  delivery fee included. Text message follows only if the sender registration is done and the owner wants it at launch; push has no native app to reach,
  so web push is off unless the PWA story delivers it.
- [ ] `VENDOR · Blocked: Meta` WhatsApp run announcements stay **off** (`deliveryRuns.whatsapp.enabled=false`, the default). Not a launch blocker. Steps when wanted are in
  `go/services/notifications/README.md`, "WhatsApp approval".

### 19.8 Observability and incident response

- [ ] `OWNER · Open` Status page / maintenance page text reviewed.

### 19.9 Backups and recovery

- [ ] **blocker** `VENDOR · Open` Layerbase Postgres: backups run daily, **point-in-time recovery** is available and its retention is known, connection limits fit the
  service pools (one database per service; count them against the plan), failover behaviour is documented. Confirm with Layerbase in writing.
- [ ] **blocker** `ENGINEERING · Open` **Restore drill**: restore a production-shaped backup into a scratch database, start the services against it, and read an order and a payment
  back. Record the date, the minutes it took and who did it. Repeat monthly in launch quarter.
- [ ] **blocker** `ENGINEERING · Open` **Nightly copy of the two R2 buckets (media and kyc-evidence) to Backblaze B2: BUILT, NOT YET RUN LIVE.** CronJob `r2-backup`
  (`infra/k8s/platform/lean/backup/r2-cronjob.yaml`, 02:30 UTC): media copied with checksums and overwritten versions kept, KYC age-encrypted with names hashed, nothing deleted at the
  destination; alerts `R2BackupStale` (26 hours) and `R2BackupFailed`; restore and drill in [backup-restore-r2.md](backup-restore-r2.md) (the drill passes against a local MinIO pair:
  `bash tools/ops/test/r2-backup.test.sh`). Open: create the two read-only R2 tokens and the B2 key, apply the Secret `ops/r2-backup`, run the job once, **run the live restore drill and record the
  minutes** (story S380 stays In Progress until then), enable R2 versioning ([r2-buckets.md](r2-buckets.md) section 7) and B2 Object Lock.
- [ ] `OWNER · Open` The v1 database dump and the Stripe inventory are kept encrypted for 30 days, then destroyed (keep the `ledger.*` finance archive).

### 19.10 Legal, support and launch

- [ ] **blocker** `OWNER/internal · Open` **Counsel review** (handled internally by the owner, not blocking engineering) of every document in `docs/legal`, with each `[[LEGAL: ...]]` placeholder filled (entity, address, governing law, arbitration,
  liability caps, DMCA agent, state rules), before they are published. Terms, privacy notice, cookie banner and marketplace-facilitator
  tax disclosures are published and accurate. Translations follow the approved English.
- [ ] `OWNER · Open` Provider terms (Stripe, Resend, OpenRouter, Cloudflare, OVH, Layerbase, Google) are read and the matrices in `docs/legal` confirmed.
- [ ] **blocker** `OWNER · Open` OpenRouter account privacy settings match the privacy notice ("AI features"). In the OpenRouter account
  settings: turn prompt/response logging OFF; turn OFF the "inputs/outputs logging" and "training" consent options; set the
  account-level privacy / zero-data-retention (ZDR) policy; restrict the allowed providers to those that do not train on
  prompts. Check that `AI_PROVIDER_DATA_COLLECTION` is `deny` (or unset) and `AI_PROVIDER_ZDR` is `true` on ai-gateway in prod. Record the date and who checked:
  ______ (date), ______ (name), re-check every quarter and whenever a model id in `at.ai.routes` changes.
- [ ] **blocker** `OWNER · Open` **ai-gateway spend cap: USD 2.00 per UTC day, enforced in code** (owner decision; `docs/runbooks/ai-spend-and-mcp-access.md`). The gateway rejects
  every call with `429 ai.budget-exceeded` once the day's total reaches `AI_GLOBAL_DAILY_COST_USD=2.00` (now only the initial default of the cap, which staff with `ai:cap.manage` can change in the console up to the hard ceiling `AI_GLOBAL_DAILY_COST_MAX_USD=20.00`; and one caller's share at `AI_CALLER_DAILY_COST_USD=0.50`), both pinned in
  `java/services/ai-gateway/deploy/helm/values-prod.yaml`, on the cheapest model. Still open: (a) `VENDOR`: set a credit limit on the OpenRouter key itself (for example USD 60 a
  month) as the second, independent guard; (b) an alert at 50 and 80 percent of the day's cap and the owner names who gets it; (c) one test: exceed the cap and see `429`.
- [ ] **blocker** `OWNER/internal · Open` Data processing agreements are signed with OpenRouter and with each model provider we route to (for example
  Google and OpenAI) if we process EU or UK personal data; the signed copies are filed and the subprocessor list is current.
- [ ] `OWNER · Open` Support inbox, customer support console and escalation contacts are staffed for launch week.
- [ ] **blocker** `VENDOR · Open` **Resend DNS and sending domain.** Add the domain in Resend, publish the DNS records it gives (SPF, DKIM, and the return-path/MX records) in Cloudflare
  as **DNS only** (unproxied), add a DMARC record (`_dmarc`, start at `p=none` with a report address, move to `quarantine` after two clean weeks),
  and wait for the domain to show **Verified**. Set the production `RESEND_API_KEY` (sending access only) in the secret store. Send a test to Gmail,
  Outlook and Yahoo and check the headers show SPF, DKIM and DMARC pass. Check the From address matches the verified domain. Templates reviewed in every language
  you ship; machine-translated strings were reviewed by a native speaker.
- [ ] **blocker** `ENGINEERING · Open` **R2 buckets: public versus private.** Both are created by `infra/cloudflare` (`afrotransact-<env>-media`, `afrotransact-<env>-kyc-evidence`; `docs/runbooks/r2-buckets.md`). Product and listing images use the **public** media bucket (custom domain `media.afrotransact.com`, `media-<suffix>.afrotransact.com` elsewhere,
  read-only to the world, no listing). Identity and seller **KYC documents** use a **private** bucket: no public access, no custom domain, no
  `r2.dev` URL, access only through the media service with short-lived signed URLs, and the `kyc-document` upload purpose pointed at it
  (`docs/runbooks/selling-approvals.md`; `docs/engineering/cloud-cost.md` says the KYC bucket stays private). Check: fetch a KYC object URL
  without credentials and expect `403`; list the public bucket anonymously and expect it refused; R2 API tokens are scoped per bucket with no
  admin token in any service; CORS on the public bucket allows only our origins; the retention rule for KYC files is decided by the owner
  (`OWNER · Open`).
- [ ] `OWNER · Open` Seller onboarding (applications → two-person review → approval) tested with a real test application, including the
  "return to applicant" path.

### 19.11 Launch day

- [ ] `OWNER · Open` Follow the order of events, go/no-go checklist and rollback in [v1-to-v2-migration.md](v1-to-v2-migration.md).
- [ ] `OWNER · Open` Confirm a real first order, a staff pickup that captured the payment, a delivery and (a week later) a payout.

## Appendix A. Owner end-to-end checklist: real flows on a Mac with Docker (before UAT)

(Formerly `release-1-verification.md` section 4; run it locally before the UAT dry run. `tools/smoke/release1.sh` automates part of it.)

Preflight once: `make preflight`, then `make up` (release 1 stack: infra, `core` database, core on :8120, afrotransact-go on :8200, storefront :3000,
Seller Center :3010, staff console :3002; it seeds demo data and prints all links); `make status` must show core readiness UP with every module. For real card
decline you need Stripe **test** keys in `.local/secrets.env` (names `STRIPE_SECRET_KEY`, `STRIPE_PUBLISHABLE_KEY`, `STRIPE_WEBHOOK_SECRET`, and
`stripe listen` as in `docs/engineering/local-development.md`). Without them payments uses a local stand-in that authorises every card (nothing can be
declined; use the Temporal UI route of A.6).

Accounts (password `Test1234!Local`, all `@afrotransact.test`): `customer.houston` (Houston 77042), `seller.owner` (Mama Put Pantry), `staff.admin`,
`staff.tax`, `provider.owner` (Aminata's Braids), `driver`. Staff sign in with a two-step code (mail in Mailpit, :8025).

SQL helper used below (the database is `core`, one schema per module):
`docker compose -f infra/local/docker-compose.yml exec postgres psql -U postgres -d core -c "<sql>"`.

### A.1 Browse, search, cart

1. Storefront http://localhost:3000, deliver-to 77042: home, a category, a product page open; images load (seeded photos).
2. Search a seeded product name (Meilisearch): results and facets appear. If empty: `make reindex-search`.
3. Add two different products to the cart as `customer.houston`, change a quantity, reload: the cart persists (afrotransact-go cart module).
   Expected in the gateway log: calls go to `http://...inproc` for cart and search (in-process), no connection errors.

### A.2 Checkout with a card (the happy path)

1. Cart -> Checkout -> choose the saved address and AfroTransact Delivery -> pay with the labelled test card (4242 4242 4242 4242) -> order page "placed".
2. Rows: `select status from orders.customer_order order by created_at desc limit 1;` is `PLACED`;
   `select status, failure_code from orders.checkout_request order by created_at desc limit 1;`;
   `select status from payments.payment order by placed_at desc limit 1;` (authorised);
   `select sku, on_hand, reserved from inventory.inventory_position where sku in (<your SKUs>);` (reserved went up, then the hold is `COMMITTED` on shipping);
   `select status from inventory.reservation order by created_at desc limit 1;` is `HELD` until shipped.
3. Temporal UI http://localhost:8088: workflow `checkout-<orderId>` Completed.

### A.3 Forced inventory failure (first step)

1. As `staff.admin` (staff console -> Inventory) set a seeded SKU's on-hand to **1** (or pick a SKU with little stock) and note `reserved`.
2. As the customer put **3** of it in the cart and check out.
3. Expected: the checkout answers 409 `orders.insufficient-stock` naming the SKU ("requested 3, available 1"); no PaymentIntent; the cart is untouched.
   Rows: `select status, failure_code from orders.checkout_request order by created_at desc limit 1;` is `FAILED`/`orders.insufficient-stock`;
   `select status from orders.customer_order order by created_at desc limit 1;` is **not** `PENDING_PAYMENT`/`PLACED` for this order (cancelled, or absent);
   `inventory_position.reserved` is exactly the value you noted; `select count(*) from payments.payment where order_id = '<orderId>'` is 0.

### A.4 Forced payment failure (the intent exists, the stock is held, then payment fails)

Pick one:
* **Stripe test card** `4000 0000 0000 0002` (declined) with test keys and `stripe listen`.
* **Temporal UI (no Stripe)**: start a checkout and stop at the pay step; in http://localhost:8088 open workflow `checkout-<orderId>` -> Send a signal ->
  name `paymentFailed`, input `"Your card was declined"`.
* **Timeout**: restart core with `CHECKOUT_PAYMENT_TIMEOUT=1m` (`make restart SERVICE=core`) and do not pay.

Expected, within seconds (timeout: after 1 minute): failure `orders.payment-failed` (or `orders.payment-timeout`); `inventory.reservation` is `RELEASED` and
`inventory_position.reserved` is back to the noted value; `payments.payment` is `canceled`; with a coupon (`WELCOME10` if seeded, else any valid coupon):
`select status from offers.coupon_redemption where order_id = '<orderId>'` is `RELEASED`; the order is `CANCELLED` with the code. Temporal shows the compensation
activities (`ReleaseReservation`, `CancelPayment`, `Abandon`) Completed.

### A.5 Forced failure of any other step (local-only fault injection)

Only in the `local` profile, default off, no production class touched (a Temporal worker interceptor, `config/LocalCheckoutFaults.java`; it does not exist under `dev`,
`uat` or `prod`, proven by `LocalCheckoutFaultsTest`). Set, then `make restart SERVICE=core` (restart re-reads your shell and `.local/secrets.env`):

| Variable | Meaning |
|---|---|
| `AT_ORDERS_FAULT_INJECTION_ENABLED=true` | the switch (default false) |
| `AT_ORDERS_FAULT_INJECTION_FAIL_BEFORE=CreatePayment` | activity names failed before they run: `Reserve`, `ReserveRunSlot`, `RedeemCoupon`, `CreatePayment`, `PersistPending`, `Place` (comma separated) |
| `AT_ORDERS_FAULT_INJECTION_FAIL_AFTER=CreatePayment` | failed after they ran (the work is done and the answer is lost) |
| `AT_ORDERS_FAULT_INJECTION_KIND=UNAVAILABLE` or `REJECTED` | retryable (default: 5 attempts, then `orders.upstream-unavailable`) or not (`orders.upstream-rejected`) |
| `AT_ORDERS_FAULT_INJECTION_TIMES=1` | how many calls fail (default all) |

Core logs a WARN line "CHECKOUT FAULT INJECTION IS ON" at start and one per injected failure. Suggested runs, each followed by the checks of A.4 (stock back, intent
cancelled, coupon released, order cancelled, right code):
1. `FAIL_BEFORE=CreatePayment KIND=REJECTED` (payment intent creation fails after the stock is held).
2. `FAIL_AFTER=CreatePayment` (intent exists at payments, answer lost: `payments.payment` for the order must end `canceled`, never two rows).
3. `FAIL_BEFORE=PersistPending` (order creation fails after reservation and intent).
4. `FAIL_BEFORE=RedeemCoupon KIND=REJECTED` with a coupon in the cart.
5. `FAIL_BEFORE=Place` after a successful payment (placing the order fails: authorisation must be cancelled).
6. `FAIL_BEFORE=Reserve TIMES=2` (retried away: checkout succeeds).
Unset the variables and restart when done. **Not exercised by me against a real Temporal server** (the interceptor is proven on the in-JVM test environment; that
Temporal's Spring starter applies `WorkerInterceptor` beans to the checkout worker is its documented behaviour but unobserved here: if the log line of the first injected
failure does not appear, tell me).

### A.6 Seller listing

`seller.owner` in Seller Center http://localhost:3010 -> Products -> add a listing for an existing catalog product (price, stock, pickup location). Expected: it saves
(catalog -> offers -> seller in-process calls), and the product page on the storefront shows the new offer after a minute (search reindex via Kafka/indexer);
an unapproved category shows the approval notice. Check `select status from offers.offer order by created_at desc limit 1;`.

### A.7 Tax shadow

`staff.tax` or `staff.admin` -> staff console -> Fees & tax -> Sales tax -> "Who calculates tax": set a *Shadow mode* provider -> place a checkout (A.2) -> Sales tax ->
**Shadow report** shows the order's lines with our quote and a column for the provider (no keys: "not configured" with the variable names, never a $0.00 quote). Orders' tax step
calls tax-returns in-process: also check the checkout still shows the tax line. See `docs/engineering/tax-shadow-mode.md`.

### A.8 Staff delivery proof

Place an order with AfroTransact Delivery (A.2) -> `staff.dispatch` -> staff console -> Delivery: take the delivery, go out, deliver with a photo (or the driver app :8082,
`driver@afrotransact.test`) -> the proof and audit trail show; the customer's order page shows Delivered with the proof. The step crosses fulfilment -> orders
in-process (`ShipmentApi`): fulfilment is the one module **I could not boot** (PostGIS), so this is its first run in core.

### A.9 Store page

`seller.owner` -> Seller Center -> Storefront/Store page: edit and publish; open the public store page on the storefront as an anonymous visitor: 200 with the content
(the seller storefront read is a public permit on core's shared security chain). Repeat with security **on** (`AT_SECURITY_ENABLED=true`, not the local default) to check the
anonymous permit and a 401 on `GET /v1/offers`.

### A.10 Booking

`customer.houston` -> storefront -> Services -> "Aminata's Braids" -> pick a slot -> pay the deposit -> booking appears under Orders/Bookings for the customer and in
`provider.owner`'s calendar. Payment goes bookings -> payments in-process (`BookingPaymentsApi`). Cancel it and check the refund shows.

### A.11 Staff invitation

`staff.admin` -> staff console -> Settings -> Staff -> invite a work-domain email -> the invitation mail arrives in Mailpit :8025 -> open the link (`/invite#token=...`): the
page shows who it is for, set a password, sign in. Reuse the link: it says it is used. (Identity's matrix row duplication that blocked its tenant-isolation test is fixed.)

### A.12 Close the gaps this session could not

| Gap | How |
|---|---|
| Testcontainers ITs | `make java-verify` |
| Kafka consumers and the outbox in core | `make up`, place an order, then `select count(*) from orders.platform_outbox where published_at is null` drains; consumer groups `orders-service`, `payments-service`... listed in Kafka UI :8090 |
| Real Temporal server | A.2 to A.5 above |
| Signed JWT across modules | sign in on the storefront and open an account page; with `AT_SECURITY_ENABLED=true` for core |
| Stripe | `stripe listen`, A.4 with the declined card |
| fulfilment, ai-gateway, mcp-server live | A.8; ask Lola in the storefront chat; connect an MCP client to :8114 `/mcp` |
| Playwright smoke and e2e | `pnpm install && pnpm --filter @afrotransact/e2e test` (mock BFF, also run by CI on `main`) |
| Route probe against full core | `python3 tools/release/route_probe.py --modules fulfilment,ai-gateway,mcp-server,identity,catalog,offers,inventory,orders,payments,seller,bookings,trust-ops,tax-returns,content` after `make up` (expects the 3 seller routes only) |
