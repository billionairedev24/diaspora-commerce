# ADR 0004 — Production runs lean on OVH Managed Kubernetes with Layerbase: one node, one replica, external Postgres

- **Status:** Accepted. Revised 2026-11-12: the first version (2026-10-18) chose GKE, Azure or AWS; the owner then decided on
  OVHcloud + Layerbase. This revision replaces the cloud choice and keeps the lean shape.
- **Date:** 2026-10-18 (revised 2026-11-12)
- **Deciders:** platform owner
- **Related:** `docs/engineering/cloud-cost.md`, `docs/runbooks/ovh-production.md`, `docs/runbooks/restore-drill.md`,
  `docs/engineering/keycloak-migration.md`, `infra/tofu/envs/prod/sizing.tf`, `infra/argocd/launch-set/`

## Context

The first production design (GKE, Aiven, Uploadthing and others) cost about USD 600 a month for about 21 users, and the design in
the repository assumed a regional cluster, three replicas, HA databases and three paid platforms (Elastic Cloud, Confluent Cloud,
Temporal Cloud) at a USD 6,500 budget. The target is now a **launch** for a small invite-only user base, at the lowest cost that
is still operable by one person, on **OVHcloud (US entity)** for compute and **Layerbase** for PostgreSQL.

Nothing in this ADR has been applied to a live account. Every vendor fact is tagged UNVERIFIED in `cloud-cost.md` and
`ovh-production.md` until someone has confirmed it with the vendor or on the first apply.

## Decision

1. **Compute: OVH Managed Kubernetes (MKS), free control plane, ONE `b3-16` node (4 vCPU / 16 GB) billed monthly**, in the OVH US
   region `US-EAST-VA-1` (Vint Hill, Virginia). No load balancer, no public node IP exposed to users, no autoscaler, no spot pool.
   The OpenTofu lean profile (`node_size = "lean"`, `zone_count = 1`, `egress = "none"`, `managed_data = false`) expresses this.
2. **Postgres is external: Layerbase Dedicated in Virginia**, one server, **19 databases** (12 Java services, `ai`, 4 Go services,
   `temporal`, `temporal_visibility`). PostGIS is created as the admin in `geo` and `fulfilment` (and `btree_gist` in `bookings`)
   before the first deploy. Backups do not depend on Layerbase alone: a nightly encrypted `pg_dump` of every database goes to a
   bucket at **another vendor** (RPO 24 h, RTO 4 h targets, `docs/runbooks/restore-drill.md`).
3. **Valkey, Meilisearch, Kafka and Temporal run in the cluster** as single instances (`infra/k8s/platform/lean`). Valkey and
   Meilisearch hold rebuildable data (cache, sessions, the search index); Kafka's events are republished from the outbox.
4. **Edge: Cloudflare Tunnel** (cloudflared pod, outbound only) to an Envoy Gateway in the cluster. TLS, WAF and rate limits are
   Cloudflare's; there is no OVH load balancer and no inbound port. The Kubernetes API is public on the free plan and restricted
   to the operators' IPs.
5. **Web apps stay on Cloudflare Workers.** Product images go to Cloudflare R2; private files (KYC and others) to an OVH Object
   Storage bucket with object lock.
6. **Registry: GHCR** (private packages, read-only pull token). No OVH Harbor; images are verified by cosign in the pipeline.
7. **Deploy: Argo CD, trimmed, manual sync in prod.** Argo creates Applications for the **launch set only** (21 of the 26
   services: 14 Java and 7 Go) from an explicit list (`infra/argocd/launch-set`). `promise`, `carrier-gateway`, `retail`,
   `delivery-runs`, `authz-sync` and OpenFGA are not deployed (`FGA_ENABLED=false`; a shadow-mode switch is kept for later).
8. **Secrets: SOPS + age** files applied by an operator (`infra/ops/secrets`). OVH Secret Manager (OKMS) with External Secrets is
   an optional alternative that is not verified against a live account.
9. **Telemetry goes to Grafana Cloud's free tier** through one OTLP collector; no in-cluster Prometheus, Loki, Tempo or Grafana.
10. **Users move from Keycloak to the identity service by importing their password hashes** (verify-only PBKDF2 encoders), so
    nobody resets a password.

## Consequences

- **Cost** is a sum of vendor prices that are not in this repository: see `cloud-cost.md` (all OVH lines UNVERIFIED). The previous
  USD 173 to 214 figures were GCP numbers and no longer apply.
- A node restart, a node upgrade or a bad deploy means minutes of downtime and there is no canary. Accepted for a small launch.
  Maintenance windows are set on the cluster.
- One person can run it, but nothing was rehearsed: the first trial (hourly, disposable project) measures real memory per JVM,
  deploy-time CPU, restore time and node-reboot downtime before the production project is created.
- Memory fits with about 3 GiB spare on a 16 GB node only if the JVMs stay near their requests (`cloud-cost.md`, table). It is
  CPU and deploy-time bursts that decide when to add a second node.
- Growing back is a set of sizing flips (cloud-cost.md), not a redesign: a second node pool, HA databases, `standard` control plane.
- dev and uat leave the cloud budget: local stack and CI previews only. The `uat` Argo environment still exists for a short,
  deliberate test and uses the same launch set.

## Alternatives considered

- **GKE (or AKS, EKS)** with the same lean shape: cheaper per hour on GCP with a commitment by the repository's own estimates, but
  the owner chose OVH; the old estimates stay in `cloud-cost.md` as the comparison.
- **Self-managed k3s on one OVH instance** (`infra/ovh-vps`): the same instance price, but the operator owns OS patches, k3s
  upgrades and the control plane. Kept as the **test** path only; it deploys the dev profile and is not production.
- **A second node** or a managed OVH PostgreSQL: both add cost without a measured need; revisit with data from the trial.
