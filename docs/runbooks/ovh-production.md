# Production on OVH Managed Kubernetes + Layerbase: from zero to a smoke-tested launch

Status: **written, never run.** There is no OVH or Layerbase account behind these steps yet; everything marked **UNVERIFIED** is a
vendor fact to confirm on first use. Do the whole procedure once in a **disposable trial** (separate OVH project, `disposable = true`,
hourly node, a Layerbase trial) and only then repeat it for the real project. Decision: [ADR 0004](../adr/0004-lean-single-node-production.md).
Sizing: [cloud-cost.md](../engineering/cloud-cost.md). Restore: [restore-drill.md](restore-drill.md).

What this deploys: 21 services (14 Java, 7 Go), Kafka, Temporal, Valkey, Meilisearch, an OTLP collector, cloudflared + Envoy on **one**
`b3-16` node; Postgres on Layerbase; web apps on Cloudflare Workers. It deploys the **prod** profile (`APP_ENV=prod`,
`SPRING_PROFILES_ACTIVE=prod`) from each chart's `values-prod.yaml`. `infra/ovh-vps` is a different thing: a one-server **test** box on
the dev profile; do not use it for production.

## 0. Tools on your machine

`tofu >= 1.9`, `kubectl`, `sops`, `age`, `psql` and `pg_restore` 17, `rclone`, `python3` with PyYAML, `helm` 3.16+, `kustomize` (or
`kubectl kustomize`), `argocd` CLI (optional), `gh`. Keep two people able to operate: two age keys, two OVH users.

Gate before anything else (works offline):

```bash
python3 infra/argocd/scripts/check_launch_set.py             # lists the Applications each env produces; prod must say 21 + 5, "OK"
python3 infra/argocd/scripts/render_apps.py --env prod --no-crd-schemas   # renders every launch-set chart with the prod values
```

## 1. OVH US account (about half a day, plus OVH's own verification time)

1. Create the account at `us.ovhcloud.com`. **The US entity is a separate account from the EU/CA ones** (different API endpoint
   `ovh-us`, different console, different credentials). Add a payment method; ask OVH about startup credits (UNVERIFIED programme).
2. Create a Public Cloud **project** (one for the trial, another for production). Check the quotas (instances, Kubernetes, block
   storage). Ask OVH, in writing: is MKS **free plan** available in `US-EAST-VA-1`, its limits and SLA; the monthly price of `b3-16` and
   whether monthly billing can be cancelled; block storage class/price and CSI snapshots; Object Storage price and **S3 object lock**
   support in the US; whether OKMS exists in the US (only matters if you choose the optional OKMS path). All **UNVERIFIED** today.
3. Create API credentials for tofu at `https://api.us.ovhcloud.com/createToken/`: rights `GET/POST/PUT/DELETE /cloud/project/<id>/*`
   and `GET /me`. Export `OVH_APPLICATION_KEY`, `OVH_APPLICATION_SECRET`, `OVH_CONSUMER_KEY` in your shell only.
4. Set Public Cloud consumption alerts (the budget module also creates them: 50/80/100 % of `monthly_budget`; **set the real
   amount from the quote**, the 150 in `sizing.tf` is a placeholder).

## 2. Infrastructure with OpenTofu

```bash
cd infra/tofu/bootstrap/ovh && tofu init && tofu apply -var service_name=<project id> -var region=US-EAST-VA -var endpoint=ovh-us
cd ../../envs/prod
cp terraform.tfvars.example terraform.tfvars      # region US-EAST-VA-1, ovh_endpoint ovh-us, ovh_service_name, admin_cidrs (REQUIRED)
cp backend.ovh.hcl.example backend.hcl            # uncomment the US lines; export the state user's S3 keys as AWS_ACCESS_KEY_ID/SECRET
printf 'terraform {\n  backend "s3" {}\n}\n' > backend.tf
tofu init -backend-config=backend.hcl && tofu fmt -check -recursive ../.. && tofu validate
tofu plan -out plan.out && tofu show -json plan.out > plan.json && conftest test plan.json --policy ../../../policy/tofu
tofu apply plan.out
```

Expected: one MKS cluster (free plan) with one `b3-16` node, a private network, Object Storage buckets (`kyc-evidence` locked,
`exports`, `labels`, `media`: currently written by no launch service, media is R2-only), per-service S3 users, consumption alerts. **No load balancer, no gateway, no database.** The plan fails
on purpose if `admin_cidrs` is empty, if a `US-*` region is paired with a non-US endpoint, or if the region is not the Layerbase one.
For the trial use a different project and `disposable = true` (hourly node, no deletion protection, KYC retention 1 day).
These modules were never applied: expect to fix first-run provider errors (the OVH provider schema moves).

## 3. Kubeconfig and cluster checks

1. Download the kubeconfig: OVH Control Panel (US) > Public Cloud > Managed Kubernetes > your cluster > kubeconfig (or the API, `tofu output cluster`).
   Keep it out of the repo. The API is restricted to `admin_cidrs`: if you cannot connect, your IP changed (edit the variable, apply).
2. `export CONFIRM_CONTEXT=$(kubectl config current-context)` (the secrets script refuses to run without it).
3. `kubectl get nodes` (one node, `b3-16`, Ready) and `kubectl describe node | grep -A8 Allocatable`: **compare allocatable memory with the
   ~13 GiB assumed** in cloud-cost.md. `kubectl get storageclass`: note the default class (UNVERIFIED which OVH CSI class is default);
   create a 1 GiB test PVC and a pod that mounts it, delete both.
4. Set the cluster **maintenance window** to a quiet hour (OVH panel). One node means every node upgrade is downtime.

## 4. Cloudflare edge (Tunnel, no load balancer)

`infra/cloudflare` with `origin_mode = "tunnel"` creates the tunnel and the DNS records for `api.`, `auth.` (and the web hosts for the
Workers). Take the token: `cloudflared tunnel token <tunnel id>` into `CLOUDFLARED_TUNNEL_TOKEN` (step 6). The prod routes in
`infra/k8s/platform/edge/overlays/prod/routes.yaml` expose **api** (BFF, identity, bookings, fulfilment, media, notifications and the three
Stripe webhook paths) and **auth** only. **There is no `mcp.` route and no `grafana.` route**: `infra/cloudflare` still lists them in
`origin_apps`, so those two hostnames reach the tunnel and get a 404 from Envoy. Remove them there if you do not want the names.
mcp-server is cluster-internal and requires OAuth bearer tokens (`AT_SECURITY_ENABLED=true`). `docs/runbooks/ai-spend-and-mcp-access.md` records the owner decision that
MCP is public eventually: the launch default here is no public route; the commented route in `routes.yaml` plus the `mcp.` name in `infra/cloudflare` publish it.
The web apps (Workers) are not part of this runbook.

## 5. Layerbase PostgreSQL

1. Create the Dedicated server (4 GB plan, UNVERIFIED) in **Virginia**; note the host, port, admin user. Send the **written questions** in
   section 14 first and wait for the answers about connections, mTLS, backups and PostGIS.
2. **Measure latency** from a pod on the node (`kubectl run -it --rm psql --image=postgres:17-alpine -- psql "host=... sslmode=require"`,
   `\timing`, `select 1` x 50): a few ms expected (UNVERIFIED). If it is above ~10 ms, stop and ask Layerbase which data centre it is.
3. Create the 19 databases, roles and extensions **before any service starts**. Either:
   - `PROFILE=prod ... infra/ovh-vps/secrets.sh databases` (also the passwords source for the rendered Secrets, step 6), or
   - `infra/tofu/scripts/postgres-bootstrap.sh` (prints each URL/password once; you then have to put them into the Secrets yourself).
   Both create `<db>_app` roles (`temporal_visibility` uses `temporal_app`) and, **as the admin**, `postgis` in **`geo` and `fulfilment`** and
   `btree_gist` in `bookings`. Fulfilment's `V3__nearest_driver.sql` runs `create extension if not exists postgis` as the service role, which fails
   on a managed server unless the extension already exists. Verify: `psql ... -d fulfilment -c "select postgis_version()"`.
4. If Layerbase requires mTLS or `verify-full`, every service needs the client certificate and `sslmode=verify-full` in its URL
   (`EXTERNAL_PG_SSLMODE`; libpq `PGSSLROOTCERT`): this is a Layerbase answer to get first.

## 6. Secrets (SOPS + age)

Follow `infra/ops/secrets/README.md`: render with `PROFILE=prod ... infra/ovh-vps/secrets.sh platform|services` into a temp directory
(`SECRETS_OUT`), encrypt with SOPS, apply with `infra/ops/secrets/apply.sh`. Prerequisites in `secrets.local.env`: Stripe **LIVE** keys and
the three webhook secrets (`docs/runbooks/stripe-webhooks.md`), `STRIPE_PUBLISHABLE_KEY` (gateway-bff refuses to start without it),
`RESEND_API_KEY` and `RESEND_WEBHOOK_SECRET` (`docs/runbooks/resend-email.md`; no Resend = no sign-up codes and no staff email MFA; notifications refuses to start in prod without both), `OPENROUTER_API_KEY` (**and a credit limit on that key in the OpenRouter
dashboard**: the USD 2 per day gateway ceiling in `ai-gateway`'s `values-prod.yaml` is the first guard, the key limit the second), geo and tax data keys, the
Cloudflare R2 account id and the two bucket tokens for media (`docs/runbooks/r2-buckets.md`; media refuses to start in prod without them), the tunnel token, Grafana Cloud OTLP credentials, the backup remote and the **age public key** of the backup, `GHCR_USER` /
`GHCR_TOKEN` (read-only `read:packages`; creates the pull secret `ghcr`), `BOOTSTRAP_ADMIN_*` for identity's first admin.
Generate **everything fresh** (never reuse trial or dev values: `STATE_DIR` must be a new directory).

Then **immediately** run `infra/ops/secrets/escrow.sh create` and store the result offline, separately from the database backups. The
two values that cannot be recovered are `IDENTITY_DATA_KEY` (personal data encryption) and the JWT signing key (`SIGNING_KEY_PEM`).
`apply.sh` refuses Secrets whose mandatory keys are empty (Stripe, Resend, OpenRouter, tunnel, backup).

The optional OKMS + External Secrets path (not verified against a live account): `ovh_okms = true` in tofu is refused for US regions; see
`infra/k8s/platform/lean/external-secrets/externalsecrets.yaml` for the exact steps. Do not mix the two paths.

## 7. Pin the images

Every launch-set chart's `values-prod.yaml` must pin `image.tag` to a full commit sha of a green `main` build (the base value `dev` does not
exist in GHCR). Use the **promote** workflow once per service (`environment: prod`, `tag: <sha>`; prod waits for the `prod` environment
reviewers; it refuses services outside the launch set). Repository variables of the GitHub `prod` environment: `CLOUD=ovh`,
`REGISTRY_URL=ghcr.io/<owner>/<repo>` (GHCR: nothing is mirrored; the signature is verified in the job). Then the gate:

```bash
python3 infra/argocd/scripts/check_launch_set.py --env prod --require-tags     # every launch-set service pinned to a 40-char sha
```

## 8. Argo CD (trimmed)

```bash
kubectl create namespace argocd
kubectl apply -n argocd --server-side -f https://raw.githubusercontent.com/argoproj/argo-cd/<pin a 3.x release tag>/manifests/install.yaml
kubectl -n argocd scale deploy argocd-dex-server argocd-notifications-controller --replicas=0     # unused: ~0.3 GiB saved
```

Keep `argocd-server`, `argocd-repo-server`, `argocd-redis`, `argocd-applicationset-controller` and `argocd-application-controller` (the
ApplicationSets need the controller). Check `kubectl -n argocd top pods` after a few syncs: the budget is ~1 GiB. If the repository is
private add its credential out of band (`argocd repo add ... --username ... --password <PAT>` or a deploy key; see
`infra/argocd/projects/repositories.yaml`). Then:

```bash
kubectl apply -n argocd -f infra/argocd/bootstrap/root-prod.yaml      # app of apps: AppProjects + the three ApplicationSets (manual sync)
argocd app sync root-prod                                             # creates the ApplicationSets and, from them, the Applications
argocd app list                                                       # expect 26 Applications, all OutOfSync, none syncing by themselves
```

## 9. First sync order (a human approves every step)

Sync by Application name (`<component>-prod`). Wait for Healthy before the next step.

1. **Platform:** `namespaces-prod` (wave -10), `envoy-gateway-prod` (-4), `opentelemetry-collector-gateway-prod` (-2), `edge-prod` (-1: Gateway,
   routes, cloudflared; needs the `cloudflared` Secret), then **`lean-prod`** (0: Kafka, Temporal, Valkey, Meilisearch, the DLQ-topic Job, the
   backup CronJob; needs the `temporal`, `valkey`, `meilisearch`, `grafana-cloud-otlp`, `pg-backup` Secrets). Check:
   `kubectl -n kafka get pods`, `kubectl -n temporal logs deploy/temporal` (both databases reachable, namespace `afrotransact` created),
   `kubectl -n ingress get pods`, `kubectl -n ops get cronjob`.
2. **identity-prod first** (it issues the tokens every service needs and runs its Flyway migrations on its own database): wait until
   `https://auth.<domain>/.well-known/openid-configuration` answers through the tunnel.
3. **Wave 2, three at a time** (JVM start-up spikes CPU; watch `kubectl top node`): catalog, offers, inventory; then orders, payments, seller;
   then fulfilment, bookings, trust-ops; then tax-returns, content, ai-gateway, mcp-server.
4. **Wave 3 (Go):** cart, geo, media, notifications, search, indexer, **gateway-bff last** (it refuses to start without `REDIS_URL`,
   `SESSION_SIGNING_KEY` and `STRIPE_PUBLISHABLE_KEY`). A failed Flyway migration leaves the pod in CrashLoop while the old pod (if any) keeps
   serving; fix forward, never edit applied migrations.
5. **Search index:** once catalog data exists, `kubectl -n afrotransact-prod create job reindex-1 --from=cronjob/indexer-reindex` (the CronJob is
   suspended; `reindex.enabled: true` in the indexer prod values renders it) and follow its logs.
6. Backups: `kubectl -n ops create job --from=cronjob/pg-backup first-backup` and **run the restore drill** (restore-drill.md) before inviting users.

Memory/CPU check after each wave: `kubectl top pods -A`, `kubectl describe node | grep -A12 "Allocated resources"`.

## 10. Smoke test

- Edge: `https://auth.<domain>/.well-known/openid-configuration` and `https://api.<domain>/bff/healthz` return 200; the unsigned Stripe webhook POST
  (`https://api.<domain>/payments/v1/webhooks/stripe`) returns **400**, not 404 or 200.
- Profile: `kubectl -n afrotransact-prod exec deploy/orders -- env | grep -E 'APP_ENV|SPRING_PROFILES'` says `prod`; the seeder never ran
  (`SEED_ALLOW_REMOTE` must not exist): no `*.test` users, no `src=demo-seed` rows.
- Sign-up: create an account, receive the verification code by email (identity -> outbox -> Kafka -> notifications -> Resend). If no mail
  arrives, check Kafka and notifications before anything else.
- Staff: sign in as the bootstrap admin with MFA, change the password, create the staff accounts.
- Catalog and search: create a product in a seller account, approve it in trust-ops, see it in search (reindex if needed).
- Home page with promise absent: the storefront renders; the "get it by" rail is missing and delivery dates are estimates (gateway-bff runs with
  `PROMISE_DISABLED` in prod; `TestHomeDegradesWhenThePromiseServiceIsNotDeployed` also proves the failing-upstream path).
- Checkout (Temporal saga): **Stripe test-mode first in the trial**; in production one real small payment, one refund, one payout test
  (`docs/runbooks/production-readiness.md` section 5). `ORDERS_TAX_FALLBACK_TO_TABLE=false` means tax-returns profiles must be `ACTIVE`
  (`docs/runbooks/tax-provider-cutover.md`) or checkout fails.
- AI: one Lola message works and `select * from ai_usage_daily where usage_day = current_date and scope = 'GLOBAL'` (in the `ai` database) shows a cost below USD 2.
- Ops: `kubectl -n ops get jobs` shows the backup succeeded; the dead-man's-switch ping arrived; Grafana Cloud shows traces from gateway-bff.
- Not a smoke item but required: nothing else is exposed: `nmap` the node's public IP from outside: no open ports.

## 11. Rollback

- **Application:** images are immutable sha tags. Revert the commit that bumped `image.tag` in `values-prod.yaml` and `argocd app sync <svc>-prod`,
  or `argocd app rollback <svc>-prod <history id>` (history is kept, `revisionHistoryLimit: 10`; auto-sync is off so this is allowed). The old pod keeps
  serving until the new one is Ready (`maxUnavailable 0`).
- **Database:** forward only. A schema change ships expand-then-contract, so the previous version still runs against the new schema for one release.
  Take `pg_dump -Fc <db>` of the affected database by hand before any release with a destructive migration. Never run a down-migration.
- **Platform component:** `argocd app rollback` the Application, or `git revert` and sync.
- **Whole cluster lost:** tofu apply in a new project (or the same after destroy), restore secrets from the SOPS files and `STATE_DIR` escrow, bootstrap Argo, sync in
  the order above, reconnect to Layerbase (no data lost), `reindex-search`, republish the outbox if events were lost (`docs/runbooks/kafka-replay.md`).
  Target RTO 4 hours for the database, 2 to 4 for the cluster (**not proven**: the drill measures it).
- **Maintenance mode:** `docs/runbooks/maintenance-mode.md`.

## 12. Measure in the trial (decision data)

`kubectl top pods` RSS per JVM at idle and under a k6 run (`tools/perf`, 50 concurrent shoppers) against the requests in cloud-cost.md; node
requests vs allocatable; CPU during a 3-service sync; downtime of a rolling update and of a node reboot (time to all-Ready, PVCs reattach, Kafka/
Temporal/Valkey resume); `pg_stat_activity` peak vs the 74 steady / 90 peak plan; migration times; Layerbase RTT; backup run time and size; **restore time**;
Kafka disk growth per day; Grafana Cloud series and log volume against the free limits; cost per day x 30 for node, disks and buckets.

## 13. Day 2

- Monthly: restore drill, cost review, `kubectl describe node` headroom, certificate/tunnel check, rotate nothing unless needed.
- OVH node upgrades need a maintenance window (downtime). Tell users.
- Alerts that must exist (Grafana Cloud alerting + an external uptime check on the storefront, `/bff/healthz` and the OIDC discovery URL): site/API
  down, 5xx rate on gateway-bff, Stripe webhook failures, Kafka consumer lag / outbox backlog / DLQ above 0, Postgres connections near the limit,
  node memory above 85 %, OOMKilled/CrashLoop, PVC above 80 %, **missed backup** (dead-man's-switch), Layerbase storage near the limit.
- Pre-orders, community, retail and delivery-runs are **not launched**: hide their navigation in the storefront (delivery-runs is not deployed).

## 14. Questions for Layerbase, in writing (all answers currently unknown)

1. Exact plan and price for 19 databases; is Dedicated 4 GB right? Per-database or per-role limits? Extension list: PostGIS version, `btree_gist`,
   and whether the admin may `CREATE EXTENSION postgis` in a database (we pre-create it in `geo` and `fulfilment`, `btree_gist` in `bookings`).
2. Which cloud, region and data centre hosts the Virginia server; private connectivity or peering options toward OVH US East; expected round-trip time.
3. `max_connections` per plan, any pooler (PgBouncer mode), idle-connection reaping. (Our plan: 74 steady, ~90 peak.)
4. Is TLS with a password enough, or is mTLS mandatory? CA certificate and server name for `verify-full`.
5. Backups: frequency, retention, point-in-time recovery (window and granularity), restore method (new instance / in place / per database), restore time for
   our size, who runs it, cost of extra copies, and may we run our own `pg_dump` and `pg_dumpall --globals-only` (superuser or equivalent role)?
6. High availability and failover, maintenance windows, minor/major upgrade policy (PostgreSQL 17), SLA, status page, support channel and response time in an incident.
7. Behaviour at the storage limit (overage price, hard stop or read-only), alerts, resizing without downtime.
8. Egress and ingress charges, in writing; data location and a DPA for KYC-adjacent data; startup-credit programme eligibility.
9. Can the superuser create roles and databases, set ownership (`ALTER DATABASE ... OWNER TO`) and `GRANT` a role to the admin (the bootstrap scripts need all of these)?
10. Is `pg_dump -Fc` from outside their network allowed (IP allow-listing: from the node's public IP and from backup/operator addresses)?

## 15. Questions for other vendors (all UNVERIFIED today)

**OVH:** MKS free plan in US East; Free vs Standard SLA; `b3-16` monthly and hourly price; node upgrade windows and what happens to PVCs; block storage and CSI
snapshot support; Object Storage price, **object lock** in the US; whether the US MKS API supports IP restrictions that keep node-to-API traffic working;
outbound traffic charges. **Cloudflare:** free-plan WAF and rate-limit rule quotas, Tunnel limits, Workers Paid need, R2 versioning/lifecycle.
**GitHub:** GHCR private package storage and transfer limits. **Grafana Cloud:** free-tier limits and alert-rule quotas.

## 16. Known gaps (not done, do not forget)

- Nothing was applied, rendered by tofu, or tested against OVH, Layerbase or Cloudflare. Argo CD, `kustomize build` and `helm template` were run for the
  repository's own manifests only.
- The backup CronJob installs `rclone`, `age` and `curl` with `apk` at run time (root, default capabilities): a pre-built tools image would remove that.
- The OTLP collector only has an OTLP receiver: service metrics (`/actuator/prometheus`, `/metrics`) are not scraped and logs are not shipped; decide between
  a Prometheus receiver, OTLP metrics or `kubectl logs` + traces only at launch.
- The tax, legal, Stripe Connect, Resend domain and counsel items of `docs/runbooks/launch-plan.md` and `production-readiness.md` are external lead-time gates.
- Keycloak user migration (`docs/engineering/keycloak-migration.md`) if real users exist.
