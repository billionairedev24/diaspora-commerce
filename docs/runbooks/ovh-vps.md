# Test environment on one OVHcloud server: step by step

**What you get:** the whole backend (26 services, Kafka, Temporal, OpenFGA, Valkey, Meilisearch, optionally Postgres) on **one**
OVHcloud Public Cloud instance running k3s, behind Traefik with Let's Encrypt certificates, created and deployed by one manual
GitHub workflow. **You give the values once to a script; the pipeline creates the server and does everything else.**

> **Status: written, never run against a real OVHcloud account.** It was checked offline only (script syntax, a stubbed run of the
> secrets script, YAML parsing). Expect to fix small things on the first run; the troubleshooting table at the end lists where to
> look. Prices are not verified: read the hourly price of the flavor in the OVHcloud console before you create anything.

```
 your machine                      GitHub (Actions)                          OVHcloud
 ─────────────                     ────────────────                          ────────
 make ovh-gh-setup  ──values──▶   Environment "ovh-vps"
                                   secrets + variables
                                          │  Run workflow ovh-vps (you approve)
                                          ▼
                                   state bucket + user  ─────────────────▶  Object Storage (Terraform state)
                                   tofu apply           ─────────────────▶  Public Cloud instance (billed hourly)
                                   DNS records (optional) ───────────────▶  Cloudflare
                                   ssh: k3s, secrets, data stores, edge, 26 services ─▶ the instance
```

## What you need

| | |
|---|---|
| An **OVHcloud account** for the location you pick (OVHcloud **US** at us.ovhcloud.com for `us-east-va`/`us-west-or`; Canada at ca.ovhcloud.com for `ca-bhs`: the two are separate companies) with a payment method | step 1 |
| Admin rights on the GitHub repository, and the **GitHub CLI** (`brew install gh`, `gh auth login`), `jq`, `curl`, `openssl`, `ssh-keygen` on your machine | step 4 |
| A **domain you control** (the examples use `test.example.com`); Cloudflare DNS if you want the pipeline to make the DNS records | step 6 |
| Stripe **test** keys (the storefront refuses to start without the publishable key), a Resend key for email | step 4 |

## Is one server safe for production? Which database?

**One server is not production.** One machine is one failure domain: a reboot, a full disk or a bad deploy takes everything down, and
k3s has a single control plane. It is right for a test, a demo or an invite-only beta *if* the database is external and managed
(backups you have restored once), everything left on the box can be rebuilt (Valkey, the search index and Kafka can: services publish
through an outbox), you can rebuild and redeploy in under an hour, and users know it is a beta.

**Do not run your own production database.** Backups, point-in-time recovery, patching and failover are what lose data when done
by hand. `DB_MODE=cluster` (Postgres in a pod on the server's disk) is for throwaway test data only: destroy the server and the data
is gone. For anything real use `DB_MODE=external` with a managed Postgres: **Layerbase** (the repo's lean-production choice,
`docs/engineering/cloud-cost.md`; Virginia, next to OVHcloud's `us-east-va`) or **OVHcloud Managed PostgreSQL** in the same location.
Check the provider's backup and point-in-time-recovery terms first. The pipeline only needs the host, an admin user, its password and
`sslmode`; it creates one role and database per service.

## Step by step

### 1. OVHcloud account and payment method (browser)

Create the account on the site of your location and add a payment method (Control Panel, your name, *Billing*, *Payment methods*).

### 2. A Public Cloud project (browser)

Control Panel > *Public Cloud* > *Create a project* (free; it costs nothing until you create instances). Open it and copy its
**project id**: 32 hexadecimal characters, shown at the top left of the project page. You will paste it in step 4.

### 3. Make sure the container images exist (GitHub)

The server pulls `ghcr.io/<owner>/<repo>/<service>:<git sha>` for all 26 services. Each service's pipeline publishes it when a push
to `main` touches that service. The commit that introduced this runbook changed `infra/helm/library`, which **every** service
pipeline watches, so all 26 images were built at the sha of that commit (or a later one that also touched it). In *Actions*, check
that the 26 `java-*` / `go-*` runs of that commit are green, and **copy that commit's full 40-character sha**: it is the `image-tag`
of step 5. (The workflow checks every image exists before it creates anything and names the ones that are missing.)

### 4. Give your values to the script (your machine, once)

```bash
make ovh-gh-setup
```

It creates the GitHub Environment `ovh-vps` (you are the required reviewer, deployments only from `main`), asks the questions below
and stores each answer as an Environment secret or variable. It never prints a secret. Re-run it any time to change a value (it shows
which keys are set). To skip the questions, put `KEY=value` lines in `.ovh/ovh-vps.env` (git-ignored) first.

| It asks | Answer |
|---|---|
| Location | `us-east-va` (default) |
| `OVH_APPLICATION_KEY`, `OVH_APPLICATION_SECRET`, `OVH_CONSUMER_KEY` | open the address it prints (signed in to your OVHcloud account, rights pre-filled, validity 30 days, no IP restriction) and copy the three values |
| `OVH_SERVICE_NAME` | the project id of step 2 |
| `TF_STATE_BUCKET` | a name for the Object Storage bucket that holds the Terraform state (the default is fine) |
| `OVH_DOMAIN`, `ACME_EMAIL` | your test domain and an email for certificate notices |
| `OVH_INSTANCE_FLAVOR` | `b3-32` (8 vCPU / 32 GB). 16 GB is too small |
| `DB_MODE` | `cluster` for a throwaway test, `external` for a managed Postgres (then host, admin user, admin password, sslmode) |
| `ADMIN_EMAIL`, `ADMIN_PHONE` | the first staff admin; the password is generated and shown **once**: keep it |
| Optional keys | `REGISTRY_PULL_TOKEN` (a classic GitHub token with only `read:packages`: without it the server pulls with a token that stops working after the run), Stripe test keys, `RESEND_API_KEY`, `OPENROUTER_API_KEY`, Google, UploadThing, carrier sandboxes, Cloudflare token + zone (lets the pipeline create the DNS records) |

Behind the scenes it also: finds or creates, through the OVHcloud API, the Public Cloud user `terraform-state` and its S3 key (so
you never create a user or a bucket by hand), and generates the pipeline's SSH key pair in `.ovh/ovh-vps-deploy-key` (git-ignored; the
private half becomes a secret, the public half is installed on the server; it is also how *you* log in: `ssh -i
.ovh/ovh-vps-deploy-key ubuntu@<ip>`). **Without `gh` signed in as a repository admin, nothing here works.** Environments with required
reviewers on a private repository need a paid GitHub plan; on a free plan the script warns and every run still needs the typed
confirmation for `destroy`.

### 5. Run the pipeline (GitHub, Actions > ovh-vps > Run workflow)

1. **`plan`**. Creates the state user key, the state bucket and shows what would be created. You approve the run (required reviewer).
   Nothing is billed yet. Fix anything it complains about (an unknown flavor in that location, a missing value).
2. **`provision-and-deploy`** with `image-tag` = the sha of step 3. You approve. It then, without you:
   - creates the **Public Cloud instance** (**billing starts here**, by the hour), waits for SSH;
   - creates the DNS records at Cloudflare if you gave it a token, else prints the three A records to create;
   - checks all 26 images exist at the sha; copies the deployment files and your configuration (never logged) to the server;
   - installs k3s and helm (about 3 minutes), then PostgreSQL (cluster mode), Valkey, Meilisearch, Kafka, Temporal, OpenFGA,
     cert-manager, Traefik, the certificates and routes, and the 26 services in three waves (identity first);
   - waits up to 10 minutes for every service and lists the ones that are not ready.

   Expect 30 to 45 minutes. The summary shows the server IPv4, the URLs and the billing warning.

Other actions: `deploy` (again, on the existing server), `services` (only the service charts, with a new `image-tag` or changed
secrets), `edge`, `status` (pods that are not Running, certificates), `down` / `up` (scale services to 0 / 1: **the server keeps
billing**), `destroy`.

### 6. DNS and certificates

Three A records, **DNS only** (grey cloud at Cloudflare, so Let's Encrypt's HTTP-01 check reaches port 80), all pointing at the
server's IPv4 from the summary: `api.<domain>`, `auth.<domain>`, `mcp.<domain>`. The pipeline makes them if you gave it a Cloudflare
token and zone. Remove any `CAA` record that does not allow `letsencrypt.org`. cert-manager retries by itself; once DNS resolves the
certificates become Ready in a few minutes (`status` action shows them). Then:

```bash
curl -i https://api.<domain>/bff/healthz
curl -s https://auth.<domain>/.well-known/openid-configuration | jq .issuer
```

### 7. Use it

- Staff console / Seller Center / storefront are Cloudflare Workers apps and are not on this server: point their `API_BASE_URL` at
  `https://api.<domain>` (and the OIDC issuer at `https://auth.<domain>`).
- Sign in as the staff admin (`ADMIN_EMAIL` and the password step 4 showed).
- Stripe: add the webhook `https://api.<domain>/payments/v1/webhooks/stripe`, put its `whsec_...` in `STRIPE_WEBHOOK_SECRET` (re-run
  `make ovh-gh-setup`, then the `services` action).
- Search starts empty: run the indexer backfill.
- Look inside: `ssh -i .ovh/ovh-vps-deploy-key ubuntu@<ip>` then `sudo k3s kubectl -n afrotransact-dev get pods` and
  `sudo k3s kubectl -n afrotransact-dev logs deploy/<service> --tail=80`.

### 8. Deploy a change

Merge to `main`; the service pipelines publish new images at the new sha. Run **`services`** with that `image-tag` (every service must
have an image at it, so use a sha where the services you changed *and* the unchanged ones exist; the simplest is to keep the sha of
step 3 for the unchanged services by running `deploy` once on the new sha after all pipelines have run).

### 9. Tear down (stops the billing)

Run **`destroy`** with `confirm-destroy` = `ovh-vps`. It deletes the instance and its disk (**including a Postgres running on it**),
the Terraform state, the state bucket and the state user. Then delete the DNS records if you made them by hand, and revoke the
OVHcloud API token and the GitHub Environment secrets. Check the Public Cloud project in the console shows no instance: that is
the only thing that bills.

## Troubleshooting

| Symptom | Look here |
|---|---|
| `plan` fails: "flavor ... is not offered" | Public Cloud > Instances > Create shows the flavors of the region; set `OVH_INSTANCE_FLAVOR` |
| `plan` fails with an authentication error | the API token expired or belongs to the other OVHcloud entity (US vs Canada); re-run `make ovh-gh-setup` |
| "no image at <sha> for: ..." | that service pipeline did not run or failed at the sha; pick a sha where all 26 are green |
| The run cannot SSH in | the instance is still starting (it waits 10 minutes); or the SSH key in the Environment differs from the one used at creation (destroy and apply again) |
| `ImagePullBackOff` | private packages and no `REGISTRY_PULL_TOKEN` (the run token expired); set the token and run `services` |
| A service is `CrashLoopBackOff` | `kubectl -n afrotransact-dev logs deploy/<service>`: usually a missing third-party key (gateway-bff needs `STRIPE_PUBLISHABLE_KEY`) or a dependency not ready yet |
| Certificate stays `False` | DNS not pointing at the server yet, grey cloud off, or a `CAA` record; `kubectl describe certificate -n afrotransact-dev` |
| Orders cannot start a workflow | Temporal TLS: orders reads `TEMPORAL_TLS_CERT/KEY` (empty here) while this Temporal runs without mTLS |
| Authorization errors | OpenFGA store id / model are not wired into the services in this kit; see the known gaps |

## Known gaps

OpenFGA store/model wiring, Temporal TLS for orders, no metrics/logs/traces (telemetry off), no automatic backups of Valkey, Kafka
or an in-cluster Postgres, Traefik and cert-manager chart versions not pinned, search index empty at start. A fuller list is in
`infra/ovh-vps/README.md`.
