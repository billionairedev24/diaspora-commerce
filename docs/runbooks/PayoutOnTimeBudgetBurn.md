# PayoutOnTimeBudgetBurn

**Severity:** `page / ticket` · **Defined in:** `infra/observability/rules/slo-payout-on-time.yaml (generated)` · **Dashboard:** Grafana → AfroTransact / Checkout & business SLOs

## What it means
The `payout_on_time` SLO is consuming its error budget faster than allowed (see thresholds below).

## Impact
Customers are affected now (page) or the monthly objective will be missed (ticket).

## SLO
- **Objective:** 99.5% of seller payouts complete on their scheduled date (30-day window). **Owner:** `payments`.
- **SLI:** `payouts_completed_total{on_time="true|false"}`. Recording rules `slo:sli_error:ratio_rate{5m,30m,1h,2h,6h,1d,3d,30d}{slo="payout_on_time"}`.
- **Alerting (multi-window, multi-burn-rate):** `page` when 1h & 5m burn > 14.4× or 6h & 30m > 6× the budget;
  `ticket` when 1d & 2h > 3× or 3d & 6h > 1×. Page routes to PagerDuty, ticket to Opsgenie/Slack.

## Diagnose
1. Payouts panel on the Business SLO dashboard; payout job logs; Stripe Connect account restrictions (requirements due).
2. Ledger balance / reserve rules blocking payouts?

## Mitigate
- Re-run failed payout batches; contact sellers with restricted Connect accounts (seller service compliance flow).

## After the incident
- Link the incident ticket to this runbook and update the steps if they were wrong or incomplete.
- If the alert was noisy, tune the threshold in the rule file (then `make rules`) rather than silencing it permanently.
