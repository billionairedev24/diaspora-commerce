# ElasticsearchClusterRed

**Severity:** `page` · **Defined in:** `infra/observability/rules/elasticsearch.yaml` · **Dashboard:** Grafana → AfroTransact / Elasticsearch

## What it means
At least one primary shard is unassigned.

## Impact
Search and/or indexing failing for the affected index (products-<market>).

## Diagnose
1. `GET _cluster/health?level=indices` and `GET _cluster/allocation/explain`.
2. Nodes lost? (`kubectl -n elastic get pods`, ECK `kubectl get elasticsearch`)
3. Disk watermarks (ElasticsearchDiskLow).

## Mitigate
- Bring nodes back / free disk; if a primary is lost and no replica exists, restore from snapshot or rebuild the product index from the catalog (reindex job) and switch the alias.

## After the incident
- Link the incident ticket to this runbook and update the steps if they were wrong or incomplete.
- If the alert was noisy, tune the threshold in the rule file (then `make rules`) rather than silencing it permanently.
