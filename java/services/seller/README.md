# seller-service

Seller and service-provider organisations, the resumable onboarding application, the two-step staff review,
Stripe Connect accounts and compliance sync, and plans & billing on Stripe Billing.
Spring Boot 4.1 · Java 25 · PostgreSQL `seller` · port **8106**.
Architecture: §7 (Sellers, payments and subscriptions) and §17d (Stripe Connect compliance sync) of
`design/AfroTransact Architecture.dc.html`. Stories S089–S098 (onboarding) and S099–S104 (compliance, billing).

## Model

| Table | What it holds |
|---|---|
| `organisation` | `org_…` id, kind `SELLER · PROVIDER`, legal and display name, country, status `DRAFT · PENDING_REVIEW · FLAGGED · AWAITING_FINAL_APPROVAL · INFO_REQUESTED · APPROVED · REJECTED · SUSPENDED` |
| `org_member` | user id (identity-service) + org role key; the applicant becomes `seller.owner` / `provider.owner` |
| `application` | one per organisation and per applicant: `APP-…` reference, status, current step, wizard sections (`data` jsonb), prefilled fields, reviewer / final approver, failed checks, requested items, reason code. DB check: final approver ≠ reviewer |
| `application_event` | timeline: action, from → to status, actor, reason code, note, items, time |
| `document_access_log` | every reviewer view of a document and of unmasked PII |
| `review_reason_code` | seeded: `ALL_CHECKS_PASSED`, `PERMIT_MISSING`, `LICENCE_EXPIRED`, `IDENTITY_MISMATCH`, `TIN_MISMATCH`, `DOCUMENT_UNREADABLE`, `INSURANCE_MISSING`, `PROHIBITED_CATEGORY`, `REPUTATION_RISK`, `SANCTIONS_HIT`, `DUPLICATE_ACCOUNT`, `OTHER` |
| `launch_list` | (country, email) for markets that are not live, with the shopping-updates opt-in |
| `plan`, `plan_price` | seeded catalogue (Seller Essentials / Growth / Pro, Provider Solo / Studio / Business) and the Stripe Price `lookup_key` per cadence (`seller_growth_quarterly`) |
| `connect_account` | Stripe requirements (`currently_due`, `eventually_due`, `past_due`, `pending_verification`, `errors`, `current_deadline`, `disabled_reason`), `charges_enabled`, `payouts_enabled`, capabilities, persons, derived status, `requirements_version`, payout hold, listings hidden |
| `connect_account_history` | full change history (Stripe events, seller answers, staff actions) with the snapshot |
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
| `stripe-verification` | `POST /v1/applications/me/stripe/account-session` creates the connected account on first use (controller: Stripe-collected requirements, fees and losses on the platform, no Stripe dashboard, manual payouts) and returns the Account Session `client_secret` for the embedded account-onboarding / account-management components; `POST …/stripe/refresh` re-reads verification results. Submission needs `details_submitted` |
| `payouts` | ABA routing checksum, account number entered twice, last 4 + bank name stored, `externalAccountToken` attached to the connected account |
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

`GET /v1/orgs/{id}/public` (no auth: display name, kind, status — indexer, cart, storefront), `GET /v1/orgs/{id}`
(members and reviewers), `PATCH /v1/orgs/{id} {displayName}` (owner, approved orgs) → `seller.org.updated`.

## Stripe compliance (S099–S102)

`POST /v1/webhooks/stripe-connect` (secret `STRIPE_CONNECT_WEBHOOK_SECRET`): signature verified with
`com.stripe.net.Webhook.constructEvent`, de-duplicated by event id in `stripe_webhook_event` (same transaction as the
change), `account.updated` snapshots applied only when `event.created ≥ requirements_version` (older ones are
recorded as `stale`). Handles `account.updated`, `capability.updated`, `person.created/updated`,
`account.external_account.updated`, `payout.failed`, `account.application.deauthorized`.

Status: past due or disabled → `RESTRICTED`; currently due with ≤ 3 days to the deadline → `RESTRICTED_SOON`, else
`ACTION_NEEDED`; only pending verification → `VERIFYING`; else `COMPLIANT`. Enforcement: past due → payouts paused
(`seller.payouts.paused`, reason `AUTO_PAST_DUE`) and resumed automatically when cleared; charges disabled →
`seller.listings.hidden`, re-enabled → `seller.listings.restored`; deauthorisation restricts everything. Staff holds
(`MANUAL`) are only released by staff and never while Stripe reports past-due items.

| Endpoint | Who |
|---|---|
| `GET /v1/orgs/{orgId}/account-health` | org owner / finance: status, flags, each requirement with a typed form (tax ID, ID number token, verification document, ownership declaration, representative DOB / title, ToS acceptance, bank account token, business URL) |
| `POST /v1/orgs/{orgId}/account-health/requirements/{requirement} {values}` | org owner / finance: validated, documents fetched from media-service (`GET /v1/assets/{id}` → signed URL) and uploaded to Stripe Files (`account_requirement`, on the connected account), then Accounts / Persons update; the item moves to pending verification |
| `GET /v1/compliance/accounts?status=&openOnly=` · `GET /v1/compliance/accounts/{id}` | `compliance:remind` or `compliance:hold` |
| `POST /v1/compliance/accounts/{id}/reminders` | `compliance:remind` |
| `POST /v1/compliance/accounts/{id}/payouts/pause` | `compliance:remind` or `compliance:hold` |
| `POST /v1/compliance/accounts/{id}/payouts/resume` | `compliance:hold` |

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

`POST /v1/webhooks/stripe-billing` (secret `STRIPE_BILLING_WEBHOOK_SECRET`): `invoice.paid`,
`invoice.payment_failed` (dunning `RETRYING` / `FINAL_ATTEMPT_FAILED`, status `PAST_DUE`),
`customer.subscription.updated` / `deleted`.

## Events (outbox → Kafka, `contracts/asyncapi/fragments/seller.yaml`)

| Topic (key) | Types | Consumers |
|---|---|---|
| `at.seller-application.events` (application id) | `seller.application.submitted / flagged / info_requested / approved / rejected` | notifications, staff console, trust-ops |
| `at.seller-org.events` (org id) | `seller.org.approved`, `seller.org.updated` | identity (unlock back office, mirror org), indexer and cart (display name) |
| `at.seller-compliance.events` (org id) | `seller.compliance.changed`, `seller.compliance.reminder`, `seller.payouts.paused / resumed`, `seller.listings.hidden / restored` | notifications (email + SMS + in-app), payments (payout holds), catalog / offers / bookings / indexer (visibility) |
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
Other: `AUTH_ISSUER_URI`, `SELLER_LIVE_COUNTRIES` (`US`), `MEDIA_BASE_URL`, `MEDIA_SERVICE_TOKEN`,
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
