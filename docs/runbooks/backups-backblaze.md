# Off-vendor backups on Backblaze B2

**Owner decision:** the nightly database backups go to **Backblaze B2**, through its S3-compatible API, with `rclone`. **Cloudflare R2 is not
used for backups**: the backups must survive a problem with the Cloudflare account. One **private bucket per environment**
(`afrotransact-test-backups` for the one-server test box, **`afrotransact-trial-backups`** for the 8-day OVH trial, `afrotransact-prod-backups`), one **application key per bucket**, restricted to that bucket, Read and Write.
The files are age-encrypted before they leave the cluster, so Backblaze only ever holds ciphertext (the private age key is offline:
`restore-drill.md`).

> **What is and is not backed up.** Only the **Postgres dumps** (and their globals) reach B2. The **two Cloudflare R2 buckets (media and kyc-evidence) are NOT copied**: the nightly R2 to B2 copy is not built
> (backlog story S380), so a deleted or overwritten R2 object, KYC documents included, is not recoverable today. Valkey and the search index are rebuilt, not backed up.

> **UNVERIFIED.** Everything below that describes Backblaze (screen names, limits, lifecycle behaviour, what an S3 delete does, what a key
> can do) is written from memory and has not been checked against Backblaze's current documentation or a live account. The repository's
> scripts were run only against a local rclone remote. The first real run (section 5) is the check; correct this page with what you see.

| Item | Test (OVH server) | Trial and production (OVH node + Layerbase) |
|---|---|---|
| Bucket | `afrotransact-test-backups` | trial `afrotransact-trial-backups`, production `afrotransact-prod-backups` (set `BACKUP_B2_BUCKET`; the metric label `env` follows `GRAFANA_ENV`) |
| What runs | `infra/ovh-vps/backup.sh` (cron on the server, also dumps the generated secrets, passphrase-encrypted) | CronJob `pg-backup` in namespace `ops`, 03:15 UTC (`infra/k8s/platform/lean/backup`, age-encrypted) |
| Where the key lives | GitHub Environment `ovh-vps`: secrets `B2_KEY_ID`, `B2_APPLICATION_KEY`, variables `B2_BUCKET`, `B2_ENDPOINT` | SOPS Secret `ops/pg-backup` (`infra/ovh-vps/secrets.sh` writes it from `secrets.local.env`) |

## 1. Names and settings

| Name | Where | Meaning |
|---|---|---|
| `B2_KEY_ID`, `B2_APPLICATION_KEY` | Secret `pg-backup` (prod) / GitHub secrets (test) | the bucket-scoped application key (the key id is the "keyID", the secret is the "applicationKey") |
| `B2_BUCKET` | same | the bucket name |
| `B2_ENDPOINT` | same | the bucket's S3 endpoint from the console, `s3.<region>.backblazeb2.com`. **No region is hard-coded anywhere**; the rclone region is the `<region>` part of this value (or `B2_REGION` if you set it) |
| `RETENTION_DAYS` | `BACKUP_RETENTION_DAYS` in `secrets.local.env` / `RETENTION_DAYS` in the test `config.env` | days of backups kept, default **14**, accepted 3 to 365; the owner may set **7** |
| `RETENTION_MODE` | `BACKUP_RETENTION_MODE` | `sweep` (default, the job deletes old objects), `lifecycle` (the bucket rule does it), `off` |
| `PUSHGATEWAY_URL` | `BACKUP_PUSHGATEWAY_URL` | optional; the size metrics are pushed there as well as logged |

In `secrets.local.env` (production, `infra/ovh-vps/secrets.local.env.example`) the same values are `BACKUP_B2_KEY_ID`,
`BACKUP_B2_APPLICATION_KEY`, `BACKUP_B2_BUCKET`, `BACKUP_B2_ENDPOINT`, `BACKUP_RETENTION_DAYS`. `infra/ops/secrets/apply.sh` refuses the Secret when
the key, bucket or endpoint is empty. Commit only the SOPS-encrypted file, never `secrets.local.env`.

The rclone remote the scripts build (you only need it by hand for the tests below):

```ini
# ~/.config/rclone/rclone.conf  (or the RCLONE_CONFIG_BACKUP_* environment variables, which the scripts export)
[backup]
type = s3
provider = Other
endpoint = https://s3.<region>.backblazeb2.com
region = <region>
access_key_id = <B2_KEY_ID>
secret_access_key = <B2_APPLICATION_KEY>
```

`provider = Other` because rclone has no dedicated "Backblaze" S3 provider in the list I remember (UNVERIFIED; rclone also has a native `b2`
backend, not used here so that one code path serves every S3 vendor). Every call passes `--s3-no-check-bucket` so a bucket-scoped key
never needs permission to create or list buckets.

## 2. Create the bucket (click by click)

Screen names are from memory (UNVERIFIED); the idea is what matters.

1. Sign in to the Backblaze account of the owner (the account exists). **Account > My Settings**: turn on two-factor authentication if it is not on.
2. **B2 Cloud Storage > Buckets > Create a Bucket.**
   - Bucket Unique Name: `afrotransact-prod-backups` (then repeat for `afrotransact-test-backups`).
   - Files in Bucket are: **Private**.
   - Default Encryption: either. The files are already age-encrypted; leave it **Disabled** to keep things simple.
   - Object Lock: **Disabled** for now (section 4 explains the trade-off; it can only be decided at creation in some plans, UNVERIFIED).
3. Open the bucket and note its **Endpoint** (`s3.<region>.backblazeb2.com`) and the region in it. This is `B2_ENDPOINT`. Different buckets can
   be in different regions; each environment uses its own endpoint value.
4. **Lifecycle Settings** on the bucket: choose **Use custom lifecycle rules** and add the rule of section 3 for your retention mode.
5. **Account > Caps & Alerts**: set a daily storage/transaction cap and an email alert so a runaway job cannot create a bill (UNVERIFIED: names and
   whether the free tier needs a payment card).

### The application key, scoped to the bucket

1. **Application Keys > Add a New Application Key.**
2. Name: `afrotransact-prod-backups-rw` (or `-test-`). **Allow access to Bucket(s): select only that bucket.** **Type of Access: Read and Write.**
   Leave "Allow List All Bucket Names" off (rclone is told not to check the bucket) and the file-name prefix and duration empty.
3. **Create New Key.** Copy the **keyID** and the **applicationKey** immediately: the secret is shown once. Put them in the password manager and
   into the SOPS file (`BACKUP_B2_KEY_ID`, `BACKUP_B2_APPLICATION_KEY`) or the GitHub Environment (`gh secret set B2_APPLICATION_KEY --env ovh-vps`).
4. A "Read and Write" key is assumed to include deleting file versions, which the retention sweep needs (UNVERIFIED: if the sweep logs
   `retention sweep failed` with an access error, the key lacks `deleteFiles`; recreate it with the delete capability).

## 3. Retention and the lifecycle rule

`RETENTION_DAYS` is the number of days kept. What happens to the bytes depends on how B2 treats a delete (all UNVERIFIED, from memory):

- A B2 bucket **keeps every version** of a file name. A delete through the S3 API (what `rclone delete` does) does not remove the bytes: it adds a
  **hide marker**, the old version stays, and **still counts toward storage** until a lifecycle rule or a delete-by-version removes it. Nightly
  objects here have unique timestamped names, so nothing is ever overwritten, but without a rule `rclone delete --min-age` would only ever hide
  them and the bucket would grow without limit.
- `rclone size` and `rclone ls` over the S3 API list the **current** versions, so the bucket total in the metrics does **not** include hidden
  versions that B2 still holds. Look at the bucket's size in the B2 console now and then.

Pick **one** mode (the default is the first):

| `RETENTION_MODE` | Who ages the files | Lifecycle rule to set on the bucket (Lifecycle Settings > custom rule) |
|---|---|---|
| `sweep` (default) | the job: after a fully successful run it runs `rclone delete postgres/ --min-age <RETENTION_DAYS>d --max-delete 400`, which hides old objects | File Path (prefix) empty or `postgres/`, **Days Before Hiding: leave empty**, **Days Before Deleting: 1**. This is the console's "Keep only the last version of the file" setting: a hidden file is physically deleted a day later |
| `lifecycle` | the bucket: set `RETENTION_MODE=lifecycle` so the job deletes nothing | Prefix `postgres/`, **Days Before Hiding = RETENTION_DAYS**, **Days Before Deleting: 1**. If you change `RETENTION_DAYS`, change the rule too (the job cannot) |
| `off` | nobody | none (the bucket grows; only for a drill) |

Safety built into the job: a sweep never runs after a night with a failed database (a broken run must not also eat the history); `RETENTION_DAYS`
below 3 or above 365 aborts the run; one sweep deletes at most `RETENTION_MAX_DELETE` (default 400) objects; only the `postgres/` prefix is touched.
Lowering 14 to 7 hides about 7 days of objects (19 databases plus globals, about 140 objects) in the next run, within the cap.
The job also runs `rclone cleanup` once (best effort) to drop abandoned multipart uploads (UNVERIFIED whether B2 supports it through S3).
The test server's `backup.sh` follows the same rules for its prefixes `postgres/` and `secrets/`; for it set the rule's prefix to empty.

## 4. Object Lock (only if the owner enables it)

Object Lock makes versions immutable for a retention period, which protects against a stolen key deleting the backups. Consequences (UNVERIFIED):

- Choose the lock period **at most `RETENTION_DAYS`** (7 or 14). A locked version cannot be deleted before the lock ends, so the lifecycle
  rule's delete (and the sweep's effect) only takes hold afterwards; the bucket then stores about `RETENTION_DAYS + lock period` of data.
- A hide marker can still be added to a locked object, so the sweep "succeeds" while the bytes stay; storage and the 8 GB alert reflect that late.
- The application key needs the permission to write retention if the lock is applied per object; a bucket default lock needs none (UNVERIFIED).
  Prefer a **bucket default** lock so the job's code does not change.
- Compliance-mode locks cannot be removed even by the owner: use **governance** mode, or do not enable the lock at all in the first months.
- Do not enable it before the first restore drill has passed.

## 5. Test the key from your laptop (before the first run)

```bash
export RCLONE_CONFIG_BACKUP_TYPE=s3 RCLONE_CONFIG_BACKUP_PROVIDER=Other \
       RCLONE_CONFIG_BACKUP_ENDPOINT=https://s3.<region>.backblazeb2.com RCLONE_CONFIG_BACKUP_REGION=<region> \
       RCLONE_CONFIG_BACKUP_ACCESS_KEY_ID=<keyID> RCLONE_CONFIG_BACKUP_SECRET_ACCESS_KEY=<applicationKey>
B=backup:afrotransact-prod-backups
rclone lsd "$B" --s3-no-check-bucket                       # may be refused for a bucket-scoped key: that is fine, use lsf below
echo "hello $(date -u +%FT%TZ)" | rclone rcat "$B/_test/hello.txt" --s3-no-check-bucket   # write
rclone lsf "$B/_test" --s3-no-check-bucket                  # list
rclone cat "$B/_test/hello.txt" --s3-no-check-bucket        # read
rclone delete "$B/_test" --s3-no-check-bucket               # delete (hides it; the lifecycle rule removes it a day later)
```

Also prove the scoping: with the production key, `rclone lsf backup:afrotransact-test-backups --s3-no-check-bucket` must be **refused**.
Then run the real job once: `kubectl -n ops create job --from=cronjob/pg-backup first-backup && kubectl -n ops logs -f job/first-backup`. Expected:
`backed up <db>` for each database, one `backup_metrics ... run_bytes=... bucket_bytes=...` line and `backup complete: 19 databases`.
In the B2 console the bucket shows the objects under `postgres/<db>/<stamp>.dump.age`. (Test box: `sudo bash infra/ovh-vps/backup.sh run`.)

## 6. Restore one database inside the 4-hour RTO

Targets: RPO 24 h (nightly), **RTO 4 h** for the production data. Do this from an operator machine that has `rclone`, `age`, `psql` and `pg_restore`
(version 17); the age **private** key never goes to the cluster.

1. **Get the credentials** (about 10 minutes): the B2 key and endpoint from the SOPS Secret (`sops -d <file> | grep B2_`) or the password manager. For a drill you may create a
   temporary **Read Only** key for the bucket and delete it afterwards.
2. **Set up the shell:**
   ```bash
   export B2_KEY_ID=... B2_APPLICATION_KEY=... B2_BUCKET=afrotransact-prod-backups B2_ENDPOINT=s3.<region>.backblazeb2.com
   export AGE_IDENTITY=~/age-backup-key.txt
   export PGHOST=<Layerbase or scratch host> PGUSER=<admin> PGPASSWORD=... PGSSLMODE=require
   ```
   (`pg-restore.sh` builds the rclone remote from `B2_*` exactly as the job does.)
3. **Find the object:** `infra/ops/backup/pg-restore.sh list orders | tail -5` (one per night, UTC stamp in the name).
4. **Restore into a new, empty database name first** (never over the damaged one):
   `infra/ops/backup/pg-restore.sh restore orders latest orders_restored` (or a stamp instead of `latest`). It downloads, decrypts, verifies with
   `pg_restore --list`, creates the role and extension if missing, restores with `--exit-on-error` and prints the table count.
5. **Verify** (row counts against the day before; one service boots against it), then switch the service to it (`restore-drill.md`, "Restoring production after an incident").
6. **Time budget** (to be replaced by the first drill's numbers): decide and get the key 15 min, download and decrypt a dump of a few hundred MB a few minutes
   (B2 download speed UNVERIFIED), `pg_restore` of a database of that size 5 to 30 min, verification and switch-over 30 min. The whole database set is
   not needed for one incident; the 19 databases in parallel are possible but `identity`, `orders`, `payments` first.
7. Roles are not in the dump with passwords: `pg-restore.sh globals latest | psql` only if roles are missing, then reset passwords and update the Secrets.

Test server: `sudo bash infra/ovh-vps/backup.sh list` and `backup.sh restore <db> <object>` (passphrase-encrypted).

## 7. Rotate the key

Rotate when someone who had it leaves, when it may have leaked, and at least once a year. B2 keys do not expire unless a duration was set (UNVERIFIED).

1. B2 console > Application Keys > **Add a New Application Key** for the **same** bucket, Read and Write, the same settings as in section 2. Copy keyID and applicationKey.
2. Replace `BACKUP_B2_KEY_ID` and `BACKUP_B2_APPLICATION_KEY` in `secrets.local.env`, re-encrypt with SOPS, commit the encrypted file, then
   `infra/ops/secrets/apply.sh` (production). Test box: `gh secret set B2_KEY_ID --env ovh-vps`, `gh secret set B2_APPLICATION_KEY --env ovh-vps`, then run the `backup` action.
3. Run one backup now (`kubectl -n ops create job --from=cronjob/pg-backup rotate-check`) and confirm `backup complete` and the new `backup_metrics` line.
4. **Only then delete the old key** in the B2 console (Application Keys > Delete). If anything fails, the old key still works: fix and retry.
5. Write the date in the result log of `restore-drill.md`. The B2 key is not part of the offline escrow (it can always be re-created); the **age private key** is, and it
   is never rotated casually: a new age key encrypts only new backups, keep the old private key until the last backup made with it has expired.

## Alerts

Backup alerts reach `hello@afrotransact.com` and `so.oluwfemi@gmail.com` (owner: confirm the second spelling) through the routing in
`observability-grafana-cloud.md` section 8; `page` backup alerts are mailed from the trial too.

Rules: `infra/observability/rules/backup.yaml` (Prometheus rules, loaded as PrometheusRule CRs by `make rules`) and, for the log path without a Pushgateway,
`infra/observability/loki-rules/backup.yaml` (LogQL, UNVERIFIED). Each run logs exactly one line:

```
backup_metrics env=prod stamp=20261112T031512Z run_bytes=412345678 bucket_bytes=5530000000 databases_ok=19 failed=0 retention_days=14 retention_mode=sweep
```

| Alert | Fires when | What to do |
|---|---|---|
| `BackupStale` (page) | no successful run for 26 hours (CronJob last success from kube-state-metrics, a CronJob that never succeeded, or the pushed `afrotransact_backup_last_success_timestamp_seconds`) | `kubectl -n ops get jobs`, `kubectl -n ops logs job/<latest>`: a `FAILED` line names the database or upload; an expired or deleted key shows as an access error (section 7); Layerbase unreachable shows as `pg_dump` failures. Run the job by hand once fixed |
| `BackupSizeHigh` (ticket) | one night's compressed, encrypted dump set is above **600 MB** | which database grew (`run_bytes` per object in the bucket); purge data, lower `RETENTION_DAYS` to 7, or move to a paid plan. 600 MB x 14 days is about 8.4 GB, near the assumed 10 GB free tier |
| `BackupBucketSizeHigh` (ticket) | the bucket total is above **8 GB** | check hidden versions in the console and the lifecycle rule (section 3); then as above |

`run_bytes` and `bucket_bytes` reach Prometheus only with `PUSHGATEWAY_URL`; without it the numbers are in the log (Alloy to Loki, `{namespace="ops", app="pg-backup"}`),
and `BackupStale` still works from kube-state-metrics. Pushing needs a Pushgateway in the cluster, which the lean platform does not install today: install one
(`prometheus-community/prometheus-pushgateway` in `observability`, scraped with `honor_labels: true`) or use the Loki rules.
