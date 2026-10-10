# Environments, hosts and URL settings

Only configuration knows which environment a service runs in. Code and shared defaults never name a public
host: they read a small set of shared settings, default them to the local stack, and every deployed
environment supplies its own values. `tools/ci/check_env_urls.py` enforces this.

## Hosts per environment

The edge is defined once in `infra/cloudflare` (`local.hosts` in `main.tf`): the base domain is
`<env>.afrotransact.com` outside prod and the bare zone in prod; every public host is `<name>.<base>`.

| Purpose | local | dev | uat | prod |
|---|---|---|---|---|
| Storefront (`PUBLIC_WEB_URL`) | `http://localhost:3000` | `https://dev.afrotransact.com` | `https://uat.afrotransact.com` | `https://afrotransact.com` (+ `www.` on the same Worker) |
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
`admin.`). `staff.`, `sell.`, `provider.`, `identity.` and `www.<env>.` hosts do not exist; Provider Hub is served
by the Seller Center app. `mcp.`, `developers.` and `grafana.` are not created by `infra/cloudflare` yet (they are
in use by mcp-server, the docs portal and kube-prometheus-stack); add them there before relying on them.
Local ports come from `infra/local/PORTS.md`.

## Shared settings

| Setting | Meaning | Main consumers |
|---|---|---|
| `PLATFORM_ENV` | `local` / `dev` / `uat` / `prod` (same as `APP_ENV`) | everything |
| `PLATFORM_DOMAIN` | the environment's base domain (`dev.afrotransact.com`, ..., `afrotransact.com`) | identity WebAuthn RP id, mcp-server docs-portal origin |
| `PUBLIC_WEB_URL` | storefront origin | identity (home, redirect URIs, CORS, WebAuthn), content newsletter links, bookings manage link, notifications links, storefront cache purges, ai-gateway OpenRouter referer, mcp-server allowed origins |
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
| `MCP_ALLOWED_ORIGINS` | `PUBLIC_WEB_URL` + `https://developers.<PLATFORM_DOMAIN>` (local: + MCP inspector) | mcp-server |
| `MCP_PUBLIC_URL` | set per environment in `values-<env>.yaml` (own host) | mcp-server |
| `PUBLIC_BASE_URL` | `<API_BASE_URL>/notifications`, `<API_BASE_URL>/carrier-gateway` | notifications, carrier-gateway |
| `PROVIDER_HUB_URL` | `SELLER_CENTER_URL` | notifications |
| `at.api-docs.contact-url` | the developer portal | every Java service (OpenAPI info) |
| `OFF_USER_AGENT`, `NOMINATIM_USER_AGENT`, `EMAIL_FROM`, `EMAIL_REPLY_TO`, `EMAIL_UNSUBSCRIBE_MAILTO` | contact addresses in config | catalog, geo, notifications |

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

## The guard

`python3 tools/ci/check_env_urls.py [paths...] [--exclude PATH] [--format github]` fails when

- a shared default or non-test source file (`application.yml`, `application-local.yml`, Go `config/local.yaml`,
  Helm `values.yaml`, `.java`/`.go`/`.ts`/`.sql`/...) names any AfroTransact host;
- a dev or uat file (`application-<env>.yml`, `config/<env>.yaml`, `values-<env>.yaml`,
  `infra/argocd/envs/<env>/`, `infra/k8s/platform/envs/<env>/`) names another environment's host, prod included;
- a prod file names a dev or uat host;
- any host is not one of the public hosts above (`staff.`, `sell.`, `www.dev.` ...);
- a problem type uses another base than the one above.

Emails, problem types, label keys and the short documented `EXCEPTIONS` list in the script are allowed; tests,
fixtures, generated clients, contracts and the docs portal are not scanned. See `docs/engineering/ci-cd.md` for
the CI step.
