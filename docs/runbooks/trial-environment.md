# Trial run on free credits (before the production decision)

Purpose: prove the whole workflow on a free-credit cloud account (GCP's $300, Azure's $200, AWS's new-account credits) and
answer the open questions of [cloud-cost.md](../engineering/cloud-cost.md) **without a commitment and without paying Layerbase**.
Check the exact credit terms (amount, days, quotas, which services are covered) on each signup page: they change, and trial
accounts often have vCPU quotas. Ask for a quota increase or pick a smaller region if the node does not start.

## Rules that keep it free and disposable

1. **A separate project/subscription/account for the trial** (GCP project `afrotransact-trial`), never the production one. Teardown =
   delete the project.
2. **`disposable = true`** in `terraform.tfvars`: deletion protection off and KYC retention 1 day, so `tofu destroy` leaves nothing
   behind (a locked 7-year retention policy cannot be deleted). **Never set it in the real production state.**
3. **A separate state**: `tofu init -backend-config=backend.hcl` with a different state prefix/key (e.g. `prod-trial`), and
   `name_prefix = "afrotransact-trial"`.
4. **No commitments, no reservations.** Test on demand.
5. **Budget alert at $60 and at $120** on the trial billing account (the OpenTofu budget module does the cloud side; also set one in
   the billing console). The lean node is ~$139/month on demand on GCP, so $300 lasts about two months: do the test in **two weeks**
   and destroy.
6. **Real secrets only for services you test** (Stripe test keys, not live). No real customer data: use the seed data
   (`make seed` equivalent) or a Keycloak export scrubbed of real emails for the migration rehearsal.

## Setup

`terraform.tfvars` for the trial (GCP shown; the cluster and Layerbase must share a location, see cloud-cost.md):

```hcl
cloud            = "gcp"
region           = "us-east4"      # Virginia: where Layerbase Dedicated runs
layerbase_region = "virginia"
gcp_project_id   = "afrotransact-trial"
name_prefix      = "afrotransact-trial"
disposable       = true
enable_elastic = false
enable_confluent = false
enable_temporal = false
```

Then follow [first-environment.md](first-environment.md) with three differences:

- **Data stores in the cluster, not Layerbase** (`managed_data = false` already creates none in OpenTofu). In
  `infra/k8s/platform/lean/kustomization.yaml` uncomment `data/namespace.yaml`, `valkey.yaml`, `meilisearch.yaml` and
  `postgres-trial.yaml`. Secrets (`infra/tofu/scripts/secret-set.sh`): `afrotransact/trial-postgres` (password),
  `afrotransact/valkey` (password), `afrotransact/meilisearch` (master-key), plus the usual per-service keys with
  `DB_URL` pointing at `postgres.data.svc.cluster.local`, `REDIS_URL` at `valkey.data.svc.cluster.local:6379` and
  `MEILISEARCH_URL` at `http://meilisearch.data.svc.cluster.local:7700`. Create the databases with
  `infra/tofu/scripts/postgres-bootstrap.sh` through `kubectl -n data port-forward svc/postgres 5432`
  (`DATABASES` = the services' databases plus `temporal temporal_visibility openfga`).
- **Argo CD** `argocd/envs/prod/platform-values.yaml`: set `cloud: gcp`, the registry and workload identity as in its GCP example.
- **A throwaway domain** for the trial (a subdomain such as `trial.afrotransact.com` through `infra/cloudflare`, `origin_mode = "tunnel"`).

## What to test, in order (tick each)

**1. Platform comes up**
- [ ] `tofu apply` succeeds on the credit account; one zonal cluster, one node, no NAT, no extra pools.
- [ ] Argo CD syncs the lean platform: cert-manager, External Secrets, Envoy, OTLP collector, Kafka, Temporal, OpenFGA.
- [ ] All 26 services reach Ready on the one node. Record `kubectl top nodes` and `kubectl describe node | grep -A8 Allocated`:
  memory requests should be near 10-11 GB of ~13, CPU under 3 of ~3.9.
- [ ] Sync a few services at a time and watch CPU: this is the deploy-spike risk named in cloud-cost.md.

**2. Workflow**
- [ ] CI builds an image, bumps the tag in `values-prod.yaml`, Argo CD shows OutOfSync, you sync by hand, the pod rolls.
- [ ] A bad deploy (wrong image tag) fails safe: Argo CD reports it and the old pod keeps running until the new one is Ready
  (with 1 replica there is a short gap; measure it).
- [ ] Secrets: change one in the store, External Secrets refreshes it, restart the service.

**3. Product paths** (storefront, Seller Center, staff console against the trial URLs)
- [ ] Sign up, verify email, sign in on web and the mobile app; add to cart; checkout with a Stripe **test** card (this exercises
  Kafka events, Temporal's checkout saga, payments, inventory, notifications).
- [ ] A seller onboards, lists a product, and it appears in search (indexer -> Meilisearch; run the backfill once).
- [ ] Upload a product image: it lands in R2 and loads from `media.<domain>`.
- [ ] Logs, traces and metrics arrive in Grafana Cloud (free tier) from the OTLP collector.

**4. Failure and cost (the decision data)**
- [ ] Restart the node (`gcloud compute instances reset`): time to all-Ready, and whether Kafka, Temporal workflows and carts
  resume. This number is your real downtime per node restart.
- [ ] Load test with k6 (`tools/perf`) at 50 concurrent shoppers: p95 latency, CPU, memory, OOM kills (`kubectl get events`).
- [ ] After 3 days read the billing report: **cloud cost per day x 30** against the estimates in cloud-cost.md, and the network
  egress line.

**5. Keycloak migration rehearsal** ([keycloak-migration.md](../engineering/keycloak-migration.md)) with a scrubbed export:
- [ ] `report` is clean; `apply` imports; two test users sign in with their old passwords (one with a non-ASCII password).
- [ ] A seller test user sees their organisation after the `--memberships` step.

**6. Layerbase probe** (separate, once the rest passes; do this last because it needs a card)
Layerbase Pro has a 7-day trial (10 databases: enough to probe four services, not all 26). In the same region as the cluster:
- [ ] Ask support which cloud hosts the server, the VM's connection limit, and whether mTLS is optional.
- [ ] Point `identity`, `orders`, `seller` and `payments` at it (JDBC with TLS; with mTLS if it is mandatory) and compare
  checkout latency with the in-cluster Postgres.
- [ ] Check cross-provider traffic on the cloud bill after a day of the load test.
- [ ] Cancel the trial before day 7 if you will not continue.

## Decide

| Result | Decision |
|---|---|
| All-Ready at ~11 GB, node restart under ~5 min, checkout p95 fine | one node works: go on |
| Memory or CPU too tight | node `e2-standard-8` (~$220, over budget) or fewer/merged services: tell me the numbers |
| Layerbase latency or egress is bad, or 8 GB is too expensive | **option B**: Layerbase 4 GB for Postgres only, Valkey and Meilisearch already proven in the cluster (data/valkey.yaml, data/meilisearch.yaml) |
| Layerbase fine at 8 GB | option A; buy the 1-year commitment when you are ready |

## Tear down

`tofu destroy` in the trial state, then delete the GCP project (or the Azure resource group / AWS account resources) and the
trial Cloudflare records and R2 bucket. Cancel the Layerbase trial. Check the billing console shows $0 the next day.
