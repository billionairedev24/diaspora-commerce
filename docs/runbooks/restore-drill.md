# Restore drill: backups, secrets and objectives

**Targets (owner to accept): RPO 24 hours, RTO 4 hours** for the production PostgreSQL data on Layerbase. They are targets, not
measurements: the first drill produces the real numbers and they go in the table at the end of this page. A backup that has never been
restored is not a backup: **the first drill is a launch blocker** (`production-readiness.md` section 9) and it is repeated monthly.

## What is backed up, where, and what is not

| Data | How | Where | Retention |
|---|---|---|---|
| PostgreSQL, all 19 databases (`identity catalog offers inventory orders payments seller fulfilment bookings trustops taxreturns content ai cart geo media notifications temporal temporal_visibility`) | CronJob `pg-backup` in namespace `ops` at **03:15 UTC** nightly: `pg_dump -Fc` per database, checked with `pg_restore --list`, encrypted with **age** (the cluster holds only the public key), uploaded with rclone | **Backblaze B2** (S3 API, rclone), one private bucket per environment (`afrotransact-prod-backups`, `afrotransact-test-backups`), one bucket-scoped key; a vendor other than Layerbase, OVH and Cloudflare (**never R2**). Setup, key, lifecycle rule: `backups-backblaze.md` | **`RETENTION_DAYS`, default 14** (owner may set 7); the job's sweep plus the bucket lifecycle rule "delete hidden versions after 1 day" (B2 versions every file) |
| Roles and grants | `pg_dumpall --globals-only --no-role-passwords` in the same job (a warning if Layerbase does not allow it) | same bucket, `postgres/_globals/` | `RETENTION_DAYS` |
| **Secrets that cannot be re-created**: `IDENTITY_DATA_KEY`, JWT signing key `SIGNING_KEY_PEM`, `HANDOVER_CODE_KEY`, `PAY_LINK_KEY`, `FIELD_ENCRYPTION_KEY`, `LINK_SIGNING_KEY`, `*_CLIENT_SECRET`, database role passwords | `infra/ops/secrets/escrow.sh create`: age bundle of `STATE_DIR` for two recipients, **plus** the SOPS-encrypted Secret files | **offline**, in two places (password manager attachment + a physical copy), **separately from the database backups** | until rotated |
| Temporal | state is in the two Postgres databases above | covered | |
| Kafka (10 GiB PVC) | not backed up: services publish through an outbox; replay `docs/runbooks/kafka-replay.md`, `tools/ops/outbox-republish.sql` | | |
| Valkey (carts, sessions, live feed) | not backed up: a cache; carts also live in the `cart` database; losing it signs everyone out | | |
| Meilisearch | not backed up: rebuild with `kubectl -n afrotransact-prod create job reindex-1 --from=cronjob/indexer-reindex` | | |
| Product images and private files (both Cloudflare R2 buckets) | not covered by the database job. To do: nightly `rclone sync` of both buckets to the OVH `media` / `kyc-evidence` buckets (a second vendor; the sources are not stored elsewhere). R2 object retention/versioning is UNVERIFIED | | |

**Why two layers.** Layerbase says it takes hourly backups (UNVERIFIED, PITR not mentioned): use them for a quick rollback if they exist,
but never as the only copy. The age private key of the off-vendor backups must exist only **offline** (never in the cluster, never in the
repository). Lose `IDENTITY_DATA_KEY` and a perfect database restore leaves identity's encrypted fields unreadable.

## Setup checklist (once)

1. `age-keygen -o ~/age-backup-key.txt` on an offline-capable machine; the **public** key (`age1...`) goes into `BACKUP_AGE_RECIPIENT`
   of `secrets.local.env`; keep the private key file in two places. Do the same for the SOPS and escrow keys (separate keys are better).
2. Create the Backblaze B2 bucket and an application key restricted to it (Read and Write), set the bucket's lifecycle rule, and put
   `BACKUP_B2_KEY_ID`, `BACKUP_B2_APPLICATION_KEY`, `BACKUP_B2_BUCKET`, `BACKUP_B2_ENDPOINT` (and `BACKUP_RETENTION_DAYS`) in `secrets.local.env`: click by click in
   `backups-backblaze.md` (sections 2 and 3). Not Cloudflare R2, not Layerbase, not OVH.
3. A dead-man's-switch URL (`BACKUP_HEALTHCHECK_URL`, healthchecks.io style) with a 26-hour grace: no success ping means someone is told. Free-tier limits UNVERIFIED.
4. Apply the Secret `pg-backup` (infra/ops/secrets), sync `lean-prod`, then run one now:
   `kubectl -n ops create job --from=cronjob/pg-backup manual-1 && kubectl -n ops logs -f job/manual-1`
   Expected: `backed up <db>` x 19, one `backup_metrics ... run_bytes=... bucket_bytes=...` line (the size alerts in `infra/observability/rules/backup.yaml` read it) and
   `backup complete: 19 databases`. Any `FAILED` line makes the job exit 1.
5. `escrow.sh create` and store the bundle; test with `escrow.sh restore` into a temp directory.

## The drill (do it in the trial, before launch, then monthly)

Use a **scratch** server: a second Layerbase database/server (a trial plan is enough), or a local `postgres:17` with PostGIS
(`postgis/postgis:17-3.5`). Never restore over production to "test".

```bash
export B2_KEY_ID=<keyID> B2_APPLICATION_KEY=<applicationKey> B2_BUCKET=afrotransact-prod-backups B2_ENDPOINT=s3.<region>.backblazeb2.com
export AGE_IDENTITY=~/age-backup-key.txt
export PGHOST=<scratch host> PGUSER=<scratch admin> PGPASSWORD=... PGSSLMODE=require

date -u +%T                                                   # START TIME
infra/ops/backup/pg-restore.sh list orders | tail -3          # newest objects, 1 per night
for db in identity orders catalog payments fulfilment geo bookings temporal; do
  infra/ops/backup/pg-restore.sh restore "$db" latest          # creates <db>_app, the extension (postgis / btree_gist) and restores
done
date -u +%T                                                   # END TIME of the database restore
```

`pg-restore.sh` decrypts with the age key, verifies the dump with `pg_restore --list`, creates the role and database when missing,
creates `postgis` (geo, fulfilment) or `btree_gist` (bookings) as admin, skips the extension objects inside the dump, restores with
`--no-owner --role=<db>_app --exit-on-error` and prints the table count. It refuses a non-empty database.

Then prove the data is usable, not just present:

1. Counts: `psql -d identity -c "select count(*) from users"` (adapt to the schema) against production's numbers from the day before.
2. Boot two services against the scratch server with the **escrowed** secrets (`escrow.sh restore`): `orders` and `identity` (Docker or a scratch
   namespace): Flyway reports **no pending or failed migration**, an existing user can sign in (this proves `IDENTITY_DATA_KEY` and the signing key
   from the escrow work), an order lookup returns data.
3. `fulfilment` and `geo`: `select postgis_version()` and one nearest-driver query succeed.
4. Rebuild what is not backed up: `reindex-1` job and a search query; check that the outbox republish tooling is understood.
5. Also drill once, separately: a **full cluster rebuild** from tofu + SOPS secrets + Argo in an empty OVH project (target 2 to 4 hours, not proven), and
   Layerbase's own restore/PITR if they offer it (ask how, section 14 of `ovh-production.md`).

## Restoring production after an incident

1. Stop writes if the data is damaged (maintenance mode, `docs/runbooks/maintenance-mode.md`; scale the affected services to 0).
2. Decide the point: the nightly dump (up to 24 h of loss, RPO) or a Layerbase restore if offered.
3. Restore into a **new empty database name first** (`pg-restore.sh restore orders latest orders_restored`), verify, then point the service at it by
   changing `DB_URL` in its Secret and restarting, or rename databases while the service is down. Never `DROP` the damaged database until the restored one is verified.
4. `pg-restore.sh globals latest | psql` only if roles are missing (it contains no passwords: reset them and update the Secrets).
5. Rebuild search, replay lost events from the outbox, and write the post-incident note with the real RPO and RTO.

The key of the bucket and its rotation, the lifecycle rule and the time budget for restoring one database inside the 4-hour RTO are in
`backups-backblaze.md` (sections 3, 6 and 7). Record the B2 download time of the largest dump in the result log below.

## Result log (fill in at every drill)

| Date | Who | Backup age restored | Dump to restored, minutes | Cluster rebuild, minutes | Problems | RPO met (24 h)? | RTO met (4 h)? |
|---|---|---|---|---|---|---|---|
| (first drill, before launch) | | | | | | | |
