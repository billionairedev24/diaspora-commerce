# Tax gate paths: nothing is live without a tax decision

**Owner rule (firm):** a product or variant is never live (purchasable, in search, buyable through an offer) unless it has a
stored, **resolved** tax decision. No default rate, no default outcome (rule TX-10 in [category-tax-design.md](./category-tax-design.md),
checkout side in [checkout-tax-estimate.md](./checkout-tax-estimate.md)). This page lists every path that can put a product
on sale, whether it goes through the gate, and the test that proves it. **A path that is not in this table is a bug.**

*Not tax or legal advice. Nothing here decides any tax outcome; it only refuses to sell when no decision exists.*

## One authority: tax-returns' stored decision (the owner's bug, 12 Nov)

**Symptom.** A product created before the gate (Basmati Rice) was still listed and sat in a cart, and checkout answered
`orders.tax-line-blocked: We can't sell Basmati Rice yet`.

**Root cause: two sources of truth.** Checkout asks **tax-returns** (`CheckoutTaxImpl` -> `POST /v1/tax/quotes` ->
`LineDecisionServiceImpl.early`): a line is taxed only when tax-returns' classification record of the variant is `APPROVED`
*and* the rules resolve it to a definite outcome (`decisionStatus` `RESOLVED`). Catalog, until this change, judged "has a
decision" from **its own copy** on the variant row (`tax_status = READY` plus a `tax_decision` object: `TaxReadiness`,
`LiveRequiresTax`, the V4 migration, the sweep and the audit). The two copies are only joined by an asynchronous Kafka event
(`tax.classification.changed`) and nothing ever compared them. They diverged because:

1. `application-local.yml` set `at.catalog.tax.review-required: false`, so catalog marked a variant `READY` the moment the
   rules resolved it (`TaxGateEvaluator.fromResolution`), while tax-returns' default approval policy `ALL` keeps the record
   `PENDING_REVIEW` until a person approves it. Catalog: READY, listed, in the cart. Checkout: `NEEDS_APPROVAL`, blocked.
2. A listing that predates the event (or whose event was missed: consumer down, group not joined, topic reset) has **no record
   in tax-returns at all**; tax-returns only creates one when the first quote asks (`LineDecisionServiceImpl.load`), as
   `PENDING_REVIEW`, so checkout blocks it and catalog never hears (it was already READY locally).
3. V4 and the sweep only closed what catalog's own copy flagged, so neither could see either case.

**Fix: tax-returns is the authority.** A listing is OK only if tax-returns answers `RESOLVED` for **every** variant.

- tax-returns: `POST /v1/tax/listing-decisions/status` (batch of up to 500 ATINs, scope `tax:classify`) reads the same record and
  rules the quote reads (`LineDecisionService.status`), without a ship-to, without creating a record, storing nothing.
  A variant with no record is `MISSING_FACTS`. Test: `ListingDecisionStatusServiceImplTest`.
- catalog: port `TaxDecisionAuthority` (`HttpTaxDecisionAuthority`: batches of 200, 2 s connect and 4 s read timeouts) behind
  `TaxAuthorityCheck`. Used by:
  - **the live gate** (`ProductLiveGateImpl`): `approve`, `resume` and `restoreFromTakedown` need `RESOLVED` from tax-returns on
    every variant *in addition to* catalog's own check; `reconcile` closes a LIVE listing it does not resolve (reason
    `TAX_RETURNS_<status>`, hold, the plain note "We are checking the tax details for this item.", `catalog.product.paused`,
    counted as a disagreement) and reopens a held one only when tax-returns resolves it;
  - **the sweep** (`TaxGateSweepJob` -> `TaxGateSweepServiceImpl`): every `at.catalog.tax-gate.authority-interval` (5 min) it asks
    tax-returns about **every** LIVE listing, one batched call per page of 200, and closes each one that is not RESOLVED. It
    re-reports the facts of every closed listing (`catalog.product.tax-facts-changed`), so tax-returns creates the missing review
    record and learns the **product title**; the Tax review queue then shows a name and the staff approval is one click;
  - **the audit** `GET /v1/tax-gate/live-without-decision`: lists a listing when catalog's check **or** tax-returns fails, with
    plain per-variant reasons; `taxReturnsChecked=false` says tax-returns could not be asked (the list is then not an all-clear).
- **tax-returns unreachable: nothing is marked OK.** Every state is kept, the sweep returns `authorityReachable=false`,
  `catalog_tax_gate_authority_unreachable_total` rises, and a `TaxNotReady` "try again in a moment" refuses a go-live. A held
  listing stays held. No decision is ever invented; the local dataset resolver only prepares answers for forms.
- `at.catalog.tax.authority=off` exists **for tests only** (the catalog ITs run no tax-returns); it logs a warning at start-up.

**Why existing (pre-gate) listings are not auto-resolved.** The gate may only close a listing, never approve one. A listing
comes back **only when tax-returns itself says RESOLVED**: the record must be `APPROVED` by a person (policy `ALL`, the default)
or by the risk rule (`taxReview.mode` `RISK_BASED`, the default; a record held under `ALL` is approved by the sweep when it qualifies), and if the category profile awaits staff approval (`NEEDS_APPROVAL`, a
provisional category such as Fabrics & textiles) the profile must be approved first. Auto-resolving would mean inventing a tax
decision, which the owner's rule forbids.

**Releasing a hold.** Staff open **Fees & commissions > Sales tax > Tax review**, find the product **by name**
(`variant_classification.title`, filled from the product title in `catalog.product.tax-facts-changed`) and approve. tax-returns
publishes `tax.classification.changed` (APPROVED), catalog's listener calls `reconcile`, the hold is released and the listing goes
back on sale (`catalog.product.resumed`) once the seller may still sell it. If the event is missed, the next sweep does it.

**Repair path (local and dev).** Run the sweep now instead of waiting for the schedule:

```bash
make tax-gate-sweep                      # POST http://localhost:8101/v1/tax-gate/sweep (security is off locally)
make tax-gate-sweep TOKEN=<staff token>  # elsewhere: permission tax:classify-admin (Tax Reviewer, Catalog Manager, Super Admin)
```

It returns `{closed, closedByTaxReturns, reopened, announced, checked, taxReturnsReachable, photoCheckSkipped}`. Safe to repeat.

**Tax never waits for media.** `ProductLiveGateImpl.reconcileChecked` decides tax first (a LIVE listing without a resolved
decision, or one tax-returns does not resolve, is closed without asking media). Only after tax passes is a held photo looked up.
When media is unavailable during the sweep (or any `reconcile`), that photo part is skipped: a held listing stays held, a photo
is never published and a held-tax listing is never reopened on an unknown media answer (fail closed). Each skip is counted in
`photoCheckSkipped` (sweep result), in `catalog_tax_gate_media_unavailable_total` (no labels) and logged at WARN with the product
id and ATIN; the sweep carries on with the other products and never answers 503 for this. Approve and resume are different: they
publish, so with media down they still refuse (`ImageServiceUnavailable`, 503 `catalog.media-unavailable`) or `ImageHeld` (422).
After a media outage, restart media (`tools/dev/local.sh restart media`) and run `make tax-gate-sweep` again.

**Carts and checkout.** Closing a listing publishes `catalog.product.paused` (status IN_REVIEW): search drops it, offers set
`productLive=false`, so the cart service (`live()`: a non-buyable offer is unavailable) shows the line as "Currently unavailable"
with Delete, and carts, search and home never present it as buyable. A line that is still in a cart when checkout is priced is
listed in `blockedLines` of the checkout summary (gateway-bff `priceTax`, `canPlaceOrder=false`) with a Remove action
(`BlockedLines.tsx`), before Place order. The checkout summary needs a delivery address to quote tax; without one the check
happens at Place order and answers with the same plain message (`orders.tax-line-blocked`), never a late saga failure.

**Metrics and alerts** (`infra/observability/rules/catalog-tax-gate.yaml`): `catalog_tax_gate_disagreement_total{reason}`
(catalog said OK, tax-returns did not; alert `CatalogTaxReturnsDisagreement`), `catalog_tax_gate_authority_unreachable_total`
(`CatalogTaxReturnsUnreachable`), `catalog_tax_gate_authority_last_check_timestamp_seconds` (`CatalogTaxReturnsCheckStale`).

## How the rule is enforced (five layers, plus the authority above)

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
| 24 | Staff repair run `POST /v1/tax-gate/sweep` (`make tax-gate-sweep`) | `adapter/in/web/TaxGateAuditController.java` -> `TaxGateSweepServiceImpl.sweepNowAsStaff` | closes (permission `tax:classify-admin`); reopens only through the gate | `TaxGateSweepAndAuditTest.theStaffRepairRunNeedsTheClassifyAdminPermission` |
| 25 | **Catalog READY but tax-returns not RESOLVED** (the owner's bug) | `ProductLiveGateImpl.reconcile`, `TaxGateSweepServiceImpl.againstTaxReturns` | yes: closed, held, announced | `ProductLiveGateImplTest.aLiveListingCatalogCallsReadyIsClosedWhenTaxReturnsIsNotResolved`, `...TaxReturnsHasNoRecordOfIsClosedToo`, `TaxGateSweepAndAuditTest.theSweepClosesALiveListingTaxReturnsDoesNotResolve...`, `...theAuditListsAListingCatalogCallsReadyWhenTaxReturnsIsNotResolved...` |
| 26 | Offers: any seller's offer on a variant (create, resume, FBA copy, relocation after a page merge) (S384) | `offers/.../OfferServiceImpl`, `OfferTaxGateImpl`, `FbaOfferServiceImpl`, `OfferRelocationServiceImpl` | yes (**fixed**, closes the second-seller gap): an offer is ACTIVE only with a `tax_basis` (OWNER_PAGE or a RESOLVED decision of that seller read from tax-returns `GET /v1/tax/listing-decisions`); otherwise it is stored `TAX_HELD`. Offer writes are guarded by `OfferRequiresTax`. A tax-returns outage holds new offers and leaves live ones on sale | `TaxHoldTest`, `MatchedOfferGuardsTest`, offers `TaxGateArchitectureTest`, `SellerListingDecisionServiceImplTest` |
| 27 | `TaxHoldService` release: `tax.classification.changed` listener and a 60s sweep (`TaxHoldSweepJob`) | `offers/.../TaxHoldServiceImpl` | the only component that moves TAX_HELD to ACTIVE; it also verifies legacy active offers without a basis | `TaxHoldTest`, offers `TaxGateArchitectureTest` |
| 28 | Catalog change requests, page merges and no-barcode exemptions (S385-S387) | `ChangeRequestApplierImpl`, `PageMergerImpl`, `GtinExemptionServiceImpl` | never put a page on sale and never change its status; an approved exemption goes to IN_REVIEW only | catalog `TaxGateArchitectureTest.theChangeRequestApplierNeverChangesAPagesStatus`, `...mergesAndExemptionDecisionsNeverPutAPageOnSale` |
| 29 | Bulk offer upload and price/quantity feed (S393, S394): rows become offers only through `OfferBatchServiceImpl` (`POST /v1/offers/batch`); a row without a stored RESOLVED tax decision comes back `TAX_HELD` and is reported HELD, never live. Catalog's importer calls it through the `OfferWriter` port and never sets a status | `offers/.../OfferBatchServiceImpl.java`, `catalog/.../OfferRowProcessorImpl.java` | yes: the batch service asks `OfferTaxGate` per row | `offers` `OfferBatchServiceTest`, `offers` and `catalog` `TaxGateArchitectureTest` |
| 30 | Stock CSV import (S395): writes only inventory ledger entries (`BULK_COUNT`); it cannot change an offer or listing status | `inventory/.../StockImportJobImpl.java` | not needed: no listing state is touched | `StockImportJobTest` |
| 31 | A **photo held for review** (S390/S391): the listing refers to it by media asset id, is saved and submitted, but is not live until media clears it | `LiveRequiresTax.holds` (same invariant as rows 12-13, so search, offers, events and public reads fail closed), `ProductLiveGateImpl.approve/resume` (`requirePhotosCleared`: media is asked again, a cleared photo is published into the listing, a held or refused one throws `ImageHeld`, 422 plain words), `closeIfNotReady` (a LIVE edit with a held photo goes back to review with the note "We are checking your photo.", reason `IMAGE_HELD`), `reconcile` (tax first, then resolves held photos, reopens a held-for-photo listing once cleared; run by the 5 min sweep over open holds; a media outage skips only the photo part and is counted, see "Tax never waits for media") | yes: no new path to LIVE; `Product.holdForImage` takes a listing off sale and is not a live transition | `HeldPhotoTest`, `ProductLiveGateImplTest`, `TaxGateArchitectureTest` (unchanged, green) |

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
- **Notification to the seller** is the status and the note in Seller Center (listing row and product form, in all five
  languages for the product form). No email or push is sent for a tax hold yet.
- Integration tests that need a database (Docker was not available here): `V4` applied to a database with LIVE rows, the audit
  SQL, the persistence guard against a real insert, and `recheckAll` paging. See the verification notes in the change description.

Metric: `offers_active_without_tax_decision` (gauge) counts active offers with no tax basis; it must be 0 after the first sweep. Alert `OffersActiveWithoutTaxDecision` (page, `infra/observability/rules/catalog-tax-gate.yaml`), runbook [CatalogLiveWithoutTaxDecision](../runbooks/CatalogLiveWithoutTaxDecision.md).
