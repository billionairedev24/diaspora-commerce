# Infrastructure (OpenTofu): OVHcloud + Layerbase for production

**Production is OVHcloud Managed Kubernetes (OVH US entity, `US-EAST-VA-1`) with PostgreSQL on Layerbase.** The same environment
roots can also target AWS, Google Cloud or Azure with one variable (`cloud`), but those are alternatives, not the launch path
([ADR 0004](../../docs/adr/0004-lean-single-node-production.md), [cost and sizing](../../docs/engineering/cloud-cost.md)).
Nothing here has been applied to a live account: treat every OVH resource as **unrun** until the first apply in a disposable project
(step by step: [docs/runbooks/ovh-production.md](../../docs/runbooks/ovh-production.md)).

Cloudflare sits in front (`infra/cloudflare`, Tunnel, no load balancer). Search is Meilisearch, events are Kafka and workflows are
Temporal, all **in the cluster** (`infra/k8s/platform/lean`). Elastic Cloud, Confluent Cloud and Temporal Cloud are not used
(their modules remain for the other clouds and are off by default: `enable_elastic/confluent/temporal = false`).

```
infra/tofu/
  modules/{aws,gcp,azure,ovh}/{network,cluster,postgres,cache,bucket,secrets,registry,identity,github-oidc,budget}
  modules/shared/{elastic,confluent,temporal}  optional SaaS, off in the lean profile
  modules/README.md                        the shared module contract (read this next)
  envs/{dev,uat,prod}/                     main.tf (identical) + sizing.tf (per env) + tfvars/backends
  bootstrap/{aws,gcp,azure,ovh}/              one-time remote-state bucket per account/project/subscription
  scripts/sync-interface.sh                keeps the four module sets on one interface (CI: --check)
  scripts/postgres-bootstrap.sh            per-service databases + roles + PostGIS (Layerbase and every other server)
  scripts/elastic-ilm.sh                   only with Elastic Cloud (not used on the OVH path)
infra/policy/tofu/                    OPA/Conftest rules run against every plan
```

Requires **OpenTofu >= 1.9** (provider `for_each`). The OVH provider is `ovh/ovh` `>= 2.0, < 3.0`.

## 1. What `cloud = "ovh"` creates in production

| Module | Production behaviour |
|---|---|
| `network` | one private network + subnet (free); **no gateway** (`egress = "none"`): nodes keep public IPs, users never reach them (Tunnel) |
| `cluster` | MKS **free plan**, ONE `b3-16` node in pool `general`, **monthly billed** (deletion protection on), no autoscaler, no spot pool; `ovh_cloud_project_kube_iprestrictions` from `admin_cidrs` |
| `bucket` | Object Storage (S3): `kyc-evidence` with object lock (compliance, 7 years), `exports`, `labels`, `media` (versioned). Media is R2-only in prod (`docs/runbooks/r2-buckets.md`), so these start as the archive/backup copy of the R2 buckets |
| `identity` | one Object Storage user per service that touches a bucket (policy limited to its buckets); the S3 keys are read from the state or the OVH panel and go into the service Secret |
| `budget` | Public Cloud consumption alerts at 50/80/100 % of `monthly_budget` |
| `github-oidc` | creates nothing (OVH has no CI identity federation); with GHCR as the registry the deploy job needs no OVH secret |
| `postgres`, `cache` | **not created**: Postgres is external (Layerbase) and Valkey runs in the cluster (`managed_data = false`) |
| `secrets` (OKMS) | **opt-in**, `ovh_okms = true`; default is SOPS/age (`infra/ops/secrets`). The module refuses a US region (OKMS in the US is unverified) |
| `registry` (Harbor) | **opt-in**, `ovh_harbor = true`; default is GHCR |

Plan-time guards in `envs/*/main.tf` (identical in all three environments):

- with external data (`managed_data = false`) the cluster region must equal the Layerbase region mapping: `virginia` is OVH
  `US-EAST-VA-1`, `oregon` is `US-WEST-OR-1` (the old mapping to Quebec `BHS5` is gone);
- a `US-*` region requires `ovh_endpoint = "ovh-us"` (the OVH US entity is its own account with its own API credentials);
- an environment with deletion protection (prod) requires `admin_cidrs` (the Kubernetes API is public on the free plan).

## 2. Pick a cloud (and account)

```hcl
# envs/prod/terraform.tfvars   (envs/prod/terraform.tfvars.example is the OVH production starting point)
cloud            = "ovh"
region           = "US-EAST-VA-1"
layerbase_region = "virginia"
ovh_service_name = "<public cloud project id>"
ovh_endpoint     = "ovh-us"
admin_cidrs      = ["203.0.113.7/32"]    # your office/VPN: the Kubernetes API is restricted to these
```

Only that cloud's modules and provider are instantiated (`for_each = local.on.<cloud>`), so `tofu plan` never needs credentials for the
others. OVH credentials are **API keys** (no OIDC): `OVH_APPLICATION_KEY`, `OVH_APPLICATION_SECRET`, `OVH_CONSUMER_KEY`, created at
`https://api.us.ovhcloud.com/createToken/` for the US entity with rights on `/cloud/project/<id>/*` and `/me`.
`disposable = true` (trial) removes deletion protection and shortens KYC retention to one day: **never** for the real project.
Switching an existing environment to another cloud is a migration, not a flag flip (back up, destroy, change, apply, restore).

## 3. Bootstrap remote state (once per project)

```bash
cd infra/tofu/bootstrap/ovh && tofu init && tofu apply \
  -var service_name=<project id> -var region=US-EAST-VA -var endpoint=ovh-us    # state bucket + state-only S3 user
```

OVH Object Storage has no native lock: do not run two applies against one state (the `tofu.yml` concurrency group is the lock).
Then in the environment directory:

```bash
cd infra/tofu/envs/prod
cp terraform.tfvars.example terraform.tfvars          # fill in; non-secret values only
cp backend.ovh.hcl.example backend.hcl                # uncomment the US lines
printf 'terraform {\n  backend "s3" {}\n}\n' > backend.tf
tofu init -backend-config=backend.hcl
```

## 4. Apply

```bash
tofu plan -out plan.out
tofu show -json plan.out > plan.json
conftest test plan.json --policy ../../../policy/tofu
tofu apply plan.out
```

After the first apply (full runbook in `docs/runbooks/ovh-production.md`):

1. Download the kubeconfig (`tofu output cluster` prints the hint), set the cluster up (Argo CD, secrets).
2. Create the databases, roles and extensions on Layerbase **before any service starts**:
   `PGHOST=<layerbase host> PGUSER=<admin> PGPASSWORD=... infra/tofu/scripts/postgres-bootstrap.sh`
   (19 databases; `postgis` in `geo` and `fulfilment`, `btree_gist` in `bookings`, created as the admin because the service roles
   cannot on a managed server; `temporal_visibility` shares the `temporal_app` role). It prints each database's URL and password once.
   For production the single source of passwords is `infra/ovh-vps/secrets.sh` with `PROFILE=prod` (it also creates roles and the
   same extensions and renders the Secrets): use one of the two, not both.
3. Cloudflare R2 tokens for the media service (`R2_*` in `secrets.local.env`, `docs/runbooks/r2-buckets.md`). The OVH per-service S3 keys (module `identity`) are only
   needed once something writes the OVH buckets (the R2 backup copy).

Environments: `dev` and `uat` use the same roots with their own `sizing.tf` and are applied only for a short, deliberate test.
`main.tf`, `providers.tf`, `variables.tf` and `outputs.tf` are identical in all three directories (`scripts/sync-interface.sh`).

## 5. Environments and cost levers

| | dev | uat | prod (OVH launch) |
|---|---|---|---|
| Zones / NAT | 1 / single | 2 / single | 1 / none |
| Nodes | 1 / 3, spot 6 | 2 / 4, spot 10 | **one `b3-16`, no autoscaling** |
| Postgres | smallest managed | medium managed | **external (Layerbase)** |
| Cache | small | medium | in the cluster (Valkey) |
| Elastic / Confluent / Temporal Cloud | off | off | off |
| Backups / KYC retention | 3 d / 1 d (governance) | 7 d / 30 d | Layerbase + nightly off-vendor dump / 7 years (compliance, locked) |
| Deletion protection | off | on | on |
| Monthly cloud budget (`monthly_budget`, UNVERIFIED placeholder in prod) | $1,000 | $1,500 | 150 until the OVH quote exists |

Levers that exist in the modules: free MKS control plane unless `zone_count > 1`; monthly billing for the always-on pool; no NAT/gateway
unless `egress != none`; Object Storage lifecycle (abort stale uploads, expire noncurrent versions, Standard-IA); consumption alerts.
OVH has no arm64 and no spot market (the "spot" pool is tainted, hourly and scales from zero; there is no discount).

## 6. Tests and checks

- `tofu test` in `modules/ovh/*` (mocked provider) pins the cost choices: lean = one `b3-16` on the free plan, monthly billing in prod.
- `scripts/sync-interface.sh --check` keeps the four module sets and the three environment roots in step (CI).
- `infra/policy/tofu` (Conftest) checks public buckets, encryption, tags and world ingress on every plan.
- What could **not** be run when these changes were written: `tofu init/validate/plan/test` (no OpenTofu binary, no provider
  registry access, no OVH account). The files were only parsed as HCL. Run `tofu fmt -check` and `tofu validate` first.

## 7. Other clouds (alternatives)

AWS, GCP and Azure keep working with the same interface (`cloud = aws|gcp|azure`, their `bootstrap/` roots, OIDC deploy roles from
`modules/<cloud>/github-oidc`, ECR/Artifact Registry/ACR mirrors by `.github/workflows/_deploy.yml`). The first apply of each is described
in `docs/runbooks/first-environment.md`. They are not the launch path and are not rehearsed against the OVH decision.
