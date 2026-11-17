# Release 1 go-live: UAT first (the dry run), then PROD, the same steps

Status 2026-11-17: **written for the first run, never executed against a live cluster.** Checked offline: `helm lint` and `helm template` of both deployables with the
uat AND prod values, `kubeconform`, `kustomize build` of every Argo CD env and of the edge overlays, `make launch-set-check`, `tools/release/upstreams.py`, `parity.py`,
`check_capacity.py`, `make release-1-check`, the secrets renderer tests. Not run (no network or no tool): any OVH, Layerbase, Stripe, Cloudflare, GitHub-settings step, `tofu`,
`argocd`, a real image pull. Vendor facts are tagged **UNVERIFIED**.

**Rule of this page: UAT and PROD differ only by VALUES, never by structure.** One `ENV` variable (`uat` or `prod`) drives every command below. Do UAT end to end, run
section 8 (what the dry run must prove), then repeat every step for PROD using the value differences in section 3. Related: [release-1-deploy.md](release-1-deploy.md)
(mechanics, cutover, rollback), [ovh-production.md](ovh-production.md) (OVH, Layerbase, secrets detail), [stripe-webhooks.md](stripe-webhooks.md),
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
   `verify` has failed on every full run. State at the time of writing: **core** fails at `bookings-service` `ServiceAreasIT` (a test that places a catering provider with a 5-mile
   radius although `AreaPolicy` requires 10; fixed on release-1 in `java/services/bookings/src/test`, **not yet proven green**, the reactor then continues to the other modules and
   core's context tests, which have never run to the end). **afrotransact-go** fails lint (golangci-lint v2.14.0): the deployable's own findings are fixed on release-1, but the per-service Go modules still carry about
   90 findings (wrapcheck, unparam, revive) and `services/gateway-bff` about 145 that CI never reached because `gofmt` failed first (gofmt fixed). main fails the same way. **Until both
   `verify` jobs are green there is no image, so nothing can be deployed.** Decide: fix the lint debt (mechanical; the linter runs locally) or accept a one-time image build that does not wait for lint (an owner decision, not made here).
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
| Google/Smarty/OpenRouter keys | optional / limited | real, with quotas |
| `DRIVER_APP_ENABLED` | unset (default `false`) | unset (default `false`) |
| Edge overlay | `infra/k8s/platform/edge/overlays/uat` (prod's edge, uat hosts and namespace) | `.../overlays/prod` |
| State dir of secrets | `$HOME/.afrotransact-uat` | `$HOME/.afrotransact-prod` (never reuse) |

## 4. Pre-flight (GitHub, local tools)

1. Items 1, 7, 8 of section 1. Verify: `gh api repos/billionairedev24/diaspora-commerce --jq .default_branch` prints `release-1`.
2. Tools: `tofu >= 1.9 kubectl sops age psql helm kustomize kubeconform argocd gh python3 rclone`. Offline gate (expect every line OK):
   ```bash
   python3 infra/argocd/scripts/check_launch_set.py            # [uat] and [prod]: afrotransact-core runs 14 modules, afrotransact-go runs 7, "OK"
   make release-1-check && python3 tools/release/check_capacity.py
   python3 tools/release/config_inventory.py --check
   for e in uat prod; do for d in java/apps/core go/apps/afrotransact-go; do ( cd $d/deploy/helm && helm dependency build . >/dev/null && \
     helm template x . -n afrotransact-$e -f values.yaml -f values-$e.yaml -f ../../../../infra/argocd/envs/$e/platform-values.yaml --set image.tag=$(git rev-parse HEAD) | kubeconform -ignore-missing-schemas -summary - ); done; done
   ```
   Rollback: nothing is changed by this step.
3. **Build the images** (blocker 1): push to release-1 and wait for `core` and `afrotransact-go` to be green incl. `image`. Check:
   `gh run list --workflow core.yml --branch release-1 --limit 3` and in GHCR `afrotransact-core:<sha>`. Then `Actions > promote > afrotransact-core, environment $ENV, tag <full sha>`, same for `afrotransact-go`;
   `python3 infra/argocd/scripts/check_launch_set.py --env $ENV --require-tags` must print OK. Rollback: re-promote the previous sha.

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
4. **Rollback plan**: image: re-promote the previous sha and sync (rolling update keeps the old pod until the new one is Ready); platform component: `argocd app rollback`; module off: `kubectl -n afrotransact-prod set env deploy/afrotransact-core MODULE_<NAME>_ENABLED=false` (git must then say the same); database: forward only, take `pg_dump -Fc` before a destructive migration; whole cluster: `ovh-production.md` section 11 (RTO unproven until the drill in section 8.9).
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
