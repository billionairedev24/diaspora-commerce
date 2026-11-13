# R2 to B2 copy: setup, restore and drill (backlog S380, audit F12)

What it does: CronJob `r2-backup` (namespace `ops`, 02:30 UTC, `infra/k8s/platform/lean/backup/r2-backup.sh`) copies the two Cloudflare R2
buckets to the environment's Backblaze B2 backup bucket, the same bucket and tooling as the database dumps ([backups-backblaze.md](backups-backblaze.md)).

| Bucket | In B2 | How |
|---|---|---|
| media (`afrotransact-<env>-media`, public images) | `r2/media/current/<key>`; a file overwritten in R2 moves its previous copy to `r2/media/changed/<stamp>/<key>` | `rclone copy --checksum --backup-dir`, then `rclone check --one-way --checksum` of every object; **nothing is deleted** |
| kyc (`afrotransact-<env>-kyc-evidence`, KYC, proofs, CVs, POs ...) | `r2/kyc/objects/<sha256(key)>.<sha256(content)[0:16]>.age` and `<sha256(key)>.path.age` (the key, encrypted); manifest `r2/kyc/_manifest/latest` | download, sha256, **age** to the public key, upload under an immutable name; no readable name or content in B2; each run verifies every manifest line has its object in B2 |

Files deleted or overwritten in R2 are **never** removed from B2 by this job; objects deleted from R2 stay in the manifest and are still verified. The private age key is
**offline only** (password manager and paper, never in the cluster, never in the repo, separate from the SOPS key): without it the KYC copy is unreadable by design.

## Setup (once per environment; the owner, with live accounts: UNVERIFIED, none of it was run)

1. **R2 tokens (two, read-only).** Cloudflare > R2 > Manage API tokens > Create: permission **Object Read only**, scoped to **one** bucket each: `afrotransact-<env>-media` and
   `afrotransact-<env>-kyc-evidence`. These are not the tokens media uses (those write). Keep key id and secret for the Secret below.
2. **B2.** Use the environment's backup bucket (`afrotransact-prod-backups`). Preferably create a second application key (Read and Write, this bucket only) for this job so it can be rotated apart
   from the database job; the same key also works. Keep the lifecycle rule of `backups-backblaze.md` **scoped to the prefix `postgres/`**.
3. **B2 Object Lock and versioning (recommended).** Object Lock can only be switched on for a bucket created with it enabled (check the current rule in the B2 console, UNVERIFIED). If the
   backup bucket was created without it, create a new bucket `afrotransact-<env>-r2-backups` with Object Lock, default retention **governance mode** (the keys cannot shorten it, the owner can) and
   use it for this job (`B2_BUCKET` of the Secret `r2-backup`). Set the default retention to cover what the data needs: media 30 days is enough (the live copy is rewritten, old versions are kept
   in `changed/`); **kyc: 7 years** for KYC evidence unless the compliance owner sets another period (state it in `docs/runbooks/` once decided). Immutable object names mean a locked KYC object is never
   rewritten. The lock stops even a stolen B2 key from deleting history; it also stops you deleting early (a wrong upload stays until the lock ends: cost only).
4. **Retention and cost.** B2 bills stored bytes (media is small; KYC grows with sellers). Nothing is pruned automatically except what the lock and a **prefix-scoped** lifecycle rule do. Review size monthly
   (`rclone size b2:<bucket>/r2`), and decide with the owner when `r2/media/changed/` older than 90 days may go (a lifecycle rule on that prefix only).
5. **Secret.** In `secrets.local.env`: `R2COPY_R2_MEDIA_KEY_ID`, `R2COPY_R2_MEDIA_SECRET`, `R2COPY_R2_KYC_KEY_ID`, `R2COPY_R2_KYC_SECRET` (and optionally `R2COPY_B2_KEY_ID`, `R2COPY_B2_APPLICATION_KEY`, `R2COPY_B2_BUCKET`;
   default: the database backup's B2 values). `infra/ovh-vps/secrets.sh` (PROFILE=prod) writes Secret `ops/r2-backup` (bucket names, the R2 account id, the public age key) into the SOPS output; apply with `infra/ops/secrets/apply.sh`.
6. **First run**, from the CronJob: `kubectl -n ops create job r2-backup-first --from=cronjob/r2-backup && kubectl -n ops logs -f job/r2-backup-first`. Expect the line
   `r2_backup_metrics ... failed=0`. In B2: `r2/media/current/...` and `r2/kyc/objects/*.age` and `r2/kyc/_manifest/latest`. Check no readable KYC file name is in the bucket.
7. Nothing in B2 is public. Confirm the bucket type is **private**.

## Alerts

`infra/observability/rules/r2-backup.yaml` (run `make observability-check`): **R2BackupStale** (page): no successful run for 26 hours, from kube-state-metrics' CronJob metrics and, when `BACKUP_PUSHGATEWAY_URL`
is set, the pushed last-success timestamp. **R2BackupFailed** (page): the last job failed. Causes appear as `FAILED ...` or `MISSING in B2 ...` lines in the job log; failed objects are retried the next night.
`HEALTHCHECK_URL` (dead-man's switch) works as for the database job.

## Restore one object

On an operator machine with `rclone` and `age` (never copy the private key onto a server for longer than the restore; delete the copy afterwards):

```sh
export B2_KEY_ID=... B2_APPLICATION_KEY=... B2_BUCKET=afrotransact-prod-backups B2_ENDPOINT=s3.<region>.backblazeb2.com   # read access is enough
infra/ops/backup/r2-restore.sh media product-image/<org>/2026/11/<file>.jpg ./restored.jpg
AGE_IDENTITY_FILE=/secure/offline/age.key infra/ops/backup/r2-restore.sh kyc kyc-document/<org>/2026/11/<file>.pdf ./restored.pdf   # checksum verified
AGE_IDENTITY_FILE=/secure/offline/age.key infra/ops/backup/r2-restore.sh kyc-list > keys.txt                                           # every original key
```

Put the object back into R2 with a write token (media's): `rclone copyto ./restored.jpg r2:<bucket>/<key>` (a target that contains `:` is written by rclone). For a whole bucket after a disaster: media is
`rclone copy b2:<bucket>/r2/media/current r2:<media bucket>`; KYC is a loop over `kyc-list` (restore each key, then copy it in). Re-point nothing: asset ids and keys in the `media` database stay valid because the key is restored unchanged.

## Restore drill (the number the story asks for)

Local rehearsal, against a MinIO pair (no accounts): `bash tools/ops/test/r2-backup.test.sh` (needs docker). It runs the real scripts: copy, delete both objects at the source, restore one media and one KYC
object, byte-compare and time them, check that an overwrite keeps the old version, that a corrupted or missing copy is detected, that no KYC name is readable in B2. Last local result: all checks passed, restores in
tens of milliseconds for 20 to 30 KB objects (meaningless for production sizes).

Real drill, **once before launch and then every quarter**, with the owner's age key: restore one media object and one KYC object from the live B2 bucket with the commands above into a scratch directory, compare with the
originals in R2 (`rclone md5sum`/`cmp`), and write the date, who and the minutes here:

| Date | Who | Media object | KYC object | Minutes | Notes |
|---|---|---|---|---|---|
| not yet run live | | | | | |

Story S380 is not Done until this row is filled.

## Not covered

R2 object metadata other than content type, custom domains and CORS (they are in `infra/cloudflare`), and the media database rows (they are in the Postgres dumps). A KYC object deleted from R2 for the retention policy stays in B2
until the B2 lock and your lifecycle decision release it: the privacy policy and DSAR process must say so (decision for the owner and the compliance lead).
