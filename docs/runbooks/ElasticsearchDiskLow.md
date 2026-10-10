# ElasticsearchDiskLow

**Severity:** `ticket` · **Defined in:** `infra/observability/rules/elasticsearch.yaml` · **Dashboard:** Grafana → AfroTransact / Elasticsearch

## What it means
A node has less than 15% disk free for 15 minutes.

## Impact
At the high watermark shards move away; at flood stage indices become read-only (indexing stops).

## Diagnose
1. `GET _cat/allocation?v`; which indices grew (`_cat/indices?s=store.size:desc`).

## Mitigate
- Expand the PVC (ECK volumeClaimTemplates), delete old index versions behind aliases, check ILM is moving logs to warm/frozen. Locally: `make reset`.

## After the incident
- Link the incident ticket to this runbook and update the steps if they were wrong or incomplete.
- If the alert was noisy, tune the threshold in the rule file (then `make rules`) rather than silencing it permanently.
