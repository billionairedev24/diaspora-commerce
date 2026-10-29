# Infrastructure (OpenTofu) - AWS, Google Cloud, Azure or OVHcloud

AfroTransact runs on Kubernetes on **one** of four clouds per environment, chosen with a single variable.
Cloudflare sits in front of every option (`infra/cloudflare`), and Elasticsearch (Elastic Cloud) and
Kafka (Confluent Cloud) are the same on all four, so moving clouds changes the origin and the data
stores - not the applications, pipelines or edge.

```
infra/tofu/
  modules/{aws,gcp,azure,ovh}/{network,cluster,postgres,cache,bucket,secrets,registry,identity,github-oidc,budget}
  modules/shared/{elastic,confluent,temporal}  same on every cloud (Elastic Cloud, Confluent Cloud, Temporal Cloud)
  modules/README.md                        the shared module contract (read this next)
  envs/{dev,uat,prod}/                     main.tf (identical) + sizing.tf (per env) + tfvars/backends
  bootstrap/{aws,gcp,azure,ovh}/              one-time remote-state bucket per account/project/subscription
  scripts/sync-interface.sh                keeps the four module sets on one interface (CI: --check)
  scripts/postgres-bootstrap.sh            per-service databases + roles (all clouds)
  scripts/elastic-ilm.sh                   ILM hot-warm-frozen policies + scoped ES API keys
infra/policy/tofu/                    OPA/Conftest rules run against every plan
```

Requires **OpenTofu >= 1.9** (provider `for_each`). OpenTofu is not supported for the env roots.

## 1. Pick a cloud

```hcl
# envs/<env>/terraform.tfvars
cloud  = "gcp"        # aws | gcp | azure | ovh  <- the only switch
region = "us-east4"
```

Only that cloud's modules - and its provider - are instantiated: every module and provider block uses
`for_each = local.on.<cloud>`, which is an empty set for the other two clouds, so `tofu plan` never needs
credentials for clouds you did not choose. Each environment may use a different cloud (e.g. try GCP in
dev while prod is on AWS).

Credentials are never in files: locally use `aws sso login` / `gcloud auth application-default login` /
`az login`; in CI the OIDC roles created by `github-oidc` are used. Elastic Cloud reads `EC_API_KEY`,
Confluent reads `CONFLUENT_CLOUD_API_KEY` / `CONFLUENT_CLOUD_API_SECRET`, Temporal Cloud reads
`TEMPORAL_CLOUD_API_KEY` (or set `enable_elastic` / `enable_confluent` / `enable_temporal = false`).
The first apply of a new environment, step by step per cloud: `docs/runbooks/first-environment.md`.

**Switching an existing environment to another cloud** is a migration, not a flag flip: back up data,
`tofu destroy` (OpenTofu warns that provider instances must outlive their resources - this is why), change
`cloud`, `tofu apply`, restore data, repoint Cloudflare (`origin_mode`/`origin_target`), set the GitHub
environment variables (step 4). Pipelines, charts and images do not change.

## 2. Bootstrap remote state (once per account / project / subscription)

```bash
cd infra/tofu/bootstrap/aws   && tofu init && tofu apply -var region=us-east-1
cd infra/tofu/bootstrap/gcp   && tofu init && tofu apply -var project_id=afrotransact-prod -var region=us-east4
cd infra/tofu/bootstrap/azure && tofu init && tofu apply -var subscription_id=<id> -var location=eastus
cd infra/tofu/bootstrap/ovh   && tofu init && tofu apply -var service_name=<project id> -var region=GRA   # state bucket + state-only S3 user
```

Each creates a versioned, encrypted, private state bucket (S3 native lockfile / GCS / Azure blob lease
locking). Then in the environment directory:

```bash
cd infra/tofu/envs/dev
cp terraform.tfvars.example terraform.tfvars          # non-secret values; commit it (CI reads it)
cp backend.<cloud>.hcl.example backend.hcl            # fill bucket / account names
printf 'terraform {\n  backend "s3" {}\n}\n' > backend.tf   # "gcs" or "azurerm" for the other clouds
tofu init -backend-config=backend.hcl
```

`main.tf` stays cloud-neutral; CI writes `backend.tf` + `backend.hcl` itself (`.github/actions/tofu-init`).

## 3. Apply an environment

```bash
tofu plan -out plan.out
tofu show -json plan.out > plan.json
conftest test plan.json --policy ../../../policy/tofu   # public buckets, encryption, tags, world ingress
tofu apply plan.out
```

After the first apply:

1. `tofu output -json cluster_secret_store` -> the `platform-secrets` ClusterSecretStore (infra/k8s).
2. `tofu output -json platform_values` -> per-service `cloud` + `workloadIdentity` values for Argo CD.
3. Run `scripts/postgres-bootstrap.sh` (admin secret: `tofu output postgres`) and merge its per-service
   `DB_URL/DB_USER/DB_PASSWORD` into the service secrets.
4. `ES_URL=... ES_PASSWORD=... scripts/elastic-ilm.sh` -> ILM policies + `ES_API_KEY` for search/indexer.
5. `tofu output github_oidc` and `registry_url` -> GitHub environment variables (see
   `docs/engineering/ci-cd.md`). The first apply of an environment is done by an admin; afterwards CI
   plans every PR and applies dev on merge, uat/prod on approval.

Order of environments: `dev` creates the account-wide GitHub OIDC provider/pool
(`create_github_oidc_provider = true`); `uat`/`prod` in the same account/project set it to `false` and pass
`github_oidc_provider_ref`. Prefer separate accounts/projects/subscriptions for prod.

## 4. Environments and cost levers

`main.tf`, `providers.tf`, `variables.tf` and `outputs.tf` are identical in all environment directories; only
`sizing.tf` differs:

| | dev | uat | prod |
|---|---|---|---|
| Zones / NAT | 1 / single | 2 / single | 3 / per zone |
| Nodes (arm64 on-demand floor / max, spot max) | 1 / 3, 6 | 2 / 4, 10 | 3 / 12, 30 |
| Postgres | smallest; Aurora Serverless v2 **min 0 ACU (auto-pause)** | medium | large, HA (standby/reader in another zone) |
| Cache | small, no replica | medium | large, replica + failover |
| Elastic / Confluent | hot only / Basic | hot + frozen / Standard | hot + warm + frozen, 3 zones / Standard multi-zone |
| Backups / KYC retention | 3 d / 1 d (governance) | 7 d / 30 d | 30 d / 7 years (compliance, locked) |
| Deletion protection | off | on | on |
| Monthly cloud budget (`monthly_budget`, alerts 50/80/100% actual + 100% forecast) | $1,000 | $1,500 | $6,500 |
| Temporal Cloud namespace retention | 3 d | 14 d | 30 d |

Levers built in (S182, IaC side):

- **Arm everywhere**: Graviton (AWS), Axion C4A (GCP), Cobalt 100 (Azure); images are multi-arch.
- **Spot pools** on every cloud with the same taint `afrotransact.com/spot=true:NoSchedule`; stateless
  services tolerate it in dev (see `values-dev.yaml`), the pool scales to zero. Karpenter (AWS) and GKE's
  optimize-utilization profile bin-pack; AKS node autoprovisioning can be enabled in `modules/azure/cluster`.
- **Scale to zero in dev**: Aurora auto-pause, Confluent Basic, spot pool min 0; preview namespaces (label
  `afrotransact.com/preview=true`) scale to zero after 30 idle minutes and outside working hours via the
  `preview-idle-scaler` CronJob (`infra/k8s/platform/previews`); web previews are Workers versions (per request).
- **Budgets and alerts** (`modules/<cloud>/budget`): AWS Budgets (+ SNS topic), GCP Cloud Billing budget (+ email
  channels), Azure consumption budget on the environment's resource group. Amounts per env in `sizing.tf`;
  recipients `budget_notification_emails`; what to do when one fires: `docs/runbooks/cost.md`.
- **No NAT for cloud APIs**: S3 gateway + interface endpoints (AWS), Private Google Access (GCP), private
  endpoints (Azure); NAT only carries third-party SaaS traffic and is single in dev/uat.
- **Storage tiering**: lifecycle to IA/Nearline/Cool then Glacier IR/Coldline/Archive; exports expire after
  30 days; R2 (zero egress) for public media.
- **Commitments (prod)**: cover ~60% of the on-demand arm64 floor and the Postgres baseline with Compute
  Savings Plans + RDS reserved capacity (AWS), committed-use discounts (GCP), or Azure savings plan +
  reserved capacity; buy after 4-6 weeks of steady prod metrics. Spot and on-demand above the baseline.
- **Search**: ILM hot-warm-frozen (`modules/shared/elastic/ilm`).
- **Sizing guard**: `tools/ci/check_env_config.py` (CI `env-urls.yml`) fails a PR that gives dev more than one
  replica or an HPA, drops the spot toleration in dev/uat, adds it in prod, or leaves requests/limits unset.

## 5. Cost comparison (launch scale)

Planning estimates for ≈50k monthly shoppers and 600 sellers (architecture §22); validate with each
provider's calculator and committed-use quotes.

| Layer | AWS | Google Cloud | Azure | Est. / month | Cost levers (where in this repo) |
|---|---|---|---|---|---|
| Kubernetes compute | EKS + Karpenter, Graviton, spot | GKE, Axion C4A, spot pool | AKS, Cobalt Arm, spot pool | $1.5k-3k | Arm, spot, bin-packing, previews to zero (`cluster`, `sizing.tf`) |
| PostgreSQL | Aurora PostgreSQL 17 Serverless v2 | Cloud SQL PostgreSQL 17 | Azure Database for PostgreSQL Flexible 17 | $1k-2k | One server per env, auto-pause in dev, replicas only where measured, reserved capacity (`postgres`) |
| Search | Elastic Cloud on AWS | Elastic Cloud on GCP | Elastic Cloud on Azure | $0.6k-1.5k | Same vendor everywhere; hot-warm-frozen ILM (`shared/elastic`) |
| Events | Confluent Cloud | Confluent Cloud | Confluent Cloud | $0.3k-0.8k | Basic in dev, Standard after; identical Kafka on every cloud (`shared/confluent`) |
| Cache | ElastiCache (Valkey) | Memorystore (Valkey) | Azure Cache for Redis | $0.15k-0.4k | Size to working set (`cache`) |
| Workflows | Temporal Cloud | Temporal Cloud | Temporal Cloud | $0.2k-0.5k | Action-efficient workflow design |
| Object storage | S3 | GCS | Blob Storage | $0.1k-0.3k | Lifecycle tiers, export expiry (`bucket`) |
| Edge (all clouds) | Cloudflare | Cloudflare | Cloudflare | $0.2k-0.5k | DNS, CDN, WAF, Turnstile, R2 for media (`infra/cloudflare`) |
| Web front-ends | Cloudflare Workers | Cloudflare Workers | Cloudflare Workers | $0.05k-0.3k | Vercel as fallback (`_web-app.yml`) |
| Registry & secrets | ECR, Secrets Manager | Artifact Registry, Secret Manager | ACR, Key Vault | $0.05k-0.2k | ESO abstracts all three (`registry`, `secrets`) |
| Network egress / NAT | NAT GW + endpoints | Cloud NAT | NAT Gateway | $0.1k-0.4k | Private endpoints for cloud APIs, single NAT outside prod (`network`) |
| Observability | Grafana Cloud | Grafana Cloud | Grafana Cloud | $0.4k-1k | OTel tail sampling, 14-day hot retention |
| Messaging | Resend + SendGrid, Twilio | same | same | $0.3k-1k | Email first, SMS for OTP/urgent only |
| AI | OpenRouter | OpenRouter | OpenRouter | $0.5k-1.5k | Model routing, caching, per-feature budgets |
| **Total (launch)** | | | | **$6k-11k** | Similar on all three at this scale; startup credits and committed-use quotes decide year one. Target ≤ 3% of GMV. |

## 6. Guard rails

- `infra/policy/tofu` (Conftest, run on every plan in CI): no public buckets, encryption at rest (and
  TLS for data services), required `env`/`owner`/`cost-center` tags, no `0.0.0.0/0` ingress except load
  balancers (tag `purpose = load-balancer`). Unit tests: `conftest verify --policy infra/policy/tofu`.
- `scripts/sync-interface.sh --check`: module inputs/outputs identical across clouds; env roots identical.
- `tofu fmt -check -recursive`, `tofu validate` for every module and every env x cloud, `tflint` (modules,
  bootstrap and Cloudflare; tflint cannot parse provider `for_each`, so env roots are covered by validate).
