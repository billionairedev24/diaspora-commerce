# PaymentWebhookLagBudgetBurn

**Severity:** `page / ticket` · **Defined in:** `infra/observability/rules/slo-payment-webhook-lag.yaml (generated)` · **Dashboard:** Grafana → AfroTransact / Checkout & business SLOs

## What it means
The `payment_webhook_lag` SLO is consuming its error budget faster than allowed (see thresholds below).

## Impact
Customers are affected now (page) or the monthly objective will be missed (ticket).

## SLO
- **Objective:** 99% of Stripe webhooks are processed within 60 s of the event (30-day window). **Owner:** `payments`.
- **SLI:** `payments_webhook_lag_seconds` histogram (event created → processed; bucket le=60). Recording rules `slo:sli_error:ratio_rate{5m,30m,1h,2h,6h,1d,3d,30d}{slo="payment_webhook_lag"}`.
- **Alerting (multi-window, multi-burn-rate):** `page` when 1h & 5m burn > 14.4× or 6h & 30m > 6× the budget;
  `ticket` when 1d & 2h > 3× or 3d & 6h > 1×. Page routes to PagerDuty, ticket to Opsgenie/Slack.

## Diagnose
1. Business SLO dashboard → webhook lag p95/p99.
2. Stripe dashboard → webhook delivery attempts/failures for the endpoint (signature errors? 5xx?).
3. Payments service errors and DB contention in traces.

## Mitigate
- Fix endpoint errors; Stripe retries automatically — once healthy, lag recovers. For long outages, replay events via Stripe CLI / events API.

## After the incident
- Link the incident ticket to this runbook and update the steps if they were wrong or incomplete.
- If the alert was noisy, tune the threshold in the rule file (then `make rules`) rather than silencing it permanently.
