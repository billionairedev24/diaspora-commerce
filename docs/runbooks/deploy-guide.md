# Deploy guide: one wizard for the OVHcloud + Cloudflare test environment

```bash
make deploy-guide
```

A guided, resumable script that takes you from "nothing" to the test environment: backend on one OVHcloud server (k3s), the three web
apps as Cloudflare Workers, DNS and certificates, and proves each step worked. It drives the same GitHub workflows and scripts the
manual runbooks describe ([ovh-vps.md](ovh-vps.md), [cloudflare-setup.md](cloudflare-setup.md)); those stay the reference.

> **Status: written and tested offline only.** `make deploy-guide-test` runs it against stubbed `gh`, `curl`, `dig` and `openssl`
> (137 checks). It has **never run against a real GitHub, OVHcloud or Cloudflare account**: expect small fixes on the first real run,
> and read the cost panels before you type a confirmation word. It was not run under `shellcheck` (not installed where it was written).

## What you will see

Colour has a meaning: cyan information, green pass, yellow warnings and costs, red failures and destructive steps, dim hints. Boxes
say **WHAT YOU NEED / WHAT IT DOES**, **COST**, **COST AND EFFECT** (billable steps), **BLOCKED**. Waits show a spinner and the elapsed
time. `NO_COLOR=1` turns colour off; it is also off when output is not a terminal or `TERM=dumb`; `GUIDE_ASCII=1` forces ASCII borders and symbols.

```
──────────────────────────────────────────────────────────────────────────────
 Phase 2 of 9  Your values, into GitHub   ████░░░░░░░░░░░░░░░░ 22%
──────────────────────────────────────────────────────────────────────────────

STRIPE_SECRET_KEY  (secret, required)
  What it is   Stripe TEST secret key, starts with sk_test_.
  Why          The payments service starts with it. Only test keys are accepted here.
  How to get it
    1. dashboard.stripe.com, switch to Test mode (top right toggle).
    2. Developers > API keys.
    3. Reveal and copy the Secret key (sk_test_...).
    type ? to see this again, q to quit
Value (hidden):
  ✓ PASS  Stripe accepted the key (account acct_1AbC...)
  ✓ stored in the GitHub Environment ovh-vps (secret, hidden)
...
┌─ COST AND EFFECT - billable ───────────────────────────────────────────────┐
│ Creates a Public Cloud instance (b3-32 in us-east-va) and deploys 26       │
│ services on it. BILLING STARTS WHEN THE INSTANCE EXISTS, by the hour,      │
│ until you run destroy (the 'down' action does NOT stop it).                │
└────────────────────────────────────────────────────────────────────────────┘
Create the server and deploy? Type  create  to go ahead (anything else cancels):
  ⠹ provisioning and deploying (30-45 min)  12m40s
  ✓ PASS  run succeeded; the server is 203.0.113.10 (billing since 2026-11-09T09:12Z)

  What just happened: GitHub Actions created the instance, the DNS records, installed k3s and deployed the services.
  If it failed: ovh-vps.md troubleshooting table (flavor, authentication, 'no image at', cannot SSH, ...).
```

After every phase: **What just happened** (two plain sentences) and **If it failed** (the exact troubleshooting row). At the end: every
URL, the next three things to do, and a checklist of every value (set / MISSING / optional skipped, never the value).

## The phases

| # | Name | What it does | Costs |
|---|---|---|---|
| 0 | `preflight` | tools, `gh` signed in with admin rights, HEAD is on `origin/main`, the three workflows exist on main, the 26 images exist for that commit (or builds them with `ovh-images`) | free (Actions minutes if it builds) |
| 1 | `accounts` | click paths for OVHcloud (account, card, project), Cloudflare (account, domain, nameservers, token), Stripe test keys, Resend; checks project id format and nameservers (and a Cloudflare token if you paste one, held in memory only) | free |
| 2 | `values` | creates the GitHub Environment `ovh-vps` and asks every value one at a time (what, why, how to get it, validation, live check), makes the state user and the SSH key | free |
| 3 | `database` | `cluster` (throwaway, typed `throwaway`) or `external` (Layerbase, managed Postgres) | cluster: data lost on destroy; external: the provider's fee |
| 4 | `flavor` | location and server size; you must read the hourly price in the console and type it | none yet |
| 5 | `server` | completeness gate, then `ovh-vps` `plan` (typed `plan`), then `provision-and-deploy` (typed `create`) | **billing starts** |
| 6 | `dns` | four hosts resolve to the server, grey cloud, Let's Encrypt certificates (issuer, expiry), API and sign-in answer | free |
| 7 | `cloudflare` | zone settings (visitor location headers, Always HTTPS, SSL Full strict), Workers plan, `ovh-web` (typed `deploy`), three hosts and the apex redirect | free, or Workers Paid |
| 8 | `smoke` | health, sign-in page and its enforced CSP, web apps' Report-Only CSP, CSP report endpoint, search reindex (typed `reindex`), seeding rules | free |
| 9 | `stop` | how to stop the billing and what is kept; can run `destroy` (typed `ovh-vps`) | saves money |

Each phase checks the current state first and skips what is done, so re-running is safe.

## Flags

| Flag | Meaning |
|---|---|
| (none) | run from the first phase not yet done |
| `--status` | phases done/pending, the server, the commit (no network) |
| `--explain` | the whole plan with the why, cost and verification of every phase and the list of values; runs nothing |
| `--doctor` | re-verify what is deployed (DNS, certificates, health, headers); read-only, any time |
| `--checklist` | every value: set / MISSING / optional skipped |
| `--set NAME` | collect or change one value with its full help |
| `--from P` / `--only P` | start at phase `P` (number or name) / run just that phase, even if done |
| `--dry-run` | print what each phase would do; runs nothing, asks nothing, writes nothing |
| `--yes` | answers the **non-billable** y/N questions with yes. Never the typed confirmations of billable or destructive steps |

With `make`: `make deploy-guide ARGS='--only values'`. Quit any time with `q`; progress is in `.ovh/deploy-guide.state` (git-ignored; only
facts such as the repository, domain, commit, server address, run id; never a secret). Delete it to start over.

## The values: one catalogue

`tools/deploy/catalogue.conf` lists every secret and variable that `ovh-vps.yml`, `ovh-web.yml` and `tools/ovh/render-config.sh` read: name,
secret or variable, required/optional/generated/external/managed, default, phase, gate, validator, live check, what it is, why, how to get
it. The wizard and `make ovh-gh-setup` (`tools/ovh/gh-setup.sh`) both read it, and the test fails when a workflow reads a name that is
missing. The wizard refuses to start the server (or the web deploy) while a required value is missing.

Live checks, all read-only: Cloudflare token (`/user/tokens/verify`), Cloudflare account and zone, Stripe test key (`GET /v1/account`),
the GitHub pull token's scopes, the OVHcloud API values (lists your Public Cloud projects so you can adopt one), nameservers.

## What it never does

- Print, log or store a secret. Secrets are read with hidden prompts and go to GitHub on stdin. The one exception is by design: the generated
  admin password and backup passphrase are shown **once** on screen (or type your own).
- Create the server, change DNS, deploy or destroy without you typing a word. `--yes` does not skip those.
- Use `down` to stop the billing (it does not stop it: `destroy` does).
- Touch production (the dev/uat/prod pipelines, `infra/cloudflare`) or deploy anything from your machine: it only starts the workflows.
- Know current prices: it makes you read the hourly price in the OVHcloud console.

## Troubleshooting

| Symptom | Do |
|---|---|
| "workflow ... not found" in preflight | the workflow files must be on the default branch (workflow_dispatch only works from there): merge, then run again |
| "HEAD is not on origin/main" | push or merge; the workflows deploy what is on main |
| The run "waits for approval" for a long time | you are the required reviewer: open the run URL it prints > Review deployments > `ovh-vps` > Approve |
| A phase failed | the failure panel names the row of [ovh-vps.md](ovh-vps.md) or [cloudflare-setup.md](cloudflare-setup.md); fix, run again: it resumes at that phase |
| The server exists but the wizard lost track | `make deploy-guide ARGS='--only dns'` and type the IPv4 when asked; or destroy from phase 9 |
| Demo data | the seeder talks to each service directly and those are not on public hosts: seeding this environment needs an SSH tunnel plus `kubectl port-forward` and a `SEED_TOKEN`. `SEED_ALLOW_REMOTE=1` is needed only when service URLs are not localhost, and the seeder always refuses `APP_ENV=prod` or `uat`. Not automated or verified here |
| Tests | `make deploy-guide-test` |
