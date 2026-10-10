# PostgresConnectionsSaturation

**Severity:** `page` · **Defined in:** `infra/observability/rules/postgres.yaml` · **Dashboard:** Grafana → AfroTransact / JVM (Spring Boot)

## What it means
More than 80% of `max_connections` are in use for 5 minutes.

## Impact
New connections soon refused → service health checks fail, cascading outages.

## Diagnose
1. `select usename, datname, state, count(*) from pg_stat_activity group by 1,2,3 order by 4 desc;` — which service?
2. Did a service scale out (HPA) with a large pool? connections ≈ Σ(pool size × replicas).

## Mitigate
- Lower `DB_POOL_SIZE` for the noisy service or cap HPA max; kill `idle in transaction` sessions; add PgBouncer / RDS Proxy; scale the instance.

## After the incident
- Link the incident ticket to this runbook and update the steps if they were wrong or incomplete.
- If the alert was noisy, tune the threshold in the rule file (then `make rules`) rather than silencing it permanently.
