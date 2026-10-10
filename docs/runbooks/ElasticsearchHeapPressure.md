# ElasticsearchHeapPressure

**Severity:** `ticket` · **Defined in:** `infra/observability/rules/elasticsearch.yaml` · **Dashboard:** Grafana → AfroTransact / Elasticsearch

## What it means
A node has used > 85% of its JVM heap for 15 minutes.

## Impact
Long GC pauses, slow queries (search SLO), circuit-breaker rejections.

## Diagnose
1. Which node/tier? Expensive aggregations or huge result windows in slow log (Loki panel on the dashboard).
2. Field data / segment memory: `GET _nodes/stats/indices/fielddata,segments`.

## Mitigate
- Fix the offending query; add hot nodes or memory (ECK nodeSet resources in elasticsearch/overlays/<env>).

## After the incident
- Link the incident ticket to this runbook and update the steps if they were wrong or incomplete.
- If the alert was noisy, tune the threshold in the rule file (then `make rules`) rather than silencing it permanently.
