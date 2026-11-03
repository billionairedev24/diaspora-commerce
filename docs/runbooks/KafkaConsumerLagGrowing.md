# KafkaConsumerLagGrowing

**Severity:** `page` · **Defined in:** `infra/observability/rules/kafka.yaml` · **Dashboard:** Grafana → AfroTransact / Kafka consumer lag

Full procedure: [Kafka dead letters, replay and recovery](kafka-replay.md).

## What it means
Lag is growing and the group committed no offsets for 15 minutes — the consumer is stuck.

## Impact
Projection completely stopped (e.g. search no longer reflects catalog changes).

## Diagnose
A poison record does not block a Java consumer for long: it is retried for about 31 s (6 deliveries), then written to
`<topic>.dlq` and skipped. A group that is stuck for 15 minutes is therefore one of:
1. **The DLQ write is failing.** The offset is not committed and the same record is retried forever. Check
   `increase(events_dead_letter_failures_total[10m])` (alert `KafkaDeadLetterWriteFailing`), the log line
   `Dead-lettering failed; the record will be retried`, and that `<topic>.dlq` exists and the service may write to it
   (Confluent ACLs: `infra/tofu/modules/shared/confluent`).
2. **A handler that hangs** (no exception, never returns: a stuck HTTP call or lock). Thread dump / traces of the
   consumer; the retry logic never runs because nothing is thrown.
3. **A Go consumer** (indexer, authz-sync, notifications): the same message failing repeatedly in the log
   (`projection failed`); they park on `at.indexer.dlq.events` / `at.authz-sync.dlq.events` after their attempts.
4. Consumer crash-looping or rebalancing constantly (`kafka-consumer-groups.sh --describe --group <group>`, group ids in
   the table in kafka-replay.md section 6; Kafka UI locally: http://localhost:8090).
5. The service is not running (`at.consumers.enabled=false`, scaled to 0).

## Mitigate
- DLQ write failing → restore broker access / create the `.dlq` topic / fix the ACL; the retry succeeds by itself.
- Hung handler → fix or restart the service; the uncommitted record is redelivered.
- Crash loop → roll back the consumer release.
- Last resort for a record that cannot be dead-lettered: skip one offset with
  `kafka-replay reset-offsets --group <group> --topic <topic> --to-datetime <time just after the bad record>` (service
  stopped), or `kafka-consumer-groups.sh --reset-offsets --shift-by 1 --execute`. Copy the skipped record first
  (`kafka-replay show` is not possible for a non-DLQ topic: use `kafka-console-consumer.sh --partition P --offset O --max-messages 1`)
  and record it in the incident log; it is data loss for that consumer.

## After the incident
- Link the incident ticket to this runbook and update the steps if they were wrong or incomplete.
- If the alert was noisy, tune the threshold in the rule file (then `make rules`) rather than silencing it permanently.
