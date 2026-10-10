#!/usr/bin/env bash
# Local Elasticsearch defaults: slow-log thresholds on product indices (S177) and a logs ILM policy.
set -euo pipefail
ES="${ES_URL:-http://elasticsearch:9200}"
curl -fsS -X PUT "$ES/_index_template/at-products-slowlog" -H 'Content-Type: application/json' -d '{
  "index_patterns": ["products*"],
  "priority": 1,
  "template": {"settings": {
    "index.search.slowlog.threshold.query.warn": "500ms",
    "index.search.slowlog.threshold.query.info": "150ms",
    "index.search.slowlog.threshold.fetch.warn": "200ms",
    "index.indexing.slowlog.threshold.index.warn": "1s"
  }}
}' && echo
curl -fsS -X PUT "$ES/_ilm/policy/at-logs" -H 'Content-Type: application/json' -d '{
  "policy": {"phases": {
    "hot":    {"actions": {"rollover": {"max_age": "1d", "max_primary_shard_size": "10gb"}}},
    "delete": {"min_age": "3d", "actions": {"delete": {}}}
  }}
}' && echo
curl -fsS "$ES/_cluster/health?pretty"
