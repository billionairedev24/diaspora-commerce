# KafkaConsumerLagHigh

**Severity:** `ticket` · **Defined in:** `infra/observability/rules/kafka.yaml` · **Dashboard:** Grafana → AfroTransact / Kafka consumer lag

## What it means
A consumer group is more than 1000 messages behind on an `at.*.events` topic for 10 minutes.

## Impact
Eventual-consistency delays: search index, inventory projections, notifications.

## Diagnose
1. Kafka consumer lag dashboard: lag by group/topic, consume vs produce rate, time to catch up.
2. Is the consumer running and how many replicas vs partitions? (`kubectl get pods -l app.kubernetes.io/name=<consumer>`)
3. Traffic spike (produce rate) vs slow handler (traces of the consumer).

## Mitigate
- Scale the consumer (up to the partition count); fix slow handler; temporarily increase partitions only with ordering in mind.

## After the incident
- Link the incident ticket to this runbook and update the steps if they were wrong or incomplete.
- If the alert was noisy, tune the threshold in the rule file (then `make rules`) rather than silencing it permanently.
