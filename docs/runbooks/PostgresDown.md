# PostgresDown

**Severity:** `page` · **Defined in:** `infra/observability/rules/postgres.yaml` · **Dashboard:** Grafana → AfroTransact / —

## What it means
postgres-exporter cannot connect to the database server.

## Impact
Every service using that server fails.

## Diagnose
1. Cloud console status (RDS / Cloud SQL / Azure Flexible Server): failover, maintenance, storage full.
2. Network policy / security group changes; exporter credentials rotated?

## Mitigate
- Follow the cloud provider failover procedure; if only the exporter is broken, fix its secret (`postgres-exporter` ExternalSecret).

## After the incident
- Link the incident ticket to this runbook and update the steps if they were wrong or incomplete.
- If the alert was noisy, tune the threshold in the rule file (then `make rules`) rather than silencing it permanently.
