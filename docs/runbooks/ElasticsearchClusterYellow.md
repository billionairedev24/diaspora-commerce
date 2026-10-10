# ElasticsearchClusterYellow

**Severity:** `ticket` · **Defined in:** `infra/observability/rules/elasticsearch.yaml` · **Dashboard:** Grafana → AfroTransact / Elasticsearch

## What it means
Replica shards unassigned for 30 minutes on a multi-node cluster.

## Impact
No redundancy; a single node loss could turn the cluster red.

## Diagnose
1. `GET _cluster/allocation/explain` — allocation filters, tier preference, disk watermarks, too few nodes for the replica count.

## Mitigate
- Add capacity or fix allocation settings; transient after node restarts — check it is recovering (`_cat/recovery`).

## After the incident
- Link the incident ticket to this runbook and update the steps if they were wrong or incomplete.
- If the alert was noisy, tune the threshold in the rule file (then `make rules`) rather than silencing it permanently.
