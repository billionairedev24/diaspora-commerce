# KafkaConsumerLagGrowing

**Severity:** `page` · **Defined in:** `infra/observability/rules/kafka.yaml` · **Dashboard:** Grafana → AfroTransact / Kafka consumer lag

## What it means
Lag is growing and the group committed no offsets for 15 minutes — the consumer is stuck.

## Impact
Projection completely stopped (e.g. search no longer reflects catalog changes).

## Diagnose
1. Consumer logs: the same message failing repeatedly (poison message)?
2. Consumer crash-looping or rebalancing constantly?
3. `kafka-consumer-groups.sh --describe --group <group>` (Kafka UI locally: http://localhost:8090).

## Mitigate
- Poison message → handler must dead-letter it to `<topic>.dlq`; as a last resort skip one offset with `kafka-consumer-groups.sh --reset-offsets --shift-by 1` (record it in the incident).
- Crash loop → roll back the consumer release.

## After the incident
- Link the incident ticket to this runbook and update the steps if they were wrong or incomplete.
- If the alert was noisy, tune the threshold in the rule file (then `make rules`) rather than silencing it permanently.
