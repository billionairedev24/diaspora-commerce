# Cloudflare edge (cloudflare/cloudflare v5)

Cloudflare is the front door for every environment whatever cloud runs the backend.

| File | Owns |
|---|---|
| `zone.tf` (only when `manage_zone = true`, i.e. the prod run) | zone settings: SSL **strict**, Always HTTPS, **min TLS 1.2**, TLS 1.3, **HSTS** (1 y, subdomains, preload, nosniff); **Authenticated Origin Pulls**; advanced certificates for `*.dev` / `*.uat` (two-level names are not covered by Universal SSL); WAF **managed rulesets** (Cloudflare Managed + OWASP Core); **custom rules** (block non-GET to `/admin` and `/v1/admin` outside the `afrotransact_admin_allowlist` IP list, challenge the staff console outside it, block `/actuator` and `/internal`, method allow-list); **rate limiting** on `/v1/auth` (+ `/oauth2/token`, `/login`) and checkout |
| `environment.tf` (every env) | DNS: `<base>`, `seller.<base>`, `admin.<base>` (Workers), `api.<base>`, `auth.<base>` (origin), `media.<base>` (R2); **Workers routes** -> `storefront-<env>`, `seller-center-<env>`, `staff-console-<env>`; origin via **Cloudflare Tunnel** (default, no public LB) or proxied record to the cluster ingress; **Turnstile** widgets `sign-in`, `sign-up`, `checkout`; **R2** bucket `afrotransact-media-<env>` with custom domain |

`<base>` is `afrotransact.com` in prod and `<env>.afrotransact.com` otherwise.

```bash
cd infra/cloudflare
export CLOUDFLARE_API_TOKEN=...          # Zone: Settings, DNS, WAF, SSL, Workers Routes; Account: Turnstile, R2, Tunnel, Lists
tofu init -backend-config=backend.hcl -backend-config="key=cloudflare/dev.tfstate"
tofu apply -var-file=envs/dev.tfvars
tofu output -json turnstile_site_keys    # NEXT_PUBLIC_TURNSTILE_SITE_KEY_* for the web apps
tofu output -json turnstile_secrets      # TURNSTILE_SECRET_* into the identity / orders secrets
```

Tunnel mode: run `cloudflared` in the cluster with the tunnel token (`cloudflared tunnel token <tunnel_id>`),
stored in the secret store and synced by ESO. Ingress mode: set `origin_target` to the load balancer and
make Envoy require Cloudflare's origin-pull client certificate. Rate-limit periods above 10 s need a
Pro/Business plan; adjust `auth_rate_limit` / `checkout_rate_limit` to the zone's plan.
