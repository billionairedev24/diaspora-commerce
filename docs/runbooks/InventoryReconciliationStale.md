# InventoryReconciliationStale

**Severity:** `ticket` · **Defined in:** `infra/observability/rules/inventory.yaml` · **Dashboard:** Grafana → AfroTransact / Inventory reconciliation drift

## What it means
The reconciliation job has not completed for more than 2 hours.

## Impact
Drift goes undetected.

## Diagnose
1. Inventory service logs for the scheduler; Temporal UI for a stuck workflow (if run as a workflow).

## Mitigate
- Restart/trigger the job; fix the failing step.

## After the incident
- Link the incident ticket to this runbook and update the steps if they were wrong or incomplete.
- If the alert was noisy, tune the threshold in the rule file (then `make rules`) rather than silencing it permanently.
