# SearchLatencyBudgetBurn

**Severity:** `page / ticket` · **Defined in:** `infra/observability/rules/slo-search-latency.yaml (generated)` · **Dashboard:** Grafana → AfroTransact / Checkout & business SLOs

## What it means
The `search_latency` SLO is consuming its error budget faster than allowed (see thresholds below).

## Impact
Customers are affected now (page) or the monthly objective will be missed (ticket).

## SLO
- **Objective:** 95% of search/suggest requests complete in < 150 ms (30-day window). **Owner:** `search`.
- **SLI:** `http_server_request_duration_seconds_bucket{service="search", route=~"GET /v1/(search|suggest)", le="0.15"}`. Recording rules `slo:sli_error:ratio_rate{5m,30m,1h,2h,6h,1d,3d,30d}{slo="search_latency"}`.
- **Alerting (multi-window, multi-burn-rate):** `page` when 1h & 5m burn > 14.4× or 6h & 30m > 6× the budget;
  `ticket` when 1d & 2h > 3× or 3d & 6h > 1×. Page routes to PagerDuty, ticket to Opsgenie/Slack.

## Diagnose
1. Elasticsearch dashboard → avg query latency, heap, cluster status; Search p95 panel.
2. Slow log (Loki panel) — which query shape? Hybrid retrieval / AI gateway embedding latency in traces.
3. Search pod CPU throttling (Go runtime dashboard).

## Mitigate
- Scale search pods / ES hot nodes; disable hybrid (vector) retrieval via config if the embedder is slow; fix expensive aggregation.

## After the incident
- Link the incident ticket to this runbook and update the steps if they were wrong or incomplete.
- If the alert was noisy, tune the threshold in the rule file (then `make rules`) rather than silencing it permanently.
