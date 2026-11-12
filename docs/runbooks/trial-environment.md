# The 8-day trial (before the production decision)

> **This page is a pointer.** The trial runs on **OVH Managed Kubernetes (one `b3-16`, `us-east-va`) + Layerbase + Cloudflare**, with the prod profile, and its procedure is the
> **"Trial" section of [ovh-production.md](ovh-production.md)**. The earlier version of this page (GCP free credits, 26 services, OpenFGA, an in-cluster Postgres) described a
> different plan and was removed because it contradicted the runbooks that are actually used. There is no GCP, Azure or AWS trial and no OpenFGA.

What the trial is, in one table (the details, the order of work and the teardown are in ovh-production.md):

| | |
|---|---|
| Purpose | Prove the whole workflow once, take the measurements of ovh-production.md section 12, run a first restore drill, then decide go or no-go for production |
| Length | 8 days, then tear down (nothing of the trial is reused for production) |
| Profile | **prod** (`APP_ENV=prod`), as a values overlay: Argo CD environment `trial` (`infra/argocd/envs/trial`, `values-trial.yaml` per chart) |
| Services | the 21 launch-set services; 19 databases on Layerbase (the plan must allow 19: 17 services + `temporal` + `temporal_visibility`); Kafka, Temporal, Valkey, Meilisearch in the cluster; no OpenFGA |
| Hosts | `test.afrotransact.com`, `seller-test.`, `admin-test.`, `api-test.`, `auth-test.`, `media-test.afrotransact.com`: one label below the zone (free Cloudflare certificate); the single map is `infra/cloudflare/hosts.json` |
| Web apps | Cloudflare Workers `storefront-trial`, `seller-center-trial`, `staff-console-trial` (workflow `trial`) |
| Edge | Cloudflare Tunnel, no load balancer; mcp private; no public Grafana |
| Money | Stripe **test** mode only; AI capped at USD 2 per UTC day in code |
| Backups | Backblaze B2 `afrotransact-trial-backups` (databases only: the R2 buckets are not copied, story S380) |
| Observability | Grafana Cloud `afrotransact.grafana.net`, label `env=trial` |
| Secrets | SOPS + age: `infra/ovh-vps/secrets.sh` with `infra/ovh-vps/*.trial.env.example`, `infra/ops/secrets` |

The click-by-click page for the owner is [trial-setup-guide.html](trial-setup-guide.html) (published as an Artifact) and the demo narration is [trial-walkthrough-script.md](trial-walkthrough-script.md).
