# OutboxBacklogGrowing

**Severity:** `ticket` · **Defined in:** `infra/observability/rules/services.yaml` · **Dashboard:** Grafana → AfroTransact / JVM (Spring Boot)

## What it means
More than 1000 transactional-outbox rows have not been relayed to Kafka for 10 minutes.

## Impact
Downstream projections (search index, notifications, analytics) are stale; events delayed but not lost.

## Diagnose
1. Service logs for the relay (`OutboxRelay`) — Kafka auth/timeout errors?
2. Kafka broker health and the producer metrics on the JVM dashboard.
3. Is `at.events.relay-enabled` true for the env?

## Mitigate
- Restore Kafka connectivity; the relay drains automatically. Do not delete outbox rows.

## After the incident
- Link the incident ticket to this runbook and update the steps if they were wrong or incomplete.
- If the alert was noisy, tune the threshold in the rule file (then `make rules`) rather than silencing it permanently.
