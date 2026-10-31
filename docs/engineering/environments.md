# Environments, hosts and URL settings

Only configuration knows which environment a service runs in. Code and shared defaults never name a public
host: they read a small set of shared settings, default them to the local stack, and every deployed
environment supplies its own values. `tools/ci/check_env_urls.py` enforces this.

## Hosts per environment

The edge is defined once in `infra/cloudflare` (`local.hosts` in `main.tf`): the base domain is
`<env>.afrotransact.com` outside prod and the bare zone in prod; every public host is `<name>.<base>`, except the
customer storefront, whose host follows the **storefront host rule** (below): `www.afrotransact.com` in prod, the base
itself in dev and uat.

| Purpose | local | dev | uat | prod |
|---|---|---|---|---|
| Storefront (`PUBLIC_WEB_URL`) | `http://localhost:3000` | `https://dev.afrotransact.com` | `https://uat.afrotransact.com` | `https://www.afrotransact.com` (the apex `https://afrotransact.com` 301-redirects to it, see below) |
| Seller Center & Provider Hub (`SELLER_CENTER_URL`) | `http://localhost:3001` | `https://seller.dev.afrotransact.com` | `https://seller.uat.afrotransact.com` | `https://seller.afrotransact.com` |
| Staff console (`STAFF_CONSOLE_URL`) | `http://localhost:3002` | `https://admin.dev.afrotransact.com` | `https://admin.uat.afrotransact.com` | `https://admin.afrotransact.com` |
| Public API, gateway-bff (`API_BASE_URL`) | `http://localhost:8200` | `https://api.dev.afrotransact.com` | `https://api.uat.afrotransact.com` | `https://api.afrotransact.com` |
| Identity / OIDC issuer (`AUTH_ISSUER_URL`) | `http://localhost:8100` | `https://auth.dev.afrotransact.com` | `https://auth.uat.afrotransact.com` | `https://auth.afrotransact.com` |
| Public media CDN (`MEDIA_PUBLIC_URL`) | `http://localhost:9000/media` (MinIO) | `https://media.dev.afrotransact.com` | `https://media.uat.afrotransact.com` | `https://media.afrotransact.com` |
| MCP server (`MCP_PUBLIC_URL`, own host) | `http://localhost:8114` | `https://mcp.dev.afrotransact.com` | `https://mcp.uat.afrotransact.com` | `https://mcp.afrotransact.com` |
| Developer portal | `http://localhost:3100` | `https://developers.dev.afrotransact.com` | `https://developers.uat.afrotransact.com` | `https://developers.afrotransact.com` |
| Grafana | `http://localhost:3200` | `https://grafana.dev.afrotransact.com` | `https://grafana.uat.afrotransact.com` | `https://grafana.afrotransact.com` |
| Email sending domain | Mailpit | `mail.dev.afrotransact.com` | `mail.uat.afrotransact.com` | `mail.afrotransact.com` |

The staff console is `admin.<base>` (the Cloudflare Worker route and the WAF admin allow-list rule both use
`admin.`). `staff.`, `sell.`, `provider.`, `identity.` and `www.<env>.` hosts do not exist (dev and uat keep the apex rule); Provider Hub is served
by the Seller Center app. `mcp.` (mcp-server) and `grafana.` (kube-prometheus-stack) are origin hosts like `api.` and
`auth.` (Cloudflare Tunnel or AOP ingress -> the edge Gateway -> HTTPRoutes in `infra/k8s/platform/edge/overlays/<env>`);
`developers.` is the docs portal on Cloudflare Pages (`infra/cloudflare/pages.tf`).
In prod the storefront is `https://www.afrotransact.com` and the bare domain `afrotransact.com` must redirect (301, path
and query kept) to it; `www.` is the only storefront origin (gateway-bff CORS/CSRF, OIDC callback, identity redirect URIs,
universal links). Every other app keeps its sub-domain. See "Storefront host rule" below.

Public API paths on `api.<base>` (edge HTTPRoute `api`, prefix stripped before the service sees it): `/bff` gateway-bff,
`/identity`, `/bookings`, `/fulfilment`, `/media`, `/notifications`, `/carrier-gateway`, and `/payments/v1/webhooks`
(Stripe webhooks only). Other services are internal.
Local ports come from `infra/local/PORTS.md`.

## Shared settings

| Setting | Meaning | Main consumers |
|---|---|---|
| `PLATFORM_ENV` | `local` / `dev` / `uat` / `prod` (same as `APP_ENV`) | everything |
| `PLATFORM_DOMAIN` | the environment's base domain (`dev.afrotransact.com`, ..., `afrotransact.com`), never `www.` | identity WebAuthn RP id, mcp-server docs-portal origin |
| `global.storefrontHost` (Helm, not an env var) | `apex` (default) or `www`: where the storefront lives; prod sets `www` | the at-service library, which derives `PUBLIC_WEB_URL` from it |
| `PUBLIC_WEB_URL` | storefront origin: `https://www.<domain>` when `storefrontHost: www`, else `https://<domain>` | identity (home, redirect URIs, CORS, WebAuthn), content newsletter links, bookings manage link, notifications links, storefront cache purges, ai-gateway OpenRouter referer, mcp-server allowed origins |
| `SELLER_CENTER_URL` | Seller Center & Provider Hub origin | identity (invitation link, redirect URIs, CORS), notifications links |
| `STAFF_CONSOLE_URL` | staff console origin | identity (redirect URIs, CORS) |
| `API_BASE_URL` | public API origin (gateway-bff) | identity BFF redirect URI, notifications and carrier-gateway public base (`<API_BASE_URL>/<service>`) |
| `AUTH_ISSUER_URL` | OIDC issuer (identity); the token endpoint is `<AUTH_ISSUER_URL>/oauth2/token` | every resource server (JWT validation), identity itself, client-credentials clients |
| `MEDIA_PUBLIC_URL` | public media CDN base | media (public store `publicBaseUrlEnv`), notifications (attachment allow-list) |

### Where the values come from

1. **Kubernetes (dev/uat/prod).** `infra/argocd/envs/<env>/platform-values.yaml` sets `global.domain` once per
   environment. The at-service library chart (`infra/helm/library/at-service`, `at-service.platformEnvVars`)
   derives every setting above from it and injects them into every service container. Precedence, last wins:
   derived value < `global.urls.<NAME>` in platform-values (environment-wide override, e.g. another CDN) <
   the service's own `extraEnv.<NAME>` (a derived entry is then not rendered, so a name never appears twice).
   Without `global.domain` (plain `helm template` in CI) only `PLATFORM_ENV` is rendered and services fall back
   to their per-environment config files.
2. **Java.** `application.yml` reads `${NAME:<localhost default>}`; `application-local.yml` keeps localhost.
   `application-<env>.yml` repeats the setting with that environment's own host as the fallback where a wrong
   value would be harmful (identity: issuer, redirect URIs, CORS, WebAuthn; mcp-server origins; links in emails).
3. **Go.** Config fields carry the shared names as `env:"..."` tags (constants in `go/pkg/config/platform.go`);
   `config/local.yaml` has localhost values and `config/<env>.yaml` that environment's hosts. Env vars override
   YAML, so the values injected by Helm win. Derived values are filled by the service's `Config.Resolve`.

4. **Web apps (Next.js on Cloudflare Workers).** Server code reads its settings per request with bracket access
   (`process.env[name]` in `src/lib/config.ts`), so one build serves every environment; nothing environment-specific
   is inlined at build time. Values come from each Worker's `vars` in `web/apps/<app>/wrangler.jsonc` (top level = dev,
   `env.preview` / `env.uat` / `env.prod`), locally from `.env.local` (see each app's `.env.example`), else the
   localhost default. Client components get them from the server: the root layout passes them to a
   `RuntimeConfigProvider` (`useStorefrontUrl()`).
5. **Mobile apps (Expo).** `EXPO_PUBLIC_*` values are inlined into the JS bundle at build time, so every deployed URL
   is set per EAS build profile in `mobile/apps/<app>/eas.json` (`development` = dev, `preview` = uat,
   `production` = prod, `development-mocks` = local on mocks). `src/config/env.ts` names no deployed host and falls back
   to the local stack on `EXPO_PUBLIC_DEV_HOST`; `app.config.ts` fails a dev/uat/prod build with a URL unset.

### Web and mobile settings

| App | Setting | local default | dev / uat / prod value | Used for |
|---|---|---|---|---|
| storefront | `BFF_ORIGIN` | `http://localhost:8200` | `<API_BASE_URL>/bff` (`https://api.<base>/bff`) | `/api/bff` proxy, server components |
| storefront | `SELLER_CENTER_URL` (`NEXT_PUBLIC_SELLER_CENTER_URL` still read) | `http://localhost:3001` | `https://seller.<base>` | `/sell`, `/provide` redirects |
| storefront | `API_MOCKS` / `NEXT_PUBLIC_API_MOCKS` | `1` in `.env.example` | `0` (`1` on PR previews) | in-process MSW mock BFF |
| seller-center, staff-console | `BFF_ORIGIN` | `http://localhost:8200` | `https://api.<base>/bff` | `/api/bff` proxy |
| storefront | `PUBLIC_WEB_URL` | unset (relative metadata) | `https://<base>`; prod `https://www.<base>` | `metadataBase`: canonical and Open Graph URLs |
| seller-center, staff-console | `PUBLIC_WEB_URL` (`NEXT_PUBLIC_STOREFRONT_URL` still read) | `http://localhost:3000` | `https://<base>`; prod `https://www.<base>` | sign-in, sign-out, "view my public page" links |
| customer app | `EXPO_PUBLIC_WEB_URL` | `http://<dev host>:3000` | `https://<base>`; prod `https://www.<base>` | legal/help/account pages, share links, universal links (`applinks:<host>`, Android `https://<host>/app`) |
| customer app | `EXPO_PUBLIC_BFF_URL` | `http://<dev host>:8200` | `https://api.<base>/bff` | screen API |
| customer app | `EXPO_PUBLIC_OIDC_ISSUER` | `http://<dev host>:8100` | `https://auth.<base>` (`AUTH_ISSUER_URL`) | sign-in |
| customer app | `EXPO_PUBLIC_IDENTITY_URL` | `http://<dev host>:8100` | `https://api.<base>/identity` | devices, notification preferences |
| customer app | `EXPO_PUBLIC_BOOKINGS_URL` | `http://<dev host>:8109` | `https://api.<base>/bookings` | bookings |
| driver app | `EXPO_PUBLIC_BFF_URL` | `http://<dev host>:8200` | `https://api.<base>/bff` | in-app sign-in |
| driver app | `EXPO_PUBLIC_FULFILMENT_URL` | `http://<dev host>:8108` | `https://api.<base>/fulfilment` | driver API |
| driver app | `EXPO_PUBLIC_MEDIA_URL` | `http://<dev host>:8207` | `https://api.<base>/media` | proof photos, signatures |
| driver app | `EXPO_PUBLIC_OIDC_ISSUER` | `http://<dev host>:8100` | `https://auth.<base>` | sign-in |
| driver app | `EXPO_PUBLIC_IDENTITY_URL` | `http://<dev host>:8100` | `https://api.<base>/identity` | push devices |
| both apps | `EXPO_PUBLIC_APP_ENV` | `local` | `dev` / `uat` / `prod` | app name, bundle id suffix, mocks |
| both apps | `EXPO_PUBLIC_DEV_HOST` | `localhost` | - | local stack host: the dev machine's LAN IP for a device, `10.0.2.2` for the Android emulator |

`<base>` is `dev.afrotransact.com`, `uat.afrotransact.com` or `afrotransact.com`. The gateway-bff derives its CORS/CSRF
origins, storefront origin, OIDC issuer and callback (`<PUBLIC_WEB_URL>/api/bff/v1/auth/oidc/callback`) from
`PUBLIC_WEB_URL`, `SELLER_CENTER_URL`, `STAFF_CONSOLE_URL` and `AUTH_ISSUER_URL` (`Config.Resolve`); `WEB_ORIGINS`,
`WEB_PUBLIC_ORIGIN`, `OIDC_ISSUER`, `OIDC_PUBLIC_URL` and `OIDC_REDIRECT_URI` override the derived values.

### Service-specific settings (explicit values win over the derivation)

| Setting | Default | Service |
|---|---|---|
| `AUTH_TOKEN_URL` | `<AUTH_ISSUER_URL>/oauth2/token` (Java clients; Go indexer/search outside local) | bookings, retail, seller, tax-returns, trust-ops, notifications, indexer, search |
| `HOME_URL` | `PUBLIC_WEB_URL` | identity |
| `INVITATION_ACCEPT_URL` | `<SELLER_CENTER_URL>/invitations/accept` | identity |
| `WEBAUTHN_RP_ID` | `PLATFORM_DOMAIN` (`localhost` locally) | identity |
| `IDENTITY_STAFF_EMAIL_DOMAINS` | `afrotransact.com` (comma-separated) | identity |
| `NEWSLETTER_CONFIRM_URL`, `NEWSLETTER_UNSUBSCRIBE_URL` | `<PUBLIC_WEB_URL>/newsletter/{confirm,unsubscribe}` | content |
| `BOOKINGS_MANAGE_URL_BASE` | `<PUBLIC_WEB_URL>/account/appointments/` | bookings |
| `OPENROUTER_REFERER` | `PUBLIC_WEB_URL` | ai-gateway |
| `AI_PROVIDER_DATA_COLLECTION` | `deny` (`deny` or `allow`; OpenRouter `provider.data_collection`) | ai-gateway |
| `AI_PROVIDER_ZDR` | `false` locally and in dev, `true` in uat and prod (OpenRouter `provider.zdr`) | ai-gateway |
| `MCP_ALLOWED_ORIGINS` | `PUBLIC_WEB_URL` + `https://developers.<PLATFORM_DOMAIN>` (local: + MCP inspector) | mcp-server |
| `MCP_PUBLIC_URL` | set per environment in `values-<env>.yaml` (own host) | mcp-server |
| `PUBLIC_BASE_URL` | `<API_BASE_URL>/notifications`, `<API_BASE_URL>/carrier-gateway` | notifications, carrier-gateway |
| `PROVIDER_HUB_URL` | `SELLER_CENTER_URL` | notifications |
| `at.api-docs.contact-url` | the developer portal | every Java service (OpenAPI info) |
| `OFF_USER_AGENT`, `NOMINATIM_USER_AGENT`, `EMAIL_FROM`, `EMAIL_REPLY_TO`, `EMAIL_UNSUBSCRIBE_MAILTO` | contact addresses in config | catalog, geo, notifications |

### Road routing (geo, S255 AfroTransact Delivery)

Road distance and duration for delivery pricing, checkout snapshots and nearest-driver suggestions come from routing
providers in priority order; PostGIS geodesic × detour factor is only the last resort. A provider is enabled only
when its key / URL is present, so **every environment starts disabled** and is switched on by adding secrets.

| Env var | local | dev | uat | prod |
|---|---|---|---|---|
| `GEO_ROUTING_PROVIDERS` (priority order) | empty (`config/local.yaml`: no vendor calls) | `google,osrm` | `google,osrm` | `google,osrm` |
| `GOOGLE_MAPS_API_KEY` (secret `afrotransact/<env>/geo`; Routes API, falls back to `GOOGLE_PLACES_API_KEY`) | unset | unset until provisioned | unset until provisioned | unset until provisioned |
| `OSRM_URL` (self-hosted OSRM, e.g. `http://osrm:5000`) | unset | unset until deployed | unset until deployed | unset until deployed |
| `GEO_ROUTING_TIMEOUT` | `2s` | `2s` | `2s` | `2s` |
| `GEO_ROUTE_CACHE_TTL` / `GEO_ROUTE_CACHE_PRECISION_METERS` | `24h` / `50` | same | same | same |
| `GEO_DETOUR_FACTOR` / `GEO_FALLBACK_SPEED_MPH` (last resort) | `1.3` / `25` | same | same | same |

Consumers: orders-service (`GEO_URL`, checkout distance), seller-service (`GEO_URL`, geocoding and pickup zones),
fulfilment-service (`GEO_URL`, driver suggestions).

Renamed in this change (old names are no longer read): `AUTH_ISSUER_URI` and identity's `AUTH_ISSUER` ->
`AUTH_ISSUER_URL`; `AUTH_TOKEN_URI` -> `AUTH_TOKEN_URL`; identity's `STOREFRONT_URL` -> `PUBLIC_WEB_URL` and
`BFF_URL` -> `API_BASE_URL`; notifications' `LINKS_STOREFRONT` / `LINKS_SELLER_CENTER` / `LINKS_PROVIDER_HUB` ->
`PUBLIC_WEB_URL` / `SELLER_CENTER_URL` / `PROVIDER_HUB_URL`; storefront's `PUBLIC_BASE_URL` -> `PUBLIC_WEB_URL`.

## Identifiers that stay the same everywhere

- **RFC 9457 problem types**: `https://developers.afrotransact.com/problems/<code>`, one constant per runtime
  (`com.afrotransact.platform.web.error.ProblemTypes`, `go/pkg/problem.TypeBase` / `TypeURI`). Types are
  identifiers, never fetched, so they do not change per environment. `errors.afrotransact.com` is retired.
- **Email addresses** (`support@`, `legal@`, ...): kept; config values and Backoffice-editable seeds
  (content's site configuration) can override them.
- **Kubernetes label / taint keys** (`afrotransact.com/env`, `afrotransact.com/spot`).
- Seeded content uses relative links (`/legal/`, `/search?...`); external social profile URLs are not ours.

## Storefront host rule

One setting decides where the customer storefront lives; everything else follows from it.

| | `apex` (default; dev, uat) | `www` (prod) |
|---|---|---|
| Storefront | `https://<base>` | `https://www.<base>` |
| Other apps | `seller.`, `admin.`, `api.`, `auth.`, `mcp.`, `grafana.`, `media.`, `developers.` `<base>` | unchanged |
| The other name | prod only: `www.<zone>` 301 -> `<base>` | the apex `<base>` 301 -> `https://www.<base>`, path and query kept |
| Helm (`infra/argocd/envs/<env>/platform-values.yaml`) | `global.storefrontHost: apex` (or unset) | `global.storefrontHost: www` |
| Cloudflare (`infra/cloudflare/envs/<env>.tfvars`) | `storefront_host = "apex"` (default) | `storefront_host = "www"` |
| Worker route (`web/apps/storefront/wrangler.jsonc`) | `<base>/*` | `www.<zone>/*`; the apex has no route, the redirect is at the edge |
| OVH test environment | variable `OVH_STOREFRONT_HOST` = `apex` and ovh-web input `storefront-host` = `apex` | both `www` (the default) |

Keep the four in step: the Helm value, the Cloudflare variable, the Worker route and (OVH) the two switches. The precedence
for `PUBLIC_WEB_URL` in a pod is: a service's own `extraEnv` > `global.urls.PUBLIC_WEB_URL` > `global.storefrontHost` with
`global.domain`. `PLATFORM_DOMAIN` stays the base domain.

What derives from it, with no per-service change: gateway-bff CORS/CSRF origins, storefront origin and OIDC callback;
identity redirect and post-logout URIs, CORS, hosted-page links and WebAuthn allowed origins; links in emails and SMS
(content, bookings, notifications, delivery-runs); the storefront's `metadataBase` (canonical and Open Graph URLs); the
seller-center and staff-console "view my public page" and sign-out links; mcp-server allowed origins. The config
fallbacks in the prod files (`application-prod.yml`, `config/prod.yaml`) name `https://www.afrotransact.com`, and
`check_env_urls.py` rejects a `https://afrotransact.com` URL anywhere (the apex only redirects).

**WebAuthn.** The relying-party id is `PLATFORM_DOMAIN` (the base domain). A passkey's RP id only has to be the origin's
host or a registrable parent of it, so `afrotransact.com` is valid for the origins `https://www.afrotransact.com`,
`https://seller.afrotransact.com`, `https://admin.afrotransact.com` and `https://auth.afrotransact.com`; passkeys
registered before the switch keep working because the RP id does not change. The origins that may run the ceremony are
listed in identity's `allowed-origins` (`PUBLIC_WEB_URL`, `AUTH_ISSUER_URL`, and the apex for safety).
Not verified against a live browser: check one passkey sign-in on `www.` after the first prod rollout.

**OIDC / sessions.** The OIDC callback is `https://www.<base>/api/bff/v1/auth/oidc/callback`; the clients' registered
redirect URIs come from `PUBLIC_WEB_URL`, so identity and gateway-bff must roll out together with the edge change (a
mismatch shows as `redirect_uri` errors). Session cookies are host-only (no `Domain=` attribute), so users signed in on the
apex are signed out once; the redirect lands them on `www.` and they sign in again.

**Mobile.** Universal links (`applinks:www.afrotransact.com`) and Android verified app links use the host of
`EXPO_PUBLIC_WEB_URL`, which is `www.` in prod. The associated-domains entitlement, `apple-app-site-association` and
`assetlinks.json` must be served at `https://www.afrotransact.com/.well-known/` (not at the apex, which only redirects, and
Apple does not follow redirects for the association file). A new app build is needed for the entitlement to change; links
that still point at the apex open in the browser and reach `www.` through the redirect.

**Edge.** HSTS (1 y, subdomains, preload) is zone-wide and covers both names. The apex needs a proxied DNS record for the
redirect rule to answer (`infra/cloudflare/environment.tf` creates it). The redirect ruleset is zone-wide, so only the prod run
owns it.

## The guard

`python3 tools/ci/check_env_urls.py [paths...] [--exclude PATH] [--format github]` fails when

- a shared default or non-test source file (`application.yml`, `application-local.yml`, Go `config/local.yaml`,
  Helm `values.yaml`, `.java`/`.go`/`.ts`/`.sql`/...) names any AfroTransact host;
- a dev or uat file (`application-<env>.yml`, `config/<env>.yaml`, `values-<env>.yaml`,
  `infra/argocd/envs/<env>/`, `infra/k8s/platform/envs/<env>/`) names another environment's host, prod included;
- a prod file names a dev or uat host;
- any file names the prod apex as a URL (`https://afrotransact.com`): the prod storefront is `https://www.afrotransact.com`;
- any host is not one of the public hosts above (`staff.`, `sell.`, `www.dev.` ...);
- a problem type uses another base than the one above.

Files that hold every environment side by side (`wrangler.jsonc` / `wrangler.toml`, `eas.json`) may name any
environment's hosts, but only real public hosts and the canonical problem-type base.

Emails, problem types, label keys and the short documented `EXCEPTIONS` list in the script are allowed; tests,
fixtures, generated clients, contracts and the docs portal are not scanned. Files under
`infra/k8s/platform/<layer>/overlays/<env>/` count as that environment's files. CI: `env-urls.yml`
(`docs/engineering/ci-cd.md`).

## Per-environment configuration guard (S188)

`python3 tools/ci/check_env_config.py` (`make env-check`, CI `env-urls.yml`) checks every service:

| Rule | local | dev | uat | prod |
|---|---|---|---|---|
| Profiles: Java `application-<env>.yml`, Go `config/<env>.yaml`, Helm `values-<env>.yaml` (`env:` matches) | yes | yes | yes | yes |
| API reference pages (Swagger UI, Scalar, Redoc): Java `at.api-docs.enabled` (+ springdoc off in prod), Go `docsEnabled` | on | on | on | off |
| Trace sampling: Java `management.tracing.sampling.probability`, Go `telemetry.sampleRatio`, Helm `telemetry.sampling` (`TRACE_SAMPLING`) | 100% | 100% | 50% | 10% |
| Sizing (S182): replicas / HPA | | 1, no HPA | 2+ | 2+ (HPA min or replicas) |
| Spot pool toleration (`afrotransact.com/spot`) | | required | required | forbidden |
| `resources.requests.cpu/memory` and `limits.memory`; no `image.tag: latest` | | yes | yes | yes |
| Stripe (S282): gateway-bff `stripe.publishableKey` empty in config (`STRIPE_PUBLISHABLE_KEY` only, listed in its chart's secrets); payments `STRIPE_SECRET_KEY` / `STRIPE_WEBHOOK_SECRET` without defaults, `require-live-key` | defaults allowed | test keys | test keys | live keys |

Secrets are never in these files: each service's `values.yaml` lists the keys it expects in
`afrotransact/<env>/<service>` (rendered as an ExternalSecret), and `docs/runbooks/first-environment.md` lists them all
with their sources. Local runs use the docker compose defaults (`infra/local`, local-only credentials).
