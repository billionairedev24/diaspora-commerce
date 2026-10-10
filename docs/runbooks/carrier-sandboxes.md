# Carrier sandboxes (USPS, UPS, FedEx, DHL Express)

**Backlog:** S204 · **Service:** carrier-gateway (`go/services/carrier-gateway`) · **Smoke script:**
`tools/smoke/carriers.sh` · **Workflow:** `.github/workflows/carrier-sandboxes.yml` (manual)

The adapters were written against each carrier's published API shapes and are covered by `httptest` fakes. This page
is how to prove them against the real sandboxes: which accounts and credentials to request, where they go, how to run
the smoke test, and what to change in code when a sandbox disagrees with the fakes.

## 1. Sandbox hosts (already configured)

`config/local.yaml`, `config/dev.yaml` and `config/uat.yaml` point every carrier at its sandbox; `config/prod.yaml` at
production. Override a host with `<CARRIER>_BASE_URL`, switch a carrier off with `<CARRIER>_ENABLED=false`. A carrier
that is enabled but has no credentials is skipped at start-up with a warning, so a partial set of credentials still
starts the service.

| Carrier | Sandbox base URL | Auth | Portal |
|---|---|---|---|
| USPS | `https://apis-tem.usps.com` (Testing Environment for Mailers) | OAuth 2.0 client credentials (`/oauth2/v3/token`) | USPS Developer Portal (developers.usps.com): app with Prices, Labels, Tracking, Addresses, Carrier Pickup |
| UPS | `https://wwwcie.ups.com` (Customer Integration Environment) | OAuth client credentials, Basic auth + `x-merchant-id` | UPS Developer Portal (developer.ups.com): app with Rating, Shipping, Tracking, Track Alert, Pickup, Address Validation |
| FedEx | `https://apis-sandbox.fedex.com` | OAuth client credentials (`/oauth/token`) | FedEx Developer Portal (developer.fedex.com): project with Rates, Ship, Track, Pickup, Address Validation; test account number |
| DHL Express | `https://express.api.dhl.com/mydhlapi/test` | HTTP Basic | DHL API Developer Portal (developer.dhl.com): MyDHL API app; test account number from the DHL Express account manager |

## 2. Credentials per carrier

The same variable names are used everywhere (env vars of the service, keys of the `afrotransact/<env>/carrier-gateway`
secret in the cloud secret store, and secrets of the GitHub environment `carrier-sandbox`).

| Carrier | Secret | Where it comes from |
|---|---|---|
| USPS | `USPS_CLIENT_ID`, `USPS_CLIENT_SECRET` | Consumer key / secret of the USPS developer app |
| USPS | `USPS_PAYMENT_TOKEN` | Payments v3 payment authorization for the CRID / MID / EPS account (out of band, expires; rotate in the secret store). Without it labels fail with `carrier-gateway.carrier-auth`, rates still work |
| UPS | `UPS_CLIENT_ID`, `UPS_CLIENT_SECRET` | UPS app credentials |
| UPS | `UPS_ACCOUNT` | 6-character shipper number (also sent as `x-merchant-id`) |
| UPS | `UPS_WEBHOOK_CREDENTIAL` | A random value we choose (`openssl rand -hex 32`); sent by UPS in the `credential` header of every Track Alert push |
| FedEx | `FEDEX_CLIENT_ID`, `FEDEX_CLIENT_SECRET` | API key / secret key of the FedEx project |
| FedEx | `FEDEX_ACCOUNT` | Sandbox account number shown in the project |
| FedEx | `FEDEX_WEBHOOK_SECRET` | Security code of the FedEx tracking webhook (Advanced Integrated Visibility) project |
| DHL | `DHL_USERNAME`, `DHL_PASSWORD` | MyDHL API key / secret |
| DHL | `DHL_ACCOUNT` | Express test account number (shipper) |
| DHL | `DHL_WEBHOOK_SECRET` | Shared secret of the DHL push subscription (HMAC-SHA256) |
| smoke (dev target only) | `SMOKE_CLIENT_ID`, `SMOKE_CLIENT_SECRET` | An identity OAuth client (client credentials) with `shipping:rate shipping:label shipping:void` |

Store them:

```bash
# GitHub environment for the ephemeral run (Settings -> Environments -> carrier-sandbox; restrict to main)
for s in USPS_CLIENT_ID USPS_CLIENT_SECRET USPS_PAYMENT_TOKEN UPS_CLIENT_ID UPS_CLIENT_SECRET UPS_ACCOUNT \
         UPS_WEBHOOK_CREDENTIAL FEDEX_CLIENT_ID FEDEX_CLIENT_SECRET FEDEX_ACCOUNT FEDEX_WEBHOOK_SECRET \
         DHL_USERNAME DHL_PASSWORD DHL_ACCOUNT DHL_WEBHOOK_SECRET; do
  gh secret set "$s" --env carrier-sandbox     # prompts for the value
done
# dev / uat: the same keys in the service secret (AWS example; GCP/Azure use afrotransact-dev-carrier-gateway)
aws secretsmanager put-secret-value --secret-id afrotransact/dev/carrier-gateway --secret-string file://carrier-gateway.json
```

Never put production carrier credentials in `carrier-sandbox` or dev/uat: the sandbox hosts reject them, and a
production key in a non-prod config would buy real labels if a host override were wrong.

## 3. Run the smoke test

The script calls carrier-gateway's public API, so it tests the whole path (auth, account selection, request mapping,
response parsing, idempotency, label storage, events) rather than the carrier APIs alone. Per carrier it runs:
rates, carrier-side address validation, label purchase, idempotent replay with the same `Idempotency-Key`, label
document download, tracking lookup, void; then a signed FedEx and DHL webhook (and the same body with a wrong signature,
which must be rejected with 401).

**In GitHub (recommended):** Actions -> carrier-sandboxes -> Run workflow.

- `target: ephemeral` builds carrier-gateway from the selected ref, runs it on the runner with `APP_ENV=local` (sandbox
  hosts, auth and Kafka off, labels on disk) against Postgres and Valkey service containers, using the
  `carrier-sandbox` environment secrets.
- `target: dev` calls `https://api.<DEV_BASE_DOMAIN>/carrier-gateway` with a token for the smoke client; dev already
  holds sandbox credentials in its secret store. Use it after a deploy to check the deployed configuration.
- `rates_only: true` buys nothing (rates + address validation only).

The job summary has one line per check; `carrier-smoke.jsonl` is uploaded as an artifact.

**Locally:**

```bash
make up                                                     # Postgres, Valkey, Kafka from infra/local
cd go/services/carrier-gateway
export APP_ENV=local GOWORK=off $(grep -v '^#' ~/.config/afrotransact/carrier-sandbox.env | xargs)   # your sandbox keys
go run ./cmd/carrier-gateway &
cd - && tools/smoke/carriers.sh                            # all carriers, http://localhost:8209
tools/smoke/carriers.sh --carriers "ups fedex" --rates-only
tools/smoke/carriers.sh --dry-run                          # print the requests only
```

Exit status 0 = every check passed (skips allowed), 1 = a check failed.

## 4. Reading failures

| Symptom | Likely cause | Fix |
|---|---|---|
| `rates FAIL status=error ... carrier-auth` | Wrong or expired client id/secret; app missing the API product | Re-issue credentials; add the product to the developer app |
| `rates FAIL status=timeout` | Sandbox slower than the fan-out budget (1.5 s; the ephemeral run uses `RATE_BUDGET=5s`) | Locally, start the service with `RATE_BUDGET=5s`; on dev, check whether the carrier is degraded. Production keeps 1.5 s |
| USPS `label FAIL ... carrier-auth` | `USPS_PAYMENT_TOKEN` missing or expired | Request a new payment authorization token |
| UPS `label FAIL ... carrier-rejected 120100` (or similar) | Shipper number not enabled for the service in CIE | Use a service from the rate quote (the script does) or ask UPS to enable it |
| DHL `label FAIL ... carrier-rejected` on customs | Test account not enabled for dutiable shipments or HS code rejected | Check the `customs` block in `tools/smoke/carriers.sh` against the MyDHL error detail |
| `void FAIL` for DHL | Expected: MyDHL has no cancel; carrier-gateway voids locally. A failure here is ours | Check the label status in the `carriers` database |
| `webhook FAIL signed push HTTP 401` | Secret, header name or encoding differs from the service's config | Same secret on both sides; header via `FEDEX_SIGNATURE_HEADER` / `DHL_SIGNATURE_HEADER` (script) and `fedex.signatureHeader` / `dhl.signatureHeader` (config) |
| `tracking skip ... 404` | Sandboxes return tracking only for their canned numbers | Expected for freshly bought sandbox labels; use the carrier's documented test tracking numbers to exercise parsing |

## 5. Remaining S204 work that needs the real sandboxes

These are the acceptance criteria that cannot be closed without credentials; record the results in the story.

1. **Rates, label, void and tracking succeed against each carrier sandbox:** run the workflow (`target: ephemeral`) and
   attach the job summary to S204.
2. **FedEx and DHL webhook signature formats confirmed and tests updated:** subscribe a test webhook in each portal to
   a reachable URL (the dev environment, `https://api.<dev domain>/carrier-gateway/v1/webhooks/fedex|dhl`), capture
   one real push (headers + raw body) from the carrier-gateway logs, and confirm the header name, the HMAC input
   (raw body vs. canonical form) and the encoding (base64 vs hex). Then add the captured payload as a fixture under
   `go/services/carrier-gateway/internal/adapter/carrier/<carrier>/testdata/` with a test that verifies its signature,
   and adjust `carrierhttp.VerifyHMAC` or `signatureHeader` if the format differs. The smoke script's webhook check
   only proves that our side and the configured secret agree; it does not prove the carrier's format.
3. **Zone charts replace the seed transit table:** download the USPS zone chart (per origin ZIP3) and the UPS, FedEx
   and DHL time-in-transit tables for each fulfilment-centre origin, and load them where promise-service reads its
   transit table (owned by the promise and carrier teams).

## After running

- Paste the job summary into S204 and set its status when the three items above are done.
- If a sandbox needed a different host, path or API version, change `config/<env>.yaml` (`<carrier>.paths`) rather
  than code, and note it in `go/services/carrier-gateway/README.md`.
