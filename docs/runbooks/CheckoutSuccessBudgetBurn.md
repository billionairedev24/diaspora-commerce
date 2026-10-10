# CheckoutSuccessBudgetBurn

**Severity:** `page / ticket` · **Defined in:** `infra/observability/rules/slo-checkout.yaml (generated)` · **Dashboard:** Grafana → AfroTransact / Checkout & business SLOs

## What it means
The `checkout` SLO is consuming its error budget faster than allowed (see thresholds below).

## Impact
Customers are affected now (page) or the monthly objective will be missed (ticket).

## SLO
- **Objective:** 99.9% of checkout attempts succeed (30-day window). **Owner:** `orders`.
- **SLI:** `checkout_attempts_total{outcome="success|failure"}` (orders service; customer declines are not failures). Recording rules `slo:sli_error:ratio_rate{5m,30m,1h,2h,6h,1d,3d,30d}{slo="checkout"}`.
- **Alerting (multi-window, multi-burn-rate):** `page` when 1h & 5m burn > 14.4× or 6h & 30m > 6× the budget;
  `ticket` when 1d & 2h > 3× or 3d & 6h > 1×. Page routes to PagerDuty, ticket to Opsgenie/Slack.

## Diagnose
1. Business SLO dashboard → Checkouts by outcome; Orders 5xx ratio.
2. Tempo: failing `checkout` traces — which saga step (inventory reserve, payment intent, order create)?
3. Temporal UI: failing / retrying checkout workflows.
4. Dependencies: payments (Stripe status page), inventory, Postgres, Kafka alerts.

## Mitigate
- Roll back the offending release; disable a failing optional step (coupons, promise) via flag; communicate on status page if customer-visible.

## After the incident
- Link the incident ticket to this runbook and update the steps if they were wrong or incomplete.
- If the alert was noisy, tune the threshold in the rule file (then `make rules`) rather than silencing it permanently.
