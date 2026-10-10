# NotificationDeliveryBudgetBurn

**Severity:** `page / ticket` · **Defined in:** `infra/observability/rules/slo-notification-delivery.yaml (generated)` · **Dashboard:** Grafana → AfroTransact / Checkout & business SLOs

## What it means
The `notification_delivery` SLO is consuming its error budget faster than allowed (see thresholds below).

## Impact
Customers are affected now (page) or the monthly objective will be missed (ticket).

## SLO
- **Objective:** 99% of notifications are handed to the provider within 30 s (30-day window). **Owner:** `notifications`.
- **SLI:** `notifications_delivery_duration_seconds` histogram (event → provider accepted; bucket le=30) by channel. Recording rules `slo:sli_error:ratio_rate{5m,30m,1h,2h,6h,1d,3d,30d}{slo="notification_delivery"}`.
- **Alerting (multi-window, multi-burn-rate):** `page` when 1h & 5m burn > 14.4× or 6h & 30m > 6× the budget;
  `ticket` when 1d & 2h > 3× or 3d & 6h > 1×. Page routes to PagerDuty, ticket to Opsgenie/Slack.

## Diagnose
1. Notification delivery p95 by channel — one channel/provider (Resend, SendGrid, SES, Twilio, Vonage) or all?
2. Provider status pages and 429 rate limiting in logs; Kafka lag for the notifications consumer.

## Mitigate
- Fail over to the secondary provider for the channel (config); scale consumers; drain backlog.

## After the incident
- Link the incident ticket to this runbook and update the steps if they were wrong or incomplete.
- If the alert was noisy, tune the threshold in the rule file (then `make rules`) rather than silencing it permanently.
