# Cloud cost and sizing: OVHcloud + Layerbase, one node

This page is the sizing and cost design for the **production launch**: OVH Managed Kubernetes (one node) + Layerbase PostgreSQL +
Cloudflare. Decision and reasons: [ADR 0004](../adr/0004-lean-single-node-production.md). Step by step from zero:
[ovh-production.md](../runbooks/ovh-production.md).

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
| Telemetry | Grafana Cloud free tier (one stack, `env` = trial or prod): traces through one OTLP collector, **metrics and logs through Grafana Alloy** (+ kube-state-metrics, kafka-exporter); no public Grafana. [observability-grafana-cloud.md](../runbooks/observability-grafana-cloud.md) |
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
centre host the Layerbase server is unknown (**ask**, see the Layerbase questions in ovh-production.md); latency between the two
sites is expected to be a few ms in the same metro (**UNVERIFIED**: measure it before the first deploy).

## Monthly cost: what is known and what is not

| Line | Amount | Status |
|---|---|---|
| OVH MKS control plane (free plan) | 0 | UNVERIFIED (repo module header; confirm the free plan exists in US East and its SLA/limits) |
| OVH node `b3-16`, monthly billing | **not in the repo** | UNVERIFIED: get the quote; monthly billing is said to be about half the hourly price and cannot be switched back |
| OVH block storage for PVCs (Kafka 10 GiB + Valkey 2 + Meilisearch 5 = 17 GiB) and boot disk | not in the repo | UNVERIFIED |
| OVH Object Storage (Terraform state, copy of the R2 buckets; the tofu `kyc-evidence` locked bucket is currently written by nothing) | not in the repo | UNVERIFIED |
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
ovh-production.md).

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

- Alloy, kube-state-metrics and kafka-exporter add **152 Mi of requests** (the only memory cost of the Grafana Cloud monitoring); both figures are guesses until `kubectl top pods -n observability` runs in the trial.
- The **backup CronJob** (128 Mi, limit 512 Mi) runs for minutes at night and is not counted above; a deploy surge adds one JVM
  (384 Mi) at a time (`maxSurge 25 %`, `maxUnavailable 0`).
- Limits sum to more than the node. That is normal for a single node, but it means that if every JVM grows to its limit at once the
  kernel OOM-kills something. Real RSS is **unmeasured**: measure it in the trial (`kubectl top`, `ovh-production.md` section 12)
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

## Cost guards that are real

- **ai-gateway spend ceiling** (owner decision, enforced in code by `SpendGuard`, `docs/runbooks/ai-spend-and-mcp-access.md`): USD 2.00 per UTC day for the whole
  gateway and USD 0.50 per caller, pinned in `values-prod.yaml` (`AI_GLOBAL_DAILY_COST_USD`, `AI_CALLER_DAILY_COST_USD`); above it every call answers 429.
  Set a **credit limit on the OpenRouter key** as the second, independent cap.
- **Connection counts** are capped (above). **Kafka** keeps 72 hours, dead letters 14 days, on a 10 GiB disk.
- **Backups** keep `RETENTION_DAYS` (default 14, the owner may set 7) at Backblaze B2; alerts at 600 MB per night and 8 GB in the bucket (`infra/observability/rules/backup.yaml`).

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

- **No workload identity.** Pods that need OVH Object Storage would use a per-service S3 user (module `identity`; OVH US uses the `.ovh.us` S3 endpoint).
  No launch service does today: media is R2-only, so the module's buckets are an archive/backup target until a service writes them (decide whether to drop `kyc-evidence`).
- **OKMS and Harbor are opt-in** (`ovh_okms`, `ovh_harbor`, both false). OKMS in the US regions is UNVERIFIED and the module refuses
  a US region. CORS for browser uploads cannot be set with the OVH provider (the media service signs server-side).
- **Elastic, Confluent and Temporal Cloud** are not offered on OVH and are off in this profile.

## Test first, decide later

[trial-environment.md](../runbooks/trial-environment.md) runs the whole workflow on a disposable project (hourly billing, separate
project, `disposable = true`) with a short Layerbase probe, and produces the measurements this page marks as missing.
Nothing there needs a commitment.

## Growing out of it

Each step is a sizing flip, not a redesign: a second node pool or a bigger flavour, `zone_count = 3` (needs the paid `standard`
control plane), `managed_data = true` (OVH Managed PostgreSQL), `enable_elastic/confluent/temporal`, switching
`argocd/envs/prod/kustomization.yaml` back to `../../applicationsets`, `replicas`/`autoscaling`/`rollout` in the services'
`values-prod.yaml`. Do it when measured CPU or downtime cost says so, not the calendar.
