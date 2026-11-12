# Cloudflare edge (cloudflare/cloudflare v5)

Cloudflare is the front door for every environment whatever cloud runs the backend.

| File | Owns |
|---|---|
| `zone.tf` (only when `manage_zone = true`, i.e. the prod run) | zone settings: SSL **strict**, Always HTTPS, **min TLS 1.2**, TLS 1.3, **HSTS** (1 y, subdomains, nosniff; `hsts_preload` is off until the hstspreload.org submission), a baseline **Referrer-Policy** response-header rule per environment (`edge_response_header_environments`; no CSP at the edge); **Authenticated Origin Pulls**; advanced certificates for `*.dev` / `*.uat` (two-level names are not covered by Universal SSL); WAF **managed rulesets** (Cloudflare Managed + OWASP Core); **custom rules** (block non-GET to `/admin` and `/v1/admin` outside the `afrotransact_admin_allowlist` IP list, challenge the staff console outside it, block `/actuator` and `/internal`, method allow-list); **rate limiting** on `/v1/auth` (+ `/oauth2/token`, `/login`) and checkout |
| `environment.tf` (every env) | DNS: `<base>` (or `www.<base>` with `storefront_host = "www"`), `seller.<base>`, `admin.<base>` (Workers), `api.<base>`, `auth.<base>`, `mcp.<base>`, `grafana.<base>` (origin: Tunnel ingress rules or proxied records), `media.<base>` (R2); **Workers routes** -> `storefront-<env>`, `seller-center-<env>`, `staff-console-<env>`; origin via **Cloudflare Tunnel** (default, no public LB) or proxied record to the cluster ingress; **Turnstile** widgets `sign-in`, `sign-up`, `checkout`; **R2** bucket `afrotransact-media-<env>` with custom domain |
| `pages.tf` | Cloudflare **Pages** projects `afrotransact-docs` and `afrotransact-storybook` (production branch `main`; created by the run with `pages_projects_owner = true`, dev) and, in every environment, the custom domain `developers.<base>` + CNAME for the docs portal |

**Storefront host rule** (`storefront_host`, `www` default | `apex` explicit opt-out): the storefront is `www.<base>` in every environment (`www.afrotransact.com`, `www.dev.afrotransact.com`, `www.uat.afrotransact.com`). Each environment's run creates the `www.<base>` DNS record and Workers route (`storefront-<env>`) and a proxied placeholder record for `<base>` itself; the **prod (`manage_zone`) run** owns the single zone-wide dynamic redirect ruleset "apex to www", with one 301 rule per environment in `apex_redirect_environments` (`<base>` -> `https://www.<base>` + path, query string kept). A zone has one ruleset per phase, hence one owner. An environment that sets `storefront_host = "apex"` serves the storefront at `<base>`: drop it from `apex_redirect_environments`. Turnstile widgets follow the storefront host. Keep it equal to `global.storefrontHost` in `infra/argocd/envs/<env>/platform-values.yaml` and to the Worker routes in `web/apps/storefront/wrangler.jsonc`. Moving an existing environment to `www`: apply the environment run first (new `www` record and route), then the prod run (redirect rules), redeploy the Helm releases so `PUBLIC_WEB_URL` becomes `https://www.<base>`, and re-register mobile associated domains for `www` (docs/runbooks/production-readiness.md). The old prod resources `www` and `www_redirect` were renamed (`moved` blocks keep the state).

`<base>` is `afrotransact.com` in prod and `<env>.afrotransact.com` otherwise.

```bash
cd infra/cloudflare
export CLOUDFLARE_API_TOKEN=...          # Zone: Settings, DNS, WAF, SSL, Workers Routes; Account: Turnstile, R2, Tunnel, Lists, Pages
tofu init -backend-config=backend.hcl -backend-config="key=cloudflare/dev.tfstate"
tofu apply -var-file=envs/dev.tfvars
tofu output -json turnstile_site_keys    # NEXT_PUBLIC_TURNSTILE_SITE_KEY_* for the web apps
tofu output -json turnstile_secrets      # TURNSTILE_SECRET_* into the identity / orders secrets
```

Tunnel mode: `cloudflared` runs in the cluster (`infra/k8s/platform/edge/base/cloudflared.yaml`) with the tunnel token
(`cloudflared tunnel token <tunnel_id>`) stored as the Secret `cloudflared` (key `token`, namespace `ingress`), applied from SOPS by `infra/ops/secrets/apply.sh` on the OVH launch path (ESO key `afrotransact/<env>/cloudflared` on the OKMS/other-cloud path);
the tunnel's ingress rules send every origin host to `http://envoy-gateway.ingress.svc.cluster.local:80`. Ingress mode: set `origin_target` to the load balancer and
make Envoy require Cloudflare's origin-pull client certificate. Rate-limit periods above 10 s need a
Pro/Business plan; adjust `auth_rate_limit` / `checkout_rate_limit` to the zone's plan.
