# Tax gate paths: nothing is live without a tax decision

**Owner rule (firm):** a product or variant is never live (purchasable, in search, buyable through an offer) unless it has a
stored, **resolved** tax decision. No default rate, no default outcome (rule TX-10 in [category-tax-design.md](./category-tax-design.md),
checkout side in [checkout-tax-estimate.md](./checkout-tax-estimate.md)). This page lists every path that can put a product
on sale, whether it goes through the gate, and the test that proves it. **A path that is not in this table is a bug.**

*Not tax or legal advice. Nothing here decides any tax outcome; it only refuses to sell when no decision exists.*

## How the rule is enforced (five layers)

1. **One choke point.** `ProductLiveGate` / `ProductLiveGateImpl` (catalog, `application`) is the only component that moves a
   product into LIVE: `approve`, `resume`, `restoreFromTakedown`, and (for a listing held for tax) `reconcile`. Each refuses
   with `TaxNotReady` (RFC 9457 ProblemDetail, code `catalog.tax-not-ready`, 422, plain words per variant) unless every
   variant is READY **and** has a stored decision (`VariantTax.ready()`: status READY and a decision object; READY without a
   decision is not trusted).
2. **Build guard.** `TaxGateArchitectureTest` (ArchUnit) fails the build when any class other than `ProductLiveGateImpl` calls
   `Product.approve`, `resume`, `restoreFromTakedown` or `restoreAfterTaxHold`, when `JdbcProductRepository` stops calling the
   invariant, or when application code (other than the writer of new products) builds a `Product` with a status by hand.
3. **Write guard.** `JdbcProductRepository.insert / update / updateWithVariants` call `LiveRequiresTax.require`: a LIVE product
   whose variants are not all resolved is **not stored** (fail closed, `TaxNotReady`).
4. **Fail-closed reads.** The public catalog (`PublicCatalogServiceImpl`, used by offers to attach an offer to a variant),
   the moderation public lookup and every `catalog.product.*` event / search export payload (`ProductEventPayload`) treat a
   LIVE product with an unresolved variant as **not live** (`status` is announced as `IN_REVIEW`). Offers (`productLive`),
   the search indexer (document `status`), inventory and the cart (live offers only) all follow that status.
5. **Safety net and alarm.** A sweep (`TaxGateSweepJob`, every 60 s) closes anything that still slipped through, and
   `catalog_live_without_tax_decision` (gauge, must be 0) pages when it is above 0
   ([runbook](../runbooks/CatalogLiveWithoutTaxDecision.md)). Staff see a red banner with the count on **Fees & commissions
   > Sales tax > Tax review**, linking to the list (`GET /v1/tax-gate/live-without-decision`, permission `tax:read`).

**The held state.** A live listing that loses its decision (a new variant, a changed answer, tax-returns says STALE, its
category closes) goes to **IN_REVIEW** with the note "We are checking the tax details for this item." and a row in
`product_tax_hold` (reason `TAX_<status>`, e.g. `TAX_PENDING_REVIEW`, `TAX_MISSING_FACTS`, `TAX_DECISION_MISSING`).
`catalog.product.paused` is published with that reason and status IN_REVIEW, so search and offers drop it. When every
variant is resolved again, and only if it was held for tax, the gate puts it back on sale (`catalog.product.resumed`) once the
seller may still sell it. A product that was never live (new, or imported from v1) has no hold row and is **never** published
by the gate on its own: it waits for a person ([migration runbook, 4.7](../runbooks/afrotransact-v1-to-v2-migration.md#47-products--never-live-without-tax)).

## Every path that can make a product live or an offer purchasable

`Gate` is "yes" when the path ends in `ProductLiveGate` (or the write guard refuses it). Test names are in
`java/services/catalog/src/test/java/com/afrotransact/catalog/` unless a service is named.

| # | Path | File:line | Gate? | Evidence |
|---|---|---|---|---|
| 1 | Staff approve `POST /v1/products/{id}/approve` (also **trust-ops approve**, which calls it) | `application/ProductServiceImpl.java:131` -> `ProductLiveGateImpl.java:46` | yes | `TaxGatePathsTest.staffApproveRefusesAVariantWithoutAResolvedDecision`, `...RefusesAReadyStatusThatHasNoStoredDecision`, `...MakesAResolvedProductLive`; `ProductLiveGateImplTest.approveRefuses...` |
| 2 | Auto-publish on create / on a rejected listing's edit (`SellingGatedProductWriter`, used by the product form, **bulk upload / CSV import** and the image library) | `application/SellingGatedProductWriter.java:93` -> gate | yes (readiness checked first, then the gate) | `SellingGatedProductWriterTest.aListingWhoseTaxIsNotReadyStaysForReviewAndAutoPublishCanBeTurnedOff`, `...aClearListingWithReadyTaxGoesLiveWithoutReview` |
| 3 | Seller resume of a paused listing (`POST /v1/products/{id}/resume`; the relist after unpublish) | `ProductServiceImpl.java:163` -> `ProductLiveGateImpl.java:54` | yes. **Was not gated before this change** (only the selling approval was checked) | `TaxGatePathsTest.aSellerCannotResumeAPausedListingWhoseTaxIsNotResolved`, `...aSellerResumesAPausedListingWhoseTaxIsResolved` |
| 4 | Selling-approval sweep resumes paused listings (seller-service `seller.selling.listings.restored` / `rule.changed`) | `application/SellingHoldServiceImpl.java:152` -> gate | yes. **Was not gated before**; a listing without a decision now stays paused | `TaxGatePathsTest.theSellingHoldSweepDoesNotResumeAListingWithoutAResolvedTaxDecision`, `...ResumesAListingWhoseTaxIsResolved` |
| 5 | Trust-ops / staff restore after a takedown `POST /v1/products/{id}/restore` | `application/ListingModerationServiceImpl.java:113` -> `ProductLiveGateImpl.java:60` | yes. **Was not gated before**; without a decision the listing returns held (in review), not live | `TaxGatePathsTest.restoringATakedownDoesNotPutAListingLiveWithoutAResolvedTaxDecision`, `ProductLiveGateImplTest.aTakedownReversedTo...` |
| 6 | Seller edit of a **live** listing (a new variant, a changed tax answer) | `application/ProductWriterImpl.java:229` -> `ProductLiveGateImpl.java:66` | yes. **Was a gap**: the product stayed LIVE with a variant in PENDING_REVIEW | `ProductWriterTaxGateTest.everyEditGoesThroughTheLiveGateSoALiveListingWith...`, `ProductLiveGateImplTest.closeIfNotReady...` |
| 7 | `tax.classification.changed` consumer: VARIANT_CLASSIFICATION (approved / stale / rejected) | `adapter/in/messaging/TaxClassificationEventListener.java:66` -> `ProductTaxServiceImpl.java:197` -> `reconcile` | yes. **Was a gap**: only the variant status was stored; a listing that went STALE stayed live, and an approved one was never put back | `TaxGatePathsTest.aClassificationThatTurnsStaleClosesTheLiveListingInTheSameTransaction`, `...anApprovedClassificationBringsAHeldListingBackOnSale`, `...NeverPublishesAListingThatWasNeverHeld` |
| 8 | `tax.classification.changed` consumer: PROFILE_STATUS (a category closes) | `ProductTaxServiceImpl.java:202` -> `recheckAll` (all pages, was the first 500) -> `reconcile` (`:106`) | yes (**fixed**: closed categories now reach every product, and live ones are closed) | `ProductLiveGateImplTest.reconcileClosesAStoredLiveProductThatNoLongerHasAResolvedDecision`; paging has no unit test (needs a database, see below) |
| 9 | `GET /v1/products/{id}/tax-readiness` (re-checks the variants) and tax evidence upload | `ProductTaxServiceImpl.java:81` (`recheck`), `:116` | yes (`recheck` ends in `reconcile`) | `ProductLiveGateImplTest.reconcile...` |
| 10 | Category request approved (`PENDING_CATEGORY_APPROVAL` -> `IN_REVIEW`) | `application/CategoryServiceImpl.java:182` -> `Product.categoryApproved` | not needed: result is IN_REVIEW, never LIVE | `CategoryRequestTest` |
| 11 | Product create `POST /v1/products`, product form, **bulk import**, image library | `ProductWriterImpl.java:94` (status IN_REVIEW or PENDING_CATEGORY_APPROVAL only) | not needed: never LIVE; the write guard would refuse LIVE | `TaxGateArchitectureTest.nothingInTheApplicationLayerBuildsAProductWithAStatus...`, `LiveRequiresTaxTest` |
| 12 | Any `ProductRepository.insert/update/updateWithVariants` of a LIVE product (a path nobody listed) | `adapter/out/persistence/JdbcProductRepository.java:64,99,121` | yes: refused (`TaxNotReady`) | `LiveRequiresTaxTest`, `TaxGateArchitectureTest.everyProductWriteInThePersistenceAdapterChecksTheLiveInvariant` |
| 13 | A new call to a live transition added anywhere | `domain/Product.java:90,121,137,83` | yes: the build fails | `TaxGateArchitectureTest.onlyTheLiveGateMayPutAProductOnSale`, `...theRulesStillSeeTheGateCallingTheTransitions` |
| 14 | `reject`, `pause`, `takedown`, `holdForTax` (take a listing off sale) | `Product.java:97,114,129,75` | not needed (cannot make anything purchasable) | n/a |
| 15 | **Migrations** (`V1__baseline`, `V1_1__initial_data`, `V2`, `V3`) | `src/main/resources/db/migration/` | they insert no products (V1_1 seeds categories only; V2 only sets package columns); `V4__product_tax_hold.sql` **closes** every live product without a resolved decision | `FreshCatalogDatabaseIT` (Docker; not run here) |
| 16 | **Seed / demo loaders** (`tools/seed/seedlib/seeder.py` `_approve_product`) | `seeder.py:471` | yes: they call the API (`POST /v1/products/{id}/approve`), review the tax in tax-returns first, never write SQL | the API tests in rows 1-3 |
| 17 | **v1 import** (nothing in the repo copies v1 products; they are re-uploaded) | `docs/runbooks/afrotransact-v1-to-v2-migration.md` 4.7 | yes: upload creates products in review, no hold row, never auto-live; SQL with LIVE is refused / closed | rows 11-12, `V4` |
| 18 | **Retail** item listing `LIVE` (`retail` -> `offers` `RetailListingSyncServiceImpl`) | `offers/.../RetailListingSyncServiceImpl.java:66` | yes, indirectly: it only sets the offer ACTIVE; an offer is purchasable only when `productLive` (catalog status LIVE), which needs the gate | `ProductEventPayloadTest.aLiveProductWithoutAResolvedTaxDecisionIsNeverAnnouncedAsLive` |
| 19 | Offers: a second seller creates an offer on a variant (`OfferServiceImpl.resolve` -> `catalog.liveVariant`) | `offers/.../OfferServiceImpl.java:372`, catalog `PublicCatalogServiceImpl.java:81` | yes (**fixed**): the variant must be on a LIVE product with every variant resolved | `PublicVariantContractTest`; `publiclyVisible` shares the invariant with rows 12-13 |
| 20 | Offers: `catalog.product.*` events set `productLive` | `offers/.../CatalogSyncServiceImpl.java:202` (`LIVE.equals(p.status())`) | yes (**fixed**): the payload says LIVE only when resolved | `ProductEventPayloadTest.aLiveProductWithoutAResolvedTaxDecisionIsNeverAnnouncedAsLive` |
| 21 | Search export / reindex (`GET /v1/search-export`) and the indexer | `application/SearchExportServiceImpl.java:42`, `ProductEventPayload.java:68`; indexer `go/services/indexer/internal/app/projector.go` (status from the payload) | yes (**fixed** for legacy rows: exported as IN_REVIEW) | `SearchExportServiceTest`, `ProductEventPayloadTest...`; search itself only returns `status = LIVE` (`go/services/search/.../query.go:171`, tested in the indexer `projector_test.go`) |
| 22 | Cart / checkout | `go/services/cart/internal/app/cart.go:134` (live offers only); orders `CheckoutTaxImpl` | yes: cart sees only live offers; checkout **also** blocks any line without a RESOLVED decision at quote time (`orders.tax-line-blocked`) | `CheckoutTaxImplTest`, gateway-bff `TestCreditNeverEntersTheTaxQuote` (existing) |
| 23 | Sweep (`TaxGateSweepJob` -> `TaxGateSweepServiceImpl`) | `adapter/in/scheduling/TaxGateSweepJob.java` | closes (and, for held listings, reopens through the gate) | `TaxGateSweepAndAuditTest` |

### Gaps found by this audit, all closed

- Resume of a paused listing, the selling-approval resume sweep and the restore after a takedown each moved a product to LIVE
  **without** the tax gate (rows 3-5).
- An edit of a live listing (new variant, changed answer) left it LIVE with a variant waiting for review (row 6).
- `tax.classification.changed` only stored the variant's status; a listing whose decision went STALE stayed live, and one that
  was approved was never put back on sale (rows 7-8). A category closing only reached the first 500 products.
- A LIVE variant with no tax record was in **no** queue, because the review queue lives in tax-returns and only holds records
  tax-returns knows. The audit now reads catalog (`catalog_live_without_tax_decision`, the staff banner and list).
- A variant whose status was READY but whose decision was missing counted as ready; it no longer does.

## Existing data

`V4__product_tax_hold.sql` closes every currently LIVE product that has a variant without a stored decision (status other than
READY, or no decision object, or no variant): status `IN_REVIEW`, note "We are checking the tax details for this item.", hold
reason `TAX_DECISION_MISSING`. It creates no decision and changes no tax status. The first sweep after start-up publishes
`catalog.product.paused` for each (search and offers drop them) and sets `announced_at`. When tax review approves a
classification, the listing comes back on its own. Running it twice changes nothing (a closed product is no longer LIVE).

## What is not closed

- **Moderation approval after tax approval is still a person.** A new listing becomes live only after tax review **and** a
  moderator approval (or at creation when its tax is already approved). The gate does not publish a never-live product when
  tax review approves it; only a listing that was live and closed for tax returns by itself. This is the existing design
  (category-tax-design.md section 6, step 5 says the product "goes live"; the code never did that and this change does not add it).
- **Per-seller decisions on a shared variant.** Several sellers can offer the same variant, each with its own stored decision in
  tax-returns (`listing_tax_decision`). Catalog holds only the listing owner's decision. A second seller's offer is created on a
  variant whose page is resolved; if that seller has no resolved decision of their own, **checkout blocks the line** (it
  quotes by variant and seller) but the offer can show. Closing that needs a tax-returns lookup at offer creation; not built.
- **Notification to the seller** is the status and the note in Seller Center (listing row and product form, in all five
  languages for the product form). No email or push is sent for a tax hold yet.
- Integration tests that need a database (Docker was not available here): `V4` applied to a database with LIVE rows, the audit
  SQL, the persistence guard against a real insert, and `recheckAll` paging. See the verification notes in the change description.
