# Cloud cost: the lean profile (USD 200 per month, one node)

Production used to cost about USD 600/month (GKE + Aiven + Uploadthing and friends). The repository's production design
was larger still (regional cluster on 3 nodes, HA databases, three paid SaaS platforms, USD 6,500 budget). This page is the
design that fits **USD 200/month for about 20-200 active users**, on **GCP, Azure or AWS**, and how to grow out of it.

The profile is `envs/prod/sizing.tf` (`node_size = "lean"`, `zone_count = 1`, `egress = "none"`, `managed_data = false`,
`monthly_budget = 200`). `dev` and `uat` are not part of the budget: keep them destroyed, develop on the local stack
(`docs/engineering/local-development.md`) and apply them only for a short test.

## What runs where

| Concern | Before | Lean |
|---|---|---|
| Kubernetes | regional GKE, 3 arm64 nodes + system pool + spot pool, autoscaling to 12 | **one zonal cluster, one 4 vCPU / 16 GB node**, no autoscaling, no spare pools |
| Replicas | 3 per service, HPA to 12, Argo Rollouts canary | **1 per service**, no HPA, no Rollouts, no PodDisruptionBudgets that block drains |
| Network | VPC per environment, Cloud NAT per zone, flow logs 50% | **one VPC**, no NAT (nodes have public IPs, inbound closed, Cloudflare Tunnel in), flow logs 10% |
| Postgres, Valkey | Cloud SQL HA + Memorystore (Terraform) | **Layerbase** (external; `managed_data = false` creates nothing) |
| Search | Elastic Cloud / ECK | **Meilisearch on Layerbase** (`SEARCH_PROVIDER=meilisearch`) |
| Events | Confluent Cloud Standard | **Apache Kafka, one KRaft pod** (`infra/k8s/platform/lean/kafka.yaml`, 10 GiB) |
| Workflows | Temporal Cloud | **Temporal auto-setup pod**, state in Layerbase Postgres (`lean/temporal.yaml`) |
| Images | Uploadthing | **Cloudflare R2** (10 GB free, no egress fees); private KYC bucket stays in the cloud's locked bucket |
| Metrics, logs, traces | kube-prometheus-stack + Loki + Tempo (3 collector replicas) | **one OTLP collector to Grafana Cloud free tier** (`envs/prod/opentelemetry-collector-gateway.yaml`) |
| Policy / canary / ECK | Kyverno, Argo Rollouts, ECK operator | not installed (`argocd/applicationsets/lean/`) |
| Web apps | Workers | unchanged (Cloudflare Workers, USD 5 plan) |
| Edge | Cloudflare | unchanged (free plan, Tunnel, no load balancer) |

## Monthly cost (list prices, estimates: check the calculators before you commit)

| Line | GCP us-central1 | Azure eastus | AWS us-east-1 |
|---|---|---|---|
| Control plane | **0** (free credit covers one zonal cluster) | **0** (Free tier, no uptime SLA) | **73** (EKS has no free tier) |
| Node (4 vCPU / 16 GB), on demand | ~98 (`e2-standard-4`) | ~115 (`Standard_D4ps_v6`) | ~98 (`t4g.xlarge`) |
| Node with a 1-year commitment | ~62 | ~75 | ~65 |
| Disks (boot + Kafka), public IPv4 | ~11 | ~13 | ~14 |
| Outbound path | 0 | ~22 (standard LB for SNAT) | 0 |
| Secrets, keys, locked bucket, registry (GHCR) | ~5 | ~4 | ~6 |
| Egress and logs | ~8 | ~8 | ~8 |
| Cloudflare Workers | 5 | 5 | 5 |
| Grafana Cloud, R2 (free tiers) | 0 | 0 | 0 |
| **Subtotal on demand** | **~127** | **~167** | **~204** |
| **Subtotal with the 1-year commitment** | **~91** | **~127** | **~171** |
| Layerbase (Postgres + Valkey + Meilisearch) | _your plan_ | _your plan_ | _your plan_ |

- **GCP is the only cloud that fits on demand with room for Layerbase** (USD 200 minus ~127 = ~73 for Layerbase).
  Azure fits with the reservation. AWS fits only with a Savings Plan and a small Layerbase plan, because the EKS control
  plane alone is USD 73: if AWS is required, plan for ~USD 215-230.
- I do not have Layerbase's price list; the table leaves that line to you. Anything under the headroom above keeps you under 200.
- The Terraform budget (`monthly_budget = 200`) alerts at 50/80/100% actual and 100% forecast, so a mistake shows within days.

## Will it fit on one node?

Memory (requests) against ~13 GB allocatable on a 16 GB node:

| Workload | Requests |
|---|---|
| 16 Java services at 384 Mi (heap capped at 70% of a 640 Mi limit, serial GC, C1 only) | 6.1 GB |
| 10 Go services at 48 Mi | 0.5 GB |
| Kafka 640 Mi, Temporal 512 Mi, OpenFGA 96 Mi | 1.2 GB |
| Envoy, cloudflared, cert-manager, External Secrets, OTLP collector | 0.7 GB |
| Argo CD | ~1.0 GB |
| Kubernetes system (kubelet, CNI, metrics, DNS) | ~1.2 GB |
| **Total** | **~10.7 GB** (about 2.5 GB spare) |

CPU is ~2.6 of ~3.9 cores requested; services idle at a few percent. The risk is **deploy time**: starting JVMs together
spikes CPU. Prod syncs are manual (`autoSync: false`): sync a few services at a time.

**Confirm with Layerbase before you commit:** it must offer about 25 databases (or one server with `CREATE DATABASE`), PostGIS,
TLS, and enough connections.

Database connections: every Java service is capped at 4 connections (`SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE`), about
60-90 in total with the Go services, OpenFGA (5) and Temporal (5). **Check Layerbase's connection limit** (or use its pooler).

## Accepted trade-offs (you asked for one replica)

One node and one replica of everything means a node restart or a bad deploy is **a few minutes of downtime**, with no
canary. Data is safe: it lives in Layerbase (backups, not this repo's job), Kafka's disk and R2/GCS. Kafka and Temporal
are single instances; both resume where they stopped. Plan GKE/AKS maintenance windows for a quiet hour.

## Setting it up

1. `terraform.tfvars` from `envs/prod/terraform.tfvars.example` (the lean defaults are already in it), `tofu apply`.
2. Put these in the secret store (`infra/terraform/scripts/secret-set.sh`): per service `DB_URL`, `DB_USER`, `DB_PASSWORD`
   (Layerbase Postgres, one database per service: run `infra/terraform/scripts/postgres-bootstrap.sh` with the admin credential and
   `DATABASES` = the service databases plus `temporal temporal_visibility openfga`; the `geo` database needs the PostGIS extension),
   `REDIS_URL` (Valkey), `MEILISEARCH_URL` and `MEILISEARCH_API_KEY` for search and indexer, `R2_ACCESS_KEY_ID` and
   `R2_SECRET_ACCESS_KEY` for media, and the platform keys `afrotransact/temporal` (host, port, user, password of the
   Temporal databases `temporal` and `temporal_visibility`), `afrotransact/grafana-cloud` (endpoint, instance-id, api-token)
   and `afrotransact/openfga`.
3. Cloudflare (`infra/cloudflare`, `origin_mode = "tunnel"`), then bootstrap Argo CD with `argocd/bootstrap/root-prod.yaml`.
4. Move users off Keycloak: [keycloak-migration.md](keycloak-migration.md).

## Growing out of it

Each step is a sizing flip, not a redesign: `zone_count = 3`, `min_nodes = 3`, `node_size = "medium"` (HA nodes);
`managed_data = true` (cloud Postgres/Valkey); `enable_elastic/confluent/temporal = true`; switch
`argocd/envs/prod/kustomization.yaml` back to `../../applicationsets`; set `replicas`, `autoscaling` and `rollout` in the
services' `values-prod.yaml`. Do it when active users, not the calendar, say so.
