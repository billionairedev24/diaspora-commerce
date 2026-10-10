#!/usr/bin/env bash
# Idempotently creates at.<aggregate>.events topics listed in topics.txt (+ a dead-letter topic each).
set -euo pipefail
BOOTSTRAP="${BOOTSTRAP:-kafka:29092}"
KT=/opt/kafka/bin/kafka-topics.sh
while read -r agg parts; do
  [[ -z "${agg}" || "${agg}" == \#* ]] && continue
  parts="${parts:-3}"
  for t in "at.${agg}.events" "at.${agg}.events.dlq"; do
    "$KT" --bootstrap-server "$BOOTSTRAP" --create --if-not-exists --topic "$t" \
      --partitions "$parts" --replication-factor 1 --config retention.ms=604800000 >/dev/null
  done
  echo "ok at.${agg}.events"
done < /topics/topics.txt
"$KT" --bootstrap-server "$BOOTSTRAP" --list | grep -c '^at\.' | xargs echo "at.* topics present:"
