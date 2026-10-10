# Module contract: one interface, three clouds

`modules/aws`, `modules/gcp` and `modules/azure` each contain the same nine modules. For every module the
**inputs are byte-identical** (`variables.tf` and `context.tf` are copied from `_interface/<module>/` and
`context.tf.tmpl`) and the **output names are identical** (values are cloud specific).
`scripts/sync-interface.sh --check` enforces both in CI (`terraform.yml`), so an environment can switch
cloud by changing the module `source` (done for you in `envs/*/main.tf` via `var.cloud`).

```
modules/
  context.tf.tmpl          shared `context` input (copied into every cloud module)
  _interface/<module>/     canonical variables.tf per module type
  aws|gcp|azure/<module>/  main.tf + outputs.tf per cloud (variables.tf/context.tf are copies)
  shared/elastic           Elasticsearch 9 on Elastic Cloud (elastic/ec) - same on every cloud
  shared/confluent         Kafka on Confluent Cloud (confluentinc/confluent) - same on every cloud
```

## Common input: `context`

| Field | Meaning |
|---|---|
| `name` | resource prefix, e.g. `afrotransact-dev` |
| `environment` | `dev` \| `uat` \| `prod` |
| `region` | cloud region (`us-east-1`, `us-east4`, `eastus` ...) |
| `scope` | GCP project id \| Azure resource group \| unused on AWS |
| `tags` | must contain `env`, `owner`, `cost-center` (validated here and by `infra/policy`) |
| `admin_cidrs` | CIDRs allowed to reach management endpoints (Kubernetes API, Key Vault) |
| `deletion_protection` | protect stateful resources (prod) |

Tags become AWS tags (+ provider `default_tags`), GCP labels (lower-cased; + provider `default_labels`) and
Azure tags. Every module adds `service` (`platform` or the service name) and `module`.

## Modules

| Module | Inputs (besides `context`) | Outputs |
|---|---|---|
| `network` | `cidr_block`, `zone_count` (1-3), `egress` (`none`\|`single`\|`per-zone`) | `network_id`, `cluster_subnet_ids`, `data_subnet_ids`, `network` (handle for other modules; includes `egress_ips`) |
| `cluster` | `network`, `kubernetes_version`, `node_size` (`small`\|`medium`\|`large`), `min_nodes`, `max_nodes`, `spot_enabled`, `spot_max_nodes`, `private_endpoint`, `admin_principals` | `cluster_name`, `cluster_endpoint`, `cluster_ca_certificate`, `oidc_issuer_url`, `workload_identity` (handle), `node_identity`, `kubeconfig_command` |
| `postgres` | `network`, `engine_version` (`17`), `size`, `high_availability`, `storage_gb`, `backup_retention_days`, `databases`, `secret_store` | `endpoint`, `reader_endpoint`, `port`, `admin_username`, `admin_secret_ref`, `databases`, `server_id` |
| `cache` | `network`, `engine_version` (`8`), `size`, `high_availability` | `host`, `port`, `tls_enabled`, `auth_token` (sensitive), `cache_id` |
| `bucket` | `network`, `buckets` (map: `versioning`, `object_lock`, `retention_days`, `archive_after_days`, `expire_after_days`, `cors_origins`) | `buckets` (key → `{name,id,url}`), `bucket_names`, `endpoint` |
| `secrets` | `prefix`, `services`, `initial_values` (sensitive) | `store` (handle), `secret_names`, `cluster_secret_store` (ESO provider spec) |
| `registry` | `repositories`, `immutable_tags`, `keep_last`, `reader_principals` | `registry_url`, `repository_urls`, `registry` (handle) |
| `identity` | `cluster` (= `cluster.workload_identity`), `services` (map: `namespace`, `service_account`, `buckets`, `secrets`), `buckets`, `secret_store` | `identities` (svc → annotation value), `helm_values` (svc → `{cloud, workloadIdentity}`) |
| `github-oidc` | `github_repository`, `subjects`, `access` (`deploy`\|`plan`\|`apply`), `create_provider`, `provider_ref`, `registry` | `login` (inputs for the login action), `principal`, `provider_ref` |

A cloud may ignore an input that has no meaning there (documented in the module header), e.g. `storage_gb`
on Aurora (auto-grows) or `secret_store` on AWS postgres (RDS manages the admin secret itself).

## What each cloud builds

| Module | AWS | GCP | Azure |
|---|---|---|---|
| network | VPC, private cluster/data subnets, tiny public subnets (LB/NAT), S3 gateway + 11 interface endpoints (ECR, STS, EC2, EKS, Secrets Manager, KMS, logs, SQS, ELB), NAT optional | custom VPC, cluster subnet (pods/services secondary ranges), PSC data subnet, Private Google Access, Private Service Access (Cloud SQL), PSC policy (Memorystore), Cloud NAT optional | VNet, AKS subnet (CNI Overlay), delegated Postgres subnet, private-endpoint subnet, NSGs, private DNS zones, NAT gateway optional |
| cluster | EKS (API auth mode, KMS secrets, IRSA OIDC), Graviton on-demand group + Graviton **spot** group tainted `afrotransact.com/spot=true:NoSchedule`, Karpenter prerequisites (node role + access entry, interruption queue + EventBridge rules, controller IRSA role, discovery tags) | GKE Standard regional, Workload Identity, Dataplane V2, private nodes, KMS secrets, small amd64 `system` pool + Axion C4A arm64 `general` + Axion **spot** pool tainted the same way | AKS, Cobalt 100 arm64 (Dpsv6) system + general pools + **spot** pool tainted the same way, CNI Overlay + Cilium, OIDC issuer + workload identity, Entra RBAC, VPA/KEDA |
| postgres | Aurora PostgreSQL 17, Serverless v2 (dev min 0 ACU = auto-pause), KMS, `rds.force_ssl`, IAM auth, RDS-managed admin secret | Cloud SQL PostgreSQL 17 Enterprise, private IP, `ENCRYPTED_ONLY`, PITR, IAM auth, REGIONAL when HA, admin in Secret Manager | Flexible Server 17, VNet-integrated, zone-redundant HA, geo backups in prod, Entra auth, admin in Key Vault, delete lock |
| cache | ElastiCache Valkey 8 (Graviton), TLS, KMS, AUTH token | Memorystore for Valkey 8, PSC, TLS | Azure Cache for Redis 6 (Premium in uat/prod), TLS only, private endpoint |
| bucket | S3 + SSE-KMS, public access block, TLS-only policy, **Object Lock** (COMPLIANCE in prod) | GCS, public access prevention enforced, UBLA, **locked retention policy** in prod | StorageV2 (no shared keys, no public), containers, **immutability policy** (locked in prod), private endpoint, delete lock |
| secrets | Secrets Manager `afrotransact/<env>/<svc>` (KMS) | Secret Manager `afrotransact-<env>-<svc>` | Key Vault (RBAC, purge protection) `afrotransact-<env>-<svc>` |
| registry | ECR per service (immutable, scan on push, KMS, lifecycle) | Artifact Registry `afrotransact` (immutable tags, cleanup policies) | ACR (Premium in prod), AcrPull for kubelet, purge task |
| identity | IRSA role per service | GSA per service + Workload Identity binding | user-assigned identity + federated credential per service |
| github-oidc | IAM OIDC provider + role (StringLike on `sub`) | WIF pool/provider (repo condition) + SA impersonation per exact `sub` | user-assigned identity + federated credential per `sub` (no app registration) |

Encryption at rest is on for every data store on every cloud; deletion protection follows
`context.deletion_protection` (Aurora/Cloud SQL/Memorystore/EKS/GKE flags, Azure `CanNotDelete` locks).

## Secrets and External Secrets Operator (S183, cloud side)

- One secret per service holding a JSON document; ESO's `ClusterSecretStore` **`platform-secrets`** uses the
  `cluster_secret_store` output as its `spec.provider` and authenticates with the `external-secrets`
  workload identity (the only identity with secret read access).
- Names: AWS `afrotransact/<env>/<svc>`; GCP and Azure `afrotransact-<env>-<svc>` because Secret Manager
  ids and Key Vault names cannot contain `/`. **The at-service Helm library currently hard-codes the
  `/` form** (`printf "afrotransact/%s/%s"` in `_objects.tpl`); on GCP/Azure it needs a
  `secrets.keySeparator` value (`-`) - see the change request in `docs/engineering/ci-cd.md`.
- Terraform seeds non-secret values (`APP_ENV`, `DB_NAME`, `DB_USER`, Kafka bootstrap + per-service
  Confluent API key, schema registry, `ES_URLS`) once; later edits happen in the store (Terraform ignores
  value drift). Passwords never live in git.

## Databases: one server, one database per service

Each environment runs **one** managed PostgreSQL 17 server; every service owns its own database (PORTS.md).
GCP and Azure create the databases natively from `databases`; Aurora cannot create databases through
its API, so on every cloud `scripts/postgres-bootstrap.sh` (run as an Argo CD PreSync Job with the admin
secret, or from a bastion) idempotently creates missing databases, a `<db>_app` login role that owns its
database, revokes `PUBLIC` connect, enables PostGIS in `geo`, and prints the per-service `DB_URL` /
`DB_USER` / `DB_PASSWORD` to merge into the service secrets.

## Elastic Cloud and Confluent Cloud (`modules/shared`)

Same module whatever `cloud` is; the deployment/cluster is created in the same cloud and region.
- `elastic`: Elasticsearch 9 deployment, hot (autoscaled) + warm + frozen tiers by profile, IP traffic
  filter on the cluster NAT IPs, ILM policies in `shared/elastic/ilm/*.json` applied by
  `scripts/elastic-ilm.sh` (which also mints scoped API keys for `search` and `indexer`).
- `confluent`: environment with Stream Governance (schema registry), Basic (dev) / Standard cluster,
  `at.<aggregate>.events` topics, one service account + API key per service, ACLs: WRITE on produced
  topics, READ on consumed topics, READ on consumer groups `<service>.*`.
