# Security headers and Content-Security-Policy

Who sets what, how to change it, and how a new policy is rolled out. Verified against the code on the day this page was written;
the tests named below keep the key facts true.

| Layer | Sets | Where |
|---|---|---|
| Identity (Spring Security, every response of the service) | **enforced** CSP, HSTS (secure requests), `nosniff`, `Referrer-Policy`, `Permissions-Policy`, `X-Frame-Options`, `Cache-Control` | `java/services/identity/.../config/IdentitySecurityHeaders.java`, `adapter/in/security/HostedPageContentSecurityPolicy.java` |
| storefront, seller-center, staff-console (Next.js) | per-request **nonce** CSP, **Report-Only until promoted**; `nosniff`, `Referrer-Policy`, `X-Frame-Options`, `Permissions-Policy` stay in `next.config.ts` | `src/middleware.ts` of each app, builder in `web/packages/ui/src/security/csp.ts` (`@afrotransact/ui/csp`) |
| notifications-service guest stock-alert page | its own strict CSP (unchanged) | notifications-service |
| Cloudflare edge | HSTS 1 y + `includeSubDomains` (no preload), `nosniff`, baseline `Referrer-Policy` when the origin sent none. **No CSP at the edge**: apps own it (nonces are per request) | `infra/cloudflare/zone.tf` |

## Identity (enforced)

Policy (everything except the API reference pages `/docs`, `/redoc`, `/swagger-ui*`, which third-party scripts render and which
are not served to the public):

```
default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'self';
base-uri 'none'; frame-ancestors 'none'; object-src 'none';
form-action 'self' <origin of the post-sign-in home URL> <origin of the pending authorization request's redirect_uri>
```

- **No inline script or style exists** on the hosted pages (templates, `auth.js`, `phone.js`, one stylesheet set, self-hosted Figtree
  fonts, SVG logo marks use presentation attributes only). `TemplatesCspCleanTest` fails the build if a template gains `<style>`,
  `style=`, an `on*=` handler, an inline `<script>`, `javascript:` or a third-party `src`/`href`, or an asset gains a remote
  `@import`/`url()`, `innerHTML` or `eval`. `data:` images are allowed for completeness; none are used today.
- **`form-action` and redirects.** Chromium applies `form-action` to every redirect after a form post, not only to the form's own
  target: `POST /login` -> `/oauth2/authorize` -> `https://www.<base>/api/auth/callback` would be blocked with a plain `'self'`.
  Firefox and Safari do not enforce it on redirects, so this only shows in Chromium. The header writer therefore adds the origin of
  the saved authorization request's `redirect_uri` (custom schemes of mobile apps as `scheme:`), but **only when that exact URI is
  registered for the request's `client_id`**, plus the origin of `identity.home-url` for sign-ins without a pending request. A
  crafted authorize URL cannot widen the policy (`SecurityHeadersTest`). Social login is a link (GET), not a form post, so
  Google/Apple/Facebook need no entry; the provider's callback is a navigation to identity itself.
- **HSTS** `max-age=31536000 ; includeSubDomains`, written by Spring Security only when `request.isSecure()`; behind Cloudflare and
  the ingress that is true because `server.forward-headers-strategy=framework` honours `X-Forwarded-Proto`. Plain HTTP (local) gets none.
- `Permissions-Policy: publickey-credentials-get=(self), publickey-credentials-create=(self), camera=(), microphone=(), geolocation=(), payment=(), usb=(), interest-cohort=()`
  (passkeys run on the hosted pages).
- `Cache-Control: no-cache, no-store, max-age=0, must-revalidate` on dynamic pages is Spring Security's default (verified in
  `SecurityHeadersTest`); static assets keep their own caching. No devtools/livereload dependency is on the identity classpath.

Change it: edit `HostedPageContentSecurityPolicy.BASE` (policy), `IdentitySecurityHeaders` (other headers); a new third-party host
must be added to the exact directive and to `SecurityHeadersTest`. Debug a blocked sign-in in the browser console
(`Refused to ... because it violates the following Content Security Policy directive`). Identity has no report endpoint: it is
enforced from day one and the page set is small and tested.

## Web apps (nonce CSP, Report-Only first)

`CSP_MODE` per app (Worker var in `wrangler.jsonc`, env var elsewhere): `report-only` (default, also for an unknown value),
`enforce`, `off`. Header names: `Content-Security-Policy-Report-Only` vs `Content-Security-Policy`. Every environment is
`report-only` today, including prod; switching an environment to `enforce` is the promotion step below.

| Directive | Value | Why (from the code) |
|---|---|---|
| `script-src` | `'self' 'nonce-<per request>' 'strict-dynamic'` (+ Stripe hosts on storefront, seller-center) (+ `'unsafe-eval'` only when `NODE_ENV=development`) | Next stamps the nonce on its scripts; Stripe.js is injected by our code (`src/lib/payments/stripe.ts`, seller `src/lib/stripe.ts`) and trusted through `strict-dynamic`; the host entries are the fallback for browsers without it. `next dev` needs eval for React refresh |
| `style-src` | `'self' 'unsafe-inline'`, **no nonce** | **The one loosened directive.** React renders `style=""` attributes (nonces cannot cover attributes) and the staff console previews email HTML in a `srcdoc` iframe that inherits the policy; a nonce here would switch `'unsafe-inline'` off |
| `img-src` | `'self' data: blob:` + media origin (`MEDIA_PUBLIC_URL`, `NEXT_PUBLIC_MEDIA_PUBLIC_URL`; `http://localhost:9000` only when `APP_ENV` is not dev/uat/prod) + `https://*.stripe.com` on the Stripe apps | product/tile images come from the media CDN as plain `<img>`; `blob:` for upload previews; Stripe card/brand images |
| `media-src` | `'self' blob:` + media origin | |
| `connect-src` | `'self'` + origin of `NEXT_PUBLIC_OTEL_EXPORTER_OTLP_ENDPOINT` + origin of the `NEXT_PUBLIC_SENTRY_DSN` ingest host (only when set) + `https://api.stripe.com` on the Stripe apps (+ `ws: wss:` in dev only) | the browser only calls its own origin (`/api/bff` proxies the BFF, so the BFF origin is **not** listed); browser telemetry (OTLP, Sentry) and Stripe.js |
| `frame-src` | storefront: `https://js.stripe.com https://*.js.stripe.com https://hooks.stripe.com`; seller-center: the same plus `https://connect-js.stripe.com`; staff-console: `'none'` | Payment/Express Checkout elements, 3-D Secure, Connect embedded onboarding. `srcdoc` email previews are not governed by `frame-src` |
| `font-src` | `'self'` | Inter/Fraunces/Figtree are self-hosted (`@afrotransact/tokens`). **No Google Fonts**: `fonts.googleFontsUrl` is only handed to Stripe's own iframe (`Elements({ fonts })`), which has its own origin and policy |
| `worker-src` / `manifest-src` | `'self' blob:` / `'self'` | |
| `object-src` / `frame-ancestors` | `'none'` | |
| `base-uri` / `form-action` | `'self'` | no form posts off-site; sign-in is a redirect (navigation), which `form-action` does not govern |
| `report-uri` / `report-to` | `/api/csp-report` / group `csp-endpoint` (`Reporting-Endpoints` header) | |

Not allowed because the code does not use them: Turnstile/captcha, analytics (Cloudflare Web Analytics, GA), Google Maps (Stripe's
optional address autocomplete would need `maps.googleapis.com`), Nominatim (`createNominatimSearch` in `@afrotransact/ui` is exported
but no app calls it; if it is wired, add `https://nominatim.openstreetmap.org` to `connect-src`), YouTube or other embeds. The
OpenStreetMap link in Ship from is a normal link. Runtime behaviours not provable from the code (Express Checkout wallets, 3-D Secure
issuers) are exactly what the Report-Only phase is for.

The middleware runs on the **edge runtime** (`middleware.ts`, deprecated name in Next 16 but supported): Cloudflare's OpenNext
adapter supports it, while a Node-runtime `proxy.ts` is experimental there. Moving to `proxy.ts` later is a rename once that is
supported. Pages must render per request for the nonce to apply (all three root layouts call `getLocale()`, which reads cookies and headers, so every page does); a
statically prerendered page would carry no nonce and be reported.

Mock mode (`NEXT_PUBLIC_API_MOCKS=1`, MSW-style in-process BFF) and Playwright e2e are unaffected: the mocks run server side, e2e
runs `next start` in Report-Only, which never blocks, and no e2e spec injects inline scripts.

### Change it

Edit `web/packages/ui/src/security/csp.ts` (`directives()`), add the test in `web/packages/ui/test/csp.test.ts`, run the app tests.
The matcher in each `src/middleware.ts` must stay a literal (Next analyses it statically); each app's `test/csp-middleware.test.ts`
fails when the middleware or the report route is missing, when the matcher drifts from `CSP_MATCHER`, and checks nonce uniqueness,
modes and dev vs prod. New allowances need a reason in this table.

### Run Report-Only and read reports

```bash
# local: report-only is the default; to see enforcement break things:
CSP_MODE=enforce pnpm --filter @afrotransact/storefront dev     # also seller-center, staff-console
# reports show in the browser console, and as one JSON line per violation on the server console
```

Reports arrive at `POST /api/csp-report` (legacy `application/csp-report` and Reporting API batches). The handler logs one line,
`console.warn`, nothing is stored, at most 10 lines per request, 120 requests per minute per runtime instance (429 over that,
413 over 16 KB):

```json
{"event":"csp.violation","app":"storefront","directive":"img-src","disposition":"report","blocked":"https://cdn.example","page":"/s","source":"https://www.example/_next/static/chunks/a.js","line":1,"column":2345}
```

`blocked` is an origin or `inline|eval|data|blob`; query strings, fragments, referrers and script samples are dropped, so there is no PII.
In deployed environments the lines are in Cloudflare Workers Logs (`observability.enabled` in `wrangler.jsonc`):
Workers & Pages > `storefront-uat` > Logs, search `csp.violation`, group by `directive` and `blocked`; live: `pnpm exec wrangler tail --env uat --search csp.violation`.
Ignore noise from browser extensions: `blocked` of `inline`/`eval`/`blob` with a `source` that is not on our origin, or a
`chrome-extension://` origin.

### Rollout plan

1. **uat, 7 days of `report-only`** with real traffic from the team: walk sign-in, search, product, cart, checkout (card, Express
   Checkout, 3-D Secure test card `4000 0027 6000 3184`), account, seller onboarding with Connect, listing images, staff email preview.
2. **Review daily**: every distinct `(directive, blocked)` pair is either fixed in the code, allowed here with a reason, or
   dismissed as extension noise. Expected sources of change: media CDN hostname (`MEDIA_PUBLIC_URL` set per Worker), telemetry hosts.
3. **Seven days with no unexplained reports** (or only dismissed ones) -> set `CSP_MODE=enforce` in uat (`wrangler.jsonc` `env.uat.vars`) and repeat the walk for two days.
4. **prod** stays `report-only` for the same 7-day review on real customers, then `enforce`. Change the three `wrangler.jsonc`
   `env.prod.vars` values in one reviewed PR and deploy; record the date in `docs/runbooks/production-readiness.md`.
5. Rollback is a var change: `CSP_MODE=report-only` (or `off`) and redeploy (`wrangler deploy --env prod --var CSP_MODE:report-only`); no code change.
   A spike of reports or breakage after promotion: [CSP violations spike](../runbooks/csp-violations-spike.md).

## Edge (Cloudflare)

Owned by the prod (`manage_zone`) run of `infra/cloudflare`, zone-wide:

- `cloudflare_zone_setting.hsts`: HSTS `max-age` 31536000 (`hsts_max_age`), `includeSubDomains`, **preload off** (`hsts_preload = false`
  until `afrotransact.com` is submitted at hstspreload.org and every sub-domain is HTTPS-only), `nosniff`; `hsts_enabled` toggles it. This setting
  is zone-wide: it cannot differ per environment, so lower `hsts_max_age` before a risky change.
- `cloudflare_ruleset.response_headers` (phase `http_response_headers_transform`): sets `Referrer-Policy: strict-origin-when-cross-origin`
  only when the origin sent none, for the hosts of each environment in `edge_response_header_environments` (default dev, uat, prod;
  remove one to switch it off there).
- `tofu fmt` / `tofu validate` were not run when this was written (OpenTofu is not installed in the authoring environment); run them
  and `tofu plan -var-file=envs/prod.tfvars` before applying. The plan should show the HSTS setting changing only `preload` true -> false
  (the previous code had preload on, which contradicted the launch plan's "submit when ready") and one new ruleset.
- No CSP, `X-Frame-Options` or `Permissions-Policy` at the edge: duplicates would fight the apps' per-request values.
