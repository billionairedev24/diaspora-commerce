# Cloud cost and sizing: OVHcloud + Layerbase, one node

This page is the sizing and cost design for the **production launch**: OVH Managed Kubernetes (one node) + Layerbase PostgreSQL +
Cloudflare. Decision and reasons: [ADR 0004](../adr/0004-lean-single-node-production.md). Step by step from zero:
[release-1-go-live.md](../runbooks/release-1-go-live.md).

**Reading the numbers.** Nothing here has been applied or priced on a live account. A figure tagged **UNVERIFIED** is a vendor fact
(OVH, Layerbase, Cloudflare, GitHub, Grafana) not confirmed against a primary source: confirm it before relying on it. Memory and
connection numbers are computed from the repository's own manifests and are labelled as requests, not measurements. **No OVH price
appears below on purpose: the repository has none and the vendor site could not be read when this was written.**

The profile is `infra/tofu/envs/prod/sizing.tf` (`node_size = "lean"`, `zone_count = 1`, `egress = "none"`, `managed_data = false`).
`dev` and `uat` are not part of the budget: develop on the local stack (`docs/engineering/local-development.md`).

## What runs where

| Concern | Choice |
|---|---|
| Kubernetes | OVH MKS **free plan** control plane, **one `b3-16` node** (4 vCPU / 16 GB), billed monthly, region `US-EAST-VA-1` (OVH US entity). No autoscaler, no spare pool |
| Services | **21 of 26**: 14 Java (identity catalog offers inventory orders payments seller fulfilment bookings tax-returns content trust-ops ai-gateway mcp-server) and 7 Go (gateway-bff cart search indexer geo media notifications), 1 replica each. Explicit list in `infra/argocd/launch-set` |
| Not deployed | promise (the BFF runs with `PROMISE_DISABLED` in prod and keeps its own delivery estimates; orders degrade to stored estimates), carrier-gateway, retail, delivery-runs, authz-sync, OpenFGA (`FGA_ENABLED=false`), Kafka UI, Mailpit, MinIO, Elasticsearch, in-cluster Prometheus/Loki/Grafana, Kyverno, Argo Rollouts, cert-manager, External Secrets |
| Postgres | **Layerbase** (external), 19 databases. Nothing is created by OpenTofu |
| Valkey, Meilisearch | in the cluster (`lean/data`), PVC 2 GiB and 5 GiB |
| Events, workflows | Kafka (KRaft, one pod, 10 GiB PVC), Temporal (one pod, state in Layerbase) |
| Edge | Cloudflare Tunnel (cloudflared) to Envoy Gateway. No load balancer, no public node IP for users |
| Web apps | Cloudflare Workers (unchanged) |
| Images and private files | **Cloudflare R2 only**: public bucket `afrotransact-prod-media` (product images) and private bucket `afrotransact-prod-kyc-evidence` (quarantine, KYC, proof photos, exports), one token each (`docs/runbooks/r2-buckets.md`; the media service refuses to start in prod otherwise). OVH Object Storage is the Terraform state and the backup copy |
| Registry | GHCR with a read-only pull secret (no OVH Harbor) |
| Telemetry | Grafana Cloud free tier (one stack, `env` = uat or prod): traces through one OTLP collector, **metrics and logs through Grafana Alloy** (+ kube-state-metrics, kafka-exporter); no public Grafana. [observability-grafana-cloud.md](../runbooks/observability-grafana-cloud.md) |
| Secrets | SOPS + age files (`infra/ops/secrets`); OKMS + External Secrets optional and unverified |
| Deploy | Argo CD (trimmed), manual sync in prod |
| Backups | nightly age-encrypted `pg_dump` of the 19 databases to a **Backblaze B2** bucket (S3 API, rclone; not Cloudflare R2, so backups do not depend on the Cloudflare account), one bucket and key per environment (`infra/k8s/platform/lean/backup`, `docs/runbooks/backups-backblaze.md`) |

## One location for everything

Layerbase Dedicated runs in US East (Virginia), US West (Oregon) and South America (Sao Paulo) (**UNVERIFIED**, copied from
Layerbase's site by an earlier version of this page). The platform lives in **Virginia**:

| Piece | Where | Setting |
|---|---|---|
| Layerbase server | US East (Virginia) | chosen when you create the server |
| OVH cluster, node, object storage | OVH US `US-EAST-VA-1` (Vint Hill, Virginia) | `region` in `envs/prod/terraform.tfvars`, `ovh_endpoint = "ovh-us"` |
| Product images (R2) | Eastern North America (`enam`) | `infra/cloudflare/environment.tf` |
| Grafana Cloud stack | US East | choose it when you create the stack |

`layerbase_region = "virginia"` maps to OVH `US-EAST-VA-1` (it used to map to Quebec `BHS5`, which made the database tens of
milliseconds away). With external data `tofu plan` fails if `region` is not the mapped one, and fails if a `US-*` region is used
without `ovh_endpoint = "ovh-us"` (the OVH US entity is a separate account with separate credentials). Which cloud and data
centre host the Layerbase server is unknown (**ask**, see the Layerbase questions in release-1-go-live.md section 16); latency between the two
sites is expected to be a few ms in the same metro (**UNVERIFIED**: measure it before the first deploy).

## Monthly cost: what is known and what is not

| Line | Amount | Status |
|---|---|---|
| OVH MKS control plane (free plan) | 0 | UNVERIFIED (repo module header; confirm the free plan exists in US East and its SLA/limits) |
| OVH node `b3-16`, monthly billing | **not in the repo** | UNVERIFIED: get the quote; monthly billing is said to be about half the hourly price and cannot be switched back |
| OVH block storage for PVCs (Kafka 10 GiB + Valkey 2 + Meilisearch 5 = 17 GiB) and boot disk | not in the repo | UNVERIFIED |
| OVH Object Storage (OpenTofu state bucket only; no media, KYC or backup buckets) | not in the repo | UNVERIFIED |
| OVH load balancer | **0, none is created** | by design (Cloudflare Tunnel); a `LoadBalancer` Service would create one |
| Layerbase Dedicated 4 GB (Postgres only) | $35 | UNVERIFIED (Layerbase site / owner chat) |
| Layerbase Dedicated 8 GB | $65 | UNVERIFIED. **Not needed**: Valkey and Meilisearch run in the cluster |
| Cloudflare Workers Paid | 5 | UNVERIFIED (only if the bundle size needs it) |
| Cloudflare R2 (both media buckets), Grafana Cloud, GHCR | free tiers | UNVERIFIED (check the free limits: Grafana Cloud 10k metric series, 50 GB logs, 50 GB traces; the expected series count of this platform is estimated in [observability-grafana-cloud.md](../runbooks/observability-grafana-cloud.md) section 10 and must be measured in the trial; R2 10 GB, and whether private KYC objects need more than that or a retention lock, which R2 may not offer) |
| Backup buckets at Backblaze B2 (`afrotransact-test-backups`, `afrotransact-prod-backups`) | 0 on the free tier | UNVERIFIED: the free tier is assumed to be 10 GB of storage with daily transaction and download allowances. 14 days x one night's dump set fits while that set stays below about 700 MB (alerts at 600 MB per night and 8 GB in the bucket). **Revisit above about 700 MB per day** (or set `RETENTION_DAYS=7`, which doubles the headroom); paid B2 storage is a few dollars per month at this size (price UNVERIFIED). Hidden versions count as storage until the lifecycle rule removes them |
| Resend (email), Stripe fees, OpenRouter | usage based | not infrastructure; OpenRouter is capped (see below) |

`monthly_budget` in `envs/prod/sizing.tf` is a **placeholder (150)** until the OVH quote exists: set it to about 1.2 times the OVH
subtotal. OVH has no budget object, only Public Cloud consumption alerts at 50/80/100 % (no forecast alerts).

Where the savings are, independent of the OVH price: monthly instead of hourly node billing, no load balancer, no in-cluster
observability, no OpenFGA and four fewer JVMs than the old 26-service design, GHCR instead of Harbor, Postgres on the 4 GB
Layerbase plan instead of 8 GB.

The previous comparison (GCP/Azure/AWS list prices, USD 173 to 279 per month in total) is kept in the git history of this file; it no
longer drives any decision.

## Layerbase plan

All plan facts here are **UNVERIFIED** (copied from Layerbase's site and an owner conversation on 2026-11-01).

| Plan | Price | vCPU / RAM / disk | Fits? |
|---|---|---|---|
| Free, Solo, Pro | $0 / $5 / $15 | pooled, shared | **No** for production: 2 or 10 databases; this platform needs 19. The Pro 7-day trial can probe a few services |
| Dedicated 4 GB | **$35** | 2 / 4 GB / 25 GB | **The plan for this design**: Postgres with 19 small databases |
| Dedicated 8 GB | $65 | 4 / 8 GB / 50 GB | Only if you also want Valkey and Meilisearch there |

Confirmed verbally by the owner, still to get in writing: PostGIS is available; no egress charges. Still unknown: which cloud hosts the
server, `max_connections` per plan, whether mTLS is optional, backup/PITR terms, behaviour at the storage limit (full list in
release-1-go-live.md section 16).

## Will it fit on one node? Memory table

Requests are what the scheduler counts; limits are what the kernel enforces. Computed from the rendered prod charts
(`python3 infra/argocd/scripts/render_apps.py --env prod`) and `infra/k8s/platform`.

| Workload | Count x request | Requests | Limits |
|---|---|---|---|
| Java services (heap 70 % of the limit, serial GC, C1 only) | 14 x 384 Mi (limit 640 Mi) | 5,376 Mi | 8,960 Mi |
| Go services | 7 x 48 Mi (limit 160 Mi) | 336 Mi | 1,120 Mi |
| Kafka (broker + controller, heap 384 Mi) | 640 Mi (limit 896 Mi) | 640 Mi | 896 Mi |
| Temporal (auto-setup, all four roles) | 512 Mi (limit 768 Mi) | 512 Mi | 768 Mi |
| Valkey | 128 Mi (limit 256 Mi) | 128 Mi | 256 Mi |
| Meilisearch | 384 Mi (limit 768 Mi) | 384 Mi | 768 Mi |
| ClamAV (media's malware scan, F10; **UNMEASURED**, signatures in RAM; not in the subtotal and total below, which still read 7.79 and 10.0 GiB: add 1.25 GiB request and 1.75 GiB limit, leaving about 1.75 GiB spare requests, or use a hosted scanner) | 1,280 Mi (limit 1,792 Mi) | 1,280 Mi | 1,792 Mi |
| OTLP collector | 128 Mi (limit 384 Mi) | 128 Mi | 384 Mi |
| Grafana Alloy (metrics + logs to Grafana Cloud; **request/limit UNMEASURED**) | 96 Mi (limit 256 Mi) | 96 Mi | 256 Mi |
| kube-state-metrics (restarts, OOMKilled, backup CronJob state; **UNMEASURED**) | 32 Mi (limit 96 Mi) | 32 Mi | 96 Mi |
| kafka-exporter (consumer lag; **UNMEASURED**) | 24 Mi (limit 64 Mi) | 24 Mi | 64 Mi |
| cloudflared | 64 Mi (limit 256 Mi) | 64 Mi | 256 Mi |
| Envoy proxy (data plane) | 128 Mi (limit 512 Mi) | 128 Mi | 512 Mi |
| Envoy Gateway controller | 128 Mi (limit 256 Mi) | 128 Mi | 256 Mi |
| **Subtotal, our workloads** | | **7,976 Mi = 7.79 GiB** | 14,592 Mi = 14.25 GiB |
| Kubernetes system on MKS (CNI, DNS, kube-proxy, CSI, metrics; the repo's figure, **UNVERIFIED** on OVH) | | ~1.2 GiB | |
| Argo CD, trimmed (repo figure; dex and notifications scaled to 0) | | ~1.0 GiB | |
| **Total with Argo CD** | | **~10.0 GiB** | |
| Allocatable on a 16 GB node (the repo's figure, **UNVERIFIED** on `b3-16`) | | ~13 GiB | |
| **Spare requests** | | **~3.0 GiB** | |

- Alloy, kube-state-metrics and kafka-exporter add **152 Mi of requests** (the only memory cost of the Grafana Cloud monitoring); both figures are guesses until `kubectl top pods -n observability` runs in UAT.
- The **backup CronJob** (128 Mi, limit 512 Mi) runs for minutes at night and is not counted above; a deploy surge adds one JVM
  (384 Mi) at a time (`maxSurge 25 %`, `maxUnavailable 0`).
- Limits sum to more than the node. That is normal for a single node, but it means that if every JVM grows to its limit at once the
  kernel OOM-kills something. Real RSS is **unmeasured**: measure it in UAT (`kubectl top`, `release-1-go-live.md` section 8.5)
  before deciding the node is big enough. An 8 GB node does not hold this.
- CPU requests: services 1,575 m + platform 525 m = ~2.1 cores of ~3.9 allocatable, plus Argo and system. Concurrent JVM
  starts spike CPU: sync a few services at a time.

## Postgres connection budget (Layerbase)

One server, ~15 services. Every number is a pool maximum from the repository's configuration, not a measurement.

| Consumer | Pools | Max each | Total |
|---|---|---|---|
| Java services with a database (12 + ai-gateway; mcp-server has none) | 13 | **4** (`SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE`, `MINIMUM_IDLE 0` in every `values-prod.yaml`) | 52 |
| Go services with a database (cart, geo, media, notifications) | 4 | **3** (explicit cap in each `main.go` / `postgres.go`; a lower `pool_max_conns` in `DB_URL` is respected) | 12 |
| Temporal (`temporal`, `temporal_visibility`) | 2 | 5 (`DB_CONNECT_MAX_CONNS`) | 10 |
| **Steady maximum** | | | **74** |
| Rolling update surge (one new JVM pool per service being synced, up to 3 at a time) | | | +12 |
| Backup job (sequential), operators (psql, Flyway at start-up shares the service pool) | | | +4 |
| **Peak to plan for** | | | **~90** |

Ask Layerbase for `max_connections` (a plan below ~100 needs action): lower Hikari to 2 or 3 for low-traffic services in their
`values-prod.yaml`, or put a pooler in session mode in front (transaction mode breaks Flyway advisory locks and prepared
statements). ai-gateway's `application-prod.yml` says `DB_POOL_SIZE` default 30; the chart's `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=4`
environment variable overrides it by Spring's property precedence: confirm with `pg_stat_activity` on the first boot.

## Release 1 (ADR 0011): two deployables instead of 21 services (release-1 branch)

Everything above describes the per-service build (`main`). On `release-1` the same node runs two deployables and **4 databases** instead of 19
(`docs/runbooks/release-1-deploy.md`). Numbers are requests and limits from `java/apps/core/deploy/helm/values.yaml` and
`go/apps/afrotransact-go/deploy/helm/values.yaml`, not measurements, except the two-module JVM measurement quoted from
`release-1-architecture.md` 11.2 (catalog + tax-returns in one JVM: 335 MB RSS warm, each further module about 40-50 MB, the 14 launch modules
extrapolated to 0.9-1.2 GB, **an extrapolation from two modules**). Re-measure after every batch (`kubectl top pod`).

**Memory.** Replace the 14 Java rows and the 7 Go rows of the table above:

| Workload | Per-service build | Release 1 | Note |
|---|---|---|---|
| Java | 14 x 384 Mi request = 5,376 Mi, limit 14 x 640 Mi = 8,960 Mi | **1 x 1,536 Mi request, 2,048 Mi limit** | the limit is the ADR 0011 S1 hard cap, 2.0 GiB RSS; heap 50 % of it |
| Go | 7 x 48 Mi = 336 Mi, limit 1,120 Mi | **1 x 128 Mi request, 384 Mi limit** | **UNMEASURED estimate**: the Go batch has not been measured |
| **Subtotal, our workloads** | 7,976 Mi = 7.79 GiB, limits 14.25 GiB | **3,928 Mi = 3.84 GiB**, limits 6,944 Mi = 6.78 GiB | ClamAV (1,280 Mi, UNMEASURED) is still not in it |
| Total with Kubernetes system (~1.2) and Argo CD (~1.0) | ~10.0 GiB | **~6.0 GiB** | |
| Spare against ~13 GiB allocatable (**UNVERIFIED** on `b3-16`) | ~3.0 GiB | **~7.0 GiB** (about 5.7 with ClamAV) | a core rolling update adds one more pod (+1.5 GiB): still about 5.5 spare |
| Worst case in the middle of the migration (old JVMs still up, core with its 1,536 Mi request and its surge pod) | | **about 13.1 GiB** with nothing moved yet, 12.4 GiB after batch 1 | **does NOT fit** the ~13 GiB: scale core's request up with the batches (below), never sync two batches at once |
| Core memory request schedule (values-prod.yaml `resources.requests.memory`) | | batch 1: 768 Mi, batch 2: 1,024 Mi, batch 3: 1,280 Mi, from batch 4 (or when RSS passes 700 MB): 1,536 Mi | 2 x request + the old pods still up stays under ~11 GiB with the 2.2 GiB of system and Argo; the limit stays 2,048 Mi throughout |

CPU requests: core 500 m + Go 100 m instead of about 1,575 m of services (**UNMEASURED**; concurrent JVM starts were the CPU spike on the per-service build, one JVM starts once).
The node stays `b3-16`: the gain is headroom (a second replica of core, ClamAV, a rolling update that does not squeeze the node), **not a smaller bill** until someone decides to resize after UAT
measurement; an 8 GB node is still not supported by these numbers.

**Postgres connections.** Pool maximums per pod, with a rolling-update surge of two pods (`values.yaml` `dbBudget`, enforced at `helm template` and by a `CONNECTION LIMIT` of 2 x pool + 2 on every role):

| Consumer | Steady | With the surge | Budget |
|---|---|---|---|
| core: 13 module pools (catalog 8, orders 6, payments 5, identity 4, tax_returns 4, offers 3, inventory 3, seller 3, fulfilment 3, bookings 3, trust_ops 2, content 2, ai_gateway 2) | 48 | 96 | under 100 |
| go: cart, geo, media, notifications at 3 (capped in code) | 12 | 24 | under 50 |
| Temporal (2 databases x 5) | 10 | 10 | |
| Backup job, operators | 10 | 10 | |
| **Total** | **80** | **140** | Layerbase **up to 500** (owner figure, **UNVERIFIED** in writing) |

The per-service plan above was 74 steady and about 90 at peak; the Release 1 totals are higher at peak because every pool in a pod is sized for its module's own peak and two pods overlap during a roll,
but they sit far under 500 and the per-role limits make them a hard cap. If Layerbase's real `max_connections` is below 150, lower the large pools (`catalog`, `orders`, `payments`) first.

**Layerbase plan.** 4 databases instead of 19: the **Dedicated 4 GB** plan stays the plan for this design (storage, memory and connections decide, not the database count). Whether a cheaper tier with
a database limit of 10 (the table above lists Pro at "2 or 10 databases", **UNVERIFIED**) is enough depends on its connection limit and storage, not on the database count any more.

**Money.** No price in this repository changes (every OVH figure is still **UNVERIFIED** and absent). GHCR stays free for what this builds (**UNVERIFIED**: two images instead of 21 are a smaller package storage
and fewer pushes). Backups: 4 dumps a night instead of 19 (the bucket size is the same data in fewer objects; `BackupSizeHigh` thresholds are per night, unchanged).

## Cost guards that are real

- **ai-gateway spend ceiling** (owner decision, enforced in code by `SpendGuard`, `docs/runbooks/ai-spend-and-mcp-access.md`): USD 2.00 per UTC day for the whole
  gateway and USD 0.50 per caller, pinned in `values-prod.yaml` (`AI_GLOBAL_DAILY_COST_USD` is the initial default of the daily total, which staff with `ai:cap.manage` can change in the console up to `AI_GLOBAL_DAILY_COST_MAX_USD`, USD 20; `AI_CALLER_DAILY_COST_USD`); above it every call answers 429.
  Set a **credit limit on the OpenRouter key** as the second, independent cap.
- **Connection counts** are capped (above). **Kafka** keeps 72 hours, dead letters 14 days, on a 10 GiB disk.
- **Backups** keep `RETENTION_DAYS` (default 14, the owner may set 7) at Backblaze B2; alerts at 600 MB per night and 8 GB in the bucket (`infra/observability/rules/backup.yaml`).

## AI model costs (`AI_MODELS_<TASK>`)

(Moved from the former `docs/runbooks/cost.md`.) Every ai-gateway text task (`INTENT`, `PLANNING`, `CHAT`, `LISTING_WRITER`, `TRANSLATION`,
`TRIAGE`, `IMAGE_MODERATION`) defaults to the cheapest capable models: `google/gemini-2.5-flash-lite` (USD 0.10 in / 0.40 out per million
tokens), then `openai/gpt-5-nano` (USD 0.05 / 0.40); embeddings use `openai/text-embedding-3-small`
(`java/services/ai-gateway/src/main/resources/application.yml`, `at.ai.routes`). A task is raised with one variable, comma-separated in
fallback order, for example `AI_MODELS_CHAT=anthropic/claude-sonnet-4.5,openai/gpt-5-mini`. A model missing from the price table is costed at
the conservative `default-price` (USD 15 / 75 per million), so it reaches the daily ceiling quickly: add its price before raising a task.
The ceiling itself (USD 2.00 per UTC day, USD 0.50 per caller) and the OpenRouter key limit are in "Cost guards that are real" above and
`docs/runbooks/ai-spend-and-mcp-access.md`. Review OpenRouter spend per task monthly (below).

## Budget alerts: what to do when one fires, and the monthly review

(Budget-alert actions moved from the former `docs/runbooks/cost.md`, rewritten for the OVH launch profile.) The OVH budget module
(`infra/tofu/modules/ovh/budget`) creates Public Cloud consumption alerts at 50, 80 and 100 % of `monthly_budget` in
`infra/tofu/envs/<env>/sizing.tf` (a placeholder until the OVH quote; UAT on the USD 200 credit: `docs/runbooks/release-1-go-live.md`
section 10). Layerbase, Cloudflare, Grafana Cloud, Backblaze B2, Resend and OpenRouter are budgeted in their own consoles: set a monthly
spend alert in each when the account is created.

When an alert fires:

1. **Which environment and which line?** OVH console > Public Cloud > the project > Billing / consumption: node, block storage, traffic.
2. **Forecast or 50 %:** usually a new steady cost (a resized node pool, a bigger volume). Confirm it was intended (recent PRs to
   `sizing.tf`, `values-<env>.yaml`); if so, adjust `monthly_budget`. Never raise a budget to silence an alert without the review below.
3. **80 % before the 20th, or 100 % at any time:** look for a second node or a node flavour change (`kubectl get nodes -o wide`), a
   `LoadBalancer` Service that created a paid load balancer (`kubectl get svc -A | grep LoadBalancer`: there must be none), growing PVCs
   (`kubectl get pvc -A`), unexpected egress; outside OVH, OpenRouter spend per task and Grafana Cloud series/log volume.
4. **Mitigate** with the least risky lever: pause UAT (scale to zero, go-live section 10), lower log levels or drop a noisy log line
   (`observability-grafana-cloud.md`), fix a runaway job. Never scale prod below what serves traffic during an incident.
5. Record the cause and the fix in the monthly review notes.

**Monthly review (first Monday of the month):** actual against `monthly_budget` per environment and per vendor; requests against p95 usage
(`kubectl top pods -A`, Grafana) and lower requests in `values-<env>.yaml` where usage stays under half of the request; OpenRouter spend per
task (ai-gateway usage rows) and whether any `AI_MODELS_<TASK>` override is still wanted; Grafana Cloud usage against the free limits;
backup bucket size. Update `monthly_budget` if the baseline changed for a known reason.

## Accepted trade-offs

One node and one replica of everything: a node restart, a node upgrade or a bad deploy is **a few minutes of downtime**, with no canary.
Data is safe only as far as the backups are: Layerbase's own backups are **UNVERIFIED** (hourly claimed, PITR not mentioned), so the
nightly off-vendor dump (RPO 24 h) is the guarantee. Kafka and Temporal are single instances; both resume where they stopped.
Valkey (carts, sessions) and Meilisearch are rebuildable. Plan MKS maintenance windows for a quiet hour.

## Alternatives, kept for comparison

The same lean profile exists for GCP (`us-east4`), Azure (`eastus`) and AWS (`us-east-1`): `cloud = gcp|azure|aws` in the same
OpenTofu root, with the same modules. They are **not** the launch path and their old list-price estimates are not repeated here. The
OVH modules (`infra/tofu/modules/ovh`) pin their cost choices with `tofu test` (free control plane, `b3-16`, monthly billing when
deletion protection is on, no gateway when `egress = none`).

What differs on OVH, all documented in the module headers:

- **No workload identity and no OVH object storage.** Media and KYC evidence are in Cloudflare R2, backups in Backblaze B2; OpenTofu creates no OVH buckets and no per-service S3 users (the module `identity` only emits Helm values). Only the OpenTofu state bucket is on OVH Object Storage.
- **OKMS and Harbor are opt-in** (`ovh_okms`, `ovh_harbor`, both false). OKMS in the US regions is UNVERIFIED and the module refuses
  a US region. CORS for browser uploads cannot be set with the OVH provider (the media service signs server-side).
- **Elastic, Confluent and Temporal Cloud** are not offered on OVH and are off in this profile.

## Test first, decide later

UAT ([release-1-go-live.md](../runbooks/release-1-go-live.md)) runs the whole workflow on a disposable project (hourly billing, separate
project, `disposable = true`) with its own Layerbase server, and produces the measurements this page marks as missing (go-live section 8).
Nothing there needs a commitment.

## Growing out of it

Each step is a sizing flip, not a redesign: a second node pool or a bigger flavour, `zone_count = 3` (needs the paid `standard`
control plane), `managed_data = true` (OVH Managed PostgreSQL), `enable_elastic/confluent/temporal`, switching
`argocd/envs/prod/kustomization.yaml` back to `../../applicationsets`, `replicas`/`autoscaling`/`rollout` in the services'
`values-prod.yaml`. Do it when measured CPU or downtime cost says so, not the calendar.
