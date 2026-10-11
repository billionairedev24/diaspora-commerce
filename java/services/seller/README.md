# seller-service

Seller and service-provider organisations, the resumable onboarding application, the two-step staff review,
Stripe Connect accounts and compliance sync, and plans & billing on Stripe Billing.
Spring Boot 4.1 · Java 25 · PostgreSQL `seller` · port **8106**.
Architecture: §7 (Sellers, payments and subscriptions) and §17d (Stripe Connect compliance sync) of
`design/AfroTransact Architecture.dc.html`. Stories S089–S098 (onboarding) and S099–S104 (compliance, billing).

## Model

| Table | What it holds |
|---|---|
| `organisation` | `org_…` id, kind `SELLER · PROVIDER` (onboarding portal), offering `PRODUCTS · SERVICES · BOTH` (S215, set at approval), legal and display name, country, status `DRAFT · PENDING_REVIEW · FLAGGED · AWAITING_FINAL_APPROVAL · INFO_REQUESTED · APPROVED · REJECTED · SUSPENDED` |
| `org_member` | user id (identity-service) + org role key; the applicant becomes `seller.owner` / `provider.owner`. Mirrors identity (S236), see [Members](#members-s236) |
| `application` | one per organisation and per applicant: `APP-…` reference, status, current step, wizard sections (`data` jsonb), prefilled fields, reviewer / final approver, failed checks, requested items, reason code. DB check: final approver ≠ reviewer |
| `application_event` | timeline: action, from → to status, actor, reason code, note, items, time |
| `document_access_log` | every reviewer view of a document and of unmasked PII |
| `review_reason_code` | seeded: `ALL_CHECKS_PASSED`, `PERMIT_MISSING`, `LICENCE_EXPIRED`, `IDENTITY_MISMATCH`, `TIN_MISMATCH`, `DOCUMENT_UNREADABLE`, `INSURANCE_MISSING`, `PROHIBITED_CATEGORY`, `REPUTATION_RISK`, `SANCTIONS_HIT`, `DUPLICATE_ACCOUNT`, `OTHER` |
| `launch_list` | (country, email) for markets that are not live, with the shopping-updates opt-in |
| `plan`, `plan_price` | seeded catalogue (Seller Essentials / Growth / Pro, Provider Solo / Studio / Business) and the Stripe Price `lookup_key` per cadence (`seller_growth_quarterly`) |
| `connect_account` | Stripe requirements (`currently_due`, `eventually_due`, `past_due`, `pending_verification`, `errors`, `current_deadline`, `disabled_reason`), `charges_enabled`, `payouts_enabled`, capabilities, persons, derived status, `requirements_version`, payout hold, listings hidden |
| `connect_account_history` | full change history (Stripe events, seller answers, staff actions) with the snapshot |
| `org_suspension` | Trust and Safety suspension holds (V3): reason code, note, actor, previous org status, reinstated at / by / note; at most one active per organisation |
| `compliance_reminder` | reminders sent; unique per (account, deadline, threshold) so the job is idempotent |
| `stripe_webhook_event` | webhook inbox (Stripe event id) with outcome |
| `subscription`, `payment_method`, `invoice` | plan subscription (status, trial, period, pending change, dunning), cards / bank accounts (brand or bank, last 4, expiry, default), invoices from webhooks |

Sensitive data: the SSN/ITIN/EIN is validated, formatted server-side (`123-45-6789`, `12-3456789`) and stored only as
last 4 + an AES-256-GCM ciphertext (`FieldCipher` port, `AesGcmFieldCipher` adapter, key id bound as AAD, older keys
kept for rotation). Bank and card numbers are never stored — only last 4, bank name / brand and expiry; the real
details go to Stripe as client-side tokens (`btok_…`, `pm_…`, `pii_…`). From Stripe we read verification results
only (requirements, `individual.verification.status`, flags), never ID images or tax numbers.

## Onboarding (S089–S096, S098)

`POST /v1/applications/me {kind}` starts (201) or resumes (200) the caller's application. Name, email and phone come
from the token's identity claims (`given_name`, `family_name`, `name`, `email`, `phone_number`) and are listed in
`prefilledFields` until the applicant changes them. `PUT /v1/applications/me/steps/{step}` autosaves one step and
validates only the fields present; `GET /v1/applications/me` returns the draft, current step, completed steps and the
steps shown (no `owners` for sole proprietors); `POST /v1/applications/me/submit` validates everything
(`422 seller.application-incomplete` with every failing field).

| Step | Rules |
|---|---|
| `business` | offering products / services / both (sellers must include products, providers services), entity type, country — non-live countries → `422 seller.country-not-live` (live list: `SELLER_LIVE_COUNTRIES`, default `US`), store name, email, phone (libphonenumber, mobile, stored E.164) |
| `business-details` | sole proprietor: legal first/last name, SSN/ITIN, DOB 18+, address. LLCs, corporation, partnership: legal name, EIN, state + date of formation, address, formation document asset id. Nonprofit: + IRS determination letter |
| `owners` | everyone ≥ 25 % (below 25 % only with significant control), total ≤ 100 %, ≥ 1 significant-control person, multi-member LLC and partnership ≥ 2 people |
| `stripe-verification` | `POST /v1/applications/me/stripe/account-session` creates the connected account on first use (controller: Stripe-collected requirements, Stripe-liable losses, the account pays its own fees, no Stripe dashboard, manual payouts) and returns the Account Session `client_secret` for the embedded account-onboarding / account-management / notification-banner components (no feature overrides: Stripe collects the payout bank account, and `external_account_collection` can only be false when the platform collects requirements); `POST …/stripe/refresh` re-reads verification results and the payout bank. Complete when details are submitted, nothing is currently due and the account isn't rejected |
| `payouts` | read-only: the payout bank account Stripe holds (bank name, last 4, Stripe's status), mirrored from the account's `external_accounts` and `account.external_account.*` webhooks. Saving the step answers `422 seller.step-managed-by-stripe`: Stripe's onboarding collects the bank and its account management changes it. Complete when Stripe holds a usable (not failed) bank, `external_account` isn't due and payouts are enabled or only waiting for Stripe's checks |
| `plan` | plan of the org's kind + cadence + payment method (`CARD` brand/last 4/expiry, or `US_BANK_ACCOUNT` = payout bank by ACH); response carries the first-charge sentence |
| `catalog-compliance` | groceries/perishables/prepared food → food permit (number, expiry, document); beauty → labelling confirmation; regulated services → licence number, issuing state, expiry (expired rejected), licence document + insurance; agreements and W-9 certification |

`POST /v1/launch-list {country, email, shoppingUpdates}` (public) and `GET /v1/markets` serve non-live countries.

## Two-step review (S097)

| Endpoint | Permission | Effect |
|---|---|---|
| `GET /v1/applications?status=…&kind=…` | `applications:review` | queue (default: pending, flagged, awaiting final approval) with review step 1 / 2 |
| `GET /v1/applications/{id}` | `applications:review` | full dossier, timeline and access log. Tax ID as last 4 and DOB as year only unless `applications:pii` (each unmasked view is logged) |
| `GET /v1/applications/{id}/documents/{assetId}` | `applications:review` | signed URL from media-service; the view is logged |
| `POST /v1/applications/{id}/review` | `applications:review` | `APPROVE` → AWAITING_FINAL_APPROVAL, `FLAG` (failedChecks) → FLAGGED, `REQUEST_INFO` (requestedItems) → INFO_REQUESTED (applicant edits and resubmits) |
| `POST /v1/applications/{id}/decision` | `applications:approve` | `FINAL_APPROVE` → APPROVED (org approved, events, subscription activated), `SEND_BACK` → PENDING_REVIEW, `REJECT` → REJECTED. Refused with `403 seller.same-person-approval` for the reviewer |
| `GET /v1/review-reason-codes` | `applications:review` | reason codes; every decision needs one |

Nobody reviews their own application (`seller.self-review`). Every transition records actor, reason code, note and time.

## Organisations

`GET /v1/orgs/directory?page=&size=&status=` (S213: paged id, display name — legal name until a store name is set —
kind, status, ordered by id, size ≤ 500; `sellers:directory`, held by the `indexer-service` client, or staff; the indexer
warms its seller names from it on start), `GET /v1/orgs/{id}/public` (no auth: display name, kind, status — indexer,
cart, storefront), `GET /v1/orgs/{id}`
(members and reviewers), `PATCH /v1/orgs/{id} {displayName}` (owner, approved orgs) → `seller.org.updated`.

## Ship-from addresses and AfroTransact pickup (S231, S255)

`GET/POST /v1/orgs/{orgId}/ship-from-addresses`, `PUT/DELETE …/{id}`, `POST …/{id}/default` (members read; owner,
admin and fulfilment members change; staff `seller:ship-from`). Up to 20 per organisation, one default.

- **Geocoding.** Every create and every address change is geocoded with geo-service `POST /v1/geocode` (provider chain,
  ZIP centroid fallback) and stored as `position {lat, lng, precision ADDRESS|ZIP, geocodedAt}` (V52). Best effort: when
  geo is down the address is saved without a position and geocoded again on the next change.
- **AfroTransact pickup** — `PUT …/{id}/afrotransact-pickup {enabled, hours: [{day, opens, closes}], version?}`: the
  seller packs, the nearest AfroTransact driver collects here and delivers. Enabling needs
  - an **approved** organisation (`409 seller.org-not-approved`);
  - pickup hours: 1–14 non-overlapping windows, `HH:MM` local time, opens before closes (`422 seller.invalid-pickup-hours`);
  - a **street-level** position (`422 seller.ship-from-not-geocoded`; a ZIP centroid is not enough for a driver);
  - a location **inside an active AfroTransact `DELIVERY` zone, or within `PICKUP_MAX_RADIUS_MILES` (25) of one** (geo
    `GET /v1/zones/covering?type=DELIVERY&withinMiles=`; `422 seller.pickup-outside-delivery-area`); the zone name is
    stored. `503 seller.geo-unavailable` when geo can't be reached.

  Moving an enabled location re-checks it (refused outside the area); disabling keeps the hours for next time. Each
  enable, move, disable or delete of a pickup location publishes `seller.pickup_location.updated` (`at.seller-org.events`).
- **Service lookup** — `GET /v1/ship-from-locations/{id}`: services with `sellers:ship-from.read` (offers-service checks
  AFROTRANSACT_DELIVERY offers, orders-service measures checkout distance from the position, fulfilment-service
  needs the pickup address), staff `seller:ship-from` and the organisation's members.

## Stripe compliance (S099–S102)

`POST /v1/webhooks/stripe-connect` (secret `STRIPE_CONNECT_WEBHOOK_SECRET`): signature verified with
`com.stripe.net.Webhook.constructEvent`, de-duplicated by event id in `stripe_webhook_event` (same transaction as the
change), `account.updated` snapshots applied only when `event.created ≥ requirements_version` (older ones are
recorded as `stale`). Handles `account.updated`, `capability.updated`, `person.created/updated`,
`account.external_account.created/updated/deleted` (bank accounts only: the payout bank mirrored onto the account and
the open application), `payout.failed`, `account.application.deauthorized`.

Status: past due or disabled → `RESTRICTED`; currently due with ≤ 3 days to the deadline → `RESTRICTED_SOON`, else
`ACTION_NEEDED`; only pending verification → `VERIFYING`; else `COMPLIANT`. Enforcement: past due → payouts paused
(`seller.payouts.paused`, reason `AUTO_PAST_DUE`) and resumed automatically when cleared; charges disabled →
`seller.listings.hidden`, re-enabled → `seller.listings.restored`; deauthorisation restricts everything. Staff holds
(`MANUAL`) are only released by staff and never while Stripe reports past-due items.

| Endpoint | Who |
|---|---|
| `GET /v1/orgs/{orgId}/account-health` | org owner / finance; staff and service tokens (principal type `SERVICE`) with `compliance:remind`, `compliance:hold` or `sellers:enforce`: status, flags, the payout bank Stripe holds and each requirement with its label and the embedded component that collects it (`account_management` for the payout bank, `account_onboarding` for everything else). Read-only |
| `POST /v1/orgs/{orgId}/stripe/account-session` | org owner / finance (not staff): Account Session for Stripe's embedded components. Seller accounts are Stripe-collected, so the platform can't update identity, tax, person or bank details through the API once onboarding started; sellers answer Stripe in its components |
| `GET /v1/compliance/accounts?status=&openOnly=` · `GET /v1/compliance/accounts/{id}` | `compliance:remind` or `compliance:hold` |
| `POST /v1/compliance/accounts/{id}/reminders` | `compliance:remind` |
| `POST /v1/compliance/accounts/{id}/payouts/pause` | `compliance:remind` or `compliance:hold` |
| `POST /v1/compliance/accounts/{id}/payouts/resume` | `compliance:hold` |

## Suspension (S228)

Trust and Safety suspension is an enforcement hold of its own, kept apart from the Stripe compliance holds on the
connected account. Callers: staff or the `trust-ops-service` client (principal type `SERVICE`) with
`sellers:enforce`.

| Endpoint | Effect |
|---|---|
| `POST /v1/orgs/{orgId}/suspend {reasonCode, note}` | Approved organisations only (`409 seller.org-not-approved`); `reasonCode` is `POOR_PERFORMANCE · PROHIBITED_ITEMS · COUNTERFEIT · FRAUD_RISK · POLICY_VIOLATION · COMPLIANCE · OTHER` (note required for `OTHER`). Adds the suspension hold, sets the organisation `SUSPENDED` and publishes `seller.listings.hidden` + `seller.payouts.paused` (reason `SUSPENDED`, note `[CODE] note`) and `seller.org.updated` (status `SUSPENDED`). Idempotent |
| `POST /v1/orgs/{orgId}/reinstate {note}` | Removes only the suspension hold and restores the previous status (`seller.org.updated`). `seller.payouts.resumed` / `seller.listings.restored` (reason `REINSTATED`) are published only when the connected account has no compliance payout hold / hidden listings of its own. Idempotent |
| `GET /v1/orgs/{orgId}/enforcement` | `sellers:enforce`, `compliance:hold` or `compliance:remind`: the suspension, the compliance holds, the effective `payoutsPaused` / `listingsHidden`, and the suspension history |

While an organisation is suspended, compliance releases are held back: a Stripe snapshot that clears past-due items or
re-enables charges, or a staff payout release, updates the account but publishes no `seller.payouts.resumed` /
`seller.listings.restored`; reinstatement publishes them once no hold remains. Both calls lock the organisation and
its connected account, so they serialise with webhooks. Suspend and reinstate also write `TRUST_SAFETY` entries to
the account's compliance history. Consumers keep their existing latest-wins handling: payments (payout hold),
catalog (hides products, republishes `catalog.product.hidden|restored`, which the indexer follows), offers, bookings,
notifications and identity (organisation status).

## Plans & billing (S103–S104)

`GET /v1/plans?kind=` (public): monthly, quarterly (3 months − 5 %) and annual (10 months) totals, per-month price,
saving and first-charge sentence. After final approval the service creates the Stripe customer, attaches the
onboarding payment method and creates the subscription with the plan's trial (free plans need no Stripe
subscription; failures are retried by a job). Billing page (owner / finance read, owner writes, staff with
`seller:billing` read):

| Endpoint | |
|---|---|
| `GET /v1/orgs/{orgId}/billing` | plan, price, status, trial end or next charge (label incl. payment method), pending change, dunning, payment methods |
| `POST /v1/orgs/{orgId}/billing/plan {planCode, cadence}` | upgrades on the same cadence prorate now (`always_invoice`; during a trial no proration); downgrades and cadence changes are recorded and applied at renewal by the job (`proration_behavior=none`) |
| `POST …/billing/cancel` · `POST …/billing/resume` | cancel / resume at period end |
| `GET/POST …/billing/payment-methods`, `POST …/{id}/default`, `DELETE …/{id}` | default can't be removed (`409 seller.default-payment-method`) |
| `GET …/billing/invoices` | from `invoice.*` webhooks, with hosted page and PDF links |
| `GET …/billing/invoices/{invoiceId}/pdf` | owner / finance: `302` to the invoice's Stripe PDF (`404 seller.invoice-pdf-missing` until finalised) |
| `GET …/billing/plan/preview?planCode=&cadence=` | owner / finance: what `POST …/billing/plan` would do — `IMMEDIATE_UPGRADE` with `amountDueNowCents` from Stripe's invoice preview (`proration_behavior=always_invoice`; 0 during a trial or from a free plan with a trial), or `AT_RENEWAL` with `effectiveAt` = the renewal; nothing changes |
| `POST …/billing/setup-intents` | owner: a Stripe SetupIntent (`usage=off_session`) on the organisation's customer (created on first use) → `{customerId, setupIntentId, clientSecret}`; Seller Center confirms it with the Payment Element, then registers the `pm_…` with `POST …/billing/payment-methods` |

`POST /v1/webhooks/stripe-billing` (secret `STRIPE_BILLING_WEBHOOK_SECRET`): `invoice.paid`,
`invoice.payment_failed` (dunning `RETRYING` / `FINAL_ATTEMPT_FAILED`, status `PAST_DUE`),
`customer.subscription.updated` / `deleted`.

**S103 acceptance criteria.** Upgrades prorate immediately (`always_invoice`, previewable), downgrades and cadence
changes apply at renewal (pending change + `Pending plan changes` job, `proration_behavior=none`); trials come from the
plan (`trial_period_days`, no proration during a trial); dunning follows Stripe Smart Retries (`invoice.payment_failed`
→ `PAST_DUE` / `RETRYING` → `FINAL_ATTEMPT_FAILED`; `invoice.paid` clears it; Stripe's own retry / cancel settings end
it with `customer.subscription.updated|deleted`); cancellation and resume at period end; invoices listed with PDF
download; metered usage below (S206). Stripe products, prices (`<plan>_<cadence>` lookup keys) and the three metered
prices / meters must exist in each Stripe account (S245; the platform team's setup).

## Metered usage (S206)

FBA storage, sponsored-products spend and AI credits are billed on **Stripe Billing meters** next to the plan
subscription. A metering run pulls usage per organisation and UTC day for a period from three ports (HTTP adapters
with the `seller-service` client-credentials token, scopes `inventory:storage.read ads:spend.read ai:usage.read`):

| Meter | Source | Unit (meter event value) | Stripe meter / metered price |
|---|---|---|---|
| `FBA_STORAGE` | inventory `GET /v1/storage/usage` (cubic-feet-days) | hundredths of a cubic-foot-day | `fba_storage_cubic_feet_days` / `metered_fba_storage` |
| `ADS_SPEND` | content `GET /v1/ads/spend` (USD only) | cents | `ads_spend_cents` / `metered_ads_spend` |
| `AI_CREDITS` | ai-gateway `GET /v1/ai/usage/orgs` | credits (1 credit = 1¢ of model cost) | `ai_credits` / `metered_ai_credits` |

Each day's quantity is stored in `usage_daily` (overwritten by every read, days the source no longer reports drop to
0), and only the difference to what Stripe already has (`reported_quantity`) is sent as a meter event with
identifier `<org>:<meter>:<day>:r<revision>` (also the idempotency key), so runs are **idempotent and re-runnable per
period**. Usage that drops after it was reported is never sent negative: the day is flagged `CORRECTION` for a
credit. Organisations without a Stripe customer (free plans) stay pending; days older than 35 days can't be sent
(Stripe limit). The first report for a paid subscription adds the three metered prices to it
(`metered_subscription_item`), so Stripe invoices carry the usage lines; `invoice.*` webhooks store the invoice lines
(`invoice.lines`, metered lines recognised by lookup key or subscription item). A lease (`usage_run_lease`) keeps one
run at a time across replicas; every run is recorded in `usage_run`.

| Endpoint | Who |
|---|---|
| `GET /v1/orgs/{orgId}/billing/usage?from=&to=` | owner / finance members; staff with `seller:billing` or `billing:usage`. Default period: the current subscription period (month to date without one). Per meter: quantity, reported, estimated amount, days with `REPORTED · PENDING · CORRECTION` |
| `POST /v1/billing/usage/runs {from, to}` · `GET /v1/billing/usage/runs` | staff with `billing:usage` (≤ 93 days, ending today at the latest; 409 `seller.usage-run-in-progress`) |
| `GET /v1/orgs/{orgId}/billing/invoices` | now with `lines[]` (`metered`, `meter`) and `meteredAmountCents` |

Job: `metering-cron` (`0 20 1 * * *` UTC) meters the two previous UTC days. Config `at.seller.metering.*`:
`INVENTORY_URL`, `CONTENT_URL`, `AI_GATEWAY_URL`, `AUTH_TOKEN_URL`, `SELLER_SERVICE_CLIENT_ID`,
`SELLER_SERVICE_CLIENT_SECRET` (blank = no token, local), `METERING_TIMEOUT`.

### AfroTransact Retail is never billed

AfroTransact Retail (`RETAIL_ORG_ID`, `at.seller.retail-org-id`, default `org_afrotransact_retail`) is the first-party
merchant of record, not a marketplace seller, so seller fees never apply to it:

- **Metering:** its samples from every source (FBA storage, ads spend, AI credits) are dropped before anything is
  stored in `usage_daily` or sent to Stripe, even if seller-service knows the org and it has a Stripe customer; stale
  Retail rows are never reported. Its storage, ads and AI use are internal costs. (content also leaves Retail out of
  `/v1/ads/spend`; inventory reports Retail stock as usual and the exclusion happens here, on the billing side.)
- **Plans & billing:** no subscription is activated for it, and plan changes, cancel / resume and payment-method
  changes are refused with `422 seller.retail-not-billed`.
- **Enforcement:** `POST /v1/orgs/{retail}/suspend` is refused with `422 seller.retail-exempt`; seller-service never
  publishes `seller.payouts.paused` or `seller.listings.hidden` for Retail (payments ignores a pause for it anyway).

## Members (S236)

identity-service is the source of truth for organisation members. seller keeps `org_member` (used by its own
authorisation, e.g. owner / finance checks on billing) consistent by consuming `identity.role.granted` /
`identity.role.revoked` with `orgId` from `at.identity.events` (consumer group `seller-service`, inbox-deduplicated,
`SELLER_CONSUMERS_ENABLED`): grants add the member or change its role unless the stored row is newer; revokes remove
it while it still holds that role. Mirroring was chosen over dropping the table because seller's access checks fall
back to the member list when a token's org/roles are stale, and the applicant must be a member before identity
knows the organisation.

## Events (outbox → Kafka, `contracts/asyncapi/fragments/seller.yaml`)

| Topic (key) | Types | Consumers |
|---|---|---|
| `at.seller-application.events` (application id) | `seller.application.submitted / flagged / info_requested / approved / rejected` | notifications, staff console, trust-ops |
| `at.seller-org.events` (org id) | `seller.pickup_location.updated` (S255 AfroTransact pickup locations); `seller.org.approved`, `seller.org.updated` (also on suspend / reinstate); `kind` is `BOTH` when the organisation offers products and services, `primaryKind` the onboarding portal (S215) | identity (unlock back office, mirror org and status), indexer and cart (display name) |
| `at.seller-compliance.events` (org id) | `seller.compliance.changed`, `seller.compliance.reminder`, `seller.payouts.paused / resumed`, `seller.listings.hidden / restored` (reasons `AUTO_PAST_DUE · MANUAL · DEAUTHORIZED · CHARGES_DISABLED · CLEARED · SUSPENDED · REINSTATED`; `accountId` is null for an organisation without a connected account) | notifications (email + SMS + in-app), payments (payout holds), catalog / offers / bookings / indexer (visibility), trust-ops (audit) |
| `at.seller-subscription.events` (org id) | `seller.subscription.changed` | identity (plan limits), finance |

## Jobs (idempotent, safe on several replicas)

| Job | Default | What |
|---|---|---|
| Compliance reminders | hourly `0 15 * * * *` | 7 / 3 / 1 day(s) before `current_deadline`; one per threshold (unique index) |
| Pending plan changes | hourly `0 5 * * * *` | downgrades and cadence changes whose renewal has come |
| Subscription activation retry | every 10 min | approvals whose Stripe subscription could not be created |

## Configuration

`application.yml` + `application-{local,dev,uat,prod}.yml`. Secrets only from env: `DB_URL`, `DB_USER`,
`DB_PASSWORD`, `KAFKA_BOOTSTRAP`, `STRIPE_SECRET_KEY`, `STRIPE_CONNECT_WEBHOOK_SECRET`,
`STRIPE_BILLING_WEBHOOK_SECRET`, `FIELD_ENCRYPTION_KEY` (base64 32 bytes; `FIELD_ENCRYPTION_KEY_ID`, default `k1`).
Other: `AUTH_ISSUER_URL`, `SELLER_LIVE_COUNTRIES` (`US`), `MEDIA_BASE_URL`, `MEDIA_SERVICE_TOKEN`, `GEO_URL`, `GEO_TIMEOUT`,
`PICKUP_MAX_RADIUS_MILES` (25),
`SELLER_JOBS_ENABLED`, `REMINDER_CRON`, `PLAN_CHANGE_CRON`, `ACTIVATION_RETRY_INTERVAL`. `at.seller.stripe.api-base` /
`files-base` point stripe-java at another host (the ITs use WireMock). The local profile ships a well-known dev
encryption key; every other environment must provide its own.

## Run & test

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
cd java && ./mvnw -B -f services/seller/pom.xml verify      # checkstyle, unit, ArchUnit, Testcontainers + WireMock ITs
docker compose -f infra/local/docker-compose.yml up -d postgres kafka
java -jar services/seller/target/seller-service-0.1.0-SNAPSHOT.jar   # local profile, port 8106, auth off
tools/contracts/export_java_openapi.sh seller seller 8106 --at.seller.jobs.enabled=false
```

The ITs never call Stripe: an in-JVM WireMock stands in for `api.stripe.com`, `files.stripe.com` and media-service,
and webhooks are signed with a test secret and verified for real.

Helm: `deploy/helm` (library chart `at-service`); validate with
`helm dependency build java/services/seller/deploy/helm && helm template x java/services/seller/deploy/helm -f java/services/seller/deploy/helm/values-prod.yaml | kubeconform -strict -ignore-missing-schemas`.
