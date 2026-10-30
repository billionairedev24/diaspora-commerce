#!/usr/bin/env bash
# Idempotently creates the event topics listed in topics.txt:
#   <aggregate>   ->  at.<aggregate>.events (7 days) and at.<aggregate>.events.dlq (14 days)
#   =<topic>      ->  that literal topic (14 days), no extra .dlq (the Go indexer / authz-sync dead-letter topics)
# <topic>.dlq is where the Java consumers' shared error handler (platform-events) parks records that failed
# permanently; docs/runbooks/kafka-replay.md. Retention is longer than the source topic on purpose.
set -euo pipefail
BOOTSTRAP="${BOOTSTRAP:-kafka:29092}"
EVENTS_RETENTION_MS="${EVENTS_RETENTION_MS:-604800000}"   # 7 days
DLQ_RETENTION_MS="${DLQ_RETENTION_MS:-1209600000}"        # 14 days
KT=/opt/kafka/bin/kafka-topics.sh

create() { # topic partitions retention_ms
  "$KT" --bootstrap-server "$BOOTSTRAP" --create --if-not-exists --topic "$1" \
    --partitions "$2" --replication-factor 1 --config "retention.ms=$3" >/dev/null
}

while read -r agg parts; do
  [[ -z "${agg}" || "${agg}" == \#* ]] && continue
  parts="${parts:-3}"
  if [[ "${agg}" == =* ]]; then
    create "${agg#=}" "$parts" "$DLQ_RETENTION_MS"
    echo "ok ${agg#=}"
    continue
  fi
  create "at.${agg}.events" "$parts" "$EVENTS_RETENTION_MS"
  create "at.${agg}.events.dlq" "$parts" "$DLQ_RETENTION_MS"
  echo "ok at.${agg}.events (+ .dlq)"
done < /topics/topics.txt
"$KT" --bootstrap-server "$BOOTSTRAP" --list | grep -c '^at\.' | xargs echo "at.* topics present:"
