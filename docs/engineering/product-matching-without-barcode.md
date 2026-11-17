# Product matching without a barcode (SPEC, owner direction)

Status: implemented in catalog, gateway-bff and Seller Center. Supersedes sections 3.1 (brand + MPN key), 3.3 (the barcode-first
seller flow) and the `brand` + `mpn` columns of the OFFER template in `bulk-upload-and-images.md`. The rest of that spec
(change requests, image rules, tax and live gate, held rows) stands.

## 1. Why

Most sellers have no barcode and will never know a manufacturer part number (MPN). Asking for either stops them at the first
screen. The seller knows the name of the thing, what kind of thing it is, the brand when it has one, and its size or pack. Matching
is keyed on those, not on a barcode or an MPN.

## 2. The seller's experience

One search box, **What are you selling?**, is the entry of Add a product.

1. The seller types a name ("basmati rice 5kg"). From 3 letters the pages that look like it show while typing: photo, title, brand,
   size or pack and the number of sellers. Name, brand, size and category are all used by the search behind the scenes; the seller is not
   asked for them one by one.
2. The category is a chip ("Suggested: Groceries > Pantry > Rice"). It is suggested from the name and the results, the seller may
   change it (a small category search) or remove their choice, and it is never a required first step. A category the seller chose
   narrows the search (same-category boost); the suggestion itself is not fed back.
3. Each page has **This is my product**. The size or pack is confirmed with one tap on the chosen page, not typed: "Same size, 5 kg?"
   with **Yes, same size** and **No, a different size**. A page with several variants asks which one first. A page that states no size
   goes straight on. Then the seller sets price and quantity (the existing price-and-stock form, shipping and fulfilment behind "more");
   **condition is asked only for the categories whose rules allow used or renewed items** (section 12) and **no SKU is asked**: the
   catalogue code of the page is shown read-only (section 13). The photos, title and details are the page's and are never edited here.
   **No, a different size** is not a dead end: the seller types their own size and asks for it to be added to the page (section 11).
4. **Not here, add it as new** starts the full product form with the name pre-filled and the category chip kept. The answer is
   recorded (section 6).
5. **I have a barcode** is an optional shortcut under the box: an exact match on the barcode, as before (check digit, UPC/EAN meet on
   GTIN-14, reserved prefixes). Nothing requires it. Search by photo is out of scope.
6. Fuzzy matches always need the seller's confirmation, never silent: every candidate is a click, and the server refuses a
   `SELL_EXISTING` answer for a page that was not in the list the seller was shown or whose stated size was not confirmed.

No part number or MPN is asked, shown or explained anywhere a seller reads: not in the form, the templates, the preview messages, the
copy or the tour. An MPN already stored on an existing page stays and is used only as a hidden ranking signal (section 4).

Copy rule: the word "offer" is banned in Seller Center English copy. Use "Sell this product" and "Selling on another seller's page".

## 3. API

Contracts first: `contracts/openapi/catalog.yaml` (`/v1/catalog/matches/candidates`, `/v1/catalog/matches/decisions`) and
`contracts/openapi/gateway-bff-seller.yaml` (`/v1/seller/catalog/candidates`, `/v1/seller/catalog/candidates/decisions`; the embedded
copy `go/services/gateway-bff/api/openapi-seller.yaml` is identical).

| Call | Request | Answer |
|---|---|---|
| `POST /v1/catalog/matches/candidates` | `{ query (max 200), categoryId? }` | up to 6 `MatchCandidate` (parentAtin, atin when one variant, title, brand, image, category, `sizeLabel`, variants, `kind` SAME_NAME or SIMILAR, `likely`, canOffer, blockers), `suggestedCategory`, `category`, `tooShort`, `searchUnavailable` |
| `POST /v1/catalog/matches/decisions` | `{ choice SELL_EXISTING or NONE_OF_THESE, query, categoryId?, atin?, sizeConfirmed? }` | `{ decisionId, choice, atin, canOffer, blockers, possibleDuplicateQueued }` |
| `POST /v1/catalog/matches/size-requests` | `{ query, categoryId?, atin, size ("1 kg"), note? }` | `{ outcome REQUESTED / ALREADY_REQUESTED / ALREADY_ON_PAGE, size, requestId, existingAtin, reviewDueAt, canOffer, blockers }` |
| `POST /v1/catalog/matches` | `{ gtin }` | as before; no brand + MPN, no `matchedOn` |

The gateway adds `sellerCount` per candidate from offers (best effort, null when unreadable), checks the Listings module (EDIT),
validates cheaply (query 1 to 200, UUID category, ATIN shape), never forwards a `sellerOrgId` from the body, and maps the
problems: `catalog.match-rate-limited` to 429 `RATE_LIMITED`, `catalog.match-size-unconfirmed` to 422 `SIZE_UNCONFIRMED`,
`catalog.match-choice-not-shown` to 422 `CHOICE_NOT_SHOWN`, and for size requests `catalog.variant-size-invalid` to 422 `SIZE_INVALID`,
`catalog.variant-page-size-unknown` to 422 `SIZE_PAGE_UNKNOWN`, `catalog.variant-theme-unsupported` to 422 `SIZE_NOT_ADDABLE` and
`catalog.variant-request-blocked` to 422 `SIZE_REQUEST_BLOCKED` (the BFF route is `POST /v1/seller/catalog/candidates/size-requests`).
Every candidate and barcode match also carries `allowedConditions` (section 12).

## 4. Candidate ranking (catalog, `domain/matching`)

Recall comes from the search index (Meilisearch or Elasticsearch, per `SEARCH_PROVIDER`) through search-service
`GET /v1/search` over title, brand, category and attributes: at most 30 ATINs, only the ids are used. The index never decides what is
shown. Ranking is on catalog's own data, in a pure, deterministic `CandidateRanker`:

| Signal | Points |
|---|---|
| words of the name (65% of the seller's words covered, 35% of the page's words covered) | 55 |
| brand named by the seller (as a column or inside the name) | +20; a different brand -25 |
| size or pack (`PackSize`: 5 kg = 5000 g = 5 kg, 1 L = 1000 ml; 6 pack) | +15 when equal; a different size caps the score below the "likely" line |
| category | +10 same category, +5 same branch, -15 another department |
| part number the page holds and the seller happened to type | +15 (hidden signal, never asked for) |

Show threshold 45, likely threshold 75, six pages at most. `SAME_NAME` is the only exact kind: the normalised name, brand and size
are all equal inside the category. Everything else is `SIMILAR`. Both need the click.

Eligibility (before ranking): the page is LIVE, has a real photo (`RealPhoto`: not a placeholder, in the rules of the main image),
and nothing about another seller is returned: no name, price, stock, cost or offer. Sellers may sell in any category: there is no
department gate. A brand needing a selling approval, a page not live yet, or an offer the seller already has is a plain `blocker`,
and the page shows without a sell button.

**Search outage never blocks the seller (fallback).** The recall URL is `SEARCH_URL` (`at.catalog.search.base-url`), like every other
module's: in the monolith that is `<afrotransact-go>/modules/search` (search is mounted `prefixed`), in a cluster `http://search`. When the
index cannot be asked (wrong URL, 5xx, timeout) or answers nothing (empty index, not yet built), catalog matches on its own tables
(`ProductRepository.findLiveByNameWords`): LIVE pages whose title or brand holds every word of the name (all but one when more than two
words; sizes, pack words and filler are not words, `NameTokens`), at most 30, pages of organisations hidden by listing enforcement left
out. The same eligibility (live, real photo) and the same `CandidateRanker` then apply, so the answer has the same shape and the same
rules. `catalog_candidates_fallback_total{reason}` counts it (`search_error`, `search_empty`, `both_failed`) and a WARN names the cause
class (no query text). `searchUnavailable` is true only when both paths fail: no suggestions, never a guess, and the seller can still add
the product as new. A steady `search_empty` or `search_error` rate means the index needs attention: `make reindex-search`
([kafka-replay.md](../runbooks/kafka-replay.md) 5.1); `make seed` waits for the index and says so when it stays empty.

Limits: 600 candidate searches per organisation per hour and 30 "None of these" answers per organisation per 24 hours
(`catalog.match-rate-limited`). Keys are the organisation of the token, never the body.

## 5. Bulk upload without barcodes

OFFER template columns: **Product name**, **Category** (optional), **Brand** (optional), **Size or pack**, then Barcode and ATIN
(optional, exact), Seller SKU, condition, price, quantity and the rest as before. There is no part number column.

Per row: a barcode or ATIN names the page exactly (as before). Otherwise the row is matched by name:

- the exact normalised name + brand + size inside the category links the row automatically (`LINKED`);
- anything else is never linked. When pages look like it, the row is **held** (`AMBIGUOUS`): "We found products that look like this one
  but are not sure which is yours: A (5 kg); B (10 kg). Nothing was added for this row", with 2 or 3 candidates, so the seller chooses
  in Add a product or adds the barcode to the row;
- when nothing is found, the row says so and points to Add a product or the products template.

Never an automatic match on a fuzzy name alone. Matching creates no live page: a linked row follows the same offer path as a barcode
row, and a seller's own tax decision must be RESOLVED before it goes on sale (section 7).

## 6. Recording "None of these"

`NONE_OF_THESE` is stored in `product_match_decision` (migration `V9__match_decisions.sql`): organisation, the typed name, the
category, the pages that were shown. When a page that scored as "likely" was shown next to it, `review_state` is `OPEN` and
`possibleDuplicateQueued` is true, so staff can compare the new page with it. It never blocks the seller.

**Nothing in the flow traps a seller (owner feedback, Nov 2026).** An answer is a record for staff, never a state that disables the
next one. Rules, server and screen:

- The server keeps no per-product "rejected" flag. `SELL_EXISTING` after any earlier `NONE_OF_THESE` (or the other way round) is
  allowed; the only limits are the rates (600 searches per organisation per hour, 30 `NONE_OF_THESE` per 24 hours, 20 waiting size
  requests), which answer 429 `RATE_LIMITED` in plain words and free themselves. The screen records `NONE_OF_THESE` without waiting for
  it, so even a refused record never stops adding the product.
- A waiting wrong-product report stops a *new page with the same barcode* (so a page is not made while staff check it) and nothing
  else. The seller who reported by mistake takes the report back (`withdraw`) from the "can't add this product yet" notice itself, and
  the barcode is free at once.
- Screen: a candidate card has four states (start, size question, own size, answered) and each has a way back: "Not this product"
  returns to the start, "No, a different size" and "It is the same size after all" go to each other, "Change my answer" reopens the
  size form. "Not here, add it as new" and the search box are never disabled. Going back from the new product form, or from a saved
  draft that autosave already gave an address, returns to the search with the typed name and category as they were (the draft stays in
  Drafts). The price-and-stock form has "Search again". The existing wrong-match
report and "this page is a duplicate" paths stay as they are (`change-requests`). A staff queue screen over `review_state = OPEN`
(and the DISTINCT or DUPLICATE decision) is not built yet: the rows are recorded and indexed for it.

## 7. Tax and live gate (unchanged, hard rule)

Matching creates no live page. Selling on an existing page goes through offers, which holds the offer until the seller's own tax
decision for the item is RESOLVED ("We are checking the tax details for this item."). A new page created after "Not here" goes
through the product form and the single tax gate like any new page. `TaxGateArchitectureTest` stays green.

## 8. Privacy and observability

The typed name is stored only in `product_match_decision` (up to 200 characters, an organisation's own words) and is never logged.
Candidate answers carry no data of any other seller. No dedicated match metrics are emitted yet (follow-up: outcome counters
found, none, too short, unavailable, rate limited, and the NONE_OF_THESE rate).

## 9. Tests

| Where | What |
|---|---|
| Java unit (JDK 25) | `CandidateRankerTest` (weights, brand and size agreement, category, hidden part number, SAME_NAME versus SIMILAR, thresholds), `PackSizeTest`, `ProductNamesTest`, `RealPhotoTest`, `CandidateMatchServiceImplTest` (live-and-photo only, six at most, confirmation, not-shown refusal, none-of-these queueing, rate limits, index down: database fallback, index empty, both down, index ok so the database is not asked), `NameTokensTest`, `HttpCandidateRecallTest` (URL with and without the `/modules/search` prefix, no token, 5xx, 404 and a dead port are unavailable), `OfferImportValidatorTest`, `CatalogMatchServiceImplTest`, `SizeVariantRulesTest`, `PageVariantServiceImplTest` (a seller's size), `ChangeRequestServiceImplTest` (`resultAtin`), `ClasspathRulesRegistryTest` (condition and per-category questions), `CatalogContractTest`, `ArchitectureTest`, `TaxGateArchitectureTest` |
| Go | gateway-bff contract conformance for all three routes against `gateway-bff-seller.yaml`, validation, 429 and size problems, size requests (requested, again, already on the page, refused), allowed conditions, no `sellerOrgId` forwarded, Listings module gate |
| vitest | api-client mock (ranking, size, never not-live, none-of-these record, size requests, allowed conditions), Seller Center flow (search, chip, one-tap size, different size request and every way back, price and stock after approval, condition only where allowed, no SKU, back from a draft, take back a report, not here, barcode shortcut, report paths), five-locale key parity, no part number copy, tour keys |
| Not run | Docker Testcontainers ITs (the JDBC decision repository, the migration and `findLiveByNameWords`; its SQL was run by hand on PostgreSQL 16) and a real search engine against `HttpCandidateRecall` |

## 10. Owner decisions

1. Thresholds (45 show, 75 likely) are starting values: tune on real search data before launch.
2. A staff screen for the possible-duplicate queue (section 6).
3. Whether "Not here" should keep sending `noGtin` (the existing exemption review) or whether a name-keyed new page needs no barcode review
   at all in categories that do not need one.

## 11. Selling a different size, weight or pack of a page that exists

The seller's product is on the page but in another size ("the page is 5 kg, I sell 1 kg"). They are not stopped and they do not create
a duplicate page: their size is added to the page as one more variant, with its own ATIN, after catalogue staff check it like any new
variant. Contract first: `POST /v1/catalog/matches/size-requests` (section 3), `ChangeRequest.resultAtin`.

1. **Seller.** "No, a different size" opens "Your size is different": one field, "Your size or weight" ("1 kg", "500 ml", "6 pack"),
   and "Ask to add my size". The page must have been in the list for the same query and category (`catalog.match-choice-not-shown`)
   and the organisation must be allowed to sell on it (`catalog.variant-request-blocked`).
2. **Size rules** (`SizeVariantRules`, pure): the text must be only a size with a unit or a pack word, read like names (5 kg = 5000 g,
   `PackSize`), else `catalog.variant-size-invalid`. A size the page already has (its own, or one of its variants, in the Size
   dimension) answers `ALREADY_ON_PAGE` with that variant: the seller sells it. A page that states no size has none to differ from
   (`catalog.variant-page-size-unknown`). A page that varies by something that is not a size alone (colour, size and colour) answers
   `catalog.variant-theme-unsupported`: the seller adds the product as new (the screen offers it). Nothing here guesses.
3. **Request.** A `NEW_VARIANT` change request, payload `{ source: SELLER_SIZE, options: { Size: "1 kg" }, existingOptions }`, stored under
   the field `MASS:100000:1` (the size key) so one size is one waiting request per page and organisation (`ALREADY_REQUESTED`, no 409).
   It needs no open offer (the generic `NEW_VARIANT` suggestion does), because the seller has none yet; eligibility is the offer
   eligibility of the page (live, brand, approval). At most 20 waiting per organisation (`openSizeRequests`, `at.catalog.matching.open-size-requests`).
4. **Staff.** The request is in the existing change-request queue. Approving goes through `PageVariantService.addVariant` with the same
   tax gate as any new variant (a live page leaves sale until the new variant has its tax decision, `holdPageForTax`). For a seller's
   size it also: gives a page **without variations** the built-in `Size` theme (every product type knows it, `VariationTheme.SIZE`; the
   page's only variant keeps its ATIN and takes its own size as its option), needs no barcode (the variant is found by its ATIN; staff may
   add one later), copies the tax facts of the first variant and generates the page owner's SKU like any variant. A page that already
   varies by Size takes the new value in its own dimension.
5. **Price and stock.** After approval the request in "Your suggestions and reports" reads Applied and carries `resultAtin`: "Add your price
   and stock" opens the same price-and-stock form for that variant. The seller never sets price, stock, shipping or fulfilment on the
   request itself (those belong to the seller's own listing and follow the normal rules once the variant exists).

## 12. Condition and other per-category questions are data

Condition is decided by catalog's listing rules (`tools/listing-rules/rules.json`, rule field `condition`, [listing-rules.md](listing-rules.md)
D9), never by the screen. Shown for 11 categories (electronics, books and magazines, toys, furniture and small appliances, kitchenware,
sports goods, school and office supplies), never required, hidden and stored as New everywhere else (groceries, beverages, beauty, health,
baby, pets, clothing, jewelry, digital, services and the consumable home goods). The rule reaches every place: the product form and bulk
template read the rule; the offer form for a page that exists reads `allowedConditions` of the candidate or barcode match (the same
answer offers uses for `offers.condition-not-allowed`): one entry (New) hides the field and the server uses New, several show it. The
server never requires a condition: an absent one is New. The audit of the other per-category questions is in listing-rules.md (D9, overrides).

## 13. SKU

For a page that exists the SKU input is gone. The catalogue's own code (the variant ATIN) identifies the item, is shown read-only
("Catalogue code") and offers generates the seller's SKU (`<ATIN>-<CONDITION>`) as before; `sellerSku` stays optional in the contract and is
not sent by Seller Center on this path. For a new product the SKU field is unchanged: optional, generated and kept stable when left empty
(`sc.pf.sku.placeholder`). The bulk upload "Seller SKU" column is optional and is not on this path. Nothing asks for an internal reference
on Add a product.
4. Whether the seller may state price and stock together with the size request (today they come after staff approve the size, so a size
   staff reject costs the seller nothing to undo). Staff-console wording for `SELLER_SIZE` requests ("a seller sells this page in 1 kg")
   is the existing generic change-request screen.
5. Fashion: the owner's note listed fashion among the categories that may have a condition; D9 hid it for clothing, bags and jewelry
   (hygiene, brand gating, "pre-owned programme later"). Kept hidden; a pre-owned programme is a rule change (one cell per category).
