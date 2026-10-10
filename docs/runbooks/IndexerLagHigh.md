# IndexerLagHigh

**Severity:** `page` · **Defined in:** `infra/observability/rules/kafka.yaml` · **Dashboard:** Grafana → AfroTransact / Elasticsearch

## What it means
The indexer (Kafka → Elasticsearch projection) is more than 60 s behind (`indexer_event_lag_seconds`).

## Impact
Search shows stale products, prices or availability.

## Diagnose
1. Elasticsearch dashboard → Indexer lag & Kafka lag panel; cluster status, indexing rate, rejected bulk requests.
2. Indexer logs: bulk errors (mapping conflicts → 400s), ES 429/circuit breaker.
3. Kafka lag for consumer group `indexer`.

## Mitigate
- ES overloaded → see ElasticsearchHeapPressure; scale indexer replicas (≤ partitions); for mapping errors fix and reindex behind the alias.

## After the incident
- Link the incident ticket to this runbook and update the steps if they were wrong or incomplete.
- If the alert was noisy, tune the threshold in the rule file (then `make rules`) rather than silencing it permanently.
