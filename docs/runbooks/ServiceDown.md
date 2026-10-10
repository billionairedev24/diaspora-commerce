# ServiceDown

**Severity:** `page` · **Defined in:** `infra/observability/rules/services.yaml` · **Dashboard:** Grafana → AfroTransact / Platform overview (RED)

## What it means
Prometheus could not scrape a service's metrics endpoint for 2 minutes (`up == 0`). Every replica is gone or the metrics port is unreachable.

## Impact
All traffic for the service fails or is routed to nothing; dependants degrade (BFF screens, checkout saga steps).

## Diagnose
1. `kubectl -n afrotransact-<env> get pods -l app.kubernetes.io/name=<service>` — CrashLoopBackOff, Pending, ImagePullBackOff?
2. `kubectl -n afrotransact-<env> describe rollout|deploy <service>` and `kubectl logs --previous` for the last crash.
3. Argo CD: was there a sync in the last 30 min (`argocd app history <service>-<env>`)? A failed canary aborts automatically.
4. Startup failures: missing secret keys (ExternalSecret `<service>-secrets` status), DB/Kafka unreachable, Flyway migration error.
5. Locally the alert is silenced (services usually not running) — check `make ps` instead.

## Mitigate
- Bad release → `argocd app rollback <service>-<env>` or `kubectl argo rollouts abort <service>` then undo the image bump in `values-<env>.yaml`.
- Secret missing → fix the entry `afrotransact/<env>/<service>` in the cloud secret store; `kubectl annotate externalsecret ... force-sync=$(date +%s)`.
- Node / capacity → check Pending reasons; scale node group.

## After the incident
- Link the incident ticket to this runbook and update the steps if they were wrong or incomplete.
- If the alert was noisy, tune the threshold in the rule file (then `make rules`) rather than silencing it permanently.
