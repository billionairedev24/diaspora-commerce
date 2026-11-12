# Production readiness checklist

Work through this before the first real customer and again before every major release. Each line is something you can check
and tick. Anything marked **blocker** must be done before launch.

Updated 2026-11-12 to the launch decisions: v1 is replaced by v2 on the **same Stripe account** (plan:
[v1-to-v2-cutover.md](v1-to-v2-cutover.md)); OVH Managed Kubernetes with Layerbase Postgres; the web apps on Cloudflare
Workers; images on R2; Resend for email; staff run fulfilment and update order statuses by hand; trust-ops, ai-gateway and
mcp-server on; OpenFGA off; no native apps (installable web app only); invite-only beta.

**Who** (first tag on each line)
- `OWNER`: a decision, an account, a signature or a payment only the business owner can give.
- `ENGINEERING`: work or verification in this repository or on the cluster.
- `VENDOR`: waits on an outside provider (Stripe, OVH, Layerbase, Cloudflare, Resend, OpenRouter, counsel's firm, an accountant).

**Status** (second tag): `Open` (not started), `In progress`, `Blocked: <on what>`, `Checked in code` (read in the source on
2026-11-12; says nothing about the live system), `Done` (checked on the live system, with date and name written on the line).
Nothing is `Done` until it has been checked where it runs.

## What changed against the earlier checklist

Removed because they no longer apply: building, signing and submitting the mobile apps; mobile deep-link files
(`apple-app-site-association`, `assetlinks.json`) as a launch item; the OpenFGA store, tuples and enforce rollout; WhatsApp
run-announcement approval as a launch step (it stays off, see section 7); push notification set-up for native apps;
Grafana Cloud as the assumed monitoring stack.
Added: admin status audit and confirmation, authorisation expiry of manual capture, Stripe fee on post-capture refunds,
Texas tax professional sign-off, counsel review of legal documents, Resend DNS, R2 bucket visibility (public images versus
private KYC), ai-gateway spend cap, MCP exposure, same-account Stripe cutover, the invite-only gate, PWA, Layerbase and OVH
specifics, a restore drill.

## 1. Environment and configuration

- [ ] **blocker** `ENGINEERING · Open` `APP_ENV=prod` on every service and every web app (the Helm library chart sets it for
  services; check the Worker vars of the web apps). The web apps turn the mock backend off when it is `prod`, `production` or `uat`.
- [ ] **blocker** `ENGINEERING · Open` `API_MOCKS` and `NEXT_PUBLIC_API_MOCKS` are not set anywhere in production.
- [ ] **blocker** `ENGINEERING · Open` Spring profile `prod` (Java) and `APP_ENV=prod` config files (Go) are the ones loaded; no `local` profile.
- [ ] **blocker** `ENGINEERING · Open` Security is on: no service runs with the local "security off" mode or the `local-dev` actor.
- [ ] **blocker** `ENGINEERING · Open` The payments service's local test stand-in for Stripe is off (allowed on the `local` profile only).
- [ ] `ENGINEERING · Open` Image tags are pinned (never `latest`); Helm templates render no `latest` tag.
- [ ] `ENGINEERING · Open` `tools/dev/secrets_check.py --check` passes (undocumented keys are listed and documented).
- [ ] `ENGINEERING · Open` `tools/dev/check_migrations.sh` passes: no Flyway version is used twice in a service.
- [ ] **blocker** `ENGINEERING · Open` OVH production path exists and is rehearsed: Managed Kubernetes cluster, ingress and
  load balancer, certificates, Argo CD (or the chosen deploy path), secret delivery, and the Helm values for the launch stack
  (FGA off, no native-app services). The existing OVH document describes a **test** server (`ovh-vps.md`), not production.
- [ ] **blocker** `ENGINEERING · Open` Services that are off at launch are confirmed off and not routed: FGA (`FGA_ENABLED=false`
  explicitly set on catalog, offers, orders, payments and the others that read it), authz-sync not deployed or idle, native-app
  push, WhatsApp. With FGA off, access control is RBAC plus organisation checks; run the cross-organisation access tests once
  with FGA off (a seller must not read another seller's orders).

## 2. Data

- [ ] **blocker** `ENGINEERING · Open` No demo data: the production databases contain no rows from `tools/seed` (demo sellers, products, orders,
  test users such as `customer.houston`, `driver@afrotransact.test`, `seller.owner`). The seeder refuses `APP_ENV=prod|uat` and
  remote targets, but check the data too: search for the `src=demo-seed` order attribution and `*.test` email addresses.
- [ ] **blocker** `OWNER · Open` Reference data is present and reviewed: product taxonomy, plans, review reason codes, markets, fee schedule,
  sales tax rate tables and tax codes, legal documents, cookie banner config.
- [ ] `OWNER · Open` Markets, states and cities for launch are switched on in Settings → Markets & locations (nothing outside them is offered a
  run); pre-order areas match.
- [ ] `OWNER · Open` Super Admin and first staff accounts exist; every staff role has only the permissions it needs (Users & roles).
  Fulfilment staff hold `orders:update-status` or `orders:manage` only if they are trained on the pickup step (section 5).
- [ ] `OWNER · Open` Retail (AfroTransact Retail) organisation, suppliers and warehouse centres are configured; exchange rates for the currencies
  you source in are entered (Settings → Exchange rates).
- [ ] **blocker** `OWNER · Open` v1 data migrated and verified: users and passwords, the seller, order history ([data runbook](afrotransact-v1-to-v2-migration.md)),
  with answers to the open questions in [v1-to-v2-cutover.md](v1-to-v2-cutover.md).
- [ ] `ENGINEERING · Open` Invite-only beta gate: only invited people (and migrated v1 users) can create or use an account. No customer
  sign-up gate was found in identity (only organisation team invitations); backlog story S355.

## 3. Secrets and identity

- [ ] **blocker** `ENGINEERING · Open` Every secret comes from the secret store, none from a file in the repo or an image. Rotate anything that was ever
  used in a test environment.
- [ ] **blocker** `ENGINEERING · Open` Identity signing key is **RSA** (3072); `SIGNING_KEY_PEM` is set; the identity data key (`IDENTITY_DATA_KEY`) is set
  and backed up separately from the database.
- [ ] `ENGINEERING · Open` Client secrets for every first-party service client are set (`*_CLIENT_SECRET`), and the BFF client redirect URIs are the
  production origins only (including the three web apps and the MCP public client).
- [ ] `ENGINEERING · Open` Identity admin console works for a Super Admin and is hidden for others (see `docs/engineering/identity-admin-dev-test.md`).
- [ ] `ENGINEERING · Open` Staff MFA is required at sign-in; sign-up MFA behaves as decided (backlog story).

## 4. Security

- [ ] **blocker** `ENGINEERING · Open` Zero known vulnerabilities in what you ship: CI `security` workflow green on `main` (CodeQL, Trivy repo and image,
  Semgrep, govulncheck, osv-scanner, gitleaks); `pnpm audit` clean apart from documented exceptions.
- [ ] `ENGINEERING · Open` Documented exceptions are reviewed: the `node-forge` advisory in the Expo dev tool (no upstream fix; dev tooling only, not shipped).
- [ ] `ENGINEERING · Open` Images are built reproducibly, signed, and carry an SBOM; the cluster admits signed images only.
- [ ] `ENGINEERING · Open` TLS everywhere; HSTS on; Cloudflare in front of the OVH origin with Authenticated Origin Pulls (or an origin firewall that
  admits Cloudflare ranges only); the origin load balancer is not reachable directly.
- [ ] **Storefront host rule:** the storefront is `https://www.<base>` in every environment (prod `https://www.afrotransact.com`, dev and uat `www.dev.` / `www.uat.afrotransact.com`); every other app keeps its sub-domain. The checks below are written for prod; dev and uat are the same with their base. `ENGINEERING · Open`
  - DNS: `www` is a proxied record served by the `storefront-prod` Worker route `www.afrotransact.com/*`; the apex
    `afrotransact.com` is a proxied placeholder (`AAAA 100::`) with no origin and no Worker route, answered by the dynamic
    redirect rule "apex to www" (one rule per environment, `afrotransact.com`, `dev.afrotransact.com`, `uat.afrotransact.com`; 301, `https://www.afrotransact.com` + path, query kept). Both are created by `infra/cloudflare`
    with `storefront_host = "www"`. Check: `curl -sI "https://afrotransact.com/p/x?a=1"` returns `301` with
    `location: https://www.afrotransact.com/p/x?a=1`; no A/CNAME record of the old (v1) setup is left on the apex or on `www`.
  - Certificates: Universal SSL covers `afrotransact.com` and `*.afrotransact.com` (apex, `www`, `seller`, `admin`, `api`, ...); nothing extra for `www`.
  - Config: `global.storefrontHost: www` in `infra/argocd/envs/prod/platform-values.yaml` (so `PUBLIC_WEB_URL` is `https://www.afrotransact.com` in every
    pod, identity redirect URIs and CORS follow) and the seller-center / staff-console `PUBLIC_WEB_URL` Worker vars; roll identity and gateway-bff
    together with the edge change, then verify a sign-in on `www`, a passkey sign-in (RP id stays `afrotransact.com`) and a sign-out round trip.
  - HSTS preload: the zone setting is `max-age` 1 year, `includeSubDomains` and, once `hsts_preload = true` is applied (`infra/cloudflare/envs/prod.tfvars`; it is `false` today), `preload`, so one submission of `afrotransact.com` at
    hstspreload.org covers `www` and every sub-domain. Submit it only when every sub-domain (including `mail.`, `developers.`, `grafana.`) is HTTPS-only;
    removal from the preload list takes months. Leave it off through the beta.
  - Search engines: the beta is invite-only; set `noindex` (or keep it behind sign-in) until launch, then canonical URLs are `https://www.afrotransact.com/...`
    (storefront `metadataBase` from `PUBLIC_WEB_URL`) and a Search Console property is added.
- [ ] `ENGINEERING · Open` CORS and cookies (`Secure`, `HttpOnly`, `SameSite`) checked on the three web apps.
- [ ] `ENGINEERING · Open` Security headers and CSP (`docs/engineering/security-headers.md`): identity serves its enforced CSP (check `curl -sI https://auth.<base>/login | grep -i content-security` and a full sign-in in Chrome and Firefox); each web app has run `CSP_MODE=report-only` in uat for 7 days with every `csp.violation` pair fixed, allowed or dismissed, then `CSP_MODE=enforce` in uat for 2 days; prod ran 7 days report-only, then `enforce` set in `web/apps/*/wrangler.jsonc` `env.prod.vars` (date: ____); HSTS 1 y + `includeSubDomains` at the edge (`curl -sI https://www.<base> | grep -i strict-transport`), preload still off; runbook `docs/runbooks/csp-violations-spike.md`.
- [ ] `ENGINEERING · Open` Rate limits and the WAF ruleset are on for sign-in, checkout and the public APIs.
- [ ] `OWNER · Open` Penetration test or an equivalent review done for sign-in, payments, uploads and the staff console.
- [ ] **blocker** `ENGINEERING · Open` **MCP exposure** decided and limited. mcp-server is on: `POST /mcp`, OAuth 2.1 bearer tokens from identity, tool
  lists filtered by scope and permission, a global rate limit (30 burst, 30 per minute in prod) and a durable audit of every call
  (`java/services/mcp-server/README.md`). Before launch: (a) the owner decides whether the endpoint is public at all or limited
  to staff and named partner clients during the beta (`OWNER · Open`); (b) the edge route and WAF rules match that decision; (c) the
  public OAuth client's redirect URIs are exactly the allowed MCP clients; (d) staff tools and tools that change data are off or need
  an explicit scope for beta users; (e) Valkey is reachable (the rate limit fails open to a per-pod bucket otherwise, so decide
  whether `MCP_RATE_FAILURE_MODE=closed` is wanted); (f) the `mcp.tool.invoked` audit events reach Kafka and are read by someone.
- [ ] `OWNER · Open` PWA: the three web apps install to the home screen (manifest, icons, service worker, offline page), and the install
  instructions for iPhone and Android are on the sign-in help page. No native apps ship at launch. Backlog story S373.

## 5. Payments and money

- [ ] **blocker** `OWNER · Open` Stripe cutover on the **same account** done per [v1-to-v2-cutover.md](v1-to-v2-cutover.md): inventory, test-mode rehearsal, freeze
  window, three **new** v2 webhook endpoints (payments, seller Connect, seller plan fees) with the SDK API version and separate secrets,
  v1's endpoints disabled and kept for 30 days, connected accounts and customers mapped, v1 `requires_capture` PaymentIntents resolved.
- [ ] **blocker** `ENGINEERING · Open` Stripe is in **live** mode with live keys and the live webhook secrets; each endpoint verified with **Send test event** (`2xx`);
  Connect settings, payout schedule and the platform's branding reviewed; connected accounts created by v1 are compatible with
  v2's onboarding (account type and who collects verification).
- [ ] **blocker** `OWNER · Open` A real small payment (and a refund) was made end to end on production, including a staff pickup that captured it and, later, the seller payout after the
  complaint hold window.
- [ ] **blocker** `ENGINEERING · Open` **Admin status changes audited and confirmed.** At launch staff set shipment statuses by hand, and **marking a shipment picked up
  captures the customer's money** (the capture task is queued when a shipment first reaches picked up, or ready for a pickup order).
  Checked in code on 2026-11-12:
  - Each change is recorded in the orders service's `shipment_status_change` table with the actor type, actor id, optional reason,
    source and time, and shown as the order's history in the staff console. That is the audit today. It is **not** copied to the
    central audit log (trust-ops) and orders emits no audit entry; history rows are only reachable through the order.
  - The staff console shows a confirmation dialog (from status, to status, order, seller) before any change. It does **not** say that the
    step will charge the customer, and a note is **optional** for picked up (required only for cancelled, delivery failed, and delivered
    where our own driver would normally prove it).
  - Gap: a mandatory reason and an explicit "this will capture the payment" warning on picked up, and an audit-log entry per manual status
    change. Backlog story S353. Until it is done, staff are told to
    write a note on every pickup and a second person reviews the daily list of captures against the shipments.
- [ ] **blocker** `ENGINEERING · Open` **Authorisation expiry of manual capture.** Each order is authorised at checkout and captured when the first shipment is picked up. A
  card authorisation lapses after about 7 days (the payments code says "cards hold ~7 days"). For ordinary orders this is **not handled**:
  if staff pick up on day 8 the capture is refused, the capture task retries 12 times, then parks as FAILED and the order is shipped but
  unpaid (`docs/engineering/inventory-rules.md`). Before launch: (a) the owner sets the rule that fulfilment must pick up within 5 days of
  the order, shown to staff; (b) engineering adds a daily list "authorised, not captured, older than 3 days" and a staff alert for FAILED
  captures; (c) the plan for an expired authorisation (re-authorise the customer's saved card off-session, or cancel and refund) is written;
  (d) the long-dated off-session path exists for bookings and pre-orders only. Backlog story S354.
- [ ] `OWNER · Open` **Stripe's fee is not returned on a refund after capture** (2.9 percent plus 30 cents of the refunded share; known in
  `docs/engineering/inventory-rules.md`, "Stripe's fee share is not returned"; verify against Stripe's current fee terms). Cancel before capture
  costs nothing. Decide who bears the fee (platform, seller, or customer's refund net of it), put it in the seller terms and refund policy, and
  tell staff to cancel before pickup where possible.
- [ ] `ENGINEERING · Open` Payment methods enabled in Stripe match what checkout shows (cards, wallets, bank). Multicapture is off (`STRIPE_MULTICAPTURE=false`), so the whole order is captured when its first shipment ships.
- [ ] `OWNER · Open` Reconciliation runs daily with a named person and has no unexplained differences (Payouts & ledger → Reconciliation); maker-checker is on for
  ledger adjustments.
- [ ] `OWNER · Open` Delivery fee rules (Settings → Delivery fees) and fee schedule (Fees & commissions) are final; run economics settings
  reviewed.
- [ ] `ENGINEERING · Open` Refund-on-cancel for pre-order windows was exercised (if runs are in launch scope): cancel a test window, every order refunded, stock released, customers
  notified.

## 6. Tax

- [ ] **blocker** `OWNER · Open` Sales tax method chosen (our own rules or Stripe Tax) and rates loaded for every state you ship to.
- [ ] **blocker** `OWNER · Blocked: tax professional` **Texas tax professional sign-off before `taxReview.mode` is changed from `ALL` to `RISK_BASED`.** At launch every variant's tax classification
  waits for a reviewer (`ALL`, the default). `RISK_BASED` lets the system approve a variant when its category is active and the rules resolve,
  with a 5 percent audit sample (`docs/runbooks/tax-provider-cutover.md`). Do not switch until a Texas tax professional has agreed in writing
  to the classification rules and the sample rate; record the reference in the change reason, and do not change it in the same window as the
  tax method. Staff capacity to review every variant must exist until then.
- [ ] `OWNER · Open` Nexus tracker reviewed; registrations in states over the threshold are filed.
- [ ] `ENGINEERING · Open` Remittance record and filings screens are accessible only to roles with the tax permissions.
- [ ] `OWNER · Open` Stored value (AfroTransact credit, gift cards): terms reviewed by counsel for each launch state (no cash-out, expiry rules,
  escheat), liability reports signed off by finance, gift card codes rate limited.

## 7. Delivery, inventory and operations

- [ ] `OWNER · Open` Hubs, delivery zones, pickup points and carrier accounts are real (carrier sandbox credentials replaced with production
  credentials; labels print). Launch shipping and delivery quote rules are final.
- [ ] `OWNER · Open` Staff fulfilment procedure written and trained: who sets which status, the 5-day pickup rule (section 5), what to write in the
  note, what to do when a status was set by mistake (who may cancel or reverse, and the refund consequence).
- [ ] `OWNER · Open` A pre-order window ran end to end (if runs are in launch scope): orders → minimum met or staff decision → run → pick lists → route → delivery proof →
  payout hold started.
- [ ] `OWNER · Open` Inventory: opening stock received with the right currency and exchange rate; expiry dates and lots correct. The inventory rules
  (`docs/engineering/inventory-rules.md`) are waiting on owner decisions; the build is blocked until they are made.
- [ ] `OWNER · Open` Staff on call know the maintenance-mode runbook (`docs/runbooks/maintenance-mode.md`) and where to turn it on.
- [ ] `OWNER · Open` Delivery run and pre-order announcements (S262): email goes out in the customer's language (`en`, `fr`, `es`, `ar`, `yo`) and the translations in
  `go/services/notifications/.../0023_run_preorder_locales.sql` have been read by a native speaker of each language; every announcement link
  carries UTM tags (check one in a received email). A window staff cancel tells each customer by email that the order is refunded in full,
  delivery fee included. Text message follows only if the sender registration is done and the owner wants it at launch; push has no native app to reach,
  so web push is off unless the PWA story delivers it.
- [ ] `VENDOR · Blocked: Meta` WhatsApp run announcements stay **off** (`deliveryRuns.whatsapp.enabled=false`, the default). Not a launch blocker. Steps when wanted are in
  `go/services/notifications/README.md`, "WhatsApp approval".

## 8. Observability and incident response

- [ ] `ENGINEERING · Open` Metrics, logs and traces reach the monitoring stack chosen for OVH (name it here: ______); dashboards for checkout, payments, orders and delivery exist.
- [ ] `ENGINEERING · Open` Alerts are routed to a real person/channel, and every alert has a runbook (`docs/runbooks/`).
- [ ] `ENGINEERING · Open` Error tracking is on for the web apps.
- [ ] `ENGINEERING · Open` Health, readiness and `/actuator/prometheus` or `/metrics` endpoints are reachable by monitoring and **not** by the public.
- [ ] `OWNER · Open` Status page / maintenance page text reviewed.
- [ ] `ENGINEERING · Open` A daily "captures" report for the first weeks: payments captured the previous day against shipments marked picked up.

## 9. Backups and recovery

- [ ] **blocker** `VENDOR · Open` Layerbase Postgres: backups run daily, **point-in-time recovery** is available and its retention is known, connection limits fit the
  service pools (one database per service; count them against the plan), failover behaviour is documented. Confirm with Layerbase in writing.
- [ ] **blocker** `ENGINEERING · Open` **Restore drill**: restore a production-shaped backup into a scratch database, start the services against it, and read an order and a payment
  back. Record the date, the minutes it took and who did it. Repeat monthly in launch quarter.
- [ ] `ENGINEERING · Open` Valkey, search index (Meilisearch or Elasticsearch) and object-storage (R2, including the KYC bucket) backups or rebuild procedures are known;
  search can be rebuilt from the databases (`kafka-replay.md`).
- [ ] `ENGINEERING · Open` Kafka retention covers the events consumers need to rebuild state.
- [ ] `ENGINEERING · Open` Rollback is rehearsed: previous image tags are kept, Flyway migrations are backward compatible for one release.
- [ ] `OWNER · Open` The v1 database dump and the Stripe inventory are kept encrypted for 30 days, then destroyed (keep the `ledger.*` finance archive).

## 10. Legal, support and launch

- [ ] **blocker** `OWNER · Blocked: counsel` **Counsel review** of every document in `docs/legal`, with each `[[LEGAL: ...]]` placeholder filled (entity, address, governing law, arbitration,
  liability caps, DMCA agent, state rules), before they are published. Terms, privacy notice, cookie banner and marketplace-facilitator
  tax disclosures are published and accurate. Translations follow the approved English.
- [ ] `OWNER · Open` Provider terms (Stripe, Resend, OpenRouter, Cloudflare, OVH, Layerbase, Google) are read and the matrices in `docs/legal` confirmed.
- [ ] **blocker** `OWNER · Open` OpenRouter account privacy settings match the privacy notice ("AI features"). In the OpenRouter account
  settings: turn prompt/response logging OFF; turn OFF the "inputs/outputs logging" and "training" consent options; set the
  account-level privacy / zero-data-retention (ZDR) policy; restrict the allowed providers to those that do not train on
  prompts. Check that `AI_PROVIDER_DATA_COLLECTION` is `deny` (or unset) and `AI_PROVIDER_ZDR` is `true` on ai-gateway in prod. Record the date and who checked:
  ______ (date), ______ (name), re-check every quarter and whenever a model id in `at.ai.routes` changes.
- [ ] **blocker** `OWNER · Open` **ai-gateway spend cap.** The gateway has per-user and per-feature daily budgets in configuration (`at.ai.budgets` in
  `java/services/ai-gateway/src/main/resources/application.yml`), but the shipped feature caps add up to roughly 1,200 US dollars a day, far above
  a beta. Before launch: (a) set a monthly credit limit on the OpenRouter account and a limit on the key used by prod (`VENDOR`: OpenRouter
  settings), so spend cannot exceed it whatever the code does; (b) `ENGINEERING`: lower the per-feature daily caps in the prod profile to the
  agreed number, and keep `degrade-on-exceed` on; (c) an alert fires at 50 and 80 percent of the monthly cap; (d) the owner names who gets it.
- [ ] **blocker** `OWNER · Open` Data processing agreements are signed with OpenRouter and with each model provider we route to (for example
  Google and OpenAI) if we process EU or UK personal data; the signed copies are filed and the subprocessor list is current.
- [ ] `OWNER · Open` Support inbox, customer support console and escalation contacts are staffed for launch week.
- [ ] `ENGINEERING · Open` Staff console can approve or reject an IN_REVIEW product and decide a seller application (checked in code, no trust-ops needed for
  either: see the cutover findings); walk one test product and one test application through, including "return to applicant".
- [ ] **blocker** `VENDOR · Open` **Resend DNS and sending domain.** Add the domain in Resend, publish the DNS records it gives (SPF, DKIM, and the return-path/MX records) in Cloudflare
  as **DNS only** (unproxied), add a DMARC record (`_dmarc`, start at `p=none` with a report address, move to `quarantine` after two clean weeks),
  and wait for the domain to show **Verified**. Set the production `RESEND_API_KEY` (sending access only) in the secret store. Send a test to Gmail,
  Outlook and Yahoo and check the headers show SPF, DKIM and DMARC pass. Check the From address matches the verified domain. Templates reviewed in every language
  you ship; machine-translated strings were reviewed by a native speaker.
- [ ] **blocker** `ENGINEERING · Open` **R2 buckets: public versus private.** Product and listing images use the **public** media bucket (custom domain, e.g. `media.<base>`,
  read-only to the world, no listing). Identity and seller **KYC documents** use a **private** bucket: no public access, no custom domain, no
  `r2.dev` URL, access only through the media service with short-lived signed URLs, and the `kyc-document` upload purpose pointed at it
  (`docs/runbooks/selling-approvals.md`; `docs/engineering/cloud-cost.md` says the KYC bucket stays private). Check: fetch a KYC object URL
  without credentials and expect `403`; list the public bucket anonymously and expect it refused; R2 API tokens are scoped per bucket with no
  admin token in any service; CORS on the public bucket allows only our origins; the retention rule for KYC files is decided by the owner
  (`OWNER · Open`).
- [ ] `OWNER · Open` Seller onboarding (applications → two-person review → approval) tested with a real test application, including the
  "return to applicant" path.

## 11. Launch day

- [ ] `ENGINEERING · Open` Freeze merges; tag the release; deploy in order: data stores → identity → platform services → BFF → web (Worker deploys).
- [ ] `ENGINEERING · Open` Run the smoke tests (`tools/smoke`) against production.
- [ ] `OWNER · Open` Follow the order of events, go/no-go checklist and rollback in [v1-to-v2-cutover.md](v1-to-v2-cutover.md).
- [ ] `ENGINEERING · Open` Watch error rate, latency, checkout success and payment webhook lag for the first hours; keep maintenance mode ready.
- [ ] `OWNER · Open` Confirm a real first order, a staff pickup that captured the payment, a delivery and (a week later) a payout.
