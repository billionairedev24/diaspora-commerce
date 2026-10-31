# Cloudflare for the OVH test environment: step by step

What this sets up: your domain on Cloudflare, the DNS records of the backend (made by the `ovh-vps` workflow), and the three web
apps (storefront, Seller Center, staff console) running as **Cloudflare Workers** on your domain through the **`ovh-web`**
workflow. The per-app pipelines (`web-*.yml`) are wired to the `afrotransact.com` hosts of dev, uat and prod; `ovh-web` takes the
hosts from your domain instead, so use it for the OVH test environment.

```
browser ──► https://www.<domain>        storefront      (Cloudflare Worker  storefront-ovh)
        ──► https://<domain>            301 to https://www.<domain>, path and query kept
                                        (Cloudflare Worker  storefront-redirect-ovh, Custom Domain on the apex)
        ──► https://seller.<domain>     Seller Center   (Cloudflare Worker  seller-center-ovh)
        ──► https://admin.<domain>      staff console   (Cloudflare Worker  staff-console-ovh)
Workers ──► https://api.<domain>/bff    gateway-bff     (OVHcloud server, A record DNS only, made by ovh-vps)
browser ──► https://auth.<domain>       identity / sign-in (OVHcloud server)
```

The hosts follow `docs/engineering/environments.md`: the services on the server are deployed with `global.domain=<domain>`, so
their redirect URIs, CORS and links already expect the storefront at `www.<domain>` (the storefront host rule; `global.storefrontHost=www`
from the `OVH_STOREFRONT_HOST` variable, default `www`), `seller.<domain>` and `admin.<domain>`. The bare domain only redirects to `www.`.
To keep the storefront at the bare domain instead, set `OVH_STOREFRONT_HOST=apex`, redeploy the services, and run `ovh-web` with
`storefront-host` = `apex`; both must say the same (the workflow refuses a mismatch).

> **Status: written, not run against a real Cloudflare account.** Expect small fixes on the first run. The checks I could do offline
> were YAML and shell syntax. The troubleshooting table at the end lists where to look.

## What you need

| | |
|---|---|
| A Cloudflare account (free to create) | step 1 |
| A domain you control, and access to its registrar to change the nameservers | step 2 |
| The OVH side done first, or in parallel: [ovh-vps.md](ovh-vps.md) | the web apps need `api.<domain>` to exist to work |
| Possibly **Workers Paid** (about 5 USD a month) | step 6 (the free Workers plan limits the size of a Worker; Next.js apps built with OpenNext are often too big for it: check the first deploy's error) |

## 1. Create the Cloudflare account (browser)

1. Go to <https://dash.cloudflare.com/sign-up>, create the account and confirm the email.
2. Turn on two-factor sign-in (Profile, *Authentication*).

## 2. Add your domain (browser)

1. Dashboard > *Add a domain* > enter the domain (for example `test.example.com`'s registered parent `example.com`) > pick the **Free** plan.
2. If the domain is a **subdomain** of a domain you keep elsewhere, Cloudflare only supports it on Business or Enterprise plans.
   Use the registered domain itself, or a separate cheap domain for the test environment.
3. Cloudflare shows two nameservers. At your registrar replace the domain's nameservers with them, then click *Check nameservers*.
   The zone becomes **Active** in minutes to a few hours.
4. Remove any existing `CAA` record that does not allow `letsencrypt.org`, and any existing A or CNAME record on the **bare domain**, `www.`, `seller.`
   and `admin.` (Workers Custom Domains create them and refuse to overwrite another record; the bare domain is the apex redirect's Custom Domain).
5. *SSL/TLS* > *Overview*: set the mode to **Full (strict)**.

## 3. Find the account id (browser)

Dashboard > *Workers & Pages* > the right-hand column shows **Account ID** (32 hexadecimal characters). Copy it. You paste it in step 5.

## 4. Create the API token (browser)

Profile > *API Tokens* > *Create Token* > *Create Custom Token*. One token covers both the DNS records of the backend and the Workers:

| Scope | Permission | Access |
|---|---|---|
| Account | Workers Scripts | Edit |
| Account | Account Settings | Read |
| Zone | Workers Routes | Edit |
| Zone | DNS | Edit |
| Zone | Zone | Read |

*Account Resources*: your account. *Zone Resources*: *Include* > *Specific zone* > your domain. Create it and copy the token (shown once).

## 5. Give the values to GitHub (your machine)

```bash
make ovh-gh-setup
```

It asks, among the other values of the OVH setup, for these three. Answer:

| Key | Value |
|---|---|
| `CLOUDFLARE_API_TOKEN` (secret) | the token of step 4 |
| `CLOUDFLARE_ACCOUNT_ID` (secret) | the account id of step 3 |
| `CLOUDFLARE_ZONE` (variable) | the zone, for example `example.com` (the registered domain) |

They go to the GitHub Environment `ovh-vps`. Re-run the command any time to change a value. `OVH_DOMAIN` (also asked there) is the base
domain of the environment: the storefront will be at `https://www.<OVH_DOMAIN>` and `https://<OVH_DOMAIN>` redirects to it
(`OVH_STOREFRONT_HOST`, default `www`, is the variable that chooses; `apex` serves the storefront at `https://<OVH_DOMAIN>` instead).

## 6. Order of operations

1. **Backend first.** Run the `ovh-vps` workflow (`plan`, then `provision-and-deploy`). With `CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ZONE`
   set, it creates the A records `api.`, `auth.`, `mcp.` and `grafana.<domain>` (DNS only, grey cloud). Check:

   ```bash
   curl -i https://api.<domain>/bff/healthz
   curl -s https://auth.<domain>/.well-known/openid-configuration | jq .issuer
   ```

2. **Web apps.** GitHub > *Actions* > **ovh-web** > *Run workflow* (branch `main`). Leave the inputs empty to deploy all three apps on
   the `OVH_DOMAIN`; or type the apps to deploy (`storefront`, `seller-center`, `staff-console`, space separated) and a domain.
   `storefront-host` stays `www` unless you set `OVH_STOREFRONT_HOST=apex`. With `www` the storefront job also deploys the small Worker
   `storefront-redirect-ovh` on the bare domain: it answers every request with a `301` to `https://www.<domain>` + path + query. Before it
   takes the bare domain it detaches that domain from whichever Worker held it in an earlier run (the API call needs the same token).
   If you prefer a dashboard rule instead of the Worker: *Rules* > *Redirect Rules* > *Create rule* > "When incoming requests match"
   `Hostname equals <domain>` > *Dynamic* `concat("https://www.<domain>", http.request.uri.path)`, status `301`, *Preserve query string*
   on; then the bare domain needs a proxied (orange cloud) placeholder record, for example `AAAA <domain> 100::`. Do not use both.
   Approve the run if the environment asks. Each app takes about 5 to 10 minutes (install, Next.js build, OpenNext build, deploy).
3. **Open them:** `https://www.<domain>` (and check `curl -sI "https://<domain>/p/x?a=1"` answers `301` with
   `location: https://www.<domain>/p/x?a=1`), `https://seller.<domain>`, `https://admin.<domain>`. The first custom-domain certificate can take a
   few minutes.
4. **Sign in** as the staff admin at `https://admin.<domain>` (`ADMIN_EMAIL` and the password the OVH setup showed once).

## 7. Redeploy one app

*Actions* > *ovh-web* > *Run workflow*, `apps` = `storefront` (or `seller-center`, or `staff-console`). Each app is its own Worker
(`storefront-ovh`, `seller-center-ovh`, `staff-console-ovh`): a deploy of one does not touch the others. Roll back with
`wrangler rollback` (Cloudflare dashboard > *Workers & Pages* > the Worker > *Deployments* > pick the earlier one > *Rollback*).

## 8. What is NOT covered here

- **Docs portal and Storybook** (`developers.<domain>`): Cloudflare Pages projects defined in `infra/cloudflare/pages.tf`; not needed for the test.
- **Turnstile bot protection, WAF rules, rate limits, HSTS, the `admin.` allow-list rule:** defined in `infra/cloudflare` (OpenTofu) for the real
  environments. For this test environment they are off. When you want them, run `infra/cloudflare` (see its README) against your zone.
- **Media CDN** (`media.<domain>`) and **mail** (`mail.<domain>`): separate; the OVH runbook covers the media bucket and Resend.
- **Production:** the real domains use the `web-*.yml` pipelines and `infra/cloudflare`; this runbook is the test setup.

## Troubleshooting

| Symptom | Look at |
|---|---|
| `ovh-web` stops at "Decide": domain or secrets missing | Set `OVH_DOMAIN` (variable) and `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID` (secrets) of the environment `ovh-vps` (`make ovh-gh-setup`) |
| Deploy error "Script startup/size limit exceeded" or "The Worker is too large" | The free Workers plan limit. Upgrade the account to Workers Paid (dashboard > *Workers & Pages* > *Plans*) and re-run |
| Deploy error about a custom domain "already has a DNS record" / "A record with that host already exists" | Delete the existing A/AAAA/CNAME record for that host in Cloudflare DNS (step 2.4), re-run. For the bare domain the same applies to the apex redirect (`storefront-redirect-ovh`): an old A record on the apex (for example one made by hand for the server) blocks it; with a dashboard Redirect Rule instead, the apex must be proxied |
| The bare domain does not redirect | the `storefront-redirect-ovh` deploy step failed or `storefront-host` is `apex`; or the apex record is DNS only (grey cloud): a redirect, Worker or rule, only runs on proxied traffic. Look at the last steps of the storefront job |
| Authentication error 10000 / "not authorized" | The token lacks a permission of step 4, or its Zone Resources do not include the domain |
| Page loads but every action fails with a 5xx or a CORS error | `api.<domain>` is not up or the web hosts do not match `global.domain` and `global.storefrontHost` of the server (the storefront must be `www.<domain>` when `OVH_STOREFRONT_HOST` is `www`, the bare domain when `apex`; `seller.` and `admin.` prefixes). Check `curl https://api.<domain>/bff/healthz` |
| Sign-in loops or "redirect_uri mismatch" | identity's redirect URIs are derived from `global.domain` and `global.storefrontHost`; the app host must be exactly `www.<domain>` (or `<domain>` with `apex`), `seller.<domain>`, `admin.<domain>` over https. The usual cause is a storefront deployed with another `storefront-host` than the backend's `OVH_STOREFRONT_HOST`: make them equal, redeploy the services (`ovh-vps`, action `services`) and the storefront. `status` action of `ovh-vps` shows the identity pod; its logs show the rejected URI |
| 522/525 on `api.` or `auth.` | The A records must be DNS only (grey cloud) and the server's certificates Ready (`status` action of `ovh-vps`) |
| Old content after a deploy | Hard refresh; the Worker versions switch at once, assets are cached by hash |
| Images do not load | `MEDIA_PUBLIC_URL`: the media bucket and its public host belong to the OVH side (see ovh-vps.md) |

## Costs

Cloudflare free plan: zone, DNS, Workers Custom Domains and a limited number of Workers requests per day. Workers Paid is about 5 USD a month
for the account if the apps exceed the free size limit (check the current price and limits at cloudflare.com/plans and in the Workers
documentation; they change).
