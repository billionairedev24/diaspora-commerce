# HighErrorRate

**Severity:** `page` · **Defined in:** `infra/observability/rules/services.yaml` · **Dashboard:** Grafana → AfroTransact / Platform overview (RED)

## What it means
More than 5% of a service's HTTP requests returned 5xx over 5 minutes (at > 0.1 req/s).

## Impact
Users see failures; may burn SLO budgets (checkout, search).

## Diagnose
1. Platform overview → 5xx ratio by service and *Top endpoints*: which route/uri?
2. Explore → Loki `{service_name="<service>"} |= "ERROR"`; open the trace via the TraceID link.
3. Tempo: search `{ resource.service.name = "<service>" && status = error }` — failing downstream (DB, Kafka, another service, Stripe)?
4. Correlate with deploys (Argo CD history) and dependency alerts (Postgres, Kafka, Elasticsearch).

## Mitigate
- Recent deploy → roll back (canary analysis should have aborted in uat/prod — check AnalysisRun).
- Downstream outage → follow that component's runbook; enable degraded mode / feature flag if available.
- Pool exhaustion → see DatabasePoolSaturation.

## After the incident
- Link the incident ticket to this runbook and update the steps if they were wrong or incomplete.
- If the alert was noisy, tune the threshold in the rule file (then `make rules`) rather than silencing it permanently.
