# KafkaDeadLetterMessages

**Severity:** `ticket` · **Defined in:** `infra/observability/rules/kafka.yaml` · **Dashboard:** Grafana → AfroTransact / Kafka consumer lag

## What it means
New messages arrived on an `at.*.events.dlq` topic in the last 30 minutes.

## Impact
Some events were not applied by a consumer (inconsistent projections until replayed).

## Diagnose
1. Inspect the DLQ messages (Kafka UI / kcat): error header, event type, aggregate id.
2. Find the consumer log line with the same correlation id.

## Mitigate
- Fix the consumer bug or bad data, then replay DLQ messages to the main topic (they are idempotent via the inbox).

## After the incident
- Link the incident ticket to this runbook and update the steps if they were wrong or incomplete.
- If the alert was noisy, tune the threshold in the rule file (then `make rules`) rather than silencing it permanently.
