# ADR 0004 — Production runs lean: one node, one replica, external data, USD 200 per month

- **Status:** Accepted
- **Date:** 2026-10-18
- **Deciders:** platform owner
- **Related:** `docs/engineering/cloud-cost.md`, `docs/engineering/keycloak-migration.md`, `infra/terraform/envs/prod/sizing.tf`

## Context

Production (GKE, Aiven, Uploadthing and others) cost about USD 600 a month for about 21 users. The design in the repository
assumed a regional cluster, three replicas, HA databases and three paid platforms (Elastic Cloud, Confluent Cloud, Temporal
Cloud) at a USD 6,500 budget. The limit is now USD 200 a month on any of GCP, Azure or AWS.

## Decision

1. A **lean profile** in the existing Terraform sizing (`node_size = "lean"`, `zone_count = 1`, `egress = "none"`,
   `managed_data = false`): one zonal cluster, one 4 vCPU / 16 GB node, one VPC, no NAT, one replica of everything.
2. **Postgres, Valkey and Meilisearch are external (Layerbase Dedicated, 8 GB at $65)**; Terraform creates none of them.
   Everything lives in **one location, Virginia** (Layerbase Dedicated offers Virginia, Oregon and Sao Paulo); `tofu plan` fails
   when the cluster's region is not the Layerbase region.
3. **Kafka and Temporal run in the cluster** (single instances) instead of Confluent Cloud and Temporal Cloud.
4. **Telemetry goes to Grafana Cloud's free tier** through one OTLP collector; no in-cluster Prometheus, Loki or Tempo.
5. **Product images move from Uploadthing to Cloudflare R2**; the locked KYC bucket stays in the cloud's object store.
6. **Users move from Keycloak to the identity service by importing their password hashes** (verify-only PBKDF2 encoders), so
   nobody resets a password.

## Consequences

- Cost is ~USD 173 on GCP with a 1-year commitment (~214 on demand, so the alert at the 135 cloud budget is expected); Azure ~202
  with a reservation; AWS stays over because the EKS control plane is USD 73 alone. Option B (Layerbase 4 GB, Valkey and
  Meilisearch in the cluster) is ~184 on GCP without a commitment (cloud-cost.md).
- A node restart or deploy means minutes of downtime and there is no canary. Accepted for ~20 users.
- Growing back is a set of sizing flips (documented in cloud-cost.md), not a redesign.
- dev and uat leave the cloud budget: local stack and CI previews only.
