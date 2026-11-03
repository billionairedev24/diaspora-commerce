# CI/CD: build once, sign, deploy each service on its own to any cloud

Every Java and Go service is built, tested, signed and deployed **independently**; the target cloud
(`aws | gcp | azure | ovh`) is configuration of a GitHub environment, never code.

```
PR / push touching go/services/search/**          (path filter: only go-search.yml runs)
  go-search.yml ──► _go-service.yml
                     ├ verify    golangci-lint, go vet, go test -race (unit + Testcontainers ITs), coverage gate
                     ├ contract  api/openapi.yaml == contracts/openapi/search.yaml, oasdiff breaking vs base
                     ├ security  gitleaks, Trivy fs (HIGH/CRITICAL), govulncheck, Semgrep, [CodeQL]
                     ├ helm      lint + template dev/uat/prod x none/aws/gcp/azure/ovh | kubeconform
                     └ image     buildx linux/amd64+arm64, reproducible (SOURCE_DATE_EPOCH = commit time,
                                 rewrite-timestamp) -> ghcr.io/<repo>/search:<sha>
                                 image policy: non-root USER, EXPOSE 8080, APP_ENV=prod / SPRING_PROFILES_ACTIVE=prod,
                                 size budget (Go 48 MB, Java 220 MB; linux/amd64 uncompressed) - also on PRs
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
Testcontainers ITs + JaCoCo + `tools/quality/coverage_report.sh` gate; Semgrep in the security job; image from
`java/Dockerfile` with `SERVICE=<svc>`).

## Workflows

| Workflow | Trigger | What |
|---|---|---|
| `java-<svc>.yml`, `go-<svc>.yml` (25, generated) | changes under that service (+ shared libs/chart/workflow) | pipeline above; deploy to dev on main |
| `web-<app>.yml` (3, generated) | `web/apps/<app>/**`, `web/packages/**` | lint, typecheck, test, Next build, OpenNext build; security (gitleaks, Semgrep, osv-scanner on `pnpm-lock.yaml`, gates the deploy); PR -> Workers preview URL comment; main -> dev; dispatch -> uat/prod; optional Vercel fallback |
| `promote.yml` (generated) | manual | promote a signed image (default: the tag running in the previous env) to uat or prod |
| `_java-service.yml`, `_go-service.yml`, `_web-app.yml`, `_deploy.yml` | `workflow_call` | reusable pipelines |
| `contracts.yml` | `contracts/**` | Redocly + Spectral lint, oasdiff breaking changes for every spec, AsyncAPI validate |
| `tofu.yml` | `infra/tofu/**`, `infra/cloudflare/**`, `infra/policy/**` | fmt, interface drift, validate (all modules, all env x cloud), tflint, conftest tests; plan per env on its cloud with conftest on the plan JSON + PR comment; apply dev on main, uat/prod on dispatch |
| `security.yml` | main, weekly, manual (+ PRs touching scanner config) | CodeQL (java-kotlin, go, js/ts, actions); Trivy repo SARIF (CRITICAL vulns/secrets fail) and Trivy IaC on `infra/` (CRITICAL/HIGH fail); Semgrep self-test + repo scan (ERROR fails); govulncheck every Go module; osv-scanner every lockfile (SARIF, CVSS >= 9 fails); gitleaks full history; OWASP Dependency-Check (weekly, `NVD_API_KEY`); ZAP baseline vs dev |
| `carrier-sandboxes.yml` | manual | carrier sandbox smoke (`tools/smoke/carriers.sh`): ephemeral carrier-gateway on the runner with the `carrier-sandbox` environment secrets, or the deployed dev service (docs/runbooks/carrier-sandboxes.md) |
| `backlog.yml` | `docs/backlog/**` | CSV parses, header, unique ids, valid status/type/priority, parents are epics |
| `docs.yml` | `docs/**`, `contracts/**` | build `docs/portal` and deploy to Cloudflare Pages (skips until it exists) |
| `storybook.yml` (hand-written) | `web/packages/{ui,tokens,i18n}/**`, `web/apps/*/src/**/*.stories.tsx`, Seller Center components/styles used by app stories, root `package.json`/lockfile/workspace | `storybook build`; Vitest addon (`test:storybook`: render + `play` + axe serious/critical, v8 coverage gated and uploaded) and Playwright visual diffs vs committed baselines in the pinned Playwright image; Cloudflare Pages `afrotransact-storybook` (PR preview comment, production on main); `update-visual-baselines` label regenerates baselines |
| `mobile.yml` | tags `mobile-v*`, main (`mobile/**`), manual | EAS build (production, auto-submit) on tags; EAS Update OTA to `preview` on main; dev/preview/production profiles |
| `workflows.yml` | `.github/**`, `tools/ci/**`, PORTS.md | generated workflows up to date + actionlint |
| `env-urls.yml` | `java/**`, `go/**`, `web/**`, `mobile/**`, `infra/**`, `tools/ci/check_env_*.py` | `tools/ci/check_env_urls.py` (no hard-coded environment hosts) and `tools/ci/check_env_config.py` (profiles, docs pages, trace sampling and sizing per environment) |

Generated workflows: `python3 tools/ci/gen_workflows.py` reads the service table in
`infra/local/PORTS.md`; add a service there and re-run. Workflows for services that do not exist yet are
harmless (their path filters never match and the `meta` job skips everything).

## Environment URL guard

`tools/ci/check_env_urls.py` (Python 3.10+, standard library only) fails when an environment-specific host is
hard-coded where it does not belong: any `*.afrotransact.com` host in shared defaults or non-test source, a prod
or other-environment host in dev/uat config, a dev/uat host in prod config, a host the edge does not serve (also in
`wrangler.jsonc` and `eas.json`, which list every environment), or a problem type outside
`https://developers.afrotransact.com/problems/`. Hosts, settings and rules are in [environments.md](environments.md).
Exit status 0 = clean, 1 = violations, 2 = usage error; `--format github` prints `::error file=…,line=…` annotations.

The whole repository passes (web, mobile and gateway-bff included), so `env-urls.yml` runs it without excludes, next
to `tools/ci/check_env_config.py` (S188/S182): every Java service has `application-{local,dev,uat,prod}.yml` and every
Go service `config/{local,dev,uat,prod}.yaml`; API reference pages are on in local/dev/uat and off in prod; trace
sampling resolves to 100% dev, 50% uat, 10% prod (profile defaults and Helm `telemetry.sampling`); `values-<env>.yaml`
follows the sizing rules (dev 1 replica without HPA on the spot pool, uat on the spot pool, prod at least 2 replicas
and no spot toleration, requests and memory limit everywhere, never `image.tag: latest`). Locally: `make env-check`.

Do not add `--exclude` flags to the URL guard: a path that needs a host belongs in its environment's config file, or, for an
identifier that only looks like a host, in the documented `EXCEPTIONS` list of the script.

### Web and mobile deploys: per-environment values (change requests for the workflow owners)

1. **`_web-app.yml`**: the deploy step runs `wrangler deploy --name <app>-<env>` without `--env`, so the `env.uat` /
   `env.prod` vars (and the prod route) in `web/apps/storefront/wrangler.jsonc` are never selected and every
   environment gets the top-level (dev) vars. Use `deploy --env ${TARGET}` for uat/prod (dev = top level, no `--env`)
   and `versions upload --env preview --preview-alias pr-<n>` for previews; the Worker names in wrangler.jsonc are
   already `<app>-<env>` (`storefront-dev`, `storefront-uat`, `storefront-prod`), so `--name` can go. The apps read
   their settings on the server per request, so the single Next/OpenNext build is correct for every environment.
2. **Seller Center and staff console** have no `wrangler.jsonc` / `@opennextjs/cloudflare` yet; when they get one, set
   `BFF_ORIGIN` and `PUBLIC_WEB_URL` per environment in its vars (see environments.md, "Web and mobile settings").
3. **`mobile.yml`**: `eas build --profile <p>` takes the `EXPO_PUBLIC_*` URLs from the profile's `env` in `eas.json`,
   but `eas update --channel <p>` does not read build-profile env, so an OTA bundle built on the runner would fall back
   to the local stack. Export the profile's env before `eas update` (for example
   `eval "$(jq -r --arg p "$PROFILE" '.build[$p].env | to_entries[] | "export \(.key)=\(.value|@sh)"' eas.json)"`
   in each app directory), or mirror the values into EAS environment variables and pass
   `--environment <development|preview|production>`.

## Container images (S010)

| | Go services | Java services |
|---|---|---|
| Dockerfile | `go/services/<svc>/Dockerfile` (context `go/`) | `java/Dockerfile` (`SERVICE=<svc>`, context `java/`) |
| Runtime base | `gcr.io/distroless/static-debian12:nonroot` (static, CGO off, `-trimpath -buildvcs=false -ldflags "-s -w"`) | `gcr.io/distroless/cc-debian12:nonroot` + a **jlinked JRE** (only the JDK modules the jars use, ~60 MB) + two layers (`lib/` dependencies, then the application jar) |
| User | `nonroot` (65532) | `1000:1000` |
| Defaults | `APP_ENV=prod`, `PORT=8080`, `EXPOSE 8080` | `SPRING_PROFILES_ACTIVE=prod`, `--server.port=8080`, `EXPOSE 8080` |
| Size budget (CI, linux/amd64 uncompressed) | 48 MB (binaries 21-37 MB + 2 MB base) | 220 MB (JRE ~60 MB + base ~25 MB + libraries 69-93 MB; orders 270, ai-gateway 310) |
| Platforms | linux/amd64 + linux/arm64 (Arm node pools) | same |

### Speed and memory (measured 2026-10-21 on seller-service, the largest default Java service)

| | Before | Now |
|---|---|---|
| Java image | ~340-460 MB (full JRE) | ~155-185 MB (jlinked JRE + distroless) |
| Java resident memory after start | ~620 MB (ZGC, full JIT) | ~275 MB (serial GC, C1-only JIT, 512 KB stacks, no perf-data file) |
| Java start | 9.4 s | 3-6 s |

The flags live in `java/Dockerfile` (`JAVA_TOOL_OPTIONS`; the same flags are in Jib, docker-compose and each `values-prod.yaml`).
C1-only JIT (`-XX:TieredStopAtLevel=1`) suits the low-traffic services we run; a service that becomes CPU-bound should drop it
in its values file. An AppCDS archive would start faster still but adds ~90 MB per image, so it is deliberately not used.
Go services get `GOMEMLIMIT` = 90% of `resources.limits.memory` from the Helm library chart (set `extraEnv.GOMEMLIMIT` to
override). Go 1.27 sizes `GOMAXPROCS` from the container's CPU limit by itself.

`.github/actions/publish-image` enforces user, port, environment defaults and the size budget on every build (pull
requests included) before anything is pushed; see change request 5 for the remaining reproducibility items.

## Security of the pipeline

- **No long-lived cloud keys**: `id-token: write` + OIDC federation created by
  `infra/tofu/modules/<cloud>/github-oidc` - three identities per environment:
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
| `CLOUD` | `aws` \| `gcp` \| `azure` \| `ovh` |
| `REGISTRY_URL` | `123456789012.dkr.ecr.us-east-1.amazonaws.com/afrotransact` \| `us-east4-docker.pkg.dev/<project>/afrotransact` \| `<acr>.azurecr.io/afrotransact` |
| `AWS_DEPLOY_ROLE_ARN`, `AWS_REGION` | aws: `github_oidc.deploy.role_arn`, region |
| `GCP_WORKLOAD_IDENTITY_PROVIDER`, `GCP_DEPLOY_SERVICE_ACCOUNT`, `GCP_REGISTRY_HOST` | gcp: `github_oidc.deploy.*`, `us-east4-docker.pkg.dev` |
| `AZURE_DEPLOY_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID` | azure: `github_oidc.deploy.*` |
| `OVH_REGISTRY_USERNAME` (+ secret `OVH_REGISTRY_PASSWORD`) | ovh: Harbor robot account, e.g. `robot$afrotransact-deploy` |

**Repository variables**: `TF_TARGETS` (below), `GITOPS_BRANCH` (default `main`), `VERCEL_FALLBACK`
(`true` to always deploy web to Vercel too), `DEV_BASE_DOMAIN` (ZAP and carrier smoke target), `DOCS_PAGES_PROJECT`.
The whole first-time setup in order (state, OIDC, variables, every secret, apply, Argo CD, Temporal, Pages, smoke):
[docs/runbooks/first-environment.md](../runbooks/first-environment.md).

**Secrets**: `CLOUDFLARE_API_TOKEN` (Workers, Pages: docs + Storybook), `CLOUDFLARE_ACCOUNT_ID`, `EC_API_KEY`, `CONFLUENT_CLOUD_API_KEY`,
`CONFLUENT_CLOUD_API_SECRET`, `TEMPORAL_CLOUD_API_KEY` (OpenTofu: Temporal Cloud namespaces), `EXPO_TOKEN`,
optional `NVD_API_KEY` (weekly OWASP Dependency-Check), `SMOKE_CLIENT_ID`/`SMOKE_CLIENT_SECRET` on `dev` and the carrier
sandbox keys on the `carrier-sandbox` environment (`carrier-sandboxes.yml`), optional `VERCEL_TOKEN`/`VERCEL_ORG_ID`/`VERCEL_PROJECT_ID`,
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

## OVHcloud

OVHcloud is a fourth target next to AWS, GCP and Azure. The pipelines treat it like the others (`CLOUD=ovh`, GitOps bump,
Argo CD sync) with three honest differences:

1. **No OIDC federation.** OVHcloud cannot trust GitHub's OIDC token, so there are no keyless identities. CI uses scoped
   credentials held as GitHub secrets, one set per privilege, and the `cloud-login` action masks them and exports them to the job only:
   - `deploy` (service pipelines): a **Harbor robot account** limited to the registry project, push and pull only.
     `OVH_REGISTRY_USERNAME` is an environment variable, `OVH_REGISTRY_PASSWORD` an environment secret of `dev`/`uat`/`prod`.
     The deploy job gets no OVH API access at all.
   - `plan` (pull requests): repository secrets `OVH_PLAN_APPLICATION_KEY`, `OVH_PLAN_APPLICATION_SECRET`,
     `OVH_PLAN_CONSUMER_KEY` (create the consumer key with read-only access rules: `GET` on `/cloud/project/*` and `/me`),
     plus `OVH_PLAN_S3_ACCESS_KEY` / `OVH_PLAN_S3_SECRET_KEY` (an Object Storage user that can read the state bucket).
   - `apply`: the same five secrets named `OVH_APPLY_*`, stored as secrets of the `<env>-infra` environment so only
     jobs that pass its protection rules (required reviewers on `prod-infra`) can read them. The consumer key carries
     write rules on `/cloud/project/{serviceName}/*` only.
   Rotate all of them on the schedule in the runbook; a leaked key is the main risk of this target, which is why the
   plan and apply sets are never shared.
2. **State** lives in an OVH Object Storage bucket through OpenTofu's `s3` backend (custom endpoint, path-style, no AWS
   probes). OVH offers no state lock, so `tofu.yml`'s concurrency group is the lock: never run `tofu` against that state
   by hand while a workflow runs. `TF_TARGETS.<env>.state` is `{"bucket": "...", "region": "gra"}`; add `"endpoint"` only
   for a non-default endpoint (default `https://s3.<region>.io.cloud.ovh.net`).
3. **Registry** is OVHcloud Managed Private Registry (Harbor). `REGISTRY_URL` is `<id>.<region>.container-registry.ovh.net/<project>`.
   `cosign copy` carries signatures and the SBOM attestation, so Kyverno verification works unchanged. Make tags
   immutable with a Harbor tag-immutability rule on the project (the Azure step that locks tags does not apply).

`TF_TARGETS` entry (only non-secret values; keys are the `cloud-login` inputs):

```json
"prod": { "cloud": "ovh", "region": "gra",
          "state": { "bucket": "afrotransact-tfstate", "region": "gra" },
          "plan":  { "ovh-endpoint": "ovh-eu", "ovh-service-name": "<public cloud project id>" },
          "apply": { "ovh-endpoint": "ovh-eu", "ovh-service-name": "<public cloud project id>" } }
```

`tofu.yml` validates `modules/ovh/*`, runs their mocked-provider tests (`tofu test`: cost guards) and validates the env roots for
`cloud=ovh`. State for OVH is created once by `infra/tofu/bootstrap/ovh`, which also prints the `state` value for `TF_TARGETS`.

## Web apps (Cloudflare Workers, Vercel fallback)

`_web-app.yml` builds with OpenNext (`opennextjs-cloudflare build`) and deploys with Wrangler: PRs upload a
version of `<app>-dev` with preview alias `pr-<n>` (URL commented on the PR); main deploys `<app>-dev`;
dispatch deploys `<app>-uat` / `<app>-prod` (prod approval). Worker names match the Workers routes in
`infra/cloudflare` (`seller.dev.afrotransact.com/*` -> `seller-center-dev`). Each app needs `wrangler.jsonc`
(or `.toml`) and the `@opennextjs/cloudflare` dev dependency (web agent). With `vercel: true` (input or
`VERCEL_FALLBACK=true`) the same app is also deployed with the Vercel CLI.

## Storybook (Cloudflare Pages)

`storybook.yml` (S199/S247) runs on PRs and main pushes that touch the shared UI, tokens, i18n, app stories or the
Seller Center components those stories render:

```
build             root `pnpm install --frozen-lockfile` -> `pnpm --filter @afrotransact/ui build-storybook`
                  (a story that does not compile fails here) -> artifact storybook-static
test              container mcr.microsoft.com/playwright:v1.56.1-noble (MCR, not Docker Hub; = @playwright/test and
                  playwright 1.56.1, the Vitest browser provider's)
                  test:storybook          `storybook` Vitest project (@storybook/addon-vitest, headless Chromium):
                                          every story rendered; play functions; axe WCAG 2.2 A/AA serious/critical
                                          fail; v8 coverage -> coverage_report.sh --service ui-storybook (gate),
                                          artifact `storybook-coverage`
                  visual                  Playwright screenshot per story vs web/packages/ui/visual-baselines;
                                          diffs -> artifact `visual-diffs` (+ HTML report)
deploy            wrangler pages deploy storybook-static --project-name=afrotransact-storybook --branch=<branch>
                  PR (same repo only): preview alias URL in one PR comment that is updated in place (also when
                  tests fail, so reviewers can look); main: production (only when tests pass)
update-baselines  PR label `update-visual-baselines` (or dispatch with update_baselines on a non-main branch):
                  rebuild, `visual:update` in the same image, commit the PNGs to the branch, remove the label
```

Details, local commands and the font-rendering caveat: [testing.md §7](testing.md#7-storybook--webpackagesui).

One-time setup:
- Secrets: the same repository secrets as `docs.yml`, `CLOUDFLARE_API_TOKEN` (needs **Account → Cloudflare Pages →
  Edit**) and `CLOUDFLARE_ACCOUNT_ID`. No variables are needed.
- The Pages projects `afrotransact-storybook` and `afrotransact-docs` (production branch `main`) are created by
  `infra/cloudflare/pages.tf` in the run with `pages_projects_owner = true` (dev); every environment's run adds its
  `developers.<base>` custom domain to the docs project. Without OpenTofu:
  `npx wrangler pages project create afrotransact-storybook --production-branch=main`.
- Create the PR label `update-visual-baselines`. If `main` is protected, the label path pushes only to PR
  branches, never to main.

## Mobile (EAS)

`mobile.yml`: tag `mobile-v1.4.0` (both apps) or `mobile-customer-v1.4.0` -> `eas build --profile production
--auto-submit`; pushes to main touching `mobile/**` -> `eas update --channel preview` (OTA for JS-only
changes); manual runs pick app, `build|update` and `development|preview|production`. Each app's `eas.json`
must define those three profiles with matching channels.

## Change requests for shared files (owned by other agents)

1. **Helm library secret key** (`infra/helm/library/at-service/templates/_objects.tpl`): the ExternalSecret
   key is hard-coded as `afrotransact/<env>/<svc>`. GCP Secret Manager ids and Azure Key Vault names cannot
   contain `/`, so OpenTofu names them `afrotransact-<env>-<svc>`. Proposed: a `secrets.keySeparator`
   value (default `/`; `-` in platform-values for gcp/azure) used in
   `printf "afrotransact%s%s%s%s" sep .Values.env sep name`. Done: `secrets.keySeparator` is in the library and
   the platform-values examples.
2. **GKE arm64 taint**: GKE taints Arm nodes `kubernetes.io/arch=arm64:NoSchedule`; platform-values for
   `cloud: gcp` should set `nodeSelector: {kubernetes.io/arch: arm64}` (GKE then adds the toleration).
   AKS spot nodes additionally carry `kubernetes.azure.com/scalesetpriority=spot:NoSchedule`; the dev
   values' spot toleration should include it for `cloud: azure`.
3. **infra/k8s**: `platform-secrets` ClusterSecretStore from `tofu output cluster_secret_store`; the
   `external-secrets` service account in namespace `external-secrets` annotated with
   `tofu output platform_values["external-secrets"]`; Envoy gateway must require Cloudflare's origin-pull
   client certificate (Authenticated Origin Pulls) when `origin_mode = ingress`. Done (S249): `cloudflared` with the
   tunnel token, the EnvoyProxy `edge` (Service `envoy-gateway` in `ingress`, deploy type GatewayNamespace) and the
   per-environment HTTPRoutes for `api.` / `auth.` / `mcp.` / `grafana.` (`infra/k8s/platform/edge`); Kyverno with
   the `verifyImages` policy for the keyless identity
   `https://github.com/billionairedev24/diaspora-commerce/.github/workflows/_(java|go)-service.yml@refs/heads/main`
   (`infra/k8s/platform/policies`; Kyverno needs registry read access on the cloud, see the first-environment runbook).
4. **Argo CD**: Applications should track `GITOPS_BRANCH` and read `values-<env>.yaml` (where `_deploy.yml`
   writes `image.tag`) plus a platform-values file with `cloud`, `image.registry` (= the environment's
   `REGISTRY_URL`; the chart appends `/<service>`) and `workloadIdentity`.
5. **Reproducible images (S010)**: CI builds with `SOURCE_DATE_EPOCH` = commit time and BuildKit
   `rewrite-timestamp`; two `--no-cache` builds of `go/services/cart` gave the same OCI manifest digest. Remaining:
   (a) `java/platform-parent/pom.xml`: set `<project.build.outputTimestamp>` (e.g. `2026-01-01T00:00:00Z`, or
   `${git.commit.time}`) so jar entries and `build-info.properties` are reproducible; (b) pin the base images by
   digest in `java/Dockerfile` and `go/services/*/Dockerfile` (`golang:1.27.2@sha256:…`,
   `gcr.io/distroless/static-debian12:nonroot@sha256:…`, `eclipse-temurin:25-jre@sha256:…`; Dependabot's docker
   ecosystem keeps the digests current), otherwise a rebuild after a base-image refresh changes the digest.
