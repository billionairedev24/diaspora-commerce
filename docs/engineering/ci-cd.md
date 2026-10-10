# CI/CD: build once, sign, deploy each service on its own to any cloud

Every Java and Go service is built, tested, signed and deployed **independently**; the target cloud
(`aws | gcp | azure`) is configuration of a GitHub environment, never code.

```
PR / push touching go/services/search/**          (path filter: only go-search.yml runs)
  go-search.yml ──► _go-service.yml
                     ├ verify    golangci-lint, go vet, go test -race (unit + Testcontainers ITs), coverage
                     ├ contract  api/openapi.yaml == contracts/openapi/search.yaml, oasdiff breaking vs base
                     ├ security  gitleaks, Trivy fs (HIGH/CRITICAL), govulncheck, [CodeQL]
                     ├ helm      lint + template dev/uat/prod x none/aws/gcp/azure | kubeconform
                     └ image     buildx linux/amd64+arm64 -> ghcr.io/<repo>/search:<sha>
                                 Trivy image (CRITICAL fails) -> Syft SPDX SBOM -> cosign sign + attest (keyless)
  main only ──► _deploy.yml (environment dev)
                     ├ cosign verify (identity = this repo's _go-service.yml@refs/heads/main)
                     ├ OIDC login to vars.CLOUD (aws-actions / google-github-actions/auth / azure/login)
                     ├ cosign copy GHCR -> ECR | Artifact Registry | ACR (image + .sig + .att)
                     └ GitOps commit: go/services/search/deploy/helm/values-dev.yaml image.tag=<sha> [skip ci]
Argo CD (search-dev Application) syncs the chart ──► Deployment (dev) / Argo Rollouts canary (uat, prod):
  10% -> 50% -> 100% with the chart's Prometheus AnalysisTemplate; error-rate breach = automatic rollback
promote.yml (manual) ──► _deploy.yml (environment uat | prod; prod waits for required reviewers)
```

Java services are identical with `_java-service.yml` (Temurin 25, Maven cache, platform libs installed
first, `./mvnw -B -f services/<svc>/pom.xml verify` = Checkstyle/Spotless + unit + ArchUnit +
Testcontainers ITs + JaCoCo; image from `java/Dockerfile` with `SERVICE=<svc>`).

## Workflows

| Workflow | Trigger | What |
|---|---|---|
| `java-<svc>.yml`, `go-<svc>.yml` (25, generated) | changes under that service (+ shared libs/chart/workflow) | pipeline above; deploy to dev on main |
| `web-<app>.yml` (3, generated) | `web/apps/<app>/**`, `web/packages/**` | lint, typecheck, test, Next build, OpenNext build; PR -> Workers preview URL comment; main -> dev; dispatch -> uat/prod; optional Vercel fallback |
| `promote.yml` (generated) | manual | promote a signed image (default: the tag running in the previous env) to uat or prod |
| `_java-service.yml`, `_go-service.yml`, `_web-app.yml`, `_deploy.yml` | `workflow_call` | reusable pipelines |
| `contracts.yml` | `contracts/**` | Redocly + Spectral lint, oasdiff breaking changes for every spec, AsyncAPI validate |
| `terraform.yml` | `infra/terraform/**`, `infra/cloudflare/**`, `infra/policy/**` | fmt, interface drift, validate (all modules, all env x cloud), tflint, conftest tests; plan per env on its cloud with conftest on the plan JSON + PR comment; apply dev on main, uat/prod on dispatch |
| `security.yml` | main, weekly, manual | CodeQL (java-kotlin, go, js/ts, actions), Trivy repo SARIF (CRITICAL fails), gitleaks full history, ZAP baseline vs dev |
| `backlog.yml` | `docs/backlog/**` | CSV parses, header, unique ids, valid status/type/priority, parents are epics |
| `docs.yml` | `docs/**`, `contracts/**` | build `docs/portal` and deploy to Cloudflare Pages (skips until it exists) |
| `mobile.yml` | tags `mobile-v*`, main (`mobile/**`), manual | EAS build (production, auto-submit) on tags; EAS Update OTA to `preview` on main; dev/preview/production profiles |
| `workflows.yml` | `.github/**`, `tools/ci/**`, PORTS.md | generated workflows up to date + actionlint |

Generated workflows: `python3 tools/ci/gen_workflows.py` reads the service table in
`infra/local/PORTS.md`; add a service there and re-run. Workflows for services that do not exist yet are
harmless (their path filters never match and the `meta` job skips everything).

## Security of the pipeline

- **No long-lived cloud keys**: `id-token: write` + OIDC federation created by
  `infra/terraform/modules/<cloud>/github-oidc` - three identities per environment:
  `deploy` (registry push only; `sub = repo:<repo>:environment:<env>`), `plan` (read-only;
  `sub = repo:<repo>:pull_request`), `apply` (admin; `sub = repo:<repo>:environment:<env>-infra`).
- Images are signed keylessly (Fulcio/Rekor) and carry an SPDX SBOM attestation; deploy refuses unsigned
  images and copies signatures to the cloud registry so Kyverno can enforce "signed images from our
  registry only" in the cluster.
- Default `permissions: contents: read`; jobs elevate only what they need. Actions are pinned to major
  versions (Dependabot keeps them current; pin to SHAs if your policy requires it).

## GitHub setup (one time)

**Environments** (Settings -> Environments):

| Environment | Protection | Used by |
|---|---|---|
| `dev` | none | service deploys on main, web previews/dev, mobile dev/preview |
| `uat` | none (optionally restrict to `main`) | promote.yml, web dispatch |
| `prod` | **required reviewers** (the `production` approvers group), branch `main` only | promote.yml, web prod, mobile production |
| `dev-infra`, `uat-infra` | `uat-infra`: required reviewers optional | terraform apply |
| `prod-infra` | **required reviewers** | terraform apply prod |

**Environment variables** on `dev` / `uat` / `prod` (values from `tofu output github_oidc` and
`tofu output registry_url`; none are secrets):

| Variable | Example |
|---|---|
| `CLOUD` | `aws` \| `gcp` \| `azure` |
| `REGISTRY_URL` | `123456789012.dkr.ecr.us-east-1.amazonaws.com/afrotransact` \| `us-east4-docker.pkg.dev/<project>/afrotransact` \| `<acr>.azurecr.io/afrotransact` |
| `AWS_DEPLOY_ROLE_ARN`, `AWS_REGION` | aws: `github_oidc.deploy.role_arn`, region |
| `GCP_WORKLOAD_IDENTITY_PROVIDER`, `GCP_DEPLOY_SERVICE_ACCOUNT`, `GCP_REGISTRY_HOST` | gcp: `github_oidc.deploy.*`, `us-east4-docker.pkg.dev` |
| `AZURE_DEPLOY_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID` | azure: `github_oidc.deploy.*` |

**Repository variables**: `TF_TARGETS` (below), `GITOPS_BRANCH` (default `main`), `VERCEL_FALLBACK`
(`true` to always deploy web to Vercel too), `DEV_BASE_DOMAIN` (ZAP target), `DOCS_PAGES_PROJECT`.

**Secrets**: `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID`, `EC_API_KEY`, `CONFLUENT_CLOUD_API_KEY`,
`CONFLUENT_CLOUD_API_SECRET`, `EXPO_TOKEN`, optional `VERCEL_TOKEN`/`VERCEL_ORG_ID`/`VERCEL_PROJECT_ID`,
optional `GITOPS_APP_ID`/`GITOPS_APP_PRIVATE_KEY` (a GitHub App allowed to push the GitOps bump through
branch protection; otherwise `GITHUB_TOKEN` is used and `main` must allow it, or set `GITOPS_BRANCH` to a
dedicated `gitops` branch that Argo CD tracks).

`TF_TARGETS` (repository variable, JSON; keys of the login objects are the `cloud-login` action inputs):

```json
{
  "dev":  { "cloud": "aws", "region": "us-east-1",
            "state": { "bucket": "afrotransact-tfstate-123456789012", "region": "us-east-1" },
            "plan":  { "aws-role-arn": "arn:aws:iam::123456789012:role/afrotransact-dev-github-plan" },
            "apply": { "aws-role-arn": "arn:aws:iam::123456789012:role/afrotransact-dev-github-apply" } },
  "uat":  { "cloud": "gcp", "region": "us-east4",
            "state": { "bucket": "afrotransact-tfstate-afrotransact-uat" },
            "plan":  { "gcp-workload-identity-provider": "projects/1/locations/global/workloadIdentityPools/github/providers/github",
                       "gcp-service-account": "gh-uat-plan@afrotransact-uat.iam.gserviceaccount.com" },
            "apply": { "gcp-workload-identity-provider": "projects/1/locations/global/workloadIdentityPools/github/providers/github",
                       "gcp-service-account": "gh-uat-apply@afrotransact-uat.iam.gserviceaccount.com" } },
  "prod": { "cloud": "azure", "region": "eastus",
            "state": { "resource_group": "afrotransact-tfstate", "storage_account": "attfstateab12cd" },
            "plan":  { "azure-client-id": "…", "azure-tenant-id": "…", "azure-subscription-id": "…" },
            "apply": { "azure-client-id": "…", "azure-tenant-id": "…", "azure-subscription-id": "…" } }
}
```

## Web apps (Cloudflare Workers, Vercel fallback)

`_web-app.yml` builds with OpenNext (`opennextjs-cloudflare build`) and deploys with Wrangler: PRs upload a
version of `<app>-dev` with preview alias `pr-<n>` (URL commented on the PR); main deploys `<app>-dev`;
dispatch deploys `<app>-uat` / `<app>-prod` (prod approval). Worker names match the Workers routes in
`infra/cloudflare` (`seller.dev.afrotransact.com/*` -> `seller-center-dev`). Each app needs `wrangler.jsonc`
(or `.toml`) and the `@opennextjs/cloudflare` dev dependency (web agent). With `vercel: true` (input or
`VERCEL_FALLBACK=true`) the same app is also deployed with the Vercel CLI.

## Mobile (EAS)

`mobile.yml`: tag `mobile-v1.4.0` (both apps) or `mobile-customer-v1.4.0` -> `eas build --profile production
--auto-submit`; pushes to main touching `mobile/**` -> `eas update --channel preview` (OTA for JS-only
changes); manual runs pick app, `build|update` and `development|preview|production`. Each app's `eas.json`
must define those three profiles with matching channels.

## Change requests for shared files (owned by other agents)

1. **Helm library secret key** (`infra/helm/library/at-service/templates/_objects.tpl`): the ExternalSecret
   key is hard-coded as `afrotransact/<env>/<svc>`. GCP Secret Manager ids and Azure Key Vault names cannot
   contain `/`, so Terraform names them `afrotransact-<env>-<svc>`. Proposed: a `secrets.keySeparator`
   value (default `/`; `-` in platform-values for gcp/azure) used in
   `printf "afrotransact%s%s%s%s" sep .Values.env sep name`.
2. **GKE arm64 taint**: GKE taints Arm nodes `kubernetes.io/arch=arm64:NoSchedule`; platform-values for
   `cloud: gcp` should set `nodeSelector: {kubernetes.io/arch: arm64}` (GKE then adds the toleration).
   AKS spot nodes additionally carry `kubernetes.azure.com/scalesetpriority=spot:NoSchedule`; the dev
   values' spot toleration should include it for `cloud: azure`.
3. **infra/k8s**: `platform-secrets` ClusterSecretStore from `tofu output cluster_secret_store`; the
   `external-secrets` service account in namespace `external-secrets` annotated with
   `tofu output platform_values["external-secrets"]`; Envoy gateway must require Cloudflare's origin-pull
   client certificate (Authenticated Origin Pulls) when `origin_mode = ingress`, or run `cloudflared`
   with the tunnel token when `origin_mode = tunnel`; Kyverno `verifyImages` with the keyless identity
   `https://github.com/billionairedev24/diaspora-commerce/.github/workflows/_(java|go)-service.yml@refs/heads/main`.
4. **Argo CD**: Applications should track `GITOPS_BRANCH` and read `values-<env>.yaml` (where `_deploy.yml`
   writes `image.tag`) plus a platform-values file with `cloud`, `image.registry` (= the environment's
   `REGISTRY_URL`; the chart appends `/<service>`) and `workloadIdentity`.
