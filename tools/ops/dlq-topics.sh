#!/usr/bin/env bash
# Dead-letter topics of the Java consumers: <event topic>.dlq for every event topic in contracts/asyncapi/events.yaml.
#   dlq-topics.sh            print the DLQ topic names, one per line
#   dlq-topics.sh --check    (tools/ops/topics-check.sh runs this and the exact-set checks) fail if infra/local/kafka/topics.txt or infra/k8s/platform/lean/kafka.yaml lacks one
# The Go consumers' own topics (at.indexer.dlq.events, at.authz-sync.dlq.events) are not listed: they ARE dead-letter
# topics and get no further suffix.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

event_topics() {
  grep -E '^[[:space:]]+address:[[:space:]]+at\.' "$ROOT/contracts/asyncapi/events.yaml" \
    | sed -E 's/.*address:[[:space:]]+//' | tr -d '"' | grep -v '\.dlq\.' | sort -u
}

case "${1:-}" in
  "") event_topics | sed 's/$/.dlq/' ;;
  --check)
    missing=0
    while read -r topic; do
      agg="${topic#at.}"; agg="${agg%.events}"
      if ! grep -qxE "${agg}([[:space:]]+[0-9]+)?" "$ROOT/infra/local/kafka/topics.txt"; then
        echo "infra/local/kafka/topics.txt: missing aggregate '${agg}' (${topic}.dlq)" >&2; missing=1
      fi
      if ! grep -q "[ \"]${topic}.dlq\([ \"]\|$\)" "$ROOT/infra/k8s/platform/lean/kafka.yaml"; then
        echo "infra/k8s/platform/lean/kafka.yaml: missing ${topic}.dlq" >&2; missing=1
      fi
    done < <(event_topics)
    [[ $missing -eq 0 ]] && echo "DLQ topics present for all $(event_topics | wc -l | tr -d ' ') event topics"
    exit $missing ;;
  *) echo "usage: $0 [--check]" >&2; exit 2 ;;
esac
