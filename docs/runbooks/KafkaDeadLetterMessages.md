# KafkaDeadLetterMessages

**Severity:** `ticket` · **Defined in:** `infra/observability/rules/kafka.yaml` · **Dashboard:** Grafana → AfroTransact / Kafka consumer lag

Full procedure: [Kafka dead letters, replay and recovery](kafka-replay.md).

## What it means
New messages arrived on a dead-letter topic in the last 30 minutes. Java services write `<original topic>.dlq`
(for example `at.order.events.dlq`) through the shared error handler in `platform-events`; the Go indexer and authz-sync
write `at.indexer.dlq.events` and `at.authz-sync.dlq.events`.

A Java consumer gets there only after retries (6 deliveries, about 31 s) for transient errors, or at once for input it can
never process (malformed JSON, `IllegalArgumentException`, `NonRetryableEventException`).

## Impact
Those events were not applied by that consumer: its projection or follow-up work is missing them until replayed. The
partition itself keeps moving, so newer events are applied; ordering for the affected aggregate may be off.

## Diagnose
1. `kafka-replay list <topic>.dlq`: counts by exception class, event type, original topic, consumer group, oldest and
   newest. The Prometheus series `events_dead_lettered_total{consumer,topic,reason}` shows the same by service.
2. One record: `kafka-replay show <topic>.dlq --partition P --offset O --redact`. Headers: `x-dlq-exception-class`,
   `x-dlq-exception-message`, `x-dlq-original-topic/partition/offset`, `x-dlq-consumer-group`, `x-dlq-failed-at`,
   `x-dlq-attempts`, plus the original `event-id` and `event-type`.
3. The consumer's log: WARN `Event dead-lettered: consumer=... offset=...` (no payload) and the `will retry` lines before it.
4. Classify: infrastructure error (transient that outlasted the retries), a bug in our code, or a poison event
   (the producer wrote something unreadable).

## Mitigate
- Fix the cause first, then replay with the dry run first: `kafka-replay replay <dlq> --consumer-group <g> ...`, then
  `--execute --by $USER`. Replays keep the `event-id`, so the consumer's inbox ignores anything it already applied.
- Poison event and not worth fixing: leave it; the DLQ keeps records 14 days. Write it in the incident log.
- Never delete DLQ records or inbox rows to "clear" the alert.

## After the incident
- Link the incident ticket to this runbook and update the steps if they were wrong or incomplete.
- Attach the replay ledger file (`kafka-replay-ledger.jsonl`) to the ticket.
- If the alert was noisy, tune the threshold in the rule file (then `make rules`) rather than silencing it permanently.
