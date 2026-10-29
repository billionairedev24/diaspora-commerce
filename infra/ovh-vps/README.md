# AfroTransact on one OVHcloud server (k3s)

A **test environment** on a single OVHcloud Public Cloud instance: k3s, every backend service, Kafka, Temporal, OpenFGA,
Valkey, Meilisearch and (optionally) Postgres on one machine, created and deployed by one manual GitHub workflow.

> **Status: written, never run.** Everything here was checked offline only (`bash -n`, a stubbed run of `secrets.sh`, YAML
> parsing). The first run will need fixes; each failure names its service (`kubectl -n afrotransact-dev logs deploy/<service>`).
> Prices are not verified: look up the instance flavor in the OVHcloud console before you order.

## Is one server safe for production?

**No, not by itself.** One machine is one failure domain: a reboot, a kernel update, a full disk or a bad deploy takes the
whole site down, k3s has a single control plane, and there is no rolling zero-downtime upgrade of the node. It is fine for
a test, a demo or a very early invite-only beta *if* all of these hold:

1. **The database is external and managed** (below), with backups you have restored at least once.
2. Everything left on the server can be rebuilt: Valkey (cache, carts), Meilisearch (re-indexed from the catalog), and
   Kafka (services publish through an outbox, so events are re-published). You accept losing in-flight work on a failure.
3. You can rebuild the server and redeploy in under an hour (this workflow), and you have tested it.
4. You have uptime monitoring and a status page, and you tell early users it is a beta.

The lean production design in `docs/engineering/cloud-cost.md` makes the same trade (one node, external data). Move to two
nodes plus a managed cluster when downtime costs you money.

## Database: use an external managed Postgres for anything real

You are right not to run the production database yourself. Backups, point-in-time recovery, patching, failover and disk
growth are the parts that lose data when done badly. Rule of thumb:

| Data | Where |
|---|---|
| Test / demo data you can throw away | `DB_MODE=cluster`: Postgres in a pod on this server's disk. If the server goes, the data goes. |
| Anything with real customers, orders or payments | `DB_MODE=external`: Layerbase (the repo's lean-production choice, `docs/engineering/cloud-cost.md`) or OVHcloud Managed PostgreSQL. |

Layerbase Dedicated runs in Virginia, Oregon and São Paulo, and OVHcloud's US location `us-east-va` (Vint Hill, Virginia) is
close to Virginia, so put the server there to keep query latency to a few milliseconds. OVHcloud Managed PostgreSQL in the
same location is the other option: one vendor and a private network, at its own price. Check the provider's backup and
point-in-time-recovery terms before you rely on either. The scripts only need a host, an admin user and `sslmode=require`.

## How to run it

Step by step, with the pipeline that creates the server for you: [`docs/runbooks/ovh-vps.md`](../../docs/runbooks/ovh-vps.md).
In short: `make ovh-gh-setup` (asks for your values, stores them as GitHub Environment secrets), then Actions > ovh-vps >
`provision-and-deploy`. The pipeline creates the Public Cloud instance (`terraform/`), installs k3s (`bootstrap.sh`), and deploys
(`secrets.sh`, `deploy.sh`). **The instance is billed by the hour until you run `destroy`.**

## What is generated, where

`/var/lib/afrotransact/generated.env` (root only) holds every generated key and password, `identity-signing-key.pem` the token
signing key. **Back them up**: without them the Secrets cannot be rebuilt and every issued token and encrypted field is lost.
Re-running changes nothing. Third-party keys come from `OVH_VPS_SECRETS`; a service whose key is empty starts without that
integration, except **gateway-bff, which refuses to start without `STRIPE_PUBLISHABLE_KEY`**.

## Resolved gaps

- **OpenFGA**: already wired (store created by `authz-sync` on start); checked, no change needed.
- **Temporal for orders**: dev/uat config no longer forces mTLS (the `temporal-cloud` profile adds it); `overrides/orders.yaml`
  points orders at the in-cluster Temporal, namespace `afrotransact`.
- **Observability**: `deploy.sh observability` (Prometheus, Grafana, Loki, Alloy; `grafana.<domain>`).
- **Backups**: `backup.sh` (encrypted Postgres + secrets to Object Storage), `restore` included.
- **Chart versions** are pinned on first install (`chart-versions.env`).

## Known gaps

- Valkey, Kafka and Temporal data are not backed up.
- Search uses Meilisearch and starts with an empty index: run the indexer backfill.
- Image names: charts default to `tax-returns-service`, CI publishes `tax-returns`; `deploy.sh` overrides it.
- Nothing has been run against a real OVHcloud/GitHub account; Helm templates were not rendered here.

## Tear down

Actions > ovh-vps > `destroy`, `confirm-destroy` = `ovh-vps`: deletes the instance and its disk, the state and the state bucket and user.
