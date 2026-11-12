# Cloudflare edge (cloudflare/cloudflare v5)

Cloudflare is the front door for every environment whatever cloud runs the backend.

## Hostnames: one map, one label below the zone

Every public hostname comes from **`hosts.json`** (this directory), read by this module (`main.tf`, `local.hosts`), by `tools/ci/env_hosts.py` (generates the Argo CD host values, checks every
file) and by `tools/ci/check_env_urls.py` (CI). Cloudflare's **free** Universal certificate covers the zone apex and **one** label below it, so a nested name such as `api.uat.afrotransact.com`
would need the paid Advanced Certificate Manager and is **not used**. The scheme (zone `afrotransact.com`):

| env | storefront | seller | admin | api | auth | media | developers |
|---|---|---|---|---|---|---|---|
| prod | `afrotransact.com` | `seller.` | `admin.` | `api.` | `auth.` | `media.` | `developers.` |
| uat | `uat.afrotransact.com` | `seller-uat.` | `admin-uat.` | `api-uat.` | `auth-uat.` | `media-uat.` | `developers-uat.` |
| dev | `dev.afrotransact.com` | `seller-dev.` | `admin-dev.` | `api-dev.` | `auth-dev.` | `media-dev.` | `developers-dev.` |
| trial | `test.afrotransact.com` | `seller-test.` | `admin-test.` | `api-test.` | `auth-test.` | `media-test.` | not attached |

(every non-prod host is `<role>-<suffix>.afrotransact.com`; the storefront is `<suffix>.afrotransact.com`; there is no `www.` host and no apex redirect). To add an environment or move the trial to
another zone edit `hosts.json` (`python3 tools/ci/env_hosts.py set-trial-zone <zone>`), run `python3 tools/ci/env_hosts.py sync` and `check`.

| File | Owns |
|---|---|
| `hosts.json` | the host map above |
| `zone.tf` (only when `manage_zone = true`, i.e. the prod run) | zone settings: SSL **strict**, Always HTTPS, **min TLS 1.2**, TLS 1.3, **HSTS** (1 y, subdomains, nosniff; `hsts_preload` is off until the hstspreload.org submission), a baseline **Referrer-Policy** response-header rule listing every host of the environments in `edge_response_header_environments` (no CSP at the edge); **Authenticated Origin Pulls** (only meaningful with `origin_mode = "ingress"`); WAF **managed rulesets** (Cloudflare Managed + OWASP Core); **custom rules** (block non-GET to `/admin` and `/v1/admin` outside the `afrotransact_admin_allowlist` IP list, challenge the staff console hosts `admin.` and `admin-*` outside it, block `/actuator` and `/internal`, method allow-list); **rate limiting** on `/v1/auth` (+ `/oauth2/token`, `/login`) and checkout |
| `environment.tf` (every env) | DNS: the storefront and `seller`, `admin` (Workers), `api`, `auth` (origin: Tunnel ingress rules or proxied records), `media` (R2); **Workers routes** -> `storefront-<env>`, `seller-center-<env>`, `staff-console-<env>`; origin via **Cloudflare Tunnel** (default, no public LB) or a proxied record to the cluster ingress; **Turnstile** widgets `sign-in`, `sign-up`, `checkout` (nothing reads them yet); the two **R2 buckets** `afrotransact-<env>-media` (custom domain `media`) and `afrotransact-<env>-kyc-evidence` (private) |
| `pages.tf` | Cloudflare **Pages** projects `afrotransact-docs` and `afrotransact-storybook` (created by the run with `pages_projects_owner = true`, dev) and, in every environment with `developers_enabled` (all but the trial), the `developers` host for the docs portal |

**R2 is defined here and only here** (`local.r2_buckets`, `tofu output r2_buckets`); `docs/runbooks/r2-buckets.md` follows it. CORS policies and R2 API tokens are dashboard steps (not managed by the module).

```bash
cd infra/cloudflare
export CLOUDFLARE_API_TOKEN=...          # Zone: Settings, DNS, WAF, SSL, Workers Routes; Account: Turnstile, R2, Tunnel, Lists, Pages
tofu init -backend-config=backend.hcl -backend-config="key=cloudflare/trial.tfstate"     # one state per environment
tofu apply -var-file=envs/trial.tfvars     # dev | uat | trial | prod
tofu output r2_buckets                     # the two bucket names
tofu output web_origins                    # the AllowedOrigins of the private bucket's CORS policy
tofu output -json turnstile_site_keys      # NEXT_PUBLIC_TURNSTILE_SITE_KEY_* (nothing reads them yet)
tofu output -json turnstile_secrets        # TURNSTILE_SECRET_*
```

The trial (`envs/trial.tfvars`) never touches the zone apex (prod's storefront) and has `manage_zone = false`: the prod run owns the zone-wide rules and already covers its hosts.

Tunnel mode: `cloudflared` runs in the cluster (`infra/k8s/platform/edge/base/cloudflared.yaml`) with the tunnel token
(`cloudflared tunnel token <tunnel_id>`) stored as the Secret `cloudflared` (key `token`, namespace `ingress`), applied from SOPS by `infra/ops/secrets/apply.sh` on the OVH launch path;
the tunnel's ingress rules send every origin host to `http://envoy-gateway.ingress.svc.cluster.local:80`. Ingress mode (not used on OVH): set `origin_target` to the load balancer and
make Envoy require Cloudflare's origin-pull client certificate. Rate-limit periods above 10 s need a Pro/Business plan; adjust `auth_rate_limit` / `checkout_rate_limit` to the zone's plan.
