# Environments, hosts and URL settings

Only configuration knows which environment a service runs in. Code and shared defaults never name a public
host: they read a small set of shared settings, default them to the local stack, and every deployed
environment supplies its own values. `tools/ci/check_env_urls.py` enforces this.

## Hosts per environment

**One map, one label below the zone.** Every public hostname comes from `infra/cloudflare/hosts.json` and is generated from it (Terraform/OpenTofu in `infra/cloudflare`, the Argo CD host values by
`python3 tools/ci/env_hosts.py sync`, the Worker `wrangler.jsonc` and mobile `eas.json` files are checked against it by `python3 tools/ci/env_hosts.py check`). Cloudflare's **free** Universal
certificate covers the zone apex and **one** label below it (`*.afrotransact.com`); nested names such as `api.uat.afrotransact.com` need the paid Advanced Certificate Manager and are **not used**. The rule:

- **prod**: the storefront is the apex `afrotransact.com`; every other host is `<role>.afrotransact.com`.
- **any other environment E** (`dev`, `uat`, `test` for the 8-day trial): the storefront is `E.afrotransact.com` and every other host is `<role>-E.afrotransact.com`.
- There is **no `www.` host and no apex redirect**; mail is sent from the bare domain (`no-reply@afrotransact.com`, Resend verified it), no `mail.` host.

| env | storefront | seller | admin | api | auth | media | developers |
|---|---|---|---|---|---|---|---|
| prod | afrotransact.com | seller.afrotransact.com | admin.afrotransact.com | api.afrotransact.com | auth.afrotransact.com | media.afrotransact.com | developers.afrotransact.com |
| uat | uat.afrotransact.com | seller-uat.afrotransact.com | admin-uat.afrotransact.com | api-uat.afrotransact.com | auth-uat.afrotransact.com | media-uat.afrotransact.com | developers-uat.afrotransact.com |
| dev | dev.afrotransact.com | seller-dev.afrotransact.com | admin-dev.afrotransact.com | api-dev.afrotransact.com | auth-dev.afrotransact.com | media-dev.afrotransact.com | developers-dev.afrotransact.com |
| trial | test.afrotransact.com | seller-test.afrotransact.com | admin-test.afrotransact.com | api-test.afrotransact.com | auth-test.afrotransact.com | media-test.afrotransact.com | developers-test.afrotransact.com |

| Purpose | local | host of the table above |
|---|---|---|
| Storefront (`PUBLIC_WEB_URL`) | `http://localhost:3000` | `storefront` |
| Seller Center & Provider Hub (`SELLER_CENTER_URL`) | `http://localhost:3010` | `seller` |
| Staff console (`STAFF_CONSOLE_URL`) | `http://localhost:3002` | `admin` |
| Public API, gateway-bff (`API_BASE_URL`) | `http://localhost:8200` | `api` |
| Identity / OIDC issuer (`AUTH_ISSUER_URL`) | `http://localhost:8100` | `auth` |
| Public media CDN (`MEDIA_PUBLIC_URL`) | `http://localhost:9000/media` (MinIO) | `media` |
| MCP server (`MCP_PUBLIC_URL`; private, ClusterIP, **no public host**) | `http://localhost:8114` | `http://mcp-server.afrotransact-<env>.svc.cluster.local` (trial and prod: namespace `afrotransact-prod`) |
| Developer portal | `http://localhost:3100` | `developers` (not attached to the trial) |
| Grafana | `http://localhost:3200` | dev and uat only, `grafana-dev` / `grafana-uat` (an origin host like `api`); prod and the trial use Grafana Cloud, no public Grafana |
| Email sending domain | Mailpit | the bare zone, `afrotransact.com`, in every environment |

The staff console is `admin` (the Worker route and the WAF admin rule cover `admin.` and `admin-*`). `staff.`, `sell.`, `provider.`, `identity.` and `www.` hosts do not exist; Provider Hub is served
by the Seller Center app. `api` and `auth` are origin hosts (Cloudflare Tunnel or AOP ingress -> the edge Gateway -> HTTPRoutes in `infra/k8s/platform/edge/overlays/<env>`); mcp-server has no public
host (private, cluster-internal, bearer tokens required); `developers` is the docs portal on Cloudflare Pages (`infra/cloudflare/pages.tf`).
`PLATFORM_DOMAIN` is the **zone** in every environment (`afrotransact.com`), which is also the WebAuthn relying-party id: it must be a suffix of every web origin, and the zone is.
A different trial zone is possible (`python3 tools/ci/env_hosts.py set-trial-zone <zone>`): the host rule, not the zone, is what the guard enforces.

Public API paths on `api` (edge HTTPRoute `api`, prefix stripped before the service sees it): `/bff` gateway-bff,
`/identity`, `/bookings`, `/fulfilment`, `/media`, `/notifications`, `/carrier-gateway`, and `/payments/v1/webhooks`
(Stripe webhooks only). Other services are internal.
Local ports come from `infra/local/PORTS.md`.

## Shared settings

| Setting | Meaning | Main consumers |
|---|---|---|
| `PLATFORM_ENV` | `local` / `dev` / `uat` / `prod` (same as `APP_ENV`) | everything |
| `PLATFORM_DOMAIN` | the ZONE (`afrotransact.com`) in every environment, never a host with a label | identity WebAuthn RP id, mcp-server docs-portal origin |
| `global.webHost`, `global.sellerHost`, `global.adminHost`, `global.apiHost`, `global.authHost`, `global.mediaHost` (Helm) | FULL host names, generated from `hosts.json` into every `infra/argocd/envs/<env>/platform-values.yaml` (`tools/ci/env_hosts.py sync`); they replace the legacy derivation from `global.domain` | the at-service library, which derives every URL below from them |
| `PUBLIC_WEB_URL` | storefront origin: `https://<storefront host>` | identity (home, redirect URIs, CORS, WebAuthn), content newsletter links, bookings manage link, notifications links, storefront cache purges, ai-gateway OpenRouter referer, mcp-server allowed origins |
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
| storefront | `BFF_ORIGIN` | `http://localhost:8200` | `<API_BASE_URL>/bff` (`https://<api host>/bff`) | `/api/bff` proxy, server components |
| storefront | `SELLER_CENTER_URL` (`NEXT_PUBLIC_SELLER_CENTER_URL` still read) | `http://localhost:3010` | `https://<seller host>` | `/sell`, `/provide` redirects |
| storefront | `API_MOCKS` / `NEXT_PUBLIC_API_MOCKS` | `1` in `.env.example` | `0` (`1` on PR previews) | in-process MSW mock BFF |
| seller-center, staff-console | `BFF_ORIGIN` | `http://localhost:8200` | `https://<api host>/bff` | `/api/bff` proxy |
| storefront | `PUBLIC_WEB_URL` | unset (relative metadata) | the storefront host (`PUBLIC_WEB_URL`) | `metadataBase`: canonical and Open Graph URLs |
| seller-center, staff-console | `PUBLIC_WEB_URL` (`NEXT_PUBLIC_STOREFRONT_URL` still read) | `http://localhost:3000` | the storefront host (`PUBLIC_WEB_URL`) | sign-in, sign-out, "view my public page" links |
| customer app | `EXPO_PUBLIC_WEB_URL` | `http://<dev host>:3000` | the storefront host (`PUBLIC_WEB_URL`) | legal/help/account pages, share links, universal links (`applinks:<host>`, Android `https://<host>/app`) |
| customer app | `EXPO_PUBLIC_BFF_URL` | `http://<dev host>:8200` | `https://<api host>/bff` | screen API |
| customer app | `EXPO_PUBLIC_OIDC_ISSUER` | `http://<dev host>:8100` | `https://<auth host>` (`AUTH_ISSUER_URL`) | sign-in |
| customer app | `EXPO_PUBLIC_IDENTITY_URL` | `http://<dev host>:8100` | `https://<api host>/identity` | devices, notification preferences |
| customer app | `EXPO_PUBLIC_BOOKINGS_URL` | `http://<dev host>:8109` | `https://<api host>/bookings` | bookings |
| driver app | `EXPO_PUBLIC_BFF_URL` | `http://<dev host>:8200` | `https://<api host>/bff` | in-app sign-in |
| driver app | `EXPO_PUBLIC_FULFILMENT_URL` | `http://<dev host>:8108` | `https://<api host>/fulfilment` | driver API |
| driver app | `EXPO_PUBLIC_MEDIA_URL` | `http://<dev host>:8207` | `https://<api host>/media` | proof photos, signatures |
| driver app | `EXPO_PUBLIC_OIDC_ISSUER` | `http://<dev host>:8100` | `https://<auth host>` | sign-in |
| driver app | `EXPO_PUBLIC_IDENTITY_URL` | `http://<dev host>:8100` | `https://<api host>/identity` | push devices |
| both apps | `EXPO_PUBLIC_APP_ENV` | `local` | `dev` / `uat` / `prod` | app name, bundle id suffix, mocks |
| both apps | `EXPO_PUBLIC_DEV_HOST` | `localhost` | - | local stack host: the dev machine's LAN IP for a device, `10.0.2.2` for the Android emulator |

`<api host>`, `<seller host>`, `<auth host>` are the hosts of that role for the environment, from the table above. The gateway-bff derives its CORS/CSRF
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

## Host rule, cookies, passkeys and sessions

- **Why flat:** see "Hosts per environment". A host is one label below the zone or it has no free certificate; the guard below fails CI otherwise.
- **Storefront = the apex in prod, `E.<zone>` elsewhere:** `PUBLIC_WEB_URL` is `https://afrotransact.com` (prod), `https://uat.afrotransact.com`, `https://dev.afrotransact.com`, `https://test.afrotransact.com` (trial).
  It is the only storefront origin of gateway-bff CORS/CSRF, the OIDC callback and identity's redirect URIs; every other app has its own origin and its own callback.
- **Cookies:** the BFF session cookie `at_session` is host-only (no `Domain`: `cookieDomain: ""`, `SESSION_COOKIE_DOMAIN` unset), `Secure`, `HttpOnly`, `SameSite=Lax`, set on the app's own origin
  (each web app proxies `/api/bff`); identity's own session cookie is host-only on the `auth` host. Flat hosts therefore need no cookie-domain setting and no host shares a cookie with another.
  All hosts share the registrable domain, so they are same-site.
- **WebAuthn:** `rp-id` = `PLATFORM_DOMAIN` = the zone, a suffix of every host; identity's allowed origins are the auth host and the storefront (and the bare zone). Passkeys of a non-prod environment
  carry the same rp id as production: never reuse non-prod accounts.
- **Mobile:** universal links and associated domains use the storefront host (`afrotransact.com` in prod, `<suffix>.afrotransact.com` in the others); `apple-app-site-association` and
  `assetlinks.json` are served on it. (Native apps are not part of the launch.)
- **Edge:** HSTS (1 y, subdomains; preload only once `hsts_preload` is set) is zone-wide; the baseline Referrer-Policy rule lists every host of the environments in `edge_response_header_environments`.
  Each environment's run creates its own DNS records and Workers routes; the prod run (`manage_zone`) owns the zone-wide objects. The trial never touches the apex.
- **Legacy test box:** `infra/ovh-vps` and the `ovh-web` workflow (one server on the dev profile, `OVH_DOMAIN`) keep their own free-form host variables (`OVH_SELLER_HOST`, ...); they are not one of
  the four environments of the map and their hosts are DNS-only (Let's Encrypt), so the certificate rule does not apply to them.

## The guard

`python3 tools/ci/check_env_urls.py [paths...] [--exclude PATH] [--format github]` and `python3 tools/ci/env_hosts.py check` (the Argo CD host values, edge routes, Worker and mobile build files must equal the map) fail when

- a shared default or non-test source file (`application.yml`, `application-local.yml`, Go `config/local.yaml`,
  Helm `values.yaml`, `.java`/`.go`/`.ts`/`.sql`/...) names any AfroTransact host;
- a dev, uat or trial file (`application-<env>.yml`, `config/<env>.yaml`, `values-<env>.yaml`,
  `infra/argocd/envs/<env>/`, `infra/k8s/platform/envs/<env>/`) names another environment's host, prod included;
- a prod file names a non-prod host;
- any host is **more than one label below the zone** (`api.uat.afrotransact.com`): Cloudflare's free certificate does not cover it;
- any host is **not derived from the map** `infra/cloudflare/hosts.json` (`staff.`, `sell.`, `www.`, `mail.` ...);
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
