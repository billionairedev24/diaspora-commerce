# First environment: bootstrap, plan and apply per cloud

**Backlog:** S249 (first real plan and apply per cloud) · **Owner:** platform · **Applies to:** a new environment
(`dev` first, then `uat`, then `prod`) on AWS, Google Cloud or Azure

Everything below is code in this repository; this page is the order in which a human with admin rights runs it the
first time. After the first apply, CI takes over: `tofu.yml` plans every PR and applies dev on merge (uat/prod on
approval), the service pipelines deploy images through GitOps, and Argo CD syncs the cluster.

Placeholders: `ENV` is `dev`, `uat` or `prod`; `BASE` is `dev.afrotransact.com`, `uat.afrotransact.com` or
`afrotransact.com`; `REPO` is `billionairedev24/diaspora-commerce`.

## 0. Before you start

| You need | For |
|---|---|
| Cloud admin on the target account / project / subscription (prefer one per environment; prod always separate) | state bucket, first apply |
| GitHub admin on `REPO` | environments, variables, secrets |
| Cloudflare account admin, zone `afrotransact.com` on the account | API token, Pages, Tunnel, DNS |
| Elastic Cloud, Confluent Cloud and Temporal Cloud organisations | their API keys (OpenTofu providers) |
| Stripe, OpenRouter, Resend (+ SendGrid, Twilio), Google Maps Platform, Expo, carrier developer accounts | service secrets (step 4) |
| Tools: `tofu` 1.9+, `kubectl`, `helm`, `jq`, `gh`, `cosign`, the cloud CLI (`aws` / `gcloud` / `az`), `wrangler` (`npx wrangler`), optionally `tcld` | |

Pick the cloud per environment with one variable (`cloud` in `infra/tofu/envs/ENV/terraform.tfvars`); nothing
else in the repository changes with it.

## 1. State bucket (once per account / project / subscription)

```bash
# AWS
aws sso login
cd infra/tofu/bootstrap/aws   && tofu init && tofu apply -var region=us-east-1
# Google Cloud
gcloud auth application-default login
cd infra/tofu/bootstrap/gcp   && tofu init && tofu apply -var project_id=afrotransact-ENV -var region=us-east4
# Azure (allowed_ips = your egress IP and, later, CI runner ranges; everything else is denied)
az login
cd infra/tofu/bootstrap/azure && tofu init && tofu apply -var subscription_id=SUB_ID -var location=eastus \
  -var 'allowed_ips=["203.0.113.10"]'
```

Then in `infra/tofu/envs/ENV`:

```bash
cp terraform.tfvars.example terraform.tfvars          # cloud, region, cidr, web_domain, emails, ... (commit it)
cp backend.<cloud>.hcl.example backend.hcl            # bucket / storage account from the bootstrap output
printf 'terraform {\n  backend "s3" {}\n}\n' > backend.tf   # "gcs" or "azurerm" for the other clouds (do not commit)
tofu init -backend-config=backend.hcl
```

In `terraform.tfvars` also set `budget_notification_emails`, on GCP `gcp_billing_account`, and for Temporal mTLS
`temporal_accepted_client_ca` (step 7). Keep `create_github_oidc_provider = true` only in the first environment of an
AWS account / GCP project.

## 2. GitHub OIDC (no long-lived cloud keys)

The first `tofu apply` (step 5) creates three identities per environment (`modules/<cloud>/github-oidc`):
`deploy` (registry push, subject `repo:REPO:environment:ENV`), `plan` (read-only, `repo:REPO:pull_request`) and `apply`
(admin, `repo:REPO:environment:ENV-infra`). Nothing to do before the apply; read the values afterwards:

```bash
tofu output -json github_oidc     # login inputs for deploy / plan / apply
tofu output -raw registry_url     # REGISTRY_URL
```

## 3. GitHub environments and variables

Create the environments (Settings -> Environments): `ENV` (prod: required reviewers, branch `main` only) and
`ENV-infra` (prod-infra: required reviewers). Then set the variables with `gh`:

```bash
gh variable set CLOUD        --env ENV --body aws           # aws | gcp | azure | ovh
gh variable set REGISTRY_URL --env ENV --body "$(tofu output -raw registry_url)"
# aws
gh variable set AWS_DEPLOY_ROLE_ARN --env ENV --body "$(tofu output -json github_oidc | jq -r '.deploy["aws-role-arn"]')"
gh variable set AWS_REGION          --env ENV --body us-east-1
# gcp
gh variable set GCP_WORKLOAD_IDENTITY_PROVIDER --env ENV --body "$(tofu output -json github_oidc | jq -r '.deploy["gcp-workload-identity-provider"]')"
gh variable set GCP_DEPLOY_SERVICE_ACCOUNT     --env ENV --body "$(tofu output -json github_oidc | jq -r '.deploy["gcp-service-account"]')"
gh variable set GCP_REGISTRY_HOST              --env ENV --body us-east4-docker.pkg.dev
# azure
gh variable set AZURE_DEPLOY_CLIENT_ID --env ENV --body "$(tofu output -json github_oidc | jq -r '.deploy["azure-client-id"]')"
gh variable set AZURE_TENANT_ID        --env ENV --body TENANT_ID
gh variable set AZURE_SUBSCRIPTION_ID  --env ENV --body SUB_ID
```

Repository variables (all environments):

| Variable | Value |
|---|---|
| `TF_TARGETS` | JSON per environment: cloud, region, state, plan and apply login inputs (example in `docs/engineering/ci-cd.md`). Until it is set, `tofu.yml` runs only the static checks |
| `GITOPS_BRANCH` | `main` (default) or a dedicated `gitops` branch that Argo CD tracks |
| `DEV_BASE_DOMAIN` | `dev.afrotransact.com` (ZAP baseline, carrier smoke `target: dev`) |
| `DOCS_PAGES_PROJECT` | `afrotransact-docs` |
| `VERCEL_FALLBACK` | `true` only if web apps must also deploy to Vercel |

GitOps writer: create a GitHub App with `contents: write` on `REPO`, allowed to bypass branch protection for the
values bump, and store `GITOPS_APP_ID` / `GITOPS_APP_PRIVATE_KEY` as repository secrets (otherwise `GITHUB_TOKEN` is used
and `main` must accept its pushes, or use a `gitops` branch).

## 4. Secrets

### 4.1 GitHub secrets (repository unless noted)

| Secret | Used by | How to get it |
|---|---|---|
| `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID` | web deploys (Workers), docs + Storybook (Pages), `tofu.yml` cloudflare jobs | Cloudflare API token with Zone: Settings, DNS, WAF, SSL, Workers Routes edit; Account: Workers Scripts, Pages, Turnstile, R2, Tunnel, Lists edit |
| `EC_API_KEY` | OpenTofu (Elastic Cloud) | Elastic Cloud -> Organization -> API keys |
| `CONFLUENT_CLOUD_API_KEY`, `CONFLUENT_CLOUD_API_SECRET` | OpenTofu (Confluent) | Cloud API key of a service account with OrganizationAdmin |
| `TEMPORAL_CLOUD_API_KEY` | OpenTofu (Temporal Cloud namespaces) | Temporal Cloud -> Settings -> API keys (Account Owner or a service account with namespace admin) |
| `EXPO_TOKEN` | `mobile.yml` (EAS build / update) | expo.dev -> Access tokens (robot user) |
| `NVD_API_KEY` | `security.yml` OWASP Dependency-Check (weekly) | nvd.nist.gov API key request |
| `GITOPS_APP_ID`, `GITOPS_APP_PRIVATE_KEY` | `_deploy.yml` GitOps bump (optional) | GitHub App above |
| `VERCEL_TOKEN`, `VERCEL_ORG_ID`, `VERCEL_PROJECT_ID` | Vercel fallback (optional) | Vercel account settings |
| `SMOKE_CLIENT_ID`, `SMOKE_CLIENT_SECRET` (env `dev`) | carrier smoke `target: dev` | identity OAuth client with `shipping:rate shipping:label shipping:void` |
| carrier sandbox keys (env `carrier-sandbox`) | `carrier-sandboxes.yml` | see [carrier-sandboxes.md](carrier-sandboxes.md) |

### 4.2 Service secrets in the cloud secret store

OpenTofu creates one secret per service (`afrotransact/ENV/<service>` on AWS, `afrotransact-ENV-<service>` on GCP and
Azure) and seeds the non-secret values once: `APP_ENV`, `DB_NAME`, `DB_USER`, `KAFKA_BOOTSTRAP` + per-service Confluent
API key, `SCHEMA_REGISTRY_URL`, `ES_URLS` (search, indexer) and `TEMPORAL_TARGET` / `TEMPORAL_NAMESPACE` (orders).
External Secrets Operator turns each into the Kubernetes Secret `<service>-secrets`. Add the rest with the merge helper,
which never overwrites keys you do not name:

```bash
cd infra/tofu
scripts/secret-set.sh <cloud> ENV payments STRIPE_SECRET_KEY=- STRIPE_WEBHOOK_SECRET=-     # prompts, no echo
scripts/secret-set.sh <cloud> ENV gateway-bff STRIPE_PUBLISHABLE_KEY=-                       # same Stripe account
scripts/secret-set.sh <cloud> ENV identity SIGNING_KEY_PEM=@signing-key.pem
scripts/secret-set.sh <cloud> ENV identity --list                                          # what is there
```

Database passwords: run `scripts/postgres-bootstrap.sh` after the apply (per-service roles) and merge its
`DB_URL` / `DB_USER` / `DB_PASSWORD` output. Elasticsearch: `scripts/elastic-ilm.sh` prints `ES_API_KEY` for search and
indexer. Generate keys with `openssl rand -base64 32` (32-byte keys) unless noted.

The keys each chart expects (from the `Secret keys expected` comment in every `deploy/helm/values.yaml`):

| Service | Keys | Source |
|---|---|---|
| ai-gateway | `DB_URL`, `DB_USER`, `DB_PASSWORD`, `KAFKA_BOOTSTRAP`, `OPENROUTER_API_KEY` | OpenRouter -> Keys (one key per environment, with a credit limit) |
| authz-sync | `KAFKA_BOOTSTRAP`, `OPENFGA_API_TOKEN` | one of OpenFGA's pre-shared keys (`afrotransact/openfga` `preshared-keys`) |
| bookings | `DB_*`, `KAFKA_BOOTSTRAP`, `BOOKINGS_CLIENT_SECRET` | identity client secret (same value in identity's secret) |
| carrier-gateway | `DB_URL`, `REDIS_URL`, `USPS_CLIENT_ID`, `USPS_CLIENT_SECRET`, `USPS_PAYMENT_TOKEN`, `UPS_CLIENT_ID`, `UPS_CLIENT_SECRET`, `UPS_ACCOUNT`, `UPS_WEBHOOK_CREDENTIAL`, `FEDEX_CLIENT_ID`, `FEDEX_CLIENT_SECRET`, `FEDEX_ACCOUNT`, `FEDEX_WEBHOOK_SECRET`, `DHL_USERNAME`, `DHL_PASSWORD`, `DHL_ACCOUNT`, `DHL_WEBHOOK_SECRET`, `S3_ACCESS_KEY_ID`, `S3_SECRET_ACCESS_KEY` (empty = workload identity) | carrier portals (sandbox keys in dev/uat): [carrier-sandboxes.md](carrier-sandboxes.md) |
| cart | `DB_URL`, `REDIS_URL` | `tofu output cache` + postgres bootstrap |
| catalog | `DB_*`, `KAFKA_BOOTSTRAP`, `GS1_API_KEY` (optional), `FGA_API_TOKEN` | GS1 US, OpenFGA pre-shared key |
| content | `DB_*`, `KAFKA_BOOTSTRAP`, `NEWSLETTER_TOKEN_KEY` | generated |
| fulfilment | `DB_*`, `KAFKA_BOOTSTRAP`, `FULFILMENT_CLIENT_SECRET` | identity client secret |
| gateway-bff | `REDIS_URL`, `OIDC_CLIENT_SECRET`, `SESSION_SIGNING_KEY`, `STRIPE_PUBLISHABLE_KEY` | identity client `gateway-bff`; generated; Stripe publishable key of the same account as payments' `STRIPE_SECRET_KEY` (`pk_test_…` in dev/uat, `pk_live_…` in prod; the BFF refuses to start without it) |
| geo | `DB_URL`, `GOOGLE_PLACES_API_KEY`, `SMARTY_AUTH_ID`, `SMARTY_AUTH_TOKEN`, `GOOGLE_MAPS_API_KEY` (optional, Routes API), `OSRM_URL` (optional, self-hosted OSRM) | Google Maps Platform (Places + Routes, key restricted by API and to the cluster egress IPs from `tofu output egress_ips`); Smarty |
| identity | `DB_*`, `KAFKA_BOOTSTRAP`, `SIGNING_KEY_PEM`, `IDENTITY_DATA_KEY` (+ optional `IDENTITY_DATA_KEY_ID`, `IDENTITY_DATA_KEYS_PREVIOUS`), `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `APPLE_CLIENT_ID`, `APPLE_CLIENT_SECRET`, `FACEBOOK_CLIENT_ID`, `FACEBOOK_CLIENT_SECRET`, `BFF_CLIENT_SECRET`, `SELLER_SERVICE_CLIENT_SECRET`, `ORDERS_CLIENT_SECRET`, `BOOKINGS_CLIENT_SECRET`, `FULFILMENT_CLIENT_SECRET`, `PAYMENTS_CLIENT_SECRET`, `NOTIFICATIONS_CLIENT_SECRET`, `SEARCH_CLIENT_SECRET`, `RETAIL_CLIENT_SECRET`, `GATEWAY_BFF_CLIENT_SECRET`, `OFFERS_CLIENT_SECRET`, `INDEXER_CLIENT_SECRET`, `TRUSTOPS_CLIENT_SECRET`, `TAX_RETURNS_CLIENT_SECRET`, `BOOTSTRAP_ADMIN_EMAIL`, `BOOTSTRAP_ADMIN_PASSWORD`, `BOOTSTRAP_ADMIN_PHONE` | `openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072` for the signing key (RSA only: identity signs RS256 and does not start with an EC key; after the first start the key lives in identity's database and is rotated in the staff console); social login consoles; every `*_CLIENT_SECRET` is generated here and copied to the calling service |
| indexer | `ES_URLS`, `ES_API_KEY`, `KAFKA_BOOTSTRAP`, `INDEXER_CLIENT_SECRET` | `elastic-ilm.sh`; identity client |
| inventory | `DB_*`, `KAFKA_BOOTSTRAP`, `INVENTORY_CLIENT_SECRET` | identity client |
| mcp-server | `KAFKA_BOOTSTRAP`, `REDIS_URL` | OpenTofu seed, `tofu output cache` |
| media | `DB_URL`, `UPLOADTHING_API_KEY`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, `AZURE_STORAGE_KEY` (azure only, else empty) | UploadThing; Cloudflare R2 API token scoped to `afrotransact-media-ENV` |
| notifications | `DB_URL`, `RESEND_API_KEY`, `SENDGRID_API_KEY`, `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, `VONAGE_API_KEY`, `VONAGE_API_SECRET`, `FCM_SERVICE_ACCOUNT_JSON`, `APNS_KEY_P8`, `WHATSAPP_TOKEN`, `RESEND_WEBHOOK_SECRET`, `SENDGRID_WEBHOOK_PUBLIC_KEY`, `UNSUBSCRIBE_SIGNING_KEY`, `NOTIFICATIONS_CLIENT_SECRET` | Resend (domain `mail.BASE` verified), SendGrid, Twilio, Vonage, Firebase, Apple, Meta; generated |
| offers | `DB_*`, `KAFKA_BOOTSTRAP`, `OFFERS_CLIENT_SECRET`, `FGA_API_TOKEN` | identity client, OpenFGA key |
| orders | `DB_*`, `KAFKA_BOOTSTRAP`, `ORDERS_CLIENT_SECRET`, `TEMPORAL_TLS_CERT`, `TEMPORAL_TLS_KEY`, `HANDOVER_CODE_KEY`, `FGA_API_TOKEN` | identity client; Temporal client certificate (step 7); generated; OpenFGA key |
| payments | `DB_*`, `KAFKA_BOOTSTRAP`, `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET`, `FGA_API_TOKEN` | Stripe (test mode keys outside prod; webhook endpoint `https://api.BASE/payments/v1/webhooks/stripe`) |
| promise | `REDIS_URL` | `tofu output cache` |
| retail | `DB_*`, `KAFKA_BOOTSTRAP`, `RETAIL_CLIENT_SECRET` | identity client |
| search | `ES_URLS`, `ES_API_KEY`, `KAFKA_BOOTSTRAP`, `ADS_TOKEN_KEY` (32+ chars), `SEARCH_CLIENT_SECRET` | `elastic-ilm.sh`; generated; identity client |
| seller | `DB_*`, `KAFKA_BOOTSTRAP`, `STRIPE_SECRET_KEY`, `STRIPE_CONNECT_WEBHOOK_SECRET`, `STRIPE_BILLING_WEBHOOK_SECRET`, `FIELD_ENCRYPTION_KEY` | Stripe Connect + Billing webhook endpoints; generated |
| storefront | `DB_URL`, `CLOUDFLARE_API_TOKEN` | Cloudflare token with Zone: Cache Purge |
| tax-returns | `DB_*`, `KAFKA_BOOTSTRAP`, `TAX_RETURNS_CLIENT_SECRET` | identity client |
| trust-ops | `DB_*`, `KAFKA_BOOTSTRAP`, `TRUSTOPS_CLIENT_SECRET` | identity client |

`DB_*` = `DB_URL`, `DB_USER`, `DB_PASSWORD`.

### 4.3 Platform secrets (read by `infra/k8s` ExternalSecrets)

| Key in the secret store | Properties | Used by |
|---|---|---|
| `afrotransact/ENV/cloudflared` | `token` (`cloudflared tunnel token $(tofu -chdir=infra/cloudflare output -raw tunnel_id)`) | cloudflared (edge overlay) |
| `afrotransact/platform/cloudflare` | `api-token` (Zone: DNS edit) | cert-manager DNS-01 |
| `afrotransact/observability` | `pagerduty-routing-key`, `opsgenie-api-key`, `slack-webhook-url`, `admin-user`, `admin-password`, `DATA_SOURCE_NAME` | Alertmanager, Grafana, postgres-exporter |
| `afrotransact/openfga` | `datastore-uri`, `preshared-keys` | OpenFGA |
| `afrotransact/platform/elasticsearch-monitoring` | `username`, `password` | Elasticsearch exporter |

Known gap: these platform keys are outside `afrotransact/ENV/`, so on AWS the External Secrets identity (scoped to
`afrotransact/ENV/*`) cannot read them, and on GCP / Azure their names contain `/`, which Secret Manager and Key Vault
reject. Until the platform ExternalSecrets get cloud-specific key names (a kustomize patch per `clouds/<cloud>`),
create them under the environment prefix and patch the `remoteRef.key` in the environment overlay, or widen the ESO
policy in `modules/<cloud>/identity`.

### 4.4 Spend alerts in the SaaS consoles

OpenTofu budgets cover the cloud account only ([cost.md](cost.md)). Set a monthly spend alert in Elastic Cloud,
Confluent Cloud, Temporal Cloud, OpenRouter (credit limit per key) and Cloudflare when the accounts are created.

## 5. tofu plan and apply

```bash
cd infra/tofu/envs/ENV
export EC_API_KEY=... CONFLUENT_CLOUD_API_KEY=... CONFLUENT_CLOUD_API_SECRET=... TEMPORAL_CLOUD_API_KEY=...
tofu plan -out plan.out
tofu show -json plan.out > plan.json
conftest test plan.json --policy ../../../policy/tofu   # must pass: public buckets, encryption, tags, ingress
tofu apply plan.out
```

Then, in order:

1. `scripts/postgres-bootstrap.sh` (admin secret from `tofu output postgres`) and merge the per-service DB values.
2. `ES_URL=$(tofu output -raw elasticsearch_url) ES_PASSWORD=$(tofu output -raw elastic_password) ../../scripts/elastic-ilm.sh`
   (ILM hot-warm-frozen policies + `ES_API_KEY` for search and indexer).
3. Fill `infra/argocd/envs/ENV/platform-values.yaml`: uncomment the block for the cloud, set `image.registry` to
   `registry_url`, `workloadIdentity` from `tofu output -json platform_values`, `secrets.keySeparator` (`-` on GCP and
   Azure). Put the `external-secrets` identity annotation from the same output into
   `infra/k8s/platform/envs/ENV/external-secrets.yaml` and the ClusterSecretStore provider from
   `tofu output -json cluster_secret_store` into `infra/k8s/platform/secret-stores/<cloud>`. Commit.
4. Cloudflare edge for the environment:

```bash
cd infra/cloudflare
export CLOUDFLARE_API_TOKEN=...
tofu init -backend-config=backend.hcl -backend-config="key=cloudflare/ENV.tfstate"
tofu apply -var-file=envs/ENV.tfvars      # DNS, Workers routes, Tunnel, Turnstile, R2, Pages domain (step 8)
tofu output -json turnstile_secrets      # TURNSTILE_SECRET_* into identity / orders secrets
```

5. Set `TF_TARGETS` (step 3) so CI plans and applies from now on. Check the budget exists: `tofu output budget`.

## 6. Argo CD bootstrap

```bash
$(tofu -chdir=infra/tofu/envs/ENV output -json cluster | jq -r .kubeconfig_command)
helm upgrade --install argocd oci://ghcr.io/argoproj/argo-helm/argo-cd -n argocd --create-namespace --version 10.10.1 \
  --set configs.params."server\.insecure"=true --wait
# Private repository: give Argo CD read access (deploy key or GitHub App) before the root app
kubectl -n argocd create secret generic repo-diaspora-commerce \
  --from-literal=type=git --from-literal=url=https://github.com/REPO.git \
  --from-literal=githubAppID=APP_ID --from-literal=githubAppInstallationID=INSTALLATION_ID \
  --from-file=githubAppPrivateKey=argocd-app.pem
kubectl -n argocd label secret repo-diaspora-commerce argocd.argoproj.io/secret-type=repository
kubectl apply -n argocd -f infra/argocd/bootstrap/root-ENV.yaml
```

The root app syncs `infra/argocd/envs/ENV`: AppProjects, then the platform charts by sync wave (cert-manager,
external-secrets, kube-prometheus-stack, Kyverno at -5; Argo Rollouts, ECK, Envoy Gateway at -4; Loki, Tempo; OTel),
then the platform manifests (namespaces, secret store, Kyverno policies, edge Gateway + cloudflared + routes,
observability content, Elasticsearch, OpenFGA, previews) and one Application per service. prod syncs manually
(`argocd.autoSync: false` in its platform-values). Watch it with `kubectl -n argocd get applications`.

Signed images: the Kyverno policy `afrotransact-signed-images` (`infra/k8s/platform/policies`) admits only images signed
by `_java-service.yml` / `_go-service.yml` on `main`. Give the Kyverno admission controller registry read access on the
cloud (workload identity annotation in `infra/k8s/platform/clouds/<cloud>/kyverno.yaml`, same pattern as
external-secrets), otherwise it cannot fetch signatures from ECR / Artifact Registry / ACR and denies every pod.

## 7. Temporal namespaces

`tofu apply` created the Temporal Cloud namespace `afrotransact-ENV` (`modules/shared/temporal`; retention 3 / 14 / 30
days; delete protection in uat/prod) and seeded `TEMPORAL_TARGET` and `TEMPORAL_NAMESPACE` into the orders secret.
orders-service connects with mTLS:

```bash
# once per environment: a client CA (keep the key offline) and a leaf certificate for the orders worker
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-384 -nodes -days 730 -subj "/CN=afrotransact-ENV-temporal-ca" \
  -keyout temporal-ca.key -out temporal-ca.pem
openssl req -newkey ec -pkeyopt ec_paramgen_curve:P-384 -nodes -subj "/CN=orders-ENV" -keyout orders.key -out orders.csr
openssl x509 -req -in orders.csr -CA temporal-ca.pem -CAkey temporal-ca.key -CAcreateserial -days 365 -out orders.pem
# trust the CA on the namespace: terraform.tfvars temporal_accepted_client_ca = "$(base64 -w0 temporal-ca.pem)", apply
infra/tofu/scripts/secret-set.sh <cloud> ENV orders TEMPORAL_TLS_CERT=@orders.pem TEMPORAL_TLS_KEY=@orders.key
tofu output temporal       # namespace id (name.account) and the mTLS endpoint
```

Without a CA the module creates the namespace with API-key auth instead (then orders needs an API key, which its
`application-ENV.yml` does not support today). Local development keeps the `afrotransact` namespace of the docker
compose Temporal.

## 8. Cloudflare Pages projects

`infra/cloudflare/pages.tf` creates the account-wide projects `afrotransact-docs` and `afrotransact-storybook`
(production branch `main`) in the run with `pages_projects_owner = true` (dev), and in every environment the custom
domain `developers.BASE` with its CNAME to `afrotransact-docs.pages.dev`. Then:

1. Run `docs.yml` and `storybook.yml` on `main` (Actions -> Run workflow) for the first production deployment.
2. Wait for the custom domain to turn active (Pages -> afrotransact-docs -> Custom domains), then open
   `https://developers.BASE`.

Workers for the web apps need no project: `_web-app.yml` creates `<app>-ENV` on the first deploy, and the routes from
`infra/cloudflare` attach them to `BASE`, `seller.BASE` and `admin.BASE`.

## 9. Smoke checks

```bash
kubectl -n argocd get applications                       # all Synced / Healthy (prod: after manual sync)
kubectl -n ingress get pods                              # envoy + cloudflared Running
curl -fsS https://api.BASE/bff/readyz                    # gateway-bff through Cloudflare Tunnel + edge route
curl -fsS https://auth.BASE/.well-known/openid-configuration | jq -r .issuer   # = https://auth.BASE
curl -fsSI https://BASE | head -1                        # storefront Worker
curl -fsSI https://developers.BASE | head -1             # docs portal on Pages
# signed images enforced: an unsigned image in a service namespace must be denied by Kyverno
kubectl -n afrotransact-ENV run unsigned --image=busybox:1.37 --restart=Never -- true   # expect: admission webhook denied
# Temporal: the orders worker polls its task queue
temporal task-queue describe --task-queue orders-checkout --namespace "$(tofu -chdir=infra/tofu/envs/ENV output -json temporal | jq -r .namespace)" \
  --address "$(tofu -chdir=infra/tofu/envs/ENV output -json temporal | jq -r .target)" --tls-cert-path orders.pem --tls-key-path orders.key
# carriers (rates only, no labels bought): Actions -> carrier-sandboxes -> target dev, rates_only
# IaC scan stays clean
trivy config --config trivy.yaml --severity CRITICAL,HIGH --exit-code 1 infra
```

Record the results in S249 (dev environment applied, Temporal namespace, docs portal host, signed images enforced,
Trivy IaC clean), then repeat from step 1 for uat and prod.

## Rollback

- OpenTofu: revert the PR and let CI apply, or `tofu apply` the previous commit. Stateful resources have deletion
  protection in uat/prod; never `tofu destroy` an environment that holds data without the migration runbook in
  `infra/tofu/README.md`.
- Argo CD: `argocd app rollback <app>` or revert the GitOps commit that bumped `values-ENV.yaml`.
- Kyverno blocking legitimate pods: set the rule's `failureAction` to `Audit` in `infra/k8s/platform/policies` (PR),
  never delete the policy in place.
