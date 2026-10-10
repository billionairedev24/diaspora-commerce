# DatabasePoolSaturation

**Severity:** `ticket` · **Defined in:** `infra/observability/rules/services.yaml` · **Dashboard:** Grafana → AfroTransact / JVM (Spring Boot)

## What it means
A Java service's HikariCP pool has been > 90% busy for 10 minutes.

## Impact
Requests wait for connections → latency and timeouts.

## Diagnose
1. JVM dashboard → Connections, Pending threads, acquire p95.
2. Long transactions? Tempo spans with long DB time; `pg_stat_activity` for `idle in transaction`.
3. Check PostgresConnectionsSaturation — the server may be the limit.

## Mitigate
- Fix the slow query / long transaction; `DB_POOL_SIZE` can be raised if Postgres max_connections allows (pool × replicas).

## After the incident
- Link the incident ticket to this runbook and update the steps if they were wrong or incomplete.
- If the alert was noisy, tune the threshold in the rule file (then `make rules`) rather than silencing it permanently.
