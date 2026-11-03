# kafka-replay

Inspect dead-letter topics and replay their records. The procedure, decision tree and safety rules are in
[`docs/runbooks/kafka-replay.md`](../../../docs/runbooks/kafka-replay.md); this file is the tool reference.

Java consumers (`java/libs/platform-events`) park a record they could not process on `<topic>.dlq` with
`x-dlq-*` headers. The Go indexer and authz-sync use `at.indexer.dlq.events` / `at.authz-sync.dlq.events`.

```bash
cd tools/ops/kafka-replay
go build -o /tmp/kafka-replay ./cmd/kafka-replay        # or: go run ./cmd/kafka-replay <args>
export KAFKA_BOOTSTRAP=localhost:9092                    # KAFKA_TLS=true, KAFKA_SASL_MECHANISM/USER/PASSWORD for a cluster
```

| Command | What it does |
|---|---|
| `list <dlq-topic>` | counts by exception class, event type, original topic, consumer group; oldest and newest |
| `show <dlq-topic> --partition P --offset O [--redact]` | one record: headers and payload (`--redact` masks every JSON string value) |
| `replay <dlq-topic> [filters]` | re-publish to `x-dlq-original-topic`. **Dry run unless `--execute`** |
| `reset-offsets --group G --topic T (--to-datetime X \| --to-earliest \| --to-latest)` | prints the `kafka-consumer-groups.sh` command; with `--execute` runs it only if the group has no active member |

Filters (`list` and `replay`): `--event-type`, `--consumer-group`, `--original-topic`, `--key`, `--since`, `--until`
(RFC 3339, or a duration ago such as `2h`; `--since` is inclusive, `--until` exclusive; the time is `x-dlq-failed-at`).

`replay` flags: `--max N`, `--rate R` (records per second), `--execute`, `--force`, `--by NAME` (required with
`--execute`; defaults to `$USER`), `--ledger FILE` (default `kafka-replay-ledger.jsonl`), `--marker-topic` (also keep the
ledger on `<dlq-topic>.replayed`), `--to-topic T` (needed for the Go DLQs, whose records have no original-topic header).

## What a replay does to a record

Published to the original topic with the same key and value, **all original headers** (`event-id`, `event-type`,
`occurred-at`, ...) except the `x-dlq-*` and `kafka_dlt-*` diagnostics, plus `x-replayed-at`, `x-replayed-by`,
`x-replayed-from` (`<dlq-topic>/<partition>/<offset>`). Because `event-id` is unchanged, a consumer whose inbox already
saw the event ignores it; one that never processed it (it was dead-lettered) handles it.

It stops at the first publish error (nothing after it is sent), appends a ledger line after every successful publish,
and skips DLQ records already in the ledger unless `--force`. A record whose original topic is unknown is reported
and skipped, never guessed.

## Layout

Same shape as the Go services: `cmd/kafka-replay/main.go` (explicit wiring), `internal/{domain,app,port,config,cli}`,
`internal/adapter/{kafka,ledger,shell}`. Kafka client: franz-go, as in `go/pkg/events`. The module is in `go/go.work`.

```bash
go vet ./... && go test ./...
golangci-lint run --config ../../../go/.golangci.yml ./...
```

The unit tests use fakes for the Kafka ports. The franz-go adapter (`internal/adapter/kafka`) is exercised only against
a real broker: rehearse it with `make up` (runbook, "Local rehearsal").
