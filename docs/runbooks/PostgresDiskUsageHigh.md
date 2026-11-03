# PostgresDiskUsageHigh

**Alerts:** `PostgresDiskUsageHigh` (ticket, over 70%), `PostgresDiskUsageCritical` (page, over 90%), `PostgresDiskFillingFast` (ticket, projected to pass the limit within 7 days) · **Defined in:** `infra/observability/rules/postgres.yaml` · **Metric:** `pg_database_size_bytes` (postgres-exporter)

## What it means
The databases on the server are using most of the plan's storage. The limit in the rules is a number (`26843545600` = 25 GiB, Layerbase Dedicated 4 GB): when the plan changes, edit it in the three rules and run `make rules`. The metric counts table and index files only, not WAL or temporary files, so the real disk use is a little higher.

## Impact
At the limit, writes fail (or the provider bills overage: ask the provider which, in writing). Orders, payments and sign-ins stop. Reads keep working.

## Diagnose
1. Which database is big (connect as the admin user):
   ```sql
   select datname, pg_size_pretty(pg_database_size(datname)) from pg_database order by pg_database_size(datname) desc;
   ```
2. In that database, which tables and indexes (include TOAST):
   ```sql
   select relname, pg_size_pretty(pg_total_relation_size(oid)) as total, pg_size_pretty(pg_relation_size(oid)) as table_only
   from pg_class where relkind = 'r' and relnamespace = 'public'::regnamespace
   order by pg_total_relation_size(oid) desc limit 15;
   ```
3. Dead space (rows deleted but the file not shrunk):
   ```sql
   select relname, n_live_tup, n_dead_tup, last_autovacuum from pg_stat_user_tables order by n_dead_tup desc limit 10;
   ```
4. Usual suspects: `platform_inbox` and `platform_outbox` in every service database (event bookkeeping), notification and audit tables, uploaded-file metadata, AI usage logs.

## Mitigate
1. **Event bookkeeping.** The retention job in each service already deletes inbox rows older than 90 days and published outbox rows older than 14 days. If it is off (`at.events.retention.enabled=false`) or behind, run it by hand per database, counts first:
   ```bash
   psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f tools/ops/platform-purge.sql                      # counts only
   psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -v days=90 -v execute=yes -f tools/ops/platform-purge.sql
   ```
   Do not go below 35 days for the inbox: a replayed or re-published event older than the retention is processed again (see [kafka-replay.md](kafka-replay.md)).
2. **Other tables.** Delete only what the business allows. Never delete orders, payments, ledger, payout, tax or consent records to make room: they are kept for financial, tax and legal reasons. Notifications and logs: agree the retention first.
3. **Give the space back.** `DELETE` only makes space reusable inside Postgres; the file does not shrink. Autovacuum does the reuse. To shrink a table that is mostly dead space:
   ```sql
   vacuum (verbose, analyze) <table>;      -- safe, no lock, does not shrink the file
   vacuum full <table>;                    -- shrinks the file, LOCKS the table and needs free space about the table size: off-peak only, one table at a time
   ```
   `pg_repack` does the same without the long lock if the provider offers the extension.
4. **Upgrade the plan** (Layerbase: Dedicated 8 GB has 50 GB) when the data is real and growing; then update the limit in the rules.
5. A throwaway test (`DB_MODE=cluster`): destroy and re-provision instead of cleaning.

## After the incident
- Add the table that grew to a retention rule if it is safe to expire, and note what must never be purged.
- Link the incident ticket to this runbook and update the steps if they were wrong or incomplete.
- If the alert was noisy, tune the threshold in the rule file (then `make rules`) rather than silencing it permanently.

## Not covered
The alerts need postgres-exporter scraped by Prometheus. The Kubernetes values (`infra/k8s/platform/values/prometheus-postgres-exporter.yaml`) wire it for the managed clouds; the OVH single-server test does not run Prometheus unless you add it, so there check by hand with the queries above.
