# InventoryReconciliationDrift

**Severity:** `ticket` · **Defined in:** `infra/observability/rules/inventory.yaml` · **Dashboard:** Grafana → AfroTransact / Inventory reconciliation drift

## What it means
More than 10 units of unexplained difference between the inventory ledger and physical/channel counts at a fulfilment centre for 30 minutes.

## Impact
Overselling (positive drift) or lost sales (negative drift).

## Diagnose
1. Dashboard → Top drifting SKUs; ledger entries for those SKUs (`inventory` DB) vs latest cycle count.
2. Lost or duplicated events? Check Kafka DLQ and consumer lag for inventory topics.
3. Expired reservations not released (reservations panel).

## Mitigate
- Post an adjustment through the inventory admin API (audited) after confirming the physical count; fix the root cause (event loss, reservation TTL job).

## After the incident
- Link the incident ticket to this runbook and update the steps if they were wrong or incomplete.
- If the alert was noisy, tune the threshold in the rule file (then `make rules`) rather than silencing it permanently.
