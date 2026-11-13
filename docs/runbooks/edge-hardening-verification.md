# Edge hardening: post-deploy verification (security audit F2, F3, F4, F5, F14)

The code, tests and config for these fixes are in the repo. **Live behaviour through Cloudflare, the Tunnel, Envoy and the Workers is UNVERIFIED** until
the checks below pass on the trial (`*-test.afrotransact.com`), then uat, then prod. Run them from a machine outside the cluster. Replace `<api>`,
`<web>`, `<auth>`, `<admin>` with the environment's hosts (`infra/cloudflare/hosts.json`). Nothing here needs a secret.

## What changed (one paragraph each)

- **F2 client IP.** One trusted source everywhere. The three web `/api/bff` proxies send the visitor from `cf-connecting-ip` only (never an incoming
  `x-forwarded-for`). The BFF takes the address from one configured header (`rateLimit.clientIpHeader`, `CF-Connecting-IP` in dev/uat/prod, empty locally =
  socket peer) and ignores `X-Forwarded-For` entirely. Identity gets the same through `at.client-ip.header: CF-Connecting-IP`
  (`TrustedClientIpFilter`, honoured only from an in-cluster peer), so its per-IP sign-in throttle, device and reset records see the visitor. Cloudflare
  rate-limit and block rules match with `contains`, so the prefixed public paths are covered.
- **F3 operational endpoints.** Cloudflare blocks any path containing `/actuator`, `/internal`, `/metrics`; Envoy routes (prod, uat) answer 404 for
  `actuator, metrics, internal, admin, v1/admin, readyz, openapi.yaml, v3/api-docs, docs, redoc, swagger-ui.html` (and `healthz`, except `/bff/healthz`)
  under every published prefix and for `/actuator, /metrics, /internal, /readyz, /healthz, ...` on the auth host. In the apps, the Helm library chart
  now runs a **management port** (9090: `MANAGEMENT_SERVER_PORT` for Java, `MANAGEMENT_ADDR=:9090` for Go). Java moves all of actuator there (probes
  and Prometheus), Go serves `/metrics` (and probes) there and drops `/metrics` from the public listener; in uat/prod a Go service without a management
  address serves no `/metrics` at all. On the public Java listener `/actuator/prometheus` now needs a token. Alloy scrapes the pod annotation, which now
  names port 9090; the NetworkPolicy admits `observability` on 9090 only. No Gateway route or Service port 80 reaches it.
- **F4 CSP.** `CSP_MODE=enforce` for uat, trial and prod on storefront, seller-center and staff-console; dev/preview stay `report-only`. The report endpoint
  `/api/csp-report` stays on.
- **F5 fail-closed.** Java `SecurityEnabledGuard` (platform-security) refuses to start when `at.security.enabled=false` and any active profile or `APP_ENV`
  is not `local`, `test` or `it`. Go services with `AUTH_ENABLED` (cart, geo, search, promise, carrier-gateway, notifications, media) refuse to start when
  `auth.enabled=false` and `APP_ENV` is not local/test (`go/pkg/config.RequireAuthOutsideLocal`). `tools/ci/check_security_off.py` (workflow
  `env-urls.yml`, `make env-check`) fails on any deployable file that sets it off.
- **F14 minimal scopes.** The gateway-bff guest paths ask identity for `media:career-resume` (résumé upload, media) and `payments:membership-offer.read`
  (membership tiers, payments); `servicetokens.Token` now returns an error for an empty scope, so a scope-less token can never be requested again. Both
  scopes are created in identity migration `V46` and granted to the `gateway-bff` client in every `application-*.yml`.
- **F16 acting-as.** `httpclient.Request.UserBearer` is true only for the person's own relayed token; only then does a storefront request add
  `X-Acting-As: CUSTOMER`. Service-token calls (the default) never do. The two paths that can carry either token (membership tiers, résumé upload and
  complete) are marked with `port.WithServiceBearer` on the guest side. `LocalActorResolver` is unchanged on purpose (see "Not changed").

## Order of rollout

1. Merge. Roll **identity first** (new migration V46 and scopes), then payments and media (accept the scopes), then gateway-bff, then everything else
   (management port). If gateway-bff ships before identity, guest résumé upload and the membership offer fail until V46 and the client scopes are live.
2. The management port must exist before the old public `/actuator/prometheus` stops answering: they ship in the same chart change, so a normal Argo CD
   sync of a service moves probes, annotation and port together.
3. Apply the Cloudflare change (`tofu apply` in `infra/cloudflare`, prod run owns the zone) after the Envoy routes are synced.

## F2: client IP and rate limits

```sh
# 1. The spoofed X-Forwarded-For must not buy extra attempts. After 20 requests/min the BFF answers 429 whatever the header says.
for i in $(seq 1 30); do curl -s -o /dev/null -w '%{http_code} ' -H "X-Forwarded-For: 198.51.100.$i" "https://<api>/bff/v1/auth/login"; done; echo
#    expect: some 302/200, then 429 (Cloudflare 429 or BFF RATE_LIMITED). If it never reaches 429 within ~60 requests, the header is still trusted somewhere.

# 2. The same through a web host (the Worker proxy).
for i in $(seq 1 30); do curl -s -o /dev/null -w '%{http_code} ' -H "X-Forwarded-For: 198.51.100.$i" "https://<web>/api/bff/v1/auth/login"; done; echo

# 3. Cloudflare rule covers the real paths (expect a Cloudflare 429 page, `server: cloudflare`, after the configured requests per period)
curl -si "https://<auth>/oauth2/token" -X POST | head -3
```

**What to look at in the Workers/BFF logs:** a request from your own IP shows your public address as the client IP in the BFF (not a Cloudflare or Worker
address). If **every** web user shares one address in the BFF (all show a Cloudflare/Worker IP), `CF-Connecting-IP` is not the visitor on Worker
subrequests: set the shared secret `GATEWAY_PROXY_TOKEN` (BFF, Secret) and `BFF_PROXY_TOKEN` (the three Workers) to the same value; the proxy then
also sends `x-at-client-ip` and the BFF believes it only with a matching `x-at-proxy-token`. Do this on the trial before prod.

Identity: sign in with a wrong password from two different networks; `sign_in_attempt.ip` (and the sign-in activity page) must show each visitor's address,
not the Envoy pod's 10.x address.

## F3: operational endpoints

```sh
for p in /bff/actuator/prometheus /bff/metrics /bff/readyz /bff/openapi.yaml /identity/actuator/prometheus /identity/actuator/health \
         /bookings/actuator/info /fulfilment/metrics /media/internal/x /notifications/actuator/prometheus /actuator/prometheus /metrics; do
  printf '%-45s %s\n' "$p" "$(curl -s -o /dev/null -w '%{http_code}' "https://<api>$p")"
done
for p in /actuator/prometheus /actuator/health /metrics /internal/x /healthz /readyz /openapi.yaml; do
  printf '%-45s %s\n' "auth $p" "$(curl -s -o /dev/null -w '%{http_code}' "https://<auth>$p")"
done
```

Expect **403** (Cloudflare block rule) or **404** (Envoy deny) for every line, never 200 or 401. **`/bff/healthz` must still return 200** (external
uptime check). Then from inside the cluster (a debug pod in the service namespace):

```sh
kubectl -n <ns> exec deploy/<service> -- wget -qO- http://127.0.0.1:9090/actuator/prometheus | head -3     # Java: 200 on the management port
kubectl -n <ns> exec deploy/gateway-bff -- wget -qO- http://127.0.0.1:9090/metrics | head -3               # Go: 200 on the management port
kubectl -n <ns> exec deploy/<service> -- wget -qO- http://127.0.0.1:8080/actuator/prometheus              # Java public port: 401/404, not metrics
kubectl -n <ns> exec deploy/gateway-bff -- wget -S -qO- http://127.0.0.1:8080/metrics                      # Go public port: 404
kubectl -n <ns> get pods          # all Ready: probes moved to 9090 (Java) work
```

Grafana Cloud / Alloy: `up{job="annotated_pods"}` stays 1 for every service, `ScrapeTargetFailing` does not fire after the rollout.

## F4: CSP enforcement

```sh
for h in <web> <seller> <admin>; do curl -sI "https://$h/" | grep -i '^content-security-policy'; done
```

Expect `content-security-policy:` (not `-report-only`) with a `nonce-`. Then walk sign-in, search, product, cart, card checkout (Stripe Elements), Seller
Center onboarding (Stripe Connect) and the staff console with the browser console open: no `Refused to ...` lines. Watch `csp.violation` log lines for
48 hours (`wrangler tail --env <env> --search csp.violation`). **Rollback** (no code change): set `CSP_MODE=report-only` for the app in `wrangler.jsonc`
or `wrangler deploy --env <env> --var CSP_MODE:report-only` (`docs/runbooks/csp-violations-spike.md`).

Known, accepted: the staff console previews e-mail HTML in a sandboxed `srcdoc` frame that inherits `img-src`; remote images inside a previewed e-mail are
blocked by design.

## F5: fail-closed

```sh
# Local proof (no cluster needed):
cd java && ./mvnw -o -pl libs/platform-security test -Dtest=SecurityEnabledGuardTest
cd go/pkg && go test ./config -run RequireAuthOutsideLocal
python3 tools/ci/check_security_off.py
```

Live: in the trial, `kubectl set env deploy/<svc> AT_SECURITY_ENABLED=false` must send the pod to CrashLoopBackOff with `Refusing to start:` in its log
(then revert it). Do not do this in prod.

## F14 / F16

```sh
# Guest career upload and membership offer still work signed out (scopes issued, receivers accept):
curl -s "https://<api>/bff/v1/membership/tiers" | head -c 200
curl -s -X POST "https://<api>/bff/v1/careers/uploads" -H 'content-type: application/json' \
  -d '{"fileName":"cv.pdf","contentType":"application/pdf","size":1000}' | head -c 200
```

Identity log for those two calls shows a token request with scope `payments:membership-offer.read` / `media:career-resume` (not an empty scope). A
signed-in seller who is also a shopper still shops as themselves (dual-role tests cover it in CI).

## Not changed on purpose

- **`LocalActorResolver` / `go/pkg/auth.LocalActor`** still map a SERVICE token to the local staff super admin: `make up` runs several services with
  security off and relies on it. The audit's coupling is fixed at the client instead (a service token never sends `X-Acting-As`).
- The Go `/healthz` and `/readyz` stay on the public listener (kubelet probes and `/bff/healthz` uptime checks); Envoy and Cloudflare keep everything but
  `/bff/healthz` away from the internet.

## Not run in the repo environment

Docker integration tests (Testcontainers ITs of identity, payments, seller, ...), Playwright against a deployed enforce build, `tofu plan/apply` (only
`tofu fmt`), a live Cloudflare/Tunnel/Envoy request, Alloy scraping, and Workers-subrequest behaviour of `CF-Connecting-IP`.
