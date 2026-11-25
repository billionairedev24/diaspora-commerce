# IndexerLagHigh

**Severity:** `page` · **Defined in:** `infra/observability/rules/kafka.yaml` · **Dashboard:** Grafana → AfroTransact / Kafka consumer lag

## What it means
The indexer (Kafka → search index projection; Meilisearch at launch) is more than 60 s behind (`indexer_event_lag_seconds`).

## Impact
Search shows stale products, prices or availability.

## Diagnose
1. Kafka consumer lag dashboard → the `indexer` group; Meilisearch pod (`kubectl -n data get pods`, logs: task failures, memory).
2. Indexer logs: write errors from the search engine (rejected documents, 4xx/5xx, timeouts).
3. Kafka lag for consumer group `indexer`.

## Mitigate
- Search engine overloaded (launch: Meilisearch; `SEARCH_PROVIDER=elasticsearch` only off the launch path): check its pod memory and logs; scale indexer replicas (≤ partitions); for mapping errors fix and reindex behind the alias.

## After the incident
- Link the incident ticket to this runbook and update the steps if they were wrong or incomplete.
- If the alert was noisy, tune the threshold in the rule file (then `make rules`) rather than silencing it permanently.
