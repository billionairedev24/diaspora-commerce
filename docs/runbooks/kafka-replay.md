# Kafka dead letters, replay and recovery

How events that a consumer could not process are parked, inspected, replayed, and how to rewind a consumer or rebuild a
read model. Tool reference: [`tools/ops/kafka-replay`](../../tools/ops/kafka-replay/README.md). Alerts:
[KafkaDeadLetterMessages](KafkaDeadLetterMessages.md), [KafkaDeadLetterWriteFailing](KafkaDeadLetterWriteFailing.md),
[KafkaConsumerLagGrowing](KafkaConsumerLagGrowing.md), [KafkaConsumerLagHigh](KafkaConsumerLagHigh.md),
[OutboxBacklogGrowing](OutboxBacklogGrowing.md), [IndexerLagHigh](IndexerLagHigh.md).

## 1. What the platform does with a failing event

**Java services** (`java/libs/platform-events`, auto-configured, nothing to add to a service): every `@KafkaListener`
container gets one error handler.

| Failure | What happens |
|---|---|
| Transient (database down, upstream 5xx, timeout, any exception not listed below) | retried with exponential backoff: 1 s, 2 s, 4 s, 8 s, 16 s, each +/- 250 ms jitter, i.e. 6 deliveries in about 31 s; then the record goes to the DLQ |
| Non-retryable: malformed JSON (Jackson), deserialization / message-conversion errors, `IllegalArgumentException` (incl. `NumberFormatException`), `DateTimeParseException`, `NonRetryableEventException` (throw it for a domain rule that can never succeed), plus anything in `at.events.consumer.retry.non-retryable-exceptions` | straight to the DLQ, no retries |
| The DLQ write itself fails (broker unreachable) | the offset is **not** committed and the record is retried again; the partition is blocked until the write succeeds. Alert `KafkaDeadLetterWriteFailing` |

The partition never stays blocked on a poison record: after the DLQ write succeeds the consumer moves on.

* DLQ topic: `<original topic>.dlq`, for example `at.order.events.dlq`. Same key, same value (byte for byte), all original
  headers (so `event-id`, `event-type`, `occurred-at` survive) plus:
  `x-dlq-exception-class`, `x-dlq-exception-message` (sanitised and truncated; parse errors never quote the payload),
  `x-dlq-original-topic`, `x-dlq-original-partition`, `x-dlq-original-offset`, `x-dlq-consumer-group`,
  `x-dlq-failed-at` (RFC 3339), `x-dlq-attempts`.
* Settings (`application.yml` or env): `at.events.consumer.retry.{enabled,initial-interval,multiplier,max-interval,jitter,max-attempts,dlq-suffix,send-timeout,max-message-length,non-retryable-exceptions,retryable-exceptions}`.
  Defaults 1s / 2.0 / 30s / 250ms / 6. `enabled=false` opts a service out; a service-defined `CommonErrorHandler` bean wins.
* Metrics on `/actuator/prometheus`: `events_consumer_retries_total{consumer,topic}`,
  `events_dead_lettered_total{consumer,topic,reason}` (`reason`: `deserialization`, `non_retryable`, `retries_exhausted`),
  `events_dead_letter_failures_total{consumer,topic}`. `consumer` is the Kafka group id. Logs: WARN
  `Event dead-lettered: consumer=... topic=... partition=... offset=... dlqTopic=... reason=... attempts=... exception=...`
  (no payload).
* **Known gap:** 37 Java listener classes catch malformed input themselves (`catch (JacksonException ...)`), log `Skipping malformed ...` at WARN and return.
  Those events are *not* dead-lettered and cannot be replayed from a DLQ; search the service log for `Skipping`. Converting
  them to throw `NonRetryableEventException` is the follow-up.

**Go services** keep their own mechanism: the indexer and authz-sync retry with backoff and republish the event to
`at.indexer.dlq.events` / `at.authz-sync.dlq.events` (envelope rebuilt: key = aggregate id, headers `event-id`,
`event-type`, `occurred-at`; **no** `x-dlq-original-topic`, so replay those with `--to-topic`). notifications keeps failed
notifications in its own dead-letter store (section 4). Go publishers that do not use an outbox (carrier-gateway, search)
cannot re-emit past events.

DLQ topics have 14 days retention (event topics 7 days; the single-node OVH Kafka keeps event topics 3 days). They are
created by `infra/local/kafka/create-topics.sh`, the `kafka-dlq-topics` Job in `infra/k8s/platform/lean/kafka.yaml` and
the Confluent Terraform module; `tools/ops/dlq-topics.sh --check` fails if one is missing for a topic in
`contracts/asyncapi/events.yaml`.

## 2. Decision tree

```
Alert or ticket about a DLQ / lag
|
|- Records on <topic>.dlq?  ->  `kafka-replay list` (section 4.1)
|    |- Same exception class for all, started at one time (deploy, outage)?
|    |    |- exception is infrastructure (timeout, connection, 5xx): TRANSIENT that outlasted the 31 s of retries.
|    |    |     Fix the dependency first. Then replay everything for that consumer (4.5).
|    |    '- exception is a code bug (NullPointer, IllegalState in our code): fix and deploy first. Then replay (4.5).
|    |- IllegalArgument / JacksonException / "deserialization": POISON. The producer wrote something the consumer
|    |     cannot read. Replaying it into the same code fails again. Fix the consumer (accept the shape) or the data.
|    |     Only then replay. If the event is junk, leave it: it expires after 14 days. Record that in the incident log.
|    '- Many different classes: stop, find the common change before replaying anything.
|
|- Lag growing, no DLQ records, offsets not moving (KafkaConsumerLagGrowing)
|    |- Java service: check `events_dead_letter_failures_total` (DLQ write failing: broker/ACL/missing topic permission
|    |     for `<topic>.dlq`) and the log for the WARN `will retry` lines (a record in its 31 s retry loop is normal).
|    |- Go indexer / authz-sync: log `projection failed` repeating. Same rules as above; it parks after its attempts.
|    '- crash loop / constant rebalance: roll back the release.
|
|- Events are missing and nothing is in a DLQ (consumer was down past retention, topic recreated, new consumer must
|    see history, consumer logic bug fixed that dropped events silently)
|    |- topic still holds the events -> reset the group's offsets (4.6)
|    '- topic no longer holds them -> re-publish from the producer's outbox (4.8). Go-published events (carrier-gateway,
|         search) have no outbox: ask the owning team.
|
'- Read model / search index wrong or empty -> section 5.
```

Rules that hold on every branch: **fix the cause first** (replaying into a still-broken consumer only re-fills the DLQ);
**always dry-run first**; replay the smallest filter that fixes the incident; one operator at a time per DLQ (use the
shared ledger, below).

## 3. Safety: idempotency, retention, what is not safe

### Java inbox (`platform_inbox`)

Consumers call `InboxDeduplicator.firstDelivery(consumer, eventId)` and do the business change in the **same database
transaction** (spot-checked in orders, payments, tax-returns, catalog: `@Transactional` or a `TransactionTemplate`
around both). An event whose `(consumer, event_id)` row exists is ignored. So:

* a replayed or re-published event that the consumer already applied is a no-op;
* a dead-lettered event was rolled back with its inbox row, so a replay applies it for the first time;
* the event id is the `event-id` header (= `platform_outbox.id`); a replay keeps it. **Never replay without it.**

**Retention, stated plainly: nothing purges `platform_inbox` or `platform_outbox`. Both grow forever** (verified: no
delete outside tests). Duplicates are only harmless while the inbox row exists, and the oldest duplicate you can create
is: offset reset (<= 7 days of topic), DLQ replay (<= 14 days in the DLQ after a failure that happens at most ~7 days
after publish), outbox re-publish (any age). Policy: keep inbox and published outbox rows at least
**7 (topic) + 14 (DLQ) + 14 (margin) = 35 days; default 90 days**, and do not replay or re-publish an event older than
the retention you run. Purge with [`tools/ops/platform-purge.sql`](../../tools/ops/platform-purge.sql) (counts by default,
`-v execute=yes` deletes in committed batches, refuses under 35 days), monthly, off-peak. No scheduled job runs it yet.

### Go consumers

| Consumer | Why a replay is safe | Care |
|---|---|---|
| indexer (`indexer`) | writes are version-guarded by the event time (`VersionOf(occurredAt)`, rating and availability versions): an older or equal event never overwrites a newer document | none for duplicates; an old event cannot resurrect old state |
| authz-sync (`authz-sync`) | writes OpenFGA tuples; adding an existing tuple / removing an absent one is idempotent | replay in order for one user (key = aggregate id): a role granted then revoked, replayed revoked-first, ends granted. Replay whole ranges, not single events, for one identity |
| notifications (`notifications`) | `inbox(consumer, event_id)` `ON CONFLICT DO NOTHING` plus `notification.idempotency_key` | use its own dead-letter API for failed *deliveries* (4.7) |

### What is NOT idempotent, or needs care

* **Ordering.** A DLQ replay and an outbox re-publish deliver an old event *after* newer ones for the same aggregate.
  Consumers that apply "last event wins" without a version guard can regress state (set status, set price). Replay the
  whole range for the aggregate (`--key`), or confirm the consumer guards on time/version.
* **Events without an `event-id` header** skip the inbox. Every platform publisher sets it; a hand-produced test record may not.
* **Go publishers with no outbox** (carrier-gateway and search call `events.Publisher` directly; carrier-gateway makes a
  fresh UUID per publish). Re-sending creates a *new* event id, so a consumer applies it again. Do not "re-publish"
  those; replay the DLQ record, which keeps its id.
* **Side effects outside the database.** An email, a Stripe call or a carrier label made before the failure is not undone.
  Services that call out inside the listener (payments, fulfilment, notifications) must be checked case by case before a
  bulk replay: read the exception, confirm the external call did not happen.
* **Money.** For payments / payouts / tax-returns events, replay one record, check the ledger, then the rest (4.4).
  Ledger postings use deterministic ids (`Fingerprint.id(...)`), but verify, do not assume.
* **Resetting offsets re-delivers everything after the timestamp** to every listener of that group on that topic. Inbox
  dedupes the ones already applied (and only while their rows exist); the Go consumers rely on version guards.
* **A Java inbox row survives a bug fix.** If a consumer *applied* an event wrongly (not failed), the inbox blocks a re-run.
  Reprocessing requires deleting that consumer's inbox rows for the event ids first
  (`delete from platform_inbox where consumer = '<name>' and event_id in (...)`), after the business effect has been undone
  or made idempotent. Treat as a code change reviewed by the service owner, never a routine step.

## 4. Commands

Set once (a cluster adds `KAFKA_TLS=true`, `KAFKA_SASL_MECHANISM`, `KAFKA_SASL_USER`, `KAFKA_SASL_PASSWORD`; read the
password from the secret store, never type it into the shell history):

```bash
cd tools/ops/kafka-replay && go build -o /tmp/kafka-replay ./cmd/kafka-replay
export KAFKA_BOOTSTRAP=localhost:9092          # cluster: the bootstrap address from the Kafka secret
export KR=/tmp/kafka-replay
export DLQ=at.order.events.dlq                  # the topic the alert named
```

### 4.1 Inspect a DLQ

```bash
$KR list $DLQ                                   # counts by exception class / event type / original topic / group, oldest, newest
$KR list $DLQ --since 2h --consumer-group payments-service
$KR show $DLQ --partition 1 --offset 42         # headers + payload (find partition/offset in a dry-run replay report)
$KR show $DLQ --partition 1 --offset 42 --redact   # masks every JSON string value; use in tickets and chat
# Raw, without the tool (local):
docker compose -f infra/local/docker-compose.yml exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka:29092 --topic $DLQ --from-beginning --timeout-ms 5000 \
  --property print.key=true --property print.headers=true
```

Match the record with the consumer log line: grep the log for `offset=<n>` and `partition=<p>`.

### 4.2 Replay one record

```bash
$KR replay $DLQ --key o-1 --event-type order.paid                 # dry run (the default): lists what would be sent
$KR replay $DLQ --key o-1 --event-type order.paid --max 1 --execute --by "$USER"
```

### 4.3 Replay a filtered range (dry run first)

```bash
$KR replay $DLQ --consumer-group payments-service --event-type order.paid \
   --since 2026-03-04T08:00:00Z --until 2026-03-04T10:00:00Z
# read the report, then:
$KR replay $DLQ --consumer-group payments-service --event-type order.paid \
   --since 2026-03-04T08:00:00Z --until 2026-03-04T10:00:00Z --rate 20 --execute --by "$USER"
```

`--since`/`--until` filter on `x-dlq-failed-at`; `--max N` caps the count; `--rate` is records per second. It stops at the
first publish error. Every sent record is appended to `kafka-replay-ledger.jsonl` (keep that file in the incident
ticket); re-running skips records already in it, `--force` overrides. Add `--marker-topic` to also write the ledger to
`<dlq>.replayed` so a colleague on another machine does not replay the same records (topic must exist or auto-create).

### 4.4 Replay a payments / money event cautiously

```bash
$KR replay at.payment.events.dlq --max 1 --execute --by "$USER"
# check the effect (section 6, payments) and the consumer log, then
$KR replay at.payment.events.dlq --rate 5 --execute --by "$USER"
```

### 4.5 Replay everything for a consumer after a bug fix

```bash
# 1. fix deployed and healthy (rollout status, /actuator/health), the cause gone
$KR list $DLQ --consumer-group payments-service
$KR replay $DLQ --consumer-group payments-service                              # dry run
$KR replay $DLQ --consumer-group payments-service --rate 20 --execute --by "$USER"
# 2. confirm (section 7). Records that fail again land on the DLQ again with fresh x-dlq-* headers.
```

Go DLQs have no original-topic header, so name the target (choose it from the event type: `catalog.product.*` ->
`at.product.events`, `inventory.availability.changed` -> `at.inventory.events`, `seller.org.*` -> `at.seller-org.events`,
`identity.*` -> `at.identity.events`):

```bash
$KR replay at.indexer.dlq.events --event-type catalog.product.created --to-topic at.product.events          # dry run
$KR replay at.authz-sync.dlq.events --to-topic at.identity.events --event-type identity.role.granted --execute --by "$USER"
```

### 4.6 Reset a consumer group's offsets to a timestamp

Kafka rejects a reset while the group has members; the tool refuses too (exit code 3).

```bash
# 1. stop the service (every replica)
kubectl -n <ns> scale deploy/<service> --replicas=0                 # note the old replica count; local: kill "$(cat .local/pids/payments.pid)"
# 2. print the plan, check the group is Empty
$KR reset-offsets --group payments-service --topic at.order.events --to-datetime 2026-03-04T08:00:00Z
#    prints the dry-run and execute commands (kafka-consumer-groups.sh) and the group state
$KR reset-offsets --group payments-service --topic at.order.events --to-datetime 2026-03-04T08:00:00Z \
   --kafka-bin /opt/kafka/bin/kafka-consumer-groups.sh --command-config client.properties --execute
# 3. start the service
kubectl -n <ns> scale deploy/<service> --replicas=<old count>       # local: tools/dev/local.sh restart payments
# 4. verify (section 7): lag rises to the backlog, then drains; DLQ count flat
```

`--to-datetime` also takes `6h` (six hours ago); times are sent to Kafka in UTC with a `Z`. The tool does not need the
Kafka scripts to print the plan. A group id with several listeners on the topic (payments: see section 6) is rewound for
all of them: the inbox ignores what was applied.

### 4.7 notifications dead letters

```bash
curl -s -H "Authorization: Bearer $TOKEN" "$NOTIFICATIONS_URL/v1/dead-letters"
curl -s -X POST -H "Authorization: Bearer $TOKEN" "$NOTIFICATIONS_URL/v1/dead-letters/$ID/replay"   # re-queues once
```

### 4.8 Re-publish outbox rows

Use when the event is gone from Kafka (see the decision tree). Run against the **producing** service's database.

```bash
# preview: nothing changes
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -v from=2026-03-04T08:00:00Z -v to=2026-03-04T10:00:00Z \
     -v aggregate=order -f tools/ops/outbox-republish.sql
# execute (by ids or range); at.events.relay-enabled must be true on the service
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -v event_ids='<uuid>,<uuid>' -v execute=yes -f tools/ops/outbox-republish.sql
psql "$DATABASE_URL" -c "select count(*) from platform_outbox where published_at is null"   # drains to 0 (OutboxBacklogGrowing if not)
```

It sets `published_at = NULL` on already-published rows; the relay (`KafkaOutboxRelay`) re-sends them, oldest first, with
the **same id as the `event-id` header**, so consumers with an inbox row ignore them. Refuses more than `max_rows`
(1000) rows; selecting nothing or a range with no ids is refused. Idempotent: a second run before the relay picks them
up updates 0 rows. Mind ordering (section 3). Verified against PostgreSQL 16.

## 5. Rebuild the search index / read models from scratch

* **Search (indexer, `products-<market>`).** There is **no** command that re-reads catalog and rebuilds the index. The
  "indexer backfill" in `docs/engineering/search-providers.md` is only the *embedding* backfill (`EmbeddingBackfill`,
  fills missing vectors every 15 min). That is a documented gap. Two ways to rebuild, both rebuild from events:
  1. Events still on the topics (< 7 days): stop the indexer, create the empty index (it does so at start), reset group
     `indexer` to earliest on `at.product.events`, `at.inventory.events`, `at.offers.events`, `at.seller-org.events` (4.6
     with `--to-earliest`, once per topic), start it.
  2. Older history: re-publish from the producers' outboxes, which keep every row: catalog (`aggregate=product`), inventory
     (`inventory`), offers (`offers`), seller (`seller-org`) with 4.8, in `occurred_at` order and in slices of at most
     `max_rows`. Version guards make the result order-independent. Product events carry the whole product, so one event
     per product rebuilds its document.
  Zero-downtime alternative: build into a new index behind the alias (`search/README.md`, "zero-downtime reindexing"),
  switch the alias after the indexer has caught up.
* **Java read models** (offers stock, inventory links, trust-ops projections, delivery-runs preorders): same two ways, per
  consumer group; the inbox rows of that consumer will block a re-run, so rebuilding a *Java* projection needs its
  inbox rows for the consumer deleted first (section 3, last bullet) and its tables truncated by the owner. There is no
  generic tool for that.

## 6. Who consumes and produces what

Derived from `@KafkaListener` annotations, `application.yml` (`spring.kafka.consumer.group-id`, default
`<service>-service` via `KAFKA_GROUP`) and the `*Events.AGGREGATE*` constants (topic = `at.<aggregate>.events`);
Go services from `config/*.yaml`. A group with several listeners shares its offsets per topic. "Business check" is what to
look at after a replay (row counts via the owner's database; compare before/after).

| Service | Consumer group(s) | Consumes | Produces | Business check after a replay |
|---|---|---|---|---|
| bookings | `bookings-service` | `at.payment.events`, `at.seller-compliance.events` | `at.bookings.events` | a paid booking leaves its pending state; `booking` rows |
| catalog | `catalog-service` | `at.offers.events`, `at.seller-compliance.events` | `at.product.events`, `at.category-request.events`, `at.taxonomy.events`, `at.product-import.events`, `at.product-review.events` | listing visibility of a suppressed seller (`org_listing_suppression`) |
| content | `content-service` | `at.ads.events`, `at.order.events` | `at.content.events` | `ad_click`, `ad_attribution` counts |
| delivery-runs | `delivery-runs-service` | `at.order.events`, `at.payment.events` | `at.delivery-runs.events` | `preorder_order` for the order |
| fulfilment | `fulfilment-service` | `at.delivery-runs.events`, `at.identity.events`, `at.order.events` | `at.fulfilment.events` | `delivery_shipment` / `run_dispatch` for the order |
| identity | `identity-service` | `at.seller-org.events` | `at.identity.events` | `org_membership` / `organization` of the seller org |
| inventory | `inventory-service` | `at.product.events`, `at.offers.events`, `at.retail.events` | `at.inventory.events`, `at.inventory-reservation.events`, `at.inventory-reconciliation.events`, `at.inventory-inbound.events` | `fba_offer_link`, `inbound_shipment` |
| offers | `offers-service` | `at.product.events`, `at.inventory.events`, `at.inventory-inbound.events`, `at.order.events`, `at.retail.events`, `at.seller-compliance.events`, `at.seller-org.events` | `at.offers.events` | `stock_level` for the SKU, `seller_standing` |
| orders | `orders-service` (= `spring.application.name`; no `KAFKA_GROUP`) | `at.carrier-shipment.events`, `at.delivery-runs.events`, `at.payment.events` | `at.order.events` | the order's status after a payment event; `shipment_status_change` |
| payments | `payments-service`, `payments-service-referral` (identity + order topics) | `at.bookings.events`, `at.carrier-shipment.events`, `at.retail.events`, `at.seller-compliance.events`, `at.seller-subscription.events`; referral: `at.identity.events`, `at.order.events` | `at.payment.events`, `at.payout.events` | ledger postings for the label/charge (no duplicates: one per label id) |
| retail | `retail-service` | `at.inventory-reservation.events` | `at.retail.events` | `received_lot` / COGS for the reservation |
| seller | `seller-service` | `at.identity.events`, `at.order.events`, `at.bookings.events`, `at.offers.events` | `at.seller-application.events`, `at.seller-org.events`, `at.seller-compliance.events`, `at.seller-subscription.events` | seller reward metrics for the org |
| tax-returns | `tax-returns-service` | `at.carrier-shipment.events`, `at.order.events`, `at.payment.events` | `at.taxreturns.events` | `order_snapshot`, `tax_transaction` for the order |
| trust-ops | `trust-ops-service-support`, `-audit`, `-reviews`, `-moderation`, `-product-reviews`, `-fulfilment`, `-seller-health` | `at.ai-handoff.events`; audit: `at.identity.events`, `at.seller-application.events`, `at.seller-compliance.events`, `at.payout.events`, `at.mcp-tool.events`, `at.order.events`, `at.delivery-runs.events`; `at.bookings.events`, `at.product.events`, `at.product-review.events`, `at.fulfilment.events`, `at.order.events` | `at.trustops.events` | `audit_entry` chain, `moderation_item` |
| ai-gateway | none | none | `at.ai-handoff.events` | support case appears |
| mcp-server | none | none | `at.mcp-tool.events` (audit trail) | audit entry |
| indexer (Go) | `indexer` | `at.product.events`, `at.inventory.events`, `at.offers.events`, `at.seller-org.events` | `at.indexer.dlq.events` | the product in search results; `indexer_event_lag_seconds` |
| authz-sync (Go) | `authz-sync` | `at.identity.events`, `at.seller-org.events` | `at.authz-sync.dlq.events` | OpenFGA check for the user/org |
| notifications (Go) | `notifications` | identity, order, seller-application, seller-compliance, seller-subscription, payout, bookings, content, inventory, payment, trustops, retail, delivery-runs (`config/*.yaml`) | none (no topic in `contracts/asyncapi`) | the notification row; dead-letter API |
| carrier-gateway, search (Go) | none | none | `at.carrier-shipment.events`; `at.search.events`, `at.ads.events` (no outbox) | n/a |

Cluster groups may carry an environment prefix: list with `kafka-consumer-groups.sh --list` before resetting. Confluent
ACLs grant groups by prefix `<service>.` (`infra/tofu/modules/shared/confluent/main.tf`), which the `*-service` ids above
do not match: check the ACLs when a group cannot join in a cluster.

## 7. Verify afterwards

```bash
# lag back to ~0 (Grafana: AfroTransact / Kafka consumer lag; kafka_consumergroup_lag)
kafka-consumer-groups.sh --bootstrap-server $KAFKA_BOOTSTRAP --describe --group payments-service
# DLQ not growing: the count below stops increasing (alert KafkaDeadLetterMessages clears after 30 min)
$KR list $DLQ
# replayed records are marked
$KR list at.order.events.dlq --since 10m            # fresh DLQ records = failing again: stop, read the exception
# Prometheus
increase(events_dead_lettered_total[15m])  increase(events_consumer_retries_total[15m])  increase(events_dead_letter_failures_total[15m])
# inbox / outbox of the service database
select count(*) from platform_inbox where consumer like 'payments%' and processed_at > now() - interval '1 hour';  -- rose by ~ the number replayed
select count(*) from platform_outbox where published_at is null;                                                    -- ~0
select consumer, count(*), max(processed_at) from platform_inbox group by 1 order by 3 desc limit 10;
```

Then the business check for the service in section 6. A replayed record that was a no-op because the inbox already
held it is expected and shows as no new inbox row.

## 8. Local rehearsal

```bash
make up                                           # Kafka, topics (incl. every .dlq), services
cd tools/ops/kafka-replay && go build -o /tmp/kafka-replay ./cmd/kafka-replay && cd -
export KR=/tmp/kafka-replay KAFKA_BOOTSTRAP=localhost:9092
EXEC="docker compose -f infra/local/docker-compose.yml exec -T kafka"

# a hand-made dead letter (tab between headers and key; | between key and value)
printf 'event-id:%s,event-type:order.paid,occurred-at:%s,x-dlq-original-topic:at.order.events,x-dlq-consumer-group:rehearsal,x-dlq-exception-class:java.lang.IllegalStateException,x-dlq-failed-at:%s\to-1|{"orderId":"o-1"}\n' \
  "$(cat /proc/sys/kernel/random/uuid)" "$(date -u +%FT%TZ)" "$(date -u +%FT%TZ)" |
  $EXEC /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka:29092 --topic at.order.events.dlq \
    --property parse.headers=true --property parse.key=true --property key.separator='|'

$KR list at.order.events.dlq
$KR replay at.order.events.dlq                                    # dry run
$KR replay at.order.events.dlq --execute --by rehearsal            # sends to at.order.events
$KR replay at.order.events.dlq --execute --by rehearsal            # second run: "skipped-already-replayed"
$EXEC /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:29092 --topic at.order.events \
  --from-beginning --timeout-ms 4000 --property print.headers=true | grep rehearsal   # x-replayed-by, no x-dlq-*

# offsets: stop one consumer, plan, apply, start
kill "$(cat .local/pids/payments.pid)"
$KR reset-offsets --group payments-service --topic at.order.events --to-datetime 1h
tools/dev/local.sh restart payments
```

To watch a Java service dead-letter for real, stop Postgres (`docker compose -f infra/local/docker-compose.yml stop postgres`)
for about 40 s while a flow produces an event that service consumes; the service logs `will retry` five times then `Event
dead-lettered`, and `increase(events_dead_lettered_total[5m])` rises. The unit tests
(`java/libs/platform-events`, `EventConsumerErrorHandlerTest`) cover the same path against a mocked broker.

## 9. Incident log template

```
Incident:            <ticket>            Date / operator: <utc> / <name>
Alert / symptom:     <alert, topic.dlq, consumer group>
First failure (UTC): <x-dlq-failed-at oldest>     Records affected: <n> (list: <command, filter>)
Exception class(es): <...>                  Classified as: transient | poison | bug in <service>
Root cause:          <...>                  Fix: <PR / deploy / config>
Decision:            replay all | replay filter | reset offsets | outbox re-publish | leave to expire (why)
Commands run:        <exact, with --execute and the ledger file attached>
Ordering / idempotency checked: <inbox | version guard | n/a>   Side effects checked: <email, Stripe, labels>
Verification:        lag <>  DLQ count <>  inbox/outbox <>  business check <>
Follow-ups:          <listener swallows malformed input | retention purge | alert tuning>
```
