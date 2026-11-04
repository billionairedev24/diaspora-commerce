#!/usr/bin/env bash
# Fails when the Kafka topic lists drift apart. contracts/asyncapi/events.yaml (generated from the fragments) is the
# authoritative topic list; everything else must match it:
#   infra/tofu/kafka-topics.json             topics, producers, consumers, consumer-group ACL prefixes, DLQ writers
#                                            (read by infra/tofu/envs/*/main.tf; generated, see --write)
#   infra/tofu/envs/*/main.tf                every service named in that file exists in local.services
#   infra/local/kafka/topics.txt             exact set (plus the Go dead-letter topics as literals)
#   infra/k8s/platform/lean/kafka.yaml       exact set of DLQ topics (OVH / lean cluster)
# and every consumer group id found in the Java application.yml / @KafkaListener defaults and the Go configs must be
# covered by the ACL prefix in the JSON.
#
#   topics-check.sh            check (CI); exit 1 with every difference
#   topics-check.sh --write    regenerate infra/tofu/kafka-topics.json from asyncapi + code, then check
#   topics-check.sh --groups   print the consumer group / ACL table (Markdown) for docs/engineering/kafka-topics-and-acls.md
#
# Needs bash and python3 (standard library only). tools/ops/dlq-topics.sh is run too: it keeps the "a DLQ exists for
# every event topic" check next to the exact-set check.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MODEL="$ROOT/tools/ops/topics_model.py"

case "${1:-}" in
  "")
    "$ROOT/tools/ops/dlq-topics.sh" --check
    python3 -I "$MODEL" ;;
  --write)
    python3 -I "$MODEL" --write
    "$ROOT/tools/ops/dlq-topics.sh" --check
    python3 -I "$MODEL" ;;
  --groups)
    python3 -I "$MODEL" --groups ;;
  *) echo "usage: $0 [--write|--groups]" >&2; exit 2 ;;
esac
