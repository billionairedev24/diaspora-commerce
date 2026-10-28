# Production readiness checklist

Work through this before the first real customer and again before every major release. Each line is something you can check
and tick; "who" is the role that signs it off. Anything marked **blocker** must be done before launch.

## 1. Environment and configuration

- [ ] **blocker** `APP_ENV=prod` on every service and every web app (the Helm library chart sets it for services; check the web
  apps). The web apps turn the mock backend off when it is `prod`, `production` or `uat`.
- [ ] **blocker** `API_MOCKS` and `NEXT_PUBLIC_API_MOCKS` are not set anywhere in production.
- [ ] **blocker** Spring profile `prod` (Java) and `APP_ENV=prod` config files (Go) are the ones loaded; no `local` profile.
- [ ] **blocker** Security is on: no service runs with the local "security off" mode or the `local-dev` actor.
- [ ] **blocker** The payments service's local test stand-in for Stripe is off (it is allowed on the `local` profile only).
- [ ] Image tags are pinned (never `latest`); Helm templates render no `latest` tag.
- [ ] `tools/dev/secrets_check.py --check` passes (undocumented keys are listed and documented).
- [ ] `tools/dev/check_migrations.sh` passes: no Flyway version is used twice in a service.

## 2. Data

- [ ] **blocker** No demo data: the production databases contain no rows from `tools/seed` (demo sellers, products, orders,
  test users such as `customer.houston`, `driver@afrotransact.test`, `seller.owner`). The seeder refuses `APP_ENV=prod|uat` and
  remote targets, but check the data too: search for the `src=demo-seed` order attribution and `*.test` email addresses.
- [ ] **blocker** Reference data is present and reviewed: product taxonomy, plans, review reason codes, markets, fee schedule,
  sales tax rate tables and tax codes, legal documents, cookie banner config.
- [ ] Markets, states and cities for launch are switched on in Settings → Markets & locations (nothing outside them is offered a
  run); pre-order areas match.
- [ ] Super Admin and first staff accounts exist; every staff role has only the permissions it needs (Users & roles).
- [ ] Retail (AfroTransact Retail) organisation, suppliers and warehouse centres are configured; exchange rates for the currencies
  you source in are entered (Settings → Exchange rates).

## 3. Secrets and identity

- [ ] **blocker** Every secret comes from the secret store, none from a file in the repo or an image. Rotate anything that was ever
  used in a test environment.
- [ ] **blocker** Identity signing key is **RSA** (3072); `SIGNING_KEY_PEM` is set; the identity data key (`IDENTITY_DATA_KEY`) is set
  and backed up separately from the database.
- [ ] Client secrets for every first-party service client are set (`*_CLIENT_SECRET`), and the BFF client redirect URIs are the
  production origins only.
- [ ] Identity admin console works for a Super Admin and is hidden for others (see `docs/engineering/identity-admin-dev-test.md`).
- [ ] OpenFGA: store and model deployed by authz-sync; service-client tuples seeded; services run `FGA_ENFORCE=true` after the
  shadow period shows no `FGA shadow:` warnings (see `docs/engineering/authorisation.md`).
- [ ] Staff MFA is required at sign-in.

## 4. Security

- [ ] **blocker** Zero known vulnerabilities in what you ship: CI `security` workflow green on `main` (CodeQL, Trivy repo and image,
  Semgrep, govulncheck, osv-scanner, gitleaks); `pnpm audit` clean apart from documented exceptions.
- [ ] Documented exceptions are reviewed: the `node-forge` advisory in the Expo dev tool (no upstream fix; dev tooling only).
- [ ] Images are built reproducibly, signed, and carry an SBOM; the cluster admits signed images only.
- [ ] TLS everywhere; HSTS on; Cloudflare in front with Authenticated Origin Pulls; the origin is not reachable directly.
- [ ] CORS, cookies (`Secure`, `HttpOnly`, `SameSite`) and CSP checked on the three web apps.
- [ ] Rate limits and the WAF ruleset are on for sign-in, checkout and the public APIs.
- [ ] Penetration test or an equivalent review done for sign-in, payments, uploads and the staff console.

## 5. Payments and money

- [ ] **blocker** Stripe is in **live** mode with live keys and the live webhook secret; the webhook endpoint is registered and
  verified; Connect is enabled with the right payout schedule and the platform's branding.
- [ ] **blocker** A real small payment (and a refund) was made end to end on production, including seller payout after the
  complaint hold window.
- [ ] Payment methods enabled in Stripe match what checkout shows (cards, wallets, bank, BNPL).
- [ ] Reconciliation runs daily and has no unexplained differences (Payouts & ledger → Reconciliation); maker-checker is on for
  ledger adjustments.
- [ ] Delivery fee rules (Settings → Delivery fees) and fee schedule (Fees & commissions) are final; run economics settings
  reviewed.
- [ ] Refund-on-cancel for pre-order windows was exercised: cancel a test window, every order refunded, stock released, customers
  notified.

## 6. Tax

- [ ] **blocker** Sales tax method chosen (our own rules or Stripe Tax) and rates loaded for every state you ship to.
- [ ] Nexus tracker reviewed; registrations in states over the threshold are filed.
- [ ] Remittance record and filings screens are accessible only to roles with the tax permissions.

- [ ] Stored value (AfroTransact credit, gift cards): terms reviewed by counsel for each launch state (no cash-out, expiry rules,
  escheat), liability reports signed off by finance, gift card codes rate limited.

## 7. Delivery, inventory and operations

- [ ] Hubs, delivery zones, pickup points and carrier accounts are real (carrier sandbox credentials replaced with production
  credentials; labels print).
- [ ] A pre-order window ran end to end: orders → minimum met or staff decision → run → pick lists → route → delivery proof →
  payout hold started.
- [ ] Inventory: opening stock received with the right currency and exchange rate; expiry dates and lots correct.
- [ ] Staff on call know the maintenance-mode runbook (`docs/runbooks/maintenance-mode.md`) and where to turn it on.

## 8. Observability and incident response

- [ ] Metrics, logs and traces reach Grafana Cloud (or your stack); dashboards for checkout, payments, orders and delivery exist.
- [ ] Alerts are routed to a real person/channel, and every alert has a runbook (`docs/runbooks/`).
- [ ] Error tracking is on for the web apps and mobile apps.
- [ ] Health, readiness and `/actuator/prometheus` or `/metrics` endpoints are reachable by monitoring and **not** by the public.
- [ ] Status page / maintenance page text reviewed.

## 9. Backups and recovery

- [ ] **blocker** Postgres backups run daily and a **restore was tested** into a scratch database this month; point-in-time
  recovery is configured if the provider supports it.
- [ ] Valkey, Meilisearch/Elasticsearch and object-storage (R2 and the KYC bucket) backups or rebuild procedures are known.
- [ ] Kafka retention covers the events authz-sync and other consumers need to rebuild state.
- [ ] Rollback is rehearsed: previous image tags are kept, Flyway migrations are backward compatible for one release.

## 10. Legal, support and launch

- [ ] Terms, privacy notice, cookie banner and marketplace-facilitator tax disclosures are published and accurate.
- [ ] Support inbox, customer support console and escalation contacts are staffed for launch week.
- [ ] Seller onboarding (applications → two-person review → approval) tested with a real test application, including the
  "return to applicant" path.
- [ ] Emails and SMS send from the production sender domain (SPF, DKIM, DMARC set); templates reviewed in every language you ship.
  Machine-translated strings were reviewed by a native speaker.
- [ ] Mobile apps are built with production endpoints, signed, and submitted; the in-app sign-in works against production identity.

## 11. Launch day

- [ ] Freeze merges; tag the release; deploy through Argo CD in order: data stores → identity → platform services → BFF → web.
- [ ] Run the smoke tests (`tools/smoke`) against production.
- [ ] Watch error rate, latency, checkout success and payment webhook lag for the first hours; keep maintenance mode ready.
- [ ] Confirm a real first order, payment, delivery and payout.
