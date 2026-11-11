# Selling approvals: sell, apply or not available

Amazon-style approvals from [the approved listing rules](../engineering/listing-rules.md) (D1 to D6, D11, sections 2 and 5).
A product is **SELL** (live after validation), **APPLY** (the seller applies once; afterwards every listing in that scope goes
live) or **NOT_AVAILABLE** (prohibited or disqualified, with the reason, not appealable). This page covers the model, who
does what, the lifecycle jobs, and how to restart and migrate.

## The model (seller-service, Flyway V90)

| Table | What it holds |
|---|---|
| `selling_rule` | One rule per scope: `CATEGORY`, `PRODUCT_TYPE`, `BRAND`, `BRAND_IN_CATEGORY` (`brand|category`), `PRODUCT` (barcode) and `CAPABILITY` (`FOOD_SERVICE`). Level `SELL`, `APPLY` or `NOT_AVAILABLE`, the documents it asks for, the reason sellers read, a reapply suggestion, and for jewelry the condition (material gold, silver, gemstone, or a watch). |
| `selling_approval` | The organisation's approval per scope: `APPLIED`, `NEEDS_ATTENTION`, `APPROVED`, `EXPIRED`, `NOT_AVAILABLE` (NOT_APPLIED is the absence of a row), with reason, reapply date, the documents on file with their dates, the earliest expiry and the warning stamps. |
| `selling_application`, `selling_application_event`, `selling_case`, `selling_document_access` | The staff case: the same shape as `application`, `application_event`, `application_case`, `document_access_log`. |
| `selling_rule_audit` | Every rule change: who, why (10+ characters), before and after. |

Category rules are **seeded from `tools/listing-rules/rules.json`** (`open` and `brand_gated` and `provider_application` are
SELL, `approval` and `conditional_approval` are APPLY, `blocked` is NOT_AVAILABLE) by `tools/listing-rules/gen_selling_seed.py`;
`python3 tools/listing-rules/gen_selling_seed.py --check <V90 file>` fails when the migration no longer matches the JSON.
The starter gated brands (designer and well-known fashion and electronics brands, perfume houses) are rows too: staff edit
them in **Catalog, Selling rules**. Everyday words (Apple, Coach, Puma) are gated only inside the categories where the brand
is the risk (`BRAND_IN_CATEGORY`); Gucci, Chanel, Rolex and similar are gated everywhere (`BRAND`).

A listing needs approval for **every** scope that applies: its category (or a parent), product type, brand, brand in the
category, barcode. `GET /v1/orgs/{orgId}/selling-status` (BFF `GET /v1/seller/selling-status`) returns the answer, the reason and
what is missing; the product form shows it as a chip. AfroTransact Retail always sells.

Documents: purchase invoice dated within 180 days, photos of all sides, brand authorization, safety or lab certificate,
permit or licence (expiry date), liability insurance naming AfroTransact as additional insured (expiry date). A seller uploads
each file to media (private, purpose `kyc-document`, the onboarding upload route) and sends the asset id with the dates read off
it. The service checks the dates (an old invoice, an expired permit, an unconfirmed insured clause) with a message per document.

## Staff: the case type "Selling approval"

It sits in the staff console under **Partners, Applications** (tab **Selling approvals**) and uses everything built for
applications ([application-review-workflow.md](application-review-workflow.md)): the queues (My queue, Awaiting review,
Awaiting approval, Escalated, Returned to applicant, All; chips Overdue and Unassigned), claim, assign, release, escalate,
"who has it" names, the SLA clocks, the escalation job, the two-person rule, request info, send back, reject, and the same
permissions (`applications:review`, `applications:approve`, `applications:assign`). The SLA and escalation settings are the
**same** settings (target 2 business days is within the 24 hour defaults; set what you want in *Application review: SLAs
and escalation*). Differences:

- A step 1 or final **approval needs every required document on file and unexpired**; a case carried over from the bakery
  flag has none, so ask the seller (Return to seller) and approve after they resubmit.
- **Cannot apply (not appealable)** at step 2: the seller reads the reason and the day they may apply again (required, after
  today). They cannot apply before it. Use it for fraud or counterfeits; use *Reject* for a fixable application (they can
  apply again at once).
- Staff open each document from the sheet (new tab, signed URL, every opening in the access log).
- **Seller approvals** (same tab group): the approvals sellers hold, with when they run out. Staff with `applications:approve`
  can **pause** a scope's listings (a performance problem; reason of 10+ characters, the seller is told), **restore** them, or
  **withdraw** the approval with a reapply date.
- **Catalog, Selling rules**: scope rules, gated brands, required documents. Changing needs `selling-rules:manage` (Super
  Admin, Catalog Manager, Application Supervisor; identity V42), a reason, and is audited. Making a scope stricter pauses the
  listings of sellers who do not qualify (never deletes them); relaxing it brings them back.

## Lifecycle (jobs, idempotent and multi-replica safe)

`SellerJobs.sellingLifecycle` runs hourly (`at.seller.jobs.selling-lifecycle-cron`, default `0 10 * * * *`). Each approval is
checked under its row lock and stamps what it did, so replicas and re-runs act once.

| When | What |
|---|---|
| 30, 14, 7 days before the earliest document expires | One warning per threshold (a job that was down skips the thresholds already passed and sends the tightest). Event `seller.selling.approval.expiry_warning` to the organisation's owners; the Selling approvals page shows a notice. |
| The day after expiry | Status `EXPIRED`, event `seller.selling.approval.expired`. New listings are refused; existing ones stay up. |
| 14 days after expiry | The scope's listings are **paused** (never deleted): `seller.selling.listings.paused` (reason `EXPIRED`); supervisors are told. |
| A renewal is approved | `seller.selling.listings.restored` (reason `RENEWED`): the listings come back. A listing the seller paused themselves stays paused. |
| A rule becomes stricter or a seller has a performance problem | `seller.selling.rule.changed` (catalog re-checks the scope) or a staff pause (`seller.selling.listings.paused`, reason `PERFORMANCE`). |

`SellerJobs.sellingEscalations` runs every 5 minutes like the application escalations. `SellerJobs.foodServiceMigration` runs
every 6 hours (see below).

## Catalog: the publish gate and the holds

catalog-service (a small port `SellingApprovals` and an adapter calling `GET /v1/orgs/{orgId}/selling-status` with its service
token, scope `sellers:catalog-scope.read`) **refuses to take a listing live** when the seller lacks an approved, unexpired
approval or the scope is not available: 409 `catalog.selling-approval-required` with a plain message, what is missing and
`applyUrl` (`/selling-approvals?apply=CATEGORY:dietary-supplements`); the transaction rolls back, so the form's draft stays.
The check is made after validation on create (form, bulk upload, image library), on a change of category, brand or material,
when staff approve a listing, and when a seller resumes a paused one. When seller-service cannot be asked the answer is 503
(fail closed). **Listings in scopes the seller may sell go live after validation without human review** when their tax details
are ready (`at.catalog.listing.auto-publish`, default true); individual listings are reviewed only when flagged (trust-ops, unchanged).

Pausing: catalog consumes `seller.selling.listings.paused` / `.restored` / `seller.selling.rule.changed` from
`at.seller-compliance.events` with its own consumer group (`catalog-service-selling`). A paused listing gets status `PAUSED` plus a
row in `product_selling_hold` (catalog Flyway V3) saying why; it is resumed only when its last hold goes, and the seller cannot
resume it by hand. Catalog publishes `catalog.product.paused` / `resumed`, which the indexer already follows, so search drops and
restores the listing.

Switches: `at.catalog.selling-gate.enabled` (default true; off restores the old review queue), `at.catalog.listing.auto-publish`.

## Food service (D11)

Seller capability `FOOD_SERVICE`: one approval (permit with expiry and insurance) covers bakery products and prepared meals
(the category rules refer to the capability). `GET /v1/orgs/{orgId}/selling-capabilities` tells the product form to show food
service fields only to approved sellers. The old bakery answer (`sellerBehaviour.seller_is_bakery` on a listing) stays in the
catalog until migrated: `FoodServiceMigrationService` reads the organisations that claim it (catalog
`GET /v1/internal/bakery-claims`, seller's service scope `catalog:bakery-claims.read`) and opens one **PENDING** application per
organisation (source `MIGRATION`, in the staff queue with "From bakery flag"). It never approves: no permit is on file, so a
reviewer asks for it. Re-runs create nothing.

Service providers keep their onboarding application unchanged.

## Events

`seller.selling.application.submitted | info_requested | approved | rejected` on `at.seller-application.events` (key = application
id); `seller.selling.approval.expiry_warning | expired`, `seller.selling.listings.paused | restored`, `seller.selling.rule.changed` on
`at.seller-compliance.events`. Payload `SellerSellingEvent` in `contracts/asyncapi/fragments/seller.yaml`. Assignment and
escalation reuse `seller.application.assigned | escalated` with `kind` `SELLING_APPROVAL`.

## Restart and migration order

1. **identity** (V42 `selling-rules:manage`; the `seller-service` client gains scope `catalog:bakery-claims.read`).
2. **seller** (V90 creates and seeds the tables; the first token request asks for the new scope, so identity must be first).
3. **catalog** (V3 `product_selling_hold`; new routes and consumer; the publish gate calls seller).
4. **gateway-bff** and the web apps (seller-center, staff-console).

Rollback: set `at.catalog.selling-gate.enabled=false` (no check, old queue) and leave the tables; nothing is deleted anywhere.

## FAQ

- **A seller says "I was approved but my listing is paused".** Look at *Seller approvals* for the organisation: a staff pause (reason
  shown), an expired approval past its grace, or a withdrawal. Restore, or have the seller renew.
- **Why can the seller not apply?** A disqualification with a reapply date; the Selling approvals page shows the date.
- **Where did the existing bakeries go?** Into *Selling approvals* as PENDING cases from the bakery flag; their listings are unaffected
  until staff decide (this migration does not pause anything).
- **Notifications.** The events carry recipients (the organisation's owners, or staff supervisors for pauses). notifications-service does
  not send these emails yet (no templates); until it does, sellers see notices on the Selling approvals page and the product form chip, and
  staff find cases in **My queue** and **Escalated**.
