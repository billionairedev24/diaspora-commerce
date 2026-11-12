# Catalog matching, bulk upload and enforced main-image rules (SPEC, proposed)

**Status:** proposed, waiting for the owner decisions in section 11. Section 6 (main-image rules, S388 to S392) was decided by the owner on 2026-11-12 (longest side >= 1000 px enforced, pure white background, product fill under 75% rejected, secondary images warn, extra photos recommended, WebP and 8 MB kept, on-model apparel off by default, existing listings grandfathered with a task and hidden only after 60 days behind a flag that is off) and built; the rest of this page is not built yet. Section 2 says what existed before.
Reference: Amazon Seller Central practice. Amazon's own help pages could not be reached from this environment (host not allowed), so every
Amazon number below comes from third-party seller guides and is marked **[verify]** until someone checks it on Seller Central.

Related: [listing-rules.md](./listing-rules.md) (approved rules, D14 barcode, D15 image), [tax-gate-paths.md](./tax-gate-paths.md) (nothing live without a tax decision),
[seller-catalog-scope.md](./seller-catalog-scope.md), [taxonomy.md](./taxonomy.md), `go/services/media/README.md`.

## 1. The owner's question, answered

> "The ATIN determines the image. How do we get that, so sellers won't need an image?"

The ATIN names a **product page**, and the page owns the images, title and attributes. A seller does not need to upload an image when the page already exists,
because the seller is not creating a page: the seller is creating an **offer** (price, stock, condition, shipping) on the existing ATIN. How the seller gets the ATIN:

1. The seller types a barcode (GTIN, UPC, EAN, ISBN) or brand plus part number.
2. The catalog normalises it (UPC 12 and EAN 13 become one 14-digit key), checks the check digit, and looks it up in the variant table. A hit returns the ATIN of the child (variant) and its page.
3. The seller sees the existing page (image, title, brand) and is asked "Is this your product?". On yes, the form shows only offer fields. No photo, title or attribute field is shown, and the server refuses them.
4. No hit: the seller creates a new page (new ATIN), must give images that pass the main-image rules, and becomes the first creator whose content the page shows.

So the image requirement attaches to **creating an ATIN**, never to **selling one**. Most catalog growth after launch should be offers.

## 2. What exists today and what is missing

| Area | Exists today (read in code) | Missing |
|---|---|---|
| ATIN | `Atin` `AT` + 8 chars, issued per product **and** per variant (`SequenceIdentifierIssuer`). Variant ATIN names the product too (`getByAtin`). | Nothing to change. |
| GTIN check | `Gtin` validates 8/12/13/14 digits and mod-10, suggests a fix, `toGtin14()` exists. `GET /v1/identifiers/gtin/{code}` returns `existingAtin`. `.../prefill` returns `existingAtin` or GS1 / Open Food Facts data. | Matching is on the **raw digits** (`findByVariantGtin(digits)`), so UPC `012345678905` and EAN `0012345678905` do not match each other. Index `product_variant_gtin` is not unique, so two concurrent creates can both pass. No reserved-prefix check. No brand + MPN lookup. No ISBN-10 handling. |
| Duplicate on create | `ProductWriterImpl.ensureGtinFree` answers 409 `catalog.gtin-exists` with the existing ATIN. | The seller-facing flow never uses it (next row). |
| Seller Center form | `ProductForm.tsx` has a GTIN field, a "no barcode" box (exempt) and a local `gtinOk` check. | The form **never calls** `/v1/identifiers/gtin/*` (no use outside the generated client and mocks). No "this page exists, add your offer" screen. A seller with a barcode is told only after submit, as a 409. |
| Offers | `offers` `OfferServiceImpl.create`: offer on a **live** variant by ATIN or GTIN; terms: condition (6 values), price, list price, shipping, fulfilment, quantity, handling days, ship-from; one open offer per (org, ATIN, condition, channel); plan listing cap. | No selling-approval / brand gate at offer creation (only the product writer has `SellingGatedProductWriter`). No tax decision lookup for the offering seller (section 4.7). |
| Changing a page | Owner edits own product (`Product.edited`); staff moderate. | No suggest-an-edit path, no wrong-match report, no change-request queue. A second seller cannot edit a page (good) and cannot propose an edit (gap). |
| Image rules (media) | `RulesFor(product-image)`: JPEG/PNG/WebP, 8 MB, `MinEdge 500`, flags `background is not white` (border mean luma < 235), `eligible as main image` (both edges >= 1000). Quarantine, EXIF strip, dHash duplicate check, AI hook (`ALLOW`/`REVIEW`/`BLOCK`). SSRF-safe URL fetch (`urlfetch`). | Every quality finding only sets status `FLAGGED`; the seller form ignores it. No fill-ratio, border, blur, CMYK, aspect checks. No per-slot rules (main vs other). Rules are not returned as codes the client can translate. |
| Image rule in catalog | None. | See "the relaxed rule" below. |
| Client-sent image facts | `ProductImage(url, width, height, bytes, contentType)` arrives in the draft from the client. | Catalog trusts those numbers. A client can lie about width. Enforcement must read the media record. |
| Bulk upload | Substantial (S308, S310): per-type and generic xlsx/csv templates built from the rules book (`ImportColumns.forType/forRules`), preview with row status VALID/WARNING/ERROR, downloadable error report, CREATE and UPDATE modes, resumable job (`ImportJobImpl`, key = upload id + SKU, row lock, savepoint), images by URL or image library, department scope re-check, limits (10 MB, 5,000 rows, 200 columns, 10 images, 200 variants), formula-injection guard, `catalog.import.completed` event. | Rows with a GTIN that matches an existing page **fail** (409 at write). No offer-only template. No lightweight price/quantity feed (UPDATE mode needs a full row by SKU). Re-uploading the same file creates a second upload (no file hash). No tax-held row state. No audit row per upload beyond the event. No inventory stock CSV at all (searched inventory, catalog, Seller Center: only the single-offer `QuickStockSheet`). |
| Tax gate | `ProductLiveGate` is the only way to LIVE; `TaxGateArchitectureTest`, `LiveRequiresTax` write guard, fail-closed public reads. | Gap recorded in tax-gate-paths.md: a second seller's offer can show while that seller has **no** decision in tax-returns; checkout blocks the line. Not built. |

### The relaxed rule (where, when, why)

* **Where:** commit `286e37c6e`, 2026-11-11, "images: drop the 1000 x 1000 px minimum for the main image (product form, API publish check, bulk import, image library attach); at least one image is still required".
  Removed: `ProductImage.MIN_MAIN_EDGE` and `largeEnoughForMain()`, the `images[0]` check in `DefaultProductDraftPolicy`, the skip in `ImageLibraryServiceImpl.attach`, `MIN_IMAGE_PX` and the `low` status in `form-model.ts` / `ImagesField.tsx`, the wording in the bulk template and in `en.json` (`sc.pf.images.rules`, `sc.pf.s.imagesHint`, `sc.img.limits`).
* **Why:** the commit message gives no reason. The only written trace is `docs/engineering/listing-rules.md` D15 ("Already relaxed: any size, at least one image") and the note in `tools/listing-rules/rules.json` ("Main image size rule is relaxed"). Backlog story S361 "Verify the image rule" confirms the relaxed behaviour as accepted. I could not find the discussion; the owner should confirm the reason (likely: do not block honest sellers with phone photos at launch).
* **Still in the repo:** the keys `sc.pf.images.tooSmall` and `sc.pf.err.imagesMainSmall` remain in all five locale files (`en fr es ar yo`), so the old copy can be reused.
* **Old rule was stricter than Amazon:** it required **both** edges >= 1000. Amazon's zoom rule is the **longest** side >= 1000 [verify]. The restored rule uses the longest side.
* **This spec supersedes D15 and S361.** On acceptance, edit D15 and the `rules.json` note, and close S361 as superseded (do not mark it Done).

## 3. Matching (part A)

### 3.1 Identifier keys

| Key | Rule |
|---|---|
| GTIN input | Strip spaces and dashes. Accept 8, 12, 13, 14 digits. ISBN-10 is converted to ISBN-13. Check digit by `Gtin.isValid`; on failure return `suggestion` (existing) in plain words: "That barcode does not look right. Did you mean 0123456789012?" |
| Match key | `gtin14` = `Gtin.toGtin14()`. New column `product_variant.gtin14` (generated, indexed). UPC-A and EAN-13 of the same item match. |
| Reserved numbers | Reject GS1 restricted-circulation / in-store prefixes (`02`, `04`, `20` to `29`) and coupon prefixes (`981` to `984`, `99`) as product barcodes: "This number is for store use or coupons, not a product barcode." [verify list against GS1] |
| Uniqueness | Partial **unique** index on `gtin14` over variants that are not exempt and whose product is not REJECTED. Create catches the violation and answers the same 409 `catalog.gtin-exists` (closes the race). |
| Brand + MPN | New normalised pair `(brand_id, mpn_key)`; `mpn_key` = upper case, letters and digits only. Unique index when both present. Used for items with no GTIN (3.5). Needs a manufacturer part number attribute in the taxonomy (confirm it exists; add if not). |
| Variant GTINs in one page | Two variants of one page with the same GTIN: row error "Two variants use the same barcode." |

### 3.2 Lookup API (one call for form and bulk)

`POST /v1/catalog/matches` (catalog, scope `catalog:write`; also read-only service use by offers). Request `{ gtin? , brand? , mpn?, categoryId? }`. Response:

```
{ result: "MATCH" | "NO_MATCH" | "INVALID" | "RESTRICTED",
  atin, parentAtin, productStatus, title, brand, image: {url,width,height}, category, variantOptions,
  offerCount, canOffer: true|false, blockers: [{code, message}], suggestion?, gtin14 }
```

`GET /v1/identifiers/gtin/{code}` and `.../prefill` stay (compatible); `existingAtin` is filled from the same service. `canOffer=false` with `blockers` when the page is not LIVE, the seller's department or brand approval is missing (3.6), the seller already has an open offer, or the plan listing cap is reached. Blockers use the existing codes and plain messages.

### 3.3 Seller flow (Seller Center "Add a product")

1. Step 0 asks "Do you have the barcode (UPC, EAN, ISBN)?" with the number field, "I don't have one" and "Search by brand and part number".
2. The check runs after the 12th to 14th digit (debounced) through the gateway BFF to `/v1/catalog/matches`.
3. **MATCH** shows a card: photo, title, brand, variant options, "N sellers". Buttons: **Yes, sell this** (opens the offer form) and **No, this is not my product** (opens the wrong-match report, 3.7). The seller does not see or edit photo, title, attributes or category.
4. **Offer form** fields: price, list price, quantity (seller-fulfilled), condition (+ condition note if not New), fulfilment (ships from you / Fulfilled by AfroTransact / pickup), handling days, ship-from location, seller SKU (generated when blank, existing behaviour). Variants: if the card is a child of a parent, the seller may pick other children (each is its own ATIN) and set an offer per child.
5. **NO_MATCH** continues into the full product form with the barcode pre-filled and, when a provider answered, the prefill (title, brand, net content, category hint). Images are mandatory (section 6).
6. **INVALID / RESTRICTED** show the message and stay on step 0.

Prefill images (GS1, Open Food Facts) are **suggestions only**: never attached automatically, never count as the main image, because of licensing and because they would not be checked against the rules. See decision D4.

### 3.4 What a matched offer may and may not change

| Allowed on the offer | Not allowed (page content, owned by the page) |
|---|---|
| Price, list price (was price), quantity, shipping charge, fulfilment channel, handling days, ship-from, seller SKU | Title, description, bullets, attributes, brand, category, product type, GTIN, variation theme and options |
| Condition: New, Renewed, Used (grades) where the category allows it (listing-rules D9); non-New needs a short condition note (max 200 characters) | Any image, including a "my own photo" |
| Fulfilment package size and weight **only** for Fulfilled by AfroTransact, stored on the offer, because the physical box depends on the seller | Tax facts of the page (reused, section 4.7) |
| Seller-specific compliance documents the category asks for (food permit, safety certificate) stored against the seller scope | Size chart, fit note, label photo (page content; staff or the page owner changes them) |

Server rule: `POST /v1/offers` with an ATIN never accepts content fields (unknown fields are a 422 "That detail belongs to the product page. Suggest an edit instead."). The product endpoints return 403 `catalog.not-your-page` for a seller that is not the page owner (they already do via `ensureOwnerOrStaff`; keep and test it).

### 3.5 No GTIN: ATIN assignment

| Case | Rule |
|---|---|
| Category and product type allow "no barcode" (handmade, unbranded, private label; D14 / `identifierRule`) | Seller ticks "No barcode" and gives a reason (existing `gtinExempt`). New ATIN issued at once. First try a brand + MPN match; on a hit it is a MATCH (offer only). |
| Brand-gated category, or the brand is a **known GS1 brand** (it appears with GTINs on other pages) and the product type requires a GTIN | Exemption needs **staff approval** (`GtinExemptionRequest`, 2 business days, same queue as change requests). Until approved the page is `PENDING_EXEMPTION` and not live. Seller reads "We are checking that this item has no barcode." |
| ISBN product types | No exemption (existing rule). |
| Exempt page, same seller adds more variants | Variants each get an ATIN; none has a GTIN; match key is `(brand, mpn)` or none. |

Because exempt pages have no barcode, duplicates are possible. Mitigation: title + brand + MPN similarity hint to staff in review (not a block), and the report-duplicate path (3.7).

### 3.6 Variants, brand gating and restricted brands

* **Parent/child.** A page has a parent ATIN (`product.atin`) and one child ATIN per variant (`product_variant.atin`); GTINs, offers and per-variant images attach to the child. Matching is by child. A seller cannot add a new child to someone else's parent directly: "Add a size or colour" creates a `NEW_VARIANT` change request (3.7). In bulk, a parent group where some children match and some do not: matched children become offers; unmatched children become `NEW_VARIANT` requests with a row **warning** "This size or colour is not on the page yet. We sent it for review."
* **Per-variant images.** Stay with the child (`Variant.images`) and are owned by the creator of that child. A seller who adds a new child through an approved request supplies that child's images under the same rules.
* **Brand-gated and restricted brands.** Gated categories are clothing, bags, electronics, perfume (D2). A match on a page whose brand or category is gated needs the seller's **selling approval for that scope**; offers must call the same `SellingPublishGate.evaluate(owner, facts)` the product writer uses, with the page's facts. Today `OfferServiceImpl.create` does not, so a seller with no approval could attach an offer to a gated page. Result codes: `offers.selling-approval-required` (422) "You need approval to sell this brand. Apply under Listings." Brands marked RETIRED/REJECTED never match for a new offer.
* **Counterfeit signal.** An offer by a seller with no approval for a registered brand is refused (above); reports of "fake" go to trust-ops through the existing product flag (`product_flag`).

### 3.7 Suggest an edit, wrong match and duplicates (change requests)

New aggregate `ProductChangeRequest` (catalog), moderated by staff (permission `catalog:moderate`):

| Type | Who may file | Staff outcome |
|---|---|---|
| `IMAGE_ADD`, `IMAGE_REPLACE` | any seller with an offer on the page | image goes through the same checks (section 6); approve adds or replaces |
| `TITLE`, `ATTRIBUTE`, `DESCRIPTION`, `CATEGORY`, `BRAND` | same | approve applies to the page; the page `version` increases; the owner is notified |
| `NEW_VARIANT` | same | approve creates a child ATIN owned by the requester |
| `WRONG_MATCH` | seller who was shown the card and answered "not my product" | staff either corrects the GTIN on the page, moves a variant, splits the variant to a new page, or rejects with a reason |
| `DUPLICATE_PAGE` | any seller | staff merge the pages (ATIN of the loser redirects, offers move) or reject |
| `REPORT` (fake, wrong image) | shoppers and sellers | existing `product_flag` flow |

Rules: one open request per (page, type, field, requester); each decision stores reason and reviewer (audit event `catalog.change_request.decided`); the seller sees "Waiting", "Applied", "Not applied, because ...". The first creator's content stays until staff apply a change; the page owner is **not** the approver (avoids owner veto of correct fixes) but is shown the request. While a `WRONG_MATCH` is open the seller may not create a duplicate page with the same GTIN; the answer is "We are checking this barcode. We will tell you within 2 business days."

### 3.8 When the first creator leaves

If the page owner's organisation is closed, the page stays (it is catalog content); `owner` is kept for audit. Staff can transfer ownership to AfroTransact Retail.

## 4. Tax and live gate for matched offers (hard rule)

1. A **page** never goes live without a stored RESOLVED tax decision (`ProductLiveGate`, `LiveRequiresTax`, `TaxGateArchitectureTest`). The new match / offer paths create **no** page and therefore never touch `Product.approve`. New code that creates pages (bulk, exemption approval, `NEW_VARIANT`) must go through `ProductWriter` and `ProductLiveGate` as today; the architecture test stays and gets a new case: nothing outside the gate may call the new `ProductChangeRequest` apply path to change `status`.
2. A matched offer **reuses** the page's variant tax facts and category tax status (same ATIN, same tax class).
3. **The second-seller gap must be closed in this work.** The decision is stored per seller in tax-returns (`listing_tax_decision`); catalog holds only the owner's. Offer creation therefore does:
   * call tax-returns `GET /v1/listing-tax-decisions?atin=&sellerOrg=` (service token);
   * RESOLVED: offer `ACTIVE` as today;
   * missing: ask tax-returns to create the seller's decision from the page's facts (it can copy the page decision's inputs, never default a rate); if it resolves at once, `ACTIVE`; otherwise the offer is saved with new status `TAX_HELD` (not buyable, not in search, not in the buy box), reason code `TAX_DECISION_MISSING`;
   * a `tax.decision.resolved` event (exists for the product side; add the seller key) moves `TAX_HELD` to `ACTIVE` through one offers component, mirroring `reconcile`;
   * metric `offers_active_without_tax_decision` (must be 0) and an alert next to the catalog one.
4. Checkout keeps its own block (defence in depth) and must not be loosened.
5. Bulk rows that cannot resolve a tax decision end as `HELD` (section 5.6), never live.

## 5. Bulk upload (part B)

Reuse the catalog import (`ProductImportService`, `ImportJobImpl`, `product_import`, `product_import_row`). It already does most of this; the changes are the match step, new templates, hold state and hardening.

### 5.1 Modes and templates

| Mode | Purpose | Template columns |
|---|---|---|
| `CREATE` (exists) | new pages | generated from the category rules book (`ImportColumns.forType` / `forRules`): required fields come from each category's own rules, nothing per category in code. Add `gtin` match behaviour (5.2). |
| `UPDATE` (exists) | change a full listing by SKU | as today |
| `OFFER` (new) | sell existing pages: one row per offer | `gtin` or `atin` or `brand`+`mpn` (one required), `seller_sku`, `condition`, `condition_note`, `price`, `list_price`, `quantity`, `fulfilment`, `handling_days`, `ship_from`, (FBA only) package size and weight. **No image, title or attribute columns.** A mixed file is allowed through `CREATE` with a GTIN column: matched rows become offers (5.2). |
| `PRICE_QTY` (new, lightweight) | change price and stock only, up to 50,000 rows | `seller_sku` (or `atin`), `price`, `list_price`, `quantity`. Blank means unchanged. Calls offers in batch (`PUT /v1/offers/stock/{sellerSku}` is "planned" in the BFF contract) instead of re-writing products. |
| `STOCK` (new, inventory) | stock counts per location | `seller_sku`, `location`, `on_hand`, optional `lot`, `expiry` (perishables). Goes to inventory-service, writes ledger entries with reason `BULK_COUNT` and the import id; no negative or above `MAX_STOCK`. |

Every template has an Instructions sheet (exists) and dropdowns (exists). Template download is scoped to the seller's departments (exists).

### 5.2 Match step inside CREATE and OFFER

Per row, before validation of content columns:

* GTIN present and valid, match found: row becomes an **offer row**. Content columns on that row (title, images, attributes) are ignored with a WARNING: "This product is already in the catalog, so we used the existing photos and details." The row result shows the ATIN.
* GTIN present and valid, no match: normal create; main image rules apply (section 6).
* GTIN invalid: ERROR "That barcode does not look right" (+ suggestion).
* Several rows with the same GTIN in one file: first wins; later rows ERROR "This barcode appears twice in your file."
* Any row of a parent group is a match: see 3.6 (matched children offers, unmatched children `NEW_VARIANT` requests).

### 5.3 Images in bulk

| Source | Rule |
|---|---|
| Public https URL (`main_image_url`, `other_image_url_1..8`, `swatch_image_url`) | Fetched **server-side by media** (`POST /v1/assets/import`) into private quarantine, then judged like an upload. Never fetched by catalog. |
| Image library file name (`SKU.MAIN.jpg` ...) | Already uploaded and judged; reused. |
| Both empty | Library images named after the SKU (exists). For a CREATE row with no match and no image: ERROR "Add a main photo (link or library file)". For an offer row: nothing needed. |

SSRF and fetch safety (`urlfetch` already: https only, no user info, every dialled IP must be public unicast, 3 redirects re-checked at dial, 20 s, 8 MB, `Accept` image types). Add: **port 443 only**; reject IP-literal hosts; a per-upload budget (max 200 MB total, max 4 parallel fetches, max 2 per host); a daily per-organisation fetch cap; cache by URL hash within an upload; record `fetch_failed` reasons in plain words ("The link did not answer", "The link is not a photo", "The link points to a private address"). Row result says which image column failed.

### 5.4 Job, report and partial success

* Upload -> parse (zip-bomb guards, exist) -> validate -> `VALIDATED` preview -> seller starts -> async job (`QUEUED/RUNNING/COMPLETED/FAILED`, resumable, exists).
* **Partial success** (exists): good rows proceed; ERROR rows are listed and skipped; a failed product marks only its rows.
* **Report** (`GET /v1/imports/{id}/report`, exists): add columns `Result` (CREATED, OFFER_CREATED, UPDATED, HELD, SKIPPED, FAILED), `ATIN`, `Severity` (Error or Warning), `Column`, `Message`, and keep the seller's original columns first so the file can be fixed and re-uploaded. Errors and warnings are separate columns and filters in the preview. CSV and XLSX.
* New row outcomes: `OFFER_CREATED`, `HELD`.

### 5.5 Idempotent re-upload

* Per upload: id + SKU (exists).
* New across uploads: store `file_sha256` and a normalised row hash. Uploading the same file again shows "You uploaded this file on {date}. Show that result or run it again?". Re-running creates no duplicates because: CREATE rows whose SKU already exists become `SKIPPED` (not errors) when the content hash matches; OFFER rows with an existing open offer become an update only if terms differ, else `SKIPPED` "No change".
* Resume after crash and second pod: exists.

### 5.6 Held rows (tax and approvals)

A row ends `HELD` (not live, not failed) when: the page or variant has no resolved tax decision (4.1), the offer is `TAX_HELD` (4.3), the seller needs a selling approval, or the image is held by moderation. The report says why in plain words ("We are checking the tax details for this item"). Rows leave `HELD` through the existing events (tax resolved, moderation released); the import row keeps a link to the page or offer. No path in the importer calls `Product.approve` or sets LIVE.

### 5.7 Limits, scope and audit

| Item | Rule |
|---|---|
| File | 10 MB (exists); `PRICE_QTY` and `STOCK` files up to 25 MB and 50,000 rows; CREATE/OFFER 5,000 rows (exists) |
| Per organisation | 1 active upload at a time per mode; 20 uploads per day (configurable via `ImportSettings`) |
| Scope | Seller org from the token; department scope re-checked at validate and at run (exists); a seller can only touch its own SKUs and offers; staff may upload on behalf with reason (audited) |
| Audit | Event `catalog.import.started` and `.completed` with who, file hash, mode, counts; one audit row per product or offer created, updated or held (`import_id`, `row`, `actor`) |
| Formula injection | Cells starting `= + - @` are neutralised on read and on report write (exists, keep) |

## 6. Main-image rules, enforced (part C)

### 6.1 Amazon numbers and ours

| Rule | Amazon (third-party sources, **[verify]**) | AfroTransact, main image | Other images |
|---|---|---|---|
| Longest side | at least 1000 px (zoom); 2000+ recommended; max 10,000 | **reject under 1000**; recommend 2000 | warn under 1000 (no zoom); reject under 500 |
| Shortest side | not stated | reject under 500 | reject under 500 |
| Background | pure white RGB 255,255,255 | **reject** when not white (6.3) | none |
| Product fills frame | about 85% of the frame [verify] | reject under 75%, warn 75 to 85% | none |
| Formats | JPEG, PNG, GIF, TIFF [verify] | JPEG, PNG, WebP (WebP kept: already accepted). TIFF and GIF not accepted. | same |
| Colour | sRGB (CMYK accepted) [verify] | convert to sRGB on processing (CMYK too) | same |
| File size | 10 MB [verify] | 8 MB (keep today's cap; owner may raise to 10) | 8 MB |
| Content | product only; no text, logos, watermarks, borders, placeholders; no props, mannequins (except apparel on-model) [verify] | automatic and moderation checks (6.2) | text and watermarks held; props allowed |
| Count | main + up to 8 more [verify] | main + 8 more (slots `MAIN`, `PT01` to `PT08`, as the image library) = 9, plus the swatch and label photo (today `MAX_IMAGES` is 10 in total; align it). | encourage 7 more (quality meter, never required) |

The "min 7 additional images" in the request is a **recommendation** shown as a listing-quality meter ("3 of 7 extra photos"). Enforcing it would stop honest sellers; see decision D6.

### 6.2 Which checks are automatic and which need a person

| Check | Kind | Where | Outcome for main |
|---|---|---|---|
| Real file type from bytes, size, pixel cap (40 MP) | automatic (exists) | media `Complete` / `ImportFromURL` | REJECT |
| Longest and shortest side | automatic (data exists in `Inspection`) | media | REJECT |
| Background: pure white | automatic, new | media `imageproc` | REJECT |
| Border / frame: a uniform non-white band at the edge | automatic, new | media | REJECT |
| Fill ratio: bounding box of non-white pixels | automatic, new | media | REJECT / WARN |
| Transparent PNG | automatic | flatten onto 255 white, keep as pass | PASS |
| CMYK / colour profile | automatic | convert to sRGB | PASS |
| Blur / low detail (variance of Laplacian, only when the product fills the frame) | automatic heuristic, may be wrong | media | WARN only |
| Aspect ratio beyond 2:1 | automatic | media | WARN |
| Exact and near duplicate (dHash, exists) | automatic | media | other seller's: HOLD (exists); own: note |
| Known placeholder ("no image available", blank) | automatic hash list + AI | media + ai-gateway | REJECT |
| Text overlay, watermark, logo, contact details | **moderation** (AI hook `REVIEW`, exists) | ai-gateway `POST /v1/ai/moderate-image`, new labels `TEXT_OVERLAY`, `WATERMARK`, `PLACEHOLDER`, `BORDER`, `PROPS`, `MANNEQUIN`, `NOT_PRODUCT` | HOLD, staff decide |
| Picture does not match title and brand | **moderation** (AI compares with title/brand; staff confirm) | same | HOLD |
| Prohibited content | AI `BLOCK` (exists) | same | REJECT, deleted |

### 6.3 Background and fill (so a normal JPEG passes)

* **Border ring:** sample the outer 2% of pixels (all four edges, every 2nd pixel) after compositing transparency on white. PASS when at least 95% of ring pixels have all channels >= 250 and the four corners are >= 250. JPEG noise makes 254 common, so exact 255 is not required for the test.
* **Snap to white:** on PASS, flood-fill from the border and set connected pixels with all channels >= 250 to exactly 255, so the stored file is pure white.
* **Fill ratio:** find the bounding box of pixels farther than 12 (per channel) from white after a 3 px blur; `fill = max(box width / width, box height / height)`. Pass at 0.85 or more, warn from 0.75.
* **Border/frame:** reject when a constant non-white band wider than 1% runs along three or more edges.
* Thresholds live in media config (`images.main.*`), with a unit test table of sample images (white packshot, off-white 245, cream, phone photo on a table, 800 px, with border, with watermark text, transparent PNG, CMYK JPEG).

### 6.4 Where it runs and outcomes

* **Pipeline:** upload or URL import -> private quarantine -> inspect (type, size, dimensions) -> **image checks** (6.2/6.3) -> metadata strip -> duplicate check -> AI moderation -> publish by copying to the public store (prod: private quarantine bucket to public bucket, exists). Nothing public until it passes. A REJECT is deleted from quarantine at once.
* **Slot-aware:** `POST /v1/uploads` and `POST /v1/assets/import` gain `slot` (`MAIN`, `OTHER`, `SWATCH`, `LABEL`) and optional `productTypeCode` (for on-model apparel). The form, bulk job and library send the slot; the library infers it from the file name (`SKU.MAIN`, `SKU.PT01`).
* **Verdict model** replaces free-text flags: asset gets `verdict` (`PASS`, `WARN`, `HOLD`, `REJECT`) and `checks: [{code, level, params}]`. Status mapping stays: REJECT -> `REJECTED`, HOLD -> `FLAGGED` (private), PASS/WARN -> `READY`. Responses carry codes and params so Seller Center translates; an English `message` is included for the API.
* **Catalog enforcement (defence in depth):** the product writer, `ProductDraftPolicy`, bulk job and library attach call a new port `ImageVerifier` (adapter to media `POST /v1/assets/verify` with asset URLs) and refuse a main image whose verdict is not PASS or WARN-on-fill. Client-sent width and height are no longer trusted. A listing cannot be submitted or made LIVE with a REJECT or HOLD main image.
* **Restore:** re-add the `images[0]` rule to `DefaultProductDraftPolicy` (now: verified main image), re-add the library-attach skip with the new message, re-add `MIN_IMAGE_PX` (1000, longest side) and the `low` status in the form, restore the bulk template wording, revert the `en.json` strings and add the new ones (6.6).

### 6.5 Seller-facing messages (plain words, no ids)

| Code | Level | Message (en) |
|---|---|---|
| `MAIN_TOO_SMALL` | reject | "This photo is too small. The main photo must be at least 1000 pixels on its longest side so shoppers can zoom. Yours is {w} by {h}." |
| `TOO_SMALL` | reject | "This photo is too small. Use a photo that is at least 500 pixels wide and tall." |
| `LOW_RES` | warn | "This photo is under 1000 pixels, so shoppers cannot zoom in. A larger photo sells better." |
| `NOT_WHITE` | reject | "The main photo needs a plain white background (pure white, RGB 255, 255, 255). Retake it or edit the background and upload it again." |
| `FILL_LOW` | reject | "The product is too small in the photo. It should fill at least 85% of the height or width. Crop closer and upload again." |
| `FILL_OK_NEAR` | warn | "The product could fill more of the photo. 85% or more looks best." |
| `BORDER` | reject | "Remove the border or frame around the photo." |
| `PLACEHOLDER` | reject | "This looks like a placeholder, not a photo of your product." |
| `FORMAT` | reject | "Use a JPG, PNG or WebP file." |
| `FILE_TOO_BIG` | reject | "This file is larger than 8 MB. Save it at a smaller size." |
| `NOT_AN_IMAGE` | reject | "This file is not a photo we can read." |
| `BLUR` | warn | "This photo looks blurry. A sharper photo sells better." |
| `ASPECT` | warn | "This photo is very wide or very tall. Square or close to square works best." |
| `HELD_TEXT` | hold | "We are checking this photo for text, logos or watermarks. Main photos show the product only. We usually answer within 2 business days." |
| `HELD_MISMATCH` | hold | "We are checking that this photo matches your product. We usually answer within 2 business days." |
| `HELD_DUPLICATE` | hold | "We are checking this photo because it looks like one already used on AfroTransact." |
| `FETCH_FAILED` (bulk) | reject | "We could not get this photo from the link. Check that the link is public and starts with https." |
| `FETCH_PRIVATE` (bulk) | reject | "This link points to a private address, so we cannot use it." |

Shown on the image card in the form (inline, next to the thumbnail), in the library (status column), and in the bulk preview and report (column "Message"). The form blocks "Submit" while a main image is rejected or held; held images are submitted but the listing waits (`IN_REVIEW`).

### 6.6 i18n keys (Seller Center, five locales `en fr es ar yo`)

All non-English strings are machine drafts and need native review before launch (flag them in each file's review list as for other drafts).

* Reuse and restore: `sc.pf.images.tooSmall`, `sc.pf.err.imagesMainSmall`, `sc.pf.images.rules` (add `{px}`), `sc.pf.s.imagesHint` (white background, 1000 px), `sc.img.limits` (restore main image note), `sc.bulk` template wording is server-side English plus the existing template translation path (confirm).
* New: `sc.img.check.<CODE>` for each code in 6.5 (params `w`, `h`, `px`), `sc.img.verdict.PASS|WARN|HOLD|REJECT`, `sc.pf.images.meter` ("{n} of {total} extra photos"), `sc.pf.images.improve`, `sc.pf.images.heldBlocks`.
* Matching: `sc.match.title`, `sc.match.haveBarcode`, `sc.match.noBarcode`, `sc.match.byBrandMpn`, `sc.match.found`, `sc.match.sellers`, `sc.match.yes`, `sc.match.no`, `sc.match.invalid`, `sc.match.restricted`, `sc.match.offerOnly`, `sc.match.contentLocked`, `sc.match.suggestEdit`, `sc.match.wrongTitle`, `sc.match.wrongSent`, `sc.match.exemptWait`, `sc.change.status.*`, `sc.change.type.*`.
* Bulk: `sc.bulk.mode.OFFER|PRICE_QTY|STOCK`, `sc.bulk.result.*` (including `HELD`, `OFFER_CREATED`), `sc.bulk.sameFile`, `sc.bulk.matched`, `sc.bulk.heldWhy.*`.
* Staff console (English only today): `staff.change.*`, `staff.image.review.*`.

### 6.7 Existing listings and migrated v1 products

| Option | Effect |
|---|---|
| **Recommended: grandfather with a task, then limit** | Live listings stay live. A back-fill job rescoring every live main image (media `POST /v1/assets/rescore`, batch, rate limited) stores `image_quality` (`OK`, `IMPROVE`, `FAIL`). Seller Center shows an "Improve your image" task on the listing row and on the Home to-do list with the reason in plain words. Any edit that touches images must pass the rules. After a grace period (60 days, owner decision D7) listings still `FAIL` are hidden from search and the buy box (not deleted; the seller can still edit; shoppers with a direct link see the page, not the buy button). |
| Grandfather forever | No suppression. Simple, but the catalog never converges. |
| Suppress at once | Matches Amazon strictly, but would empty the launch catalog (seeded and migrated products). Not recommended before launch. |

Seeded demo data and AfroTransact Retail products follow the same rules (no exemption for the platform owner); the seeder must source images that pass, or the seeded items show the task.

## 7. Contracts and events

| File | Change |
|---|---|
| `contracts/openapi/catalog.yaml` | `POST /v1/catalog/matches`; `GET /v1/identifiers/gtin/{code}` adds `gtin14`, `result`; `ProductChangeRequest` (`POST/GET /v1/products/{atin}/change-requests`, staff `GET /v1/change-requests`, `POST /v1/change-requests/{id}/decision`); `GtinExemptionRequest`; imports: mode `OFFER`, `PRICE_QTY`; row outcome `OFFER_CREATED`, `HELD`; report columns; `ImageVerifier` is internal (media contract) |
| `contracts/openapi/media.yaml` | `slot` on `/v1/uploads` and `/v1/assets/import`; `Asset.verdict`, `Asset.checks[]` (`code`, `level`, `params`); `POST /v1/assets/verify`; `POST /v1/assets/rescore` |
| `contracts/openapi/offers.yaml` | offer status `TAX_HELD`; 422 on content fields; `offers.selling-approval-required`; batch `PUT /v1/offers/prices` and `/stock` |
| `contracts/openapi/inventory.yaml` | `POST /v1/stock/imports` (bulk count) |
| `contracts/openapi/gateway-bff-seller.yaml` | `POST /v1/seller/catalog/match`, `/v1/seller/offers` (create), change requests, `GET /v1/seller/image-tasks`, import modes |
| `contracts/openapi/tax-returns.yaml` | `GET /v1/listing-tax-decisions` by ATIN and seller; request decision for a seller on an existing ATIN |
| `contracts/asyncapi/events.yaml` | `catalog.match.requested` (no PII, metrics only), `catalog.change_request.created` / `.decided`, `catalog.import.started`, `catalog.import.row_held`, `media.asset.verdict` (asset, verdict, check codes), `offers.offer.tax_held` / `.tax_released`, `catalog.listing.image_task` |

Contract first: each story below lists its contract change as an acceptance criterion with a conformance test (existing pattern).

## 8. Data model sketch (migrations)

| Service | Migration | Change |
|---|---|---|
| catalog | `V5__gtin14_and_mpn.sql` | `product_variant.gtin14` generated, backfilled; partial unique index (not exempt, product not REJECTED); `mpn_key`, `(brand_id, mpn_key)` unique; pre-check query lists current duplicates for staff before the index is created |
| catalog | `V6__change_requests.sql` | `product_change_request(id, atin, type, field, payload jsonb, status, requested_by_org, requested_by, decided_by, decided_at, reason, version)`; `gtin_exemption_request`; audit rows |
| catalog | `V7__image_quality.sql` | `product.image_quality`, `image_checked_at`; `product_import.file_sha256`; `product_import_row.outcome` adds `OFFER_CREATED`, `HELD`; `held_reason` |
| media (Go) | `00N_asset_verdict.sql` | `asset.slot`, `verdict`, `checks jsonb`, `fill_ratio`, `border_ratio`; backfill existing assets with `verdict=PASS` and `checks=[]` until rescored |
| offers | `V_n__tax_held.sql` | status `TAX_HELD`, `held_reason`; index for the sweep |
| inventory | `V_n__bulk_count.sql` | `stock_import(id, org, file_sha256, status, counts)` and ledger reason `BULK_COUNT` |

Flyway scripts are forward-only; the unique index migration must fail loudly if duplicates exist (and list them) instead of dropping data.

## 9. Observability

| Metric / log | Use |
|---|---|
| `catalog_match_total{result}` (MATCH, NO_MATCH, INVALID, RESTRICTED) and `catalog_match_seller_accept_ratio` | how often sellers use existing pages; wrong-match rate = `WRONG_MATCH` / MATCH |
| `catalog_new_page_total{has_gtin}` | duplicate-risk trend |
| `catalog_change_request_open` (gauge), `..._age_seconds` histogram | staff queue; alert if oldest > 3 business days |
| `media_image_verdict_total{slot,verdict,code}` | which rule rejects most (guides copy changes); alert if reject rate for MAIN > 60% for 1 h (a bad threshold or outage) |
| `media_check_duration_seconds` | pipeline cost; budget 1.5 s per 2000 px image |
| `catalog_import_rows_total{mode,outcome}`, `catalog_import_duration_seconds`, `catalog_import_fetch_failures_total{reason}` | bulk health; SSRF refusals counted (`reason=private_address`) |
| `offers_active_without_tax_decision` | must be 0 (page); alert like `catalog_live_without_tax_decision` |
| `catalog_image_quality{state}` gauge | back-fill progress and task backlog |

Logs carry upload id, row number and asset id, never the seller's file content or URLs with query strings.

## 10. Test plan

| Layer | Tests |
|---|---|
| `Gtin` unit | UPC/EAN/GTIN-14 normalisation equality, ISBN-10 to 13, reserved prefixes, bad check digit with suggestion |
| Catalog IT (Testcontainers) | unique index race (two threads create the same GTIN: one wins, one 409 with ATIN); match by gtin14 across UPC/EAN; brand+MPN match; exemption flow; change request lifecycle; content lock on offer create |
| Offers IT | offer on matched variant; 422 on content fields; selling approval refused; `TAX_HELD` then released by event; never ACTIVE without decision; checkout still blocks |
| Architecture | `TaxGateArchitectureTest` extended (change-request apply and import code cannot set LIVE); `ArchitectureTest` layering for new ports |
| Media Go unit | table tests for each check with the sample image set (6.3); verdict mapping; slot-aware rules; thresholds from config; CMYK and transparent PNG; fuzz for zero-size and huge images |
| Media integration | quarantine -> verdict -> publish copy; REJECT deleted from quarantine; HOLD not public; AI hook fail-closed |
| SSRF | `urlfetch` table: private IPv4/IPv6, loopback, link-local, metadata IP `169.254.169.254`, DNS rebinding (resolver returns private on 2nd lookup), redirect to private, redirect to http, port 8080, user-info, oversized body, slow body, per-host cap |
| Bulk IT | partial success, idempotent re-upload (same file), matched rows become offers, held rows on missing tax, crash and resume, report contents, row limits, formula injection |
| Web unit / Playwright (mock) | match card flow, locked content, wrong-match report, image card messages per code, form blocks submit on rejected main image, bulk wizard for OFFER and PRICE_QTY, five locales render (no missing keys; existing i18n parity test) |
| E2E (staging) | seller A creates page, seller B matches by UPC and sells without uploading an image, buy box shows both |
| Back-fill | dry run report on the seeded catalog before enabling suppression |

## 11. Rollout

1. **Shadow mode** (1 release): media computes verdict and checks but only records them (`media.image.enforce=false`); dashboard shows what would be rejected on real uploads; tune thresholds.
2. **Matching** ships first (stories 1 to 4) because it removes most image work and is low risk.
3. **Enforce** per environment with flags: dev, then uat for 1 week, then prod for new uploads. Restore the catalog checks at the same time.
4. **Back-fill and task** (story 9), then the grace period starts; suppression is a separate flag.
5. Bulk modes behind `import.modes` flag per organisation; start with staff-assisted sellers.
6. Rollback: every flag returns to today's behaviour; the unique index and new columns stay.

## 12. Owner decisions needed

| # | Question | Recommendation |
|---|---|---|
| D1 | Confirm why the 1000 px rule was relaxed on 2026-11-11 (not recorded) and that this spec **supersedes D15**. | Yes, restore and enforce. |
| D2 | Longest side >= 1000 (Amazon) instead of both edges (old rule). | Longest side. |
| D3 | Strictness: reject main image on non-white background and fill under 75%? Or warn only at first. | Reject; shadow mode first. |
| D4 | May we pre-fill images from GS1 / Open Food Facts as suggestions (licences)? | Suggestions only, never the main image, until licences are checked. |
| D5 | Who approves page edits: staff only, or also the first creator / verified brand owner? | Staff only at launch. |
| D6 | "7 extra photos": recommendation with a quality meter, or required? | Recommendation. |
| D7 | Existing listings: grandfather with task, hide failing ones after 60 days, or never hide? | Task now, hide after 60 days. |
| D8 | Apparel main image on a model allowed? WebP and the 8 MB cap vs Amazon's TIFF/10 MB. | Allow on-model for apparel; keep WebP and 8 MB. |
| D9 | Exemption: staff approval for brand-gated categories and known GS1 brands. | Yes. |
| D10 | Bulk caps: 5,000 rows (offers, creates), 50,000 rows (price/stock). | As written. |
| D11 | Offer-level package size for Fulfilled by AfroTransact. | Yes, FBA only. |
| D12 | Amazon numbers are from third-party guides; someone with Seller Central access should confirm the **[verify]** items (or allow `sellercentral.amazon.com` in the session network and re-run). | Confirm before launch. |

## 13. Backlog stories

| Story | Topic |
|---|---|
| S381 | Match service, GTIN-14 key, unique index, brand + MPN |
| S382 | Seller Center barcode step and offer-only flow |
| S383 | Matched offer guards (content lock, selling approval) |
| S384 | Second-seller tax decision, `TAX_HELD` offers |
| S385 | Change requests, wrong match, duplicates |
| S386 | Staff review queues |
| S387 | GTIN exemption and brand + MPN ATIN assignment |
| S388 | Media image checks and verdict model |
| S389 | AI moderation labels |
| S390 | Enforce main image in catalog, bulk, library |
| S391 | Seller Center image cards and i18n |
| S392 | Existing listings rescore and task |
| S393 | Bulk: match-aware rows, OFFER template, held rows, idempotency |
| S394 | Bulk: price and quantity feed |
| S395 | Stock CSV import |
| S396 | Bulk hardening, report, audit |
| S397 | Test, observability and rollout |
