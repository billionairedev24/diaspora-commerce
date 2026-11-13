# Postgres TLS: switch to verify-full (audit F11)

`sslmode=require` encrypts the link to Layerbase but does not check the server certificate or host name, so a network
attacker between the node and Layerbase can impersonate it. `verify-full` checks both. Default today stays `require`
(the Layerbase CA is not yet in hand); `infra/ovh-vps/secrets.sh` prints a warning in prod while it is `require`.

## What is wired

| Piece | Where |
|---|---|
| Mode and CA input | `EXTERNAL_PG_SSLMODE` (`require` default, `verify-ca`, `verify-full`), `EXTERNAL_PG_CA_FILE` (PEM on the operator machine), `EXTERNAL_PG_SSLROOTCERT` (path inside pods, default `/etc/pg-ca/ca.crt`, must end in `ca.crt`) in `infra/ovh-vps/config.env` |
| URLs | Java: `jdbc:postgresql://host:5432/db?sslmode=verify-full&sslrootcert=/etc/pg-ca/ca.crt`; Go (pgx): `postgres://role:pw@host:5432/db?sslmode=verify-full&sslrootcert=/etc/pg-ca/ca.crt`; both come from `secrets.sh` into each `<service>-secrets` `DB_URL` |
| CA Secret | `secrets.sh services` writes Secret `pg-ca` (key `ca.crt`) in the service namespace |
| Mount | at-service library chart: `postgresCA.enabled: true` mounts `pg-ca` read-only at `postgresCA.mountPath` in every Java and Go service (`infra/argocd/envs/prod/platform-values.yaml`) |
| Guard | `EXTERNAL_PG_REQUIRE_VERIFY=true` makes `secrets.sh` refuse any prod mode other than `verify-full`; verify modes without a readable PEM file are refused |
| Operator tools | `secrets.sh` (psql) and `backup.sh` (pg_dump) pass `PGSSLROOTCERT` from `EXTERNAL_PG_CA_FILE` |

Tests: `bash tools/ovh/test/pg-tls.test.sh` (URL rendering for both runtimes, Secret, refusals, and helm template of the mount).
Run it in CI next to the other `tools/ovh/test` scripts (needs bash 4+, kubectl for `--dry-run=client`, helm optional).

## Not covered by the chart (do these by hand)

- **Temporal** (`infra/k8s/platform/lean/temporal.yaml`) connects to Postgres itself: set `SQL_TLS=true`, `SQL_TLS_ENABLED=true`,
  `SQL_HOST_VERIFICATION=true`, `SQL_CA=<mounted path>` and mount `pg-ca` in the `temporal` namespace (copy the Secret there).
- **Backup CronJob** (`infra/k8s/platform/lean/backup`): add `PGSSLROOTCERT` to the `pg-backup` Secret, mount `pg-ca` in `ops`, set `PGSSLMODE=verify-full`.
- **Anything using libpq tools** (`pg-restore.sh`, `postgres-bootstrap.sh`): export `PGSSLMODE=verify-full PGSSLROOTCERT=<file>`.

## Switch to verify-full (when Layerbase has given the CA)

1. Get from Layerbase: the CA certificate (PEM, whole chain if intermediate) and confirm the server certificate's SAN contains the
   host name you connect to (`EXTERNAL_PG_HOST`). `verify-full` fails if you connect by IP or an alias not in the SAN.
2. Test from your machine first:
   `psql "host=<host> user=<admin> dbname=postgres sslmode=verify-full sslrootcert=/path/ca.pem" -c 'select ssl from pg_stat_ssl where pid = pg_backend_pid()'` returns `t`.
   Negative test: the same with a wrong CA file must fail with `certificate verify failed`.
3. Test from the cluster, in uat first: `kubectl run -it --rm psql --image=postgres:17-alpine -- sh`, mount or paste the CA, same command.
4. Set in `config.env`: `EXTERNAL_PG_SSLMODE=verify-full`, `EXTERNAL_PG_CA_FILE=/path/ca.pem`, `EXTERNAL_PG_REQUIRE_VERIFY=true`.
5. Re-render the secrets (`PROFILE=prod SECRETS_OUT=./out ... ./secrets.sh services`), encrypt with SOPS, apply (`infra/ops/secrets/apply.sh`).
   Check: `grep -c sslrootcert` of a decoded `DB_URL`, and that `pg-ca` exists in `afrotransact-prod`.
6. Set `postgresCA.enabled: true` in `infra/argocd/envs/prod/platform-values.yaml` and sync. **The Secret must exist before the
   sync**, otherwise pods stay in `ContainerCreating`. Restart services (the URL is read at start).
7. Verify: services Ready; `select ssl, version from pg_stat_ssl s join pg_stat_activity a using (pid) where a.usename like '%_app'` all `t`;
   no `certificate verify failed` in logs.
8. Rollback: set `EXTERNAL_PG_SSLMODE=require`, re-apply the Secrets, restart. `postgresCA.enabled` can stay on.
9. Rotate: when the provider rotates its CA, ship a bundle containing old and new CA first, re-apply `pg-ca`, restart, then drop the old.

UNVERIFIED: that Layerbase serves a certificate with a SAN for the host name, that pgjdbc and pgx accept its chain, and whether it
requires client certificates (mTLS; not wired: it would need a second Secret and `sslcert`/`sslkey` parameters).
