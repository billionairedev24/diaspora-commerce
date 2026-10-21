# AfroTransact on one OVHcloud server (k3s)

A **test environment** on a single OVHcloud Public Cloud instance: k3s, every backend service, Kafka, Temporal, OpenFGA,
Valkey, Meilisearch and (optionally) Postgres on one machine, deployed by one manual GitHub workflow. Nothing here is
created or billed by code: you create the server by hand and delete it by hand.

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

## What you do by hand

1. **Create the instance** (OVHcloud Control Panel > Public Cloud > Instances): Ubuntu 24.04, flavor with **8 vCPU / 32 GB**
   (`b3-32`; 16 GB is too small for 26 services plus the data stores), hourly billing, your SSH public key, location
   `us-east-va` for a Virginia database. Note the IPv4.
   **Cost: an hourly instance keeps billing while powered off or scaled down. Delete the instance when the test is over.**
2. **DNS** (Cloudflare, grey cloud / DNS only): `api.<domain>`, `auth.<domain>`, `mcp.<domain>` as A records to the IPv4.
   Remove any `CAA` record that does not allow Let's Encrypt (`letsencrypt.org`).
3. **GitHub Environment `ovh-vps`** (require yourself as reviewer): secret `OVH_VPS_SSH_KEY` (private key), secret
   `OVH_VPS_CONFIG` (content of `config.env.example`, filled), secret `OVH_VPS_SECRETS` (content of
   `secrets.local.env.example`: Stripe test keys, Resend ...), variable `OVH_VPS_HOST` (the IPv4).
4. **Images.** CI publishes `ghcr.io/billionairedev24/diaspora-commerce/<service>:<git sha>` for a service when its code
   changes on `main`. The commit that introduced this folder changed `infra/helm/library`, which every service pipeline
   watches, so **all 26 images are built at that commit's sha**: use that sha as `image-tag`. If the packages are private,
   put a GitHub token with `read:packages` in `GHCR_USER` / `GHCR_TOKEN` (config).

## Run it (Actions > ovh-vps > Run workflow)

| Action | Does |
|---|---|
| `bootstrap` | firewall (22, 80, 443), k3s without Traefik, helm. Once. |
| `deploy` | secrets, Postgres (cluster mode), Valkey, Meilisearch, Kafka, Temporal, OpenFGA, cert-manager, Traefik, certificates, routes, all services in three waves. |
| `services` | only the service charts (new `image-tag`, changed secrets). |
| `edge` | Traefik, cert-manager, certificates and routes. |
| `status` | pods that are not Running, certificates, URLs. |
| `down` / `up` | scale every service to 0 / 1. **Does not stop billing.** |

Then: `https://api.<domain>/bff`, `https://auth.<domain>` (the OIDC issuer), `https://mcp.<domain>`.
The web apps (storefront, Seller Center, staff console) are Cloudflare Workers apps, not part of this server: point their
`API_BASE_URL` at `https://api.<domain>`.

## What is generated, where

`/var/lib/afrotransact/generated.env` (root only) holds every generated key and password, `identity-signing-key.pem` the token
signing key. **Back them up**: without them the Secrets cannot be rebuilt and every issued token and encrypted field is lost.
Re-running changes nothing. Third-party keys come from `OVH_VPS_SECRETS`; a service whose key is empty starts without that
integration, except **gateway-bff, which refuses to start without `STRIPE_PUBLISHABLE_KEY`**.

## Known gaps (found while writing it, not fixed)

- **OpenFGA**: the stack is deployed with the pre-shared key, but I did not wire a store id or authorization model into the
  services; whether `authz-sync` creates them on start needs checking on the first run.
- **Temporal**: orders reads `TEMPORAL_TLS_CERT/KEY` (empty here) while the lean manifest runs Temporal without mTLS.
- **No metrics, logs or traces**: no Prometheus, Loki or OTLP collector (telemetry sampling is 0). Use `kubectl logs`.
- **Image names**: the charts' `image.repository` defaults (`tax-returns-service`) differ from what CI publishes
  (`tax-returns`); `deploy.sh` overrides it to the service name.
- **No automatic backups** of Valkey, Kafka or (cluster mode) Postgres.
- Traefik and cert-manager chart versions are not pinned.
- Search uses Meilisearch (`SEARCH_PROVIDER=meilisearch`), which starts with an empty index: run the indexer backfill.

## Tear down

Delete the instance in the OVHcloud console (and the DNS records). Revoke the SSH key and the GitHub environment secrets.
