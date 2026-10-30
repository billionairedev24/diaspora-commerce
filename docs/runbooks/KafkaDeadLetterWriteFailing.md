# KafkaDeadLetterWriteFailing

**Severity:** `page` · **Defined in:** `infra/observability/rules/kafka.yaml` · **Dashboard:** Grafana → AfroTransact / Kafka consumer lag

Full procedure: [Kafka dead letters, replay and recovery](kafka-replay.md).

## What it means
A Java service could not write a failed record to its dead-letter topic (`events_dead_letter_failures_total` rose for 5
minutes). The offset is not committed and the same record is retried, so that partition makes no progress. Nothing is lost.

## Impact
Consumption of the affected topic partition by that group is stopped (lag grows, KafkaConsumerLagGrowing follows).

## Diagnose
1. Which service and topic: the alert labels (`job`, `consumer` = group id, `topic` = the source topic).
2. Service log: `Dead-lettering failed; the record will be retried ... dlqError=<class>`.
3. Does `<topic>.dlq` exist (`kafka-topics.sh --describe --topic <topic>.dlq`)? With broker auto-create off (Confluent) a
   missing topic fails the write: `tools/ops/dlq-topics.sh --check`, then create it.
4. Permission: the service needs WRITE on `<topic>.dlq` (Confluent ACLs come from `infra/tofu/modules/shared/confluent`).
5. Broker unreachable / `max.message.bytes` smaller on the DLQ topic than on the source topic.

## Mitigate
- Create the topic / fix the ACL / restore the broker; the next retry succeeds and the record lands on the DLQ.
- Do not skip the offset to unblock: that loses the event. See KafkaConsumerLagGrowing for the last resort.

## After the incident
- Link the incident ticket to this runbook and update the steps if they were wrong or incomplete.
