# HighLatency

**Severity:** `ticket` · **Defined in:** `infra/observability/rules/services.yaml` · **Dashboard:** Grafana → AfroTransact / Platform overview (RED)

## What it means
p95 latency of a service (except search, which has its own SLO) has been above 1 s for 10 minutes.

## Impact
Slow pages and API calls; upstream timeouts may turn into errors.

## Diagnose
1. Platform overview → p95/p99 by service; Top endpoints for the slow route.
2. Tempo: `{ resource.service.name = "<service>" && duration > 1s }` — where is time spent (DB, HTTP client, lock)?
3. JVM / Go runtime dashboards: GC pauses, CPU throttling, goroutine growth, Hikari pending threads.
4. Postgres: slow queries (`pg_stat_statements`), locks.

## Mitigate
- Scale out (HPA max) if CPU-bound; add missing index; reduce N+1 calls; raise pool size only if DB has headroom.

## After the incident
- Link the incident ticket to this runbook and update the steps if they were wrong or incomplete.
- If the alert was noisy, tune the threshold in the rule file (then `make rules`) rather than silencing it permanently.
