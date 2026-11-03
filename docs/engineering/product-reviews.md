# Verified-purchase product ratings and reviews (S324)

A customer who received a product can rate it 1 to 5 stars and add a title, text (at most 4000 characters) and up to
5 photos. The review shows as **Verified purchase** with the reviewer's first name and last initial only. The average,
the count and the 1 to 5 star breakdown follow the product everywhere: the product page, listing cards, search (rating
filter and sort) and the structured data of the product page. Booking reviews (bookings-service) are a separate feature
and keep working unchanged.

## Where it lives

| Concern | Service | What |
|---|---|---|
| Reviews, replies, reports, the per-product aggregate | **catalog-service** (`com.afrotransact.catalog`, Flyway `V10__product_reviews.sql`) | Tables `product_review` (optimistic `version`), `product_review_report`, `product_rating`. Keyed by the product **ATIN**. |
| Did this customer receive the product? | **orders-service** | `GET /v1/lookup/customers/{customerId}/purchases/{atin}` (scope `orders:purchase-check`) answers from a **DELIVERED** order line. |
| Moderation queue | **trust-ops-service** | Item type `PRODUCT_REVIEW` (Flyway `V6__product_review_moderation.sql`). Staff approve, reject with a reason, or remove. |
| Search document | **indexer** | `catalog.product.rating_changed` sets `rating` and `reviewCount` (Elasticsearch and Meilisearch). |
| Customer, seller and staff APIs | **gateway-bff** | Tag `Reviews` in `gateway-bff.yaml`, `gateway-bff-seller.yaml`; resource `product-reviews` and the moderation queue in `gateway-bff-staff.yaml`. |

Catalog follows the usual hexagonal layout. `ProductReviewService` (customers), `SellerReviewService` (the product's
seller) and `ReviewModerationService` (trust-ops) are interfaces with package-private implementations. The ports are
`ProductReviewRepository` (JDBC) and `PurchaseVerifier`, whose `HttpPurchaseVerifier` adapter calls orders-service with a
client-credentials token (`orders:purchase-check`). `domain` imports no Spring; `ArchitectureTest` checks it.

## Rules

- **Who can review.** Only a customer with a delivered order line for that ATIN. Anyone else gets `403` with the code
  `REVIEW_NOT_VERIFIED_PURCHASE` (BFF) and a clear message. If orders-service cannot be reached the answer is `503`, never a
  guess.
- **One review per customer per product** (unique index on `product_id, customer_id` for live reviews; deleting a review
  frees the slot). A second attempt answers `409 REVIEW_ALREADY_EXISTS`.
- **Edit within 30 days** (`at.catalog.reviews.edit-window`, default `30d`), then it is final (`409
  REVIEW_EDIT_WINDOW_CLOSED`). A customer can **delete** their review at any time. Editing sends the review through the
  automatic checks again.
- **Name.** The public name is built from the account's first name and the initial of the last name, for example
  `Adaeze N.`; nothing else about the customer is returned by the public or the seller API.
- **Photos.** Uploaded with the media service's signed direct upload (`POST /v1/review-photos/uploads`, `PUT` the file,
  `POST .../complete`), purpose `storefront`. The BFF only accepts the id of an asset that media marked `READY`, or a URL the
  customer's own review already has; catalog also accepts only https URLs (optionally limited to
  `at.catalog.reviews.photo-hosts`). A forged URL answers `422`.

## Automatic checks and moderation

On every write catalog runs `ReviewContentPolicy` (`DefaultReviewContentPolicy`) over the title and text:

| Flag | Examples |
|---|---|
| `PROFANITY` | built-in word list plus `at.catalog.reviews.extra-blocked-words`; stretched spellings and roots inside words |
| `LINK` | `http://`, `https://`, `www.` and common domain names such as `shop.example.com` |
| `EMAIL` | `name@example.com`, `name (at) example.com` |
| `PHONE` | eight or more digits with the usual separators |

No flag: the review is **PUBLISHED** at once. Any flag: it is **PENDING**, not public, and catalog publishes
`catalog.review.submitted` with the flags. trust-ops consumes it (topic `at.product-review.events`, inbox consumer
`trust-ops:product-reviews`), opens a `PRODUCT_REVIEW` moderation item (same SLA, assignment, audit trail and AI triage as
listings; the AI step fails open) and staff decide in the console, **Catalog, Moderation queue** (or **Product reviews** for
any review):

- **Approve** publishes it. **Reject** (with a reason code and note the customer sees) or **Remove** takes it down; the
  customer sees the reason and can edit and resubmit.
- Reasons: `ABUSIVE`, `PERSONAL_INFORMATION`, `SPAM`, `OFF_TOPIC`, `CONFLICT_OF_INTEREST`, `OTHER`.
- Staff see **names, never ids**: the product title, the seller and the reviewer by name. The console resource
  `product-reviews` and the queue are server-driven, so the names framework resolves the author's account.

**Reports.** A customer (not the author) or the product's seller can report a published review
(`POST /v1/reviews/{id}/reports`, `POST /v1/seller/reviews/{id}/reports`): one report per person and review, a reason code
and details (required for `OTHER`). The review stays public. The first report opens a moderation item (or raises the
report count of an open one). Approving a reported review keeps it public; rejecting or removing it takes it down.

**Sellers** read the published reviews of their products in **Seller Center, Reviews** (module key `reviews`, identity
module `sListings`), reply **once** (public, `1..1000` characters, cannot be edited or deleted) and report. There is no
route to edit, hide or remove a review: only Trust & Safety can.

## Aggregate and events

`product_rating` holds, per product, the average over PUBLISHED reviews, the count and the breakdown (`star1..star5`).
It is rewritten **in the transaction of every status change** (publish, edit, delete, reject, remove, reinstate), so it
never drifts from the reviews. Rejected, removed and deleted reviews do not count.

Events (transactional outbox): the review events use aggregate `product-review` (topic `at.product-review.events`):
`catalog.review.submitted|published|updated|rejected|removed|deleted|reported|replied`. **`catalog.product.rating_changed`**
`{productId, atin, ownerOrgId, average, count, breakdown}` goes on the product aggregate (topic `at.product.events`). The
indexer applies it with the event version in `rating_version`, so an older event can never overwrite a newer one, and a
rating for a product that is not indexed yet is ignored (the product document starts at rating 0 and count 0). See
`contracts/asyncapi/events.yaml` (generated; edit the fragments and run `tools/contracts/merge_asyncapi.py`).

## API

Contracts first: `catalog.yaml` (`/v1/products/{atin}/reviews`, `/reviews/mine`, `/v1/product-reviews/**` for staff,
`/v1/public/products/{atin}/rating|reviews`, `/v1/seller/reviews`), `orders.yaml` (purchase check), `trust-ops.yaml`
(item type), and the three gateway contracts. The BFF copies are byte-identical to `go/services/gateway-bff/api/*.yaml`
(a test enforces it) and a conformance test validates every operation.

The public list takes `sort` (`recent`, `highest`, `lowest`), `stars` (filter), `page` and `size`; catalog answers with
`Cache-Control: public, max-age=30` and it covers only PUBLISHED reviews. `GET /v1/products/{atin}/reviews/mine` answers `{eligible, reason, review}`,
where `reason` is `not-purchased` or `already-reviewed`.

## Clients

- **Storefront.** The product page has the ratings section (average, count, star breakdown that filters the list, sort,
  paging, the shopper's own review, report). **Rate this item** is offered on delivered items of the order page
  (`OrderDetailView`) and of Your orders; it opens the review sheet (stars, title, text, photos). Texts are in
  `rev.*` in all five locales; styles use design tokens only.
- **Seller Center.** Reviews (`sc.reviews.*`): list, unanswered filter, reply, report.
- **Customer app.** The delivered items of the tracking screen offer **Rate this item** (stars, title, text). Photos are not
  collected in the app yet.
- **api-client.** `reviewMethods` (+ `uploadReviewPhoto`), `reviewKeys` / `reviewQueries`, Zod schemas in
  `schemas/reviews.ts`, and MSW mocks (`mocks/reviews.ts`, seller and staff mocks).

## Operating it

| Setting | Default | |
|---|---|---|
| `at.catalog.reviews.edit-window` | `30d` | How long a review can be edited |
| `at.catalog.reviews.photo-hosts` | any https host | Restrict photo URLs to media-service hosts in uat/prod |
| `at.catalog.reviews.extra-blocked-words` | empty | Extra words that send a review to moderation |
| `at.catalog.orders.base-url` / `timeout` | `http://localhost:8104` / `3s` | orders-service for the purchase check |

identity needs the client scope `orders:purchase-check` on `catalog-service` (seeded in `application-{local,dev,uat,prod}.yml`).

## Tests

Java: `DefaultReviewContentPolicyTest`, `ProductReviewTest`, `RatingSummaryTest`, `ArchitectureTest` and `ProductReviewIT`
(catalog), `PurchaseCheckIT` (orders), `ProductReviewModerationIT` and `ModerationItemTest` (trust-ops). The ITs honour
`AT_IT_POSTGRES_URL`. Go: the indexer projector and index tests, the BFF `product_reviews_test.go` and the seller and
staff conformance tests. TypeScript: api-client `reviews.test.ts`, storefront and Seller Center `reviews.test.tsx`, customer app
`reviews.test.ts`.
