# Cost: budgets, alerts and the monthly review

**Backlog:** S182 · **Budgets:** `infra/terraform/modules/<cloud>/budget` · **Sizing:** `infra/terraform/envs/<env>/sizing.tf` ·
**Cost table:** `infra/terraform/README.md` sections 4 and 5

This page is for the platform on-call and whoever owns the cloud bill. It covers what keeps the bill down by default,
what fires when it does not, and what to do about it.

## 1. Baseline (what is already in place)

| Lever | Where | Effect |
|---|---|---|
| Arm nodes everywhere (Graviton / Axion / Cobalt) | `modules/<cloud>/cluster`, multi-arch images (`.github/actions/publish-image`) | ~20-40% cheaper per vCPU than x86 |
| Spot pools, tainted `afrotransact.com/spot=true:NoSchedule`, min 0 | `modules/<cloud>/cluster`, `spot_max_nodes` in `sizing.tf` | dev and uat workloads tolerate the taint (`values-dev.yaml`, `values-uat.yaml`); prod does not (`tools/ci/check_env_config.py` enforces both) |
| Per-environment sizing | `sizing.tf` (nodes, database, cache, SaaS profile), service `values-<env>.yaml` | dev: 1 replica, no HPA; uat: 2 replicas, small HPA; prod: 3+ replicas, HPA, Argo Rollouts canary |
| Scale to zero in dev | Aurora Serverless v2 min 0 ACU, Confluent Basic, spot pool min 0 | idle dev costs storage only |
| Preview namespaces scale to zero when idle | `infra/k8s/platform/previews` (CronJob `preview-idle-scaler`, dev cluster) | namespaces labelled `afrotransact.com/preview=true` go to 0 replicas after 30 minutes without requests and outside Mon-Fri 07-21 US Central; a redeploy or `kubectl annotate ns <ns> afrotransact.com/wake="$(date -u +%FT%TZ)" --overwrite` wakes them |
| Web previews | Cloudflare Workers versions (`_web-app.yml`) | billed per request; nothing runs when idle |
| Search tiers | `modules/shared/elastic` + ILM `modules/shared/elastic/ilm/events-hot-warm-frozen.json` (hot 7 d -> warm -> frozen at 30 d -> delete at 400 d) | prod keeps old events on searchable snapshots instead of hot disks |
| Trace sampling | 100% dev, 50% uat, 10% prod plus tail sampling in the OTel gateway (errors and slow traces always kept) | observability volume scales with traffic, not with environments |
| Storage lifecycle | `modules/<cloud>/bucket` (IA / Nearline / Cool, then archive; exports expire after 30 days) | |
| Budgets with alerts | `modules/<cloud>/budget` (AWS Budgets, GCP Cloud Billing budget, Azure consumption budget) | see section 2 |

## 2. Budgets and alerts

Every environment root creates one monthly budget for its cloud spend (`monthly_budget` in `sizing.tf`):

| Environment | Monthly budget (USD) | Alerts |
|---|---|---|
| dev | 1,000 | actual 50%, 80%, 100%; forecast 100% |
| uat | 1,500 | same |
| prod | 6,500 | same |

The amounts cover the cloud account only (compute, databases, cache, storage, network, registry). Elastic Cloud,
Confluent Cloud, Temporal Cloud, Cloudflare, Grafana Cloud, OpenRouter and messaging vendors are budgeted in their own
consoles (set a monthly spend alert in each when the account is created; first-environment runbook step 4).

Recipients: `budget_notification_emails` in `terraform.tfvars`, plus

- **AWS:** the SNS topic `afrotransact-<env>-monthly-budget-alerts` (output `budget.alert_target`); subscribe AWS Chatbot
  (Slack) or PagerDuty to it. When several environments share one account, set `budget_shared_account = true` and
  activate the `env` cost allocation tag once in Billing -> Cost allocation tags (it can only be activated after tagged
  resources have existed for about 24 hours; until then a tag-filtered budget reads 0).
- **GCP:** billing account admins and one Cloud Monitoring email channel per address. Needs `gcp_billing_account` in
  `terraform.tfvars`, the Cloud Billing Budget API, and `roles/billing.costsManager` for the identity that applies.
- **Azure:** the subscription Owners and the listed addresses; the budget is scoped to the environment's resource group,
  so it is exact even when environments share a subscription.

Change an amount in `sizing.tf` and apply (PR -> plan -> merge). Do not raise a budget to silence an alert without the
review below.

## 3. When a budget alert fires

1. **Which environment and which service?**
   - AWS: Cost Explorer, group by *Service* and by tag *env* / *service* (every resource carries `env`, `owner`,
     `cost-center`, `service`, `module`).
   - GCP: Billing -> Reports, filter the project, group by *Service* or by label `service`.
   - Azure: Cost Management -> Cost analysis on the resource group `afrotransact-<env>`, group by *Service name* or tag
     `service`.
2. **Forecast alert only (no actual threshold yet):** usually a new steady cost (bigger node pool, new database size).
   Confirm it was intended (recent PRs to `sizing.tf`, `values-<env>.yaml`); if so, adjust `monthly_budget`.
3. **Actual 80% before the 20th of the month, or 100% at any time:** look for the usual causes:
   - Compute: node count stuck at max (`kubectl get nodes -L afrotransact.com/spot,karpenter.sh/capacity-type`),
     HPA pinned at max, preview namespaces not sleeping (`kubectl get ns -l afrotransact.com/preview=true -o
     custom-columns=NAME:.metadata.name,ASLEEP:.metadata.annotations.afrotransact\.com/scaled-to-zero-at,KEEP:.metadata.annotations.afrotransact\.com/keep-awake`
     and the CronJob logs in `previews-system`).
   - Data: database scaled up (Aurora ACU max, Cloud SQL tier), storage growth, cross-zone or NAT egress (NAT bytes in
     the cloud console; cloud APIs should go through private endpoints, not NAT).
   - Logs and metrics: a noisy service (Loki ingestion by `service`), high-cardinality metrics.
4. **Mitigate** with the least risky lever: scale down a non-prod environment (`replicas`, spot max), put previews to
   sleep, remove `keep-awake`, lower log levels, fix a runaway job. Never disable prod autoscaling to save money during
   an incident.
5. Record the cause and the fix in the cost review notes (section 4).

## 4. Monthly review (first Monday of the month)

- Actual vs budget per environment and per layer, compared with the planning table in `infra/terraform/README.md`
  section 5 (launch estimate 6k-11k USD per month across all layers; target at most 3% of GMV).
- Commitments (prod only, after 4-6 weeks of stable usage): cover about 60% of the on-demand arm64 floor and the
  Postgres baseline with Compute Savings Plans + RDS reserved capacity (AWS), committed-use discounts (GCP) or an Azure
  savings plan + reservations. Re-check coverage and utilisation each month.
- Spot interruption rate and evictions in dev/uat (if a pool is interrupted constantly, add another instance type to
  the pool rather than moving to on-demand).
- Right-sizing: compare requests with p95 usage (Grafana, Kubernetes / Compute Resources / Namespace (Workloads));
  lower requests in `values-<env>.yaml` where usage stays under half of the request.
- SaaS: Elastic deployment size and tier usage (ILM moving data to warm/frozen), Confluent cluster type and partitions,
  Temporal actions, OpenRouter spend per feature (ai-gateway budgets), messaging volume.

## After the review

- Update the budget amounts in `sizing.tf` if the baseline changed for a known reason.
- File follow-ups for the owning teams (label `finops`) and link them in the review notes.
