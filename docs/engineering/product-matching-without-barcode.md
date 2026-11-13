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
   goes straight on. Then the seller sets price, quantity and condition (the existing price-and-stock form, shipping and fulfilment
   behind "more"). The photos, title and details are the page's and are never edited here.
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
| `POST /v1/catalog/matches` | `{ gtin }` | as before; no brand + MPN, no `matchedOn` |

The gateway adds `sellerCount` per candidate from offers (best effort, null when unreadable), checks the Listings module (EDIT),
validates cheaply (query 1 to 200, UUID category, ATIN shape), never forwards a `sellerOrgId` from the body, and maps the
problems: `catalog.match-rate-limited` to 429 `RATE_LIMITED`, `catalog.match-size-unconfirmed` to 422 `SIZE_UNCONFIRMED`,
`catalog.match-choice-not-shown` to 422 `CHOICE_NOT_SHOWN`.

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
and the page shows without a sell button. An unreachable index answers `searchUnavailable` with no suggestions, never a guess, and
the seller can still add the product as new.

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
`possibleDuplicateQueued` is true, so staff can compare the new page with it. It never blocks the seller. The existing wrong-match
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
| Java unit (JDK 25) | `CandidateRankerTest` (weights, brand and size agreement, category, hidden part number, SAME_NAME versus SIMILAR, thresholds), `PackSizeTest`, `ProductNamesTest`, `RealPhotoTest`, `CandidateMatchServiceImplTest` (live-and-photo only, six at most, confirmation, not-shown refusal, none-of-these queueing, rate limits, index down), `OfferImportValidatorTest`, `CatalogMatchServiceImplTest`, `CatalogContractTest`, `ArchitectureTest`, `TaxGateArchitectureTest` |
| Go | gateway-bff contract conformance for both routes against `gateway-bff-seller.yaml`, validation, 429 and size problems, no `sellerOrgId` forwarded, Listings module gate |
| vitest | api-client mock (ranking, size, never not-live, none-of-these record), Seller Center flow (search, chip, one-tap size, different size, not here, barcode shortcut, report paths), five-locale key parity, no part number copy, tour keys |
| Not run | Docker Testcontainers ITs (the JDBC decision repository and the migration) and a real search engine against `HttpCandidateRecall` |

## 10. Owner decisions

1. Thresholds (45 show, 75 likely) are starting values: tune on real search data before launch.
2. A staff screen for the possible-duplicate queue (section 6).
3. Whether "Not here" should keep sending `noGtin` (the existing exemption review) or whether a name-keyed new page needs no barcode review
   at all in categories that do not need one.
