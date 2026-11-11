# Home page content and placements

The storefront home page is assembled by the gateway-bff (`GET /v1/home`, `SiteService.Home`). Staff set the content in
the staff console (Marketing > Placements & ads); the gateway turns it into products. Clients hold no home content.

| Section | Source | Product rules |
|---|---|---|
| Announcement bar | content placements (`announcement_bar`) | none (fixed copy) |
| Hero carousel | `hero_carousel` placements | optional per-slide schedule |
| Tiles | `home_tiles` placements | each cell is a fixed image or a **rule** |
| **Body** (everything under the tiles) | the **home composition**, see below: rails, banners, sponsored modules and rule-based modules, in priority order | per module |

The body is the part of the page staff arrange. `GET /v1/home` returns it as `blocks`, a typed list (see
[Home composition](#home-composition)); the older `banners` and `rails` lists are gone from the response.

## Eligibility: in stock, real photo

Everything the gateway puts in front of a shopper by a rule is eligible only when the product is **in stock** and has
a **real photograph**: an `http(s)` or site-relative image address that is not inline (`data:`), not a vector or icon
(`.svg`, `.ico`, `/icons/`) and not a known placeholder (`placeholder`, `no-image`, ...). The check is
`eligibleCard` / `realPhoto` in `go/services/gateway-bff/internal/app/site_home.go` and applies to tile rules, product
rails, "Based on your orders" and "Recently viewed". Fixed-image cells and banners are content and are shown as set.

## Tile cells: fixed or rule-based

A tile has up to four cells. A cell is either

- **fixed**: `label`, `href` and `imageUrl` set by staff (communities, services, anything that is not a product), or
- **a rule**: a `source` with the same shape as a rail's (`kind`, `query`, `department`, `sort`, `atins`, `limit`;
  schema `TileCellSource` in `contracts/openapi/content.yaml`).

The gateway resolves each rule to the best eligible product and fills the cell with that product's photo, title and
`/p/{atin}` link (the response cell also carries `atin`). The `source` never reaches the storefront.

| Staff picks | Stored source |
|---|---|
| Top rated in a department | `{kind: search, department, sort: rating}` |
| New in a department | `{kind: search, department, sort: newest}` |
| Best sellers in a department | `{kind: department-bestsellers, department}` |
| Search results for words | `{kind: search, query[, department]}` |
| Fastest delivery from AfroTransact | `{kind: fulfilled-fastest}` |
| Picked products | `{kind: atins, atins: [...]}` |

Behaviour to know:

- A product appears in **one cell of the page only**: cells are filled in page order, so the second "top rated" cell
  gets the next-best product when the first already took the winner.
- A rule with nothing eligible **drops its cell**. A tile with rule cells that ends up with fewer than two cells is
  dropped, rather than shown half empty. A tile of fixed cells is never dropped for lack of products.
- Rule cells are cheap: one search per rule per page render, fetched in parallel, and content placements are cached
  by the gateway.
- Department names are the catalogue's top-level departments (Groceries, Fashion, Beauty, Home & Art, Books, Health,
  Event & party). A rule on a department with no eligible products shows nothing.

In the staff console the tile and slide fields appear only for their slot (`FormField.showWhen`): choose **Home tiles**
to get four cell sections, each with a product rule picker next to the fixed label/link/image fields.

## Hero schedule

A hero slide may carry optional `startsAt` / `endsAt` (RFC 3339) in its payload. The gateway returns only slides whose
window contains "now", in placement order, evaluated on every request (so the gateway's content cache never keeps an
expired slide). An unreadable bound is ignored rather than hiding the carousel. This is in addition to the placement's
own review and schedule window (Starts / Ends), which content enforces before the gateway sees the slide: use the slide
window for a campaign that should switch on and off by itself inside an approved placement. Staff set it with
**Show the slide from** / **Hide the slide after** on a hero placement; the end must be after the start.

## Personalised rails

- **Based on your orders** (`id: based-on-orders`, first rail, signed-in only). The gateway reads the shopper's latest
  orders, finds the departments of what they bought most, and offers the best-rated eligible products there that they
  have not bought. Absent for guests, shoppers without orders, or when nothing eligible is found. Cost: one orders read
  plus one cached catalog read per distinct product (at most eight).
- **Recently viewed** is client-side. The product page remembers the ATIN in local storage (`at.recentlyViewed`, at
  most 12, most recent first); the home page asks `GET /v1/home/recently-viewed?atins=...` for cards. Nothing is stored
  server side. The rail is not rendered at all until at least one remembered product is eligible (no empty state).

## The home page follows the shopper's place

The deliver-to ZIP (session cookie, or the signed-in profile's choice) reaches `Home` as `Location.Current`: the ZIP,
and its city and state from the geo service. It steers the page in three ways, all by rule (nothing is written per
category or ZIP). How the location is detected on a first visit and chosen afterwards (city shown, ZIP internal, the
`LOCATION_DETECT_ZIP` local override) is in [delivery-location.md](delivery-location.md).

1. **Content targeting.** The gateway asks content for placements of that ZIP (`zipPrefixes` on a placement), so a hero
   slide, tile or rail can be shown to Texas ZIPs only. The gateway caches content per market and ZIP.
2. **Location rules.** A rail (`home_product_row`, payload `kind`) or a tile cell (`source.kind`) may be

   | Kind | Shows |
   |---|---|
   | `near-you` | products whose offer ships from the shopper's city, then their state, then their census region (the offer's "Houston, TX" ship-from label); nothing from further away. Hidden when nothing qualifies. |
   | `deliverable-fast` | products the promise service says reach the shopper's ZIP within `maxDays` (default 2); the card shows that promise. |
   | `community` | products of one `community` key (staff choose the community; there is no per-city community data yet). |

   Rail titles may use `{city}`, `{state}` and `{day}` (the day by which everything in the rail arrives: "Get it by
   Wednesday, Nov 11"). A rail left without products is not shown.
3. **Closeness and a daily rotation for every rule.** All rule products (except picked ATINs) are ordered by how close
   they ship from the shopper, then rotated: inside each group of four neighbouring search ranks the order is a hash of
   (UTC day, ZIP, signed-in shopper or guest session, ATIN). The page therefore differs by ZIP and by day and is
   identical on every load of one visit. A product appears once per page: tiles are filled first, then "Based on your
   orders", then the rails in order, each dropping what is already shown.

The pre-order hero and delivery runs use the same `Location.Current`, so the pre-order banner, the delivery run
advertorials and the rest of the home always describe the same city.

`GET /v1/home` is `Cache-Control: no-store`, and the storefront reads it with `cache: 'no-store'` and invalidates it
(and refreshes the route) when the ZIP changes, so a new ZIP always yields a new page. Root cause of "nothing changes":
the seeded content was not targeted by ZIP and every rule ignored the location, so the same products qualified for
every ZIP.

## Demo data

`make seed` (`tools/seed`, step `content`) creates the community and kitchens tiles with fixed images, and the
product-driven tiles **Top rated by department**, **New in the shop**, **Get it tomorrow** (`deliverable-fast`, one day)
and **Shipping from near you** (`near-you`) from rules only, with no image pinned per category (`TILE_RULES` in
`tools/seed/seedlib/dataset.py`). The earlier per-category "Shop by department" tile is ended on the next run.
Location demo content:

- the hero **Fresh from Houston kitchens** is targeted at Texas ZIP prefixes (75 to 79) and **Diwali table** at the
  Northeast (06, 07, 08, 10, 11, 12, 19); the other slides show everywhere;
- the rails **Shipping from near {city}** (`near-you`) and **Get it by {day}** (`deliverable-fast`, 2 days) replace the
  earlier "Spice cupboard" and "Fabrics & fashion" rails (ended on the next run).

### Demo ZIPs and how to check

| ZIP | Place | Expect |
|---|---|---|
| 77042 | Houston, TX | the Texas hero, no Diwali slide; "Shipping from near Houston" starts with Houston sellers (Mama Put Pantry, Lagos Larder), then Dallas, then the South (Atlanta, Miami, Silver Spring) |
| 75212 | Dallas, TX | the Texas hero; Texas sellers first, same state before the rest of the South |
| 08817 | Edison, NJ | the Diwali slide, no Texas slide; the near rail starts with Edison (Patel Grocers), then the Northeast (Bronx, Accra Market) |
| 10453 | Bronx, NY | the Diwali slide; Bronx sellers first |

1. `make seed ARGS="--only content"` (idempotent; changed placements are replaced, retired ones are ended). The content
   service needs migration `V17__home_location_rules.sql` first: `tools/dev/local.sh restart content`.
2. Restart the gateway to pick up the new rules: `tools/dev/local.sh restart gateway-bff`.
3. Open the storefront, choose **Deliver to** and enter `77042`, then `08817` (or call
   `curl -s -b jar -c jar -X PUT -H 'Content-Type: application/json' -H 'Origin: <storefront origin>' -d '{"zip":"08817"}' <gateway>/v1/delivery-location`
   then `curl -s -b jar <gateway>/v1/home | jq '.hero[].title, .rails[] | {id, title, n: (.items | length)}'`). The hero,
   tiles and rails change; reload and they stay the same; tomorrow they rotate.
4. Storefront in mock mode (`NEXT_PUBLIC_API_MOCKING=enabled`): the same behaviour from `web/packages/api-client/src/mocks`.

`near-you` reads the offer's ship-from place, so only seller-shipped offers count; AfroTransact-fulfilled offers ship
from a fulfilment centre and rank as far. The seed marks 13 Houston, Edison and Bronx products as self-shipped
(`SELF_SHIPPED` in `dataset.py`; Bonwire's Atlanta items already were). The seeder never rewrites an existing offer, so
on a database seeded earlier either wipe and seed again, or switch those offers to "Ships from seller" in Seller Center.

Deploying the rule schema needs the content-service migrations `V16__home_tile_rules_and_hero_schedule.sql` (slot payload
schemas accept `source` on tile cells and `startsAt`/`endsAt` on hero slides) and `V17__home_location_rules.sql` (the
location kinds above on rails and tile cells); existing placements stay valid.


## Home composition

Everything under the tiles is a **block**. The gateway (`go/services/gateway-bff/internal/app/site_compose.go`) takes the
placements of the body slots, orders them by **priority** (highest first; ties keep content's order), drops what does not
apply to this shopper, resolves each module, and returns `blocks`. The storefront renders by `type` and ignores a type it does
not know (`web/apps/storefront/src/components/home/HomeBlocks.tsx`). Staff change the page by changing placements, never code.

| Slot | Block `type` | Paid | What it is |
|---|---|---|---|
| `home_product_row` | `rail` | no | product carousel (search, ATINs, location rules); hidden under **4** products (`minItems` per rail) |
| `display_banner` | `banner` | yes | the older single banner, always labelled Sponsored |
| `home_brand_banner` | `brand-banner` | yes | full-width **sponsored brand banner**: logo, headline, image, button, optional strip of up to 3 products |
| `home_sponsored_products` | `sponsored-products` | yes | **sponsored products rail** from the sponsored-search auction |
| `home_ad_row` | `ad-row` | yes | **2 or 3 image ads** side by side, each tagged "Ad" |
| `home_advertorial` | `advertorial` | yes | "**From our partners**": image, headline, two lines, link |
| `home_block` `deals` | `deals` | no | **deals of the day**: in-stock, real-photo items with at least 10% off compare-at price, biggest first, countdown to the end of the UTC day (or `endsAt`) |
| `home_block` `based-on-orders` | `based-on-orders` | no | the personal rail (implicit first when staff do not place it) |
| `home_block` `recently-viewed` | `recently-viewed` | no | client-side marker; the browser decides (implicit last) |
| `home_block` `preorders` | `preorders` | no | the pre-order hero where the shopper's city has a live pre-order; without this block it opens the page as before |
| `home_block` `services-near-you` | `services-near-you` | no | a band linking to `/services`, **hidden when no provider lists for the shopper's ZIP** (only a link: the services page is another team's) |
| `home_block` `community-spotlight` | `community-spotlight` | no | one community's products; the community is staff-chosen or, by rule, one of the communities with enough stock, rotating by day and ZIP (search facet) |
| `home_block` `seller-spotlight` | `seller-spotlight` | no | the best review-weighted marketplace seller among products shipping near the shopper (at least 3 products, sellers without reviews cannot win, AfroTransact's own catalogue is not a "seller") |
| `home_block` `membership-promo` | `membership-promo` | no | membership / gift cards band |
| `home_block` `signin-promo` | `signin-promo` | no | sign-in band for signed-out visitors (`audience: signed-out`) |
| `home_block` `occasions` | `occasions` | no | **shop by occasion**: cells with a label and link; a cell with a product rule borrows the photo of that rule's best eligible product |

Not built, on purpose: a **right-hand promo column** at wide widths. The page uses one centred content width (1500 px, the
`--at-page-max` token) for hero, tiles, banners and rails; a side column would narrow every carousel for the sake of one ad
slot, so wide screens get the full-width brand banner and the 3-up ad row instead. "Because you viewed X" is a follow-up
(it needs a related-products read keyed by the browser's viewed ATINs).

### Priority is the position

A placement's **priority** is its place on the page: the "Home page layout" list in the staff console (Marketing, tab *Home
page layout*) shows the modules in order and moves one up or down, or sets an exact priority, without a new review (content
endpoint `PUT /v1/placements/{id}/priority`; only the position changes, never the creative). The seed spreads priorities so
there is room to insert: see `HOME_MODULES` in `tools/seed/seedlib/dataset.py`.

### Targeting and schedule

Every module carries what any placement has: market, **ZIP prefixes** (content filters before the gateway sees it), and a
schedule (`startsAt`, `endsAt`; content serves only live placements). The home slots add two payload fields, applied by the
gateway: `states` (two-letter codes, e.g. `["TX","GA"]`) against the shopper's delivery state, and for `home_block`
`audience` (`all`, `signed-in`, `signed-out`). Paid modules also take `maxPerSession`.

### Rules every module follows

- **One product, once per page.** Tiles claim first; **paid products claim next** (sponsored products and a brand banner's
  strip), so an advertised product is not repeated further down as an organic card; then the body in page order. A module
  that ends up with fewer products than its minimum (rails 4, deals 3, sponsored 2, spotlights 3 to 4) is hidden and does
  not use up products another module could show.
- **Eligibility** is unchanged: in stock with a real photo, "never show 0 reviews" (cards hide a zero-review rating), and the
  delivery-location-aware ranking of the rail rules.
- **Hide when empty**: a module with nothing to show renders nothing, and a failing upstream (search, bookings, the ad
  auction) omits that block, never the page.

### Ads: disclosure, counting, caps

- **Disclosure.** Every paid block has `sponsored: true` and names its advertiser; the storefront draws a visible
  "Sponsored" tag with the advertiser on banners, brand banners, advertorials and the sponsored rail (and each sponsored card
  keeps its own label), a compact "Ad" tag on every image ad whose accessible name starts "Ad · advertiser". Links are
  `rel="sponsored"`. A paid module without an advertiser is dropped by the gateway. A paid module never takes the organic look:
  hairline border and a gold rule on its left edge.
- **Counting.** Image/brand/advertorial/banner modules report to `POST /v1/home/ad-events` (`{placementId, event:
  impression|click}`), which the gateway forwards to content (`POST /v1/placements/{id}/events`) and content adds to
  `placement_stat` per placement and UTC day. An impression is one per page view, when at least half the module has been on
  screen; a click is a click on its links. Nothing identifies the shopper (no cookie, ID or IP is sent or stored). Reading
  the counts in the staff console is a follow-up (see below).
- **Sponsored products reuse the sponsored-search pipeline**: the gateway runs the same ad auction as search results
  (`ads=true`, not logged as a search), one auction per term of the module's `query` ("rice OR shea OR kente", at most four,
  in parallel, because one auction places only two ads), keeps the sponsored hits that are eligible, and gives each card its
  `sponsor.adToken` and the `sponsor.searchId` of the search it came from. The auction records the impression
  (`ads.impression.recorded`); a click is reported with `POST /v1/search/clicks` exactly like a sponsored search result, so the
  seller's campaign is charged once per token and budgets, pausing and attribution work unchanged.
- **Frequency cap.** `maxPerSession` (defaults: brand banner 3, advertorial 3, ad row 5) is enforced in the browser with
  `sessionStorage` (per tab session, nothing leaves the browser): a module seen that many times is not rendered again and not
  counted again. It is a courtesy cap, not a billing control.
- **Failure is quiet.** A counter request that fails is ignored; a storage error means "no cap".

### Layout

- One content width for everything: hero, tiles, bands and carousels all sit in `.page` (`--at-page-max` 1500 px, centred,
  side gutters from `--at-page-gutter`).
- **Tiles** take their columns from the number of tiles (`data-count`), so there is never an empty column: two across on
  tablets, up to four across from 1100 px, five or six across from 1400 px for five or six tiles, one on phones.
- **Rails** are carousels (`RailCarousel.tsx`): fixed-width cards (`clamp(150px, 15.5vw, 204px)`, 44 vw on phones) in a
  scrolling row with scroll-snap, "See all", labelled previous/next buttons that appear only while there is more to see and
  not on touch screens, Left/Right keys when the row has focus, and `prefers-reduced-motion` honoured. The gateway sends up
  to 24 products per rail. A row with few products simply ends (rails under four are not shown at all).
- Images below the fold are `loading="lazy"` inside fixed-aspect boxes (no layout shift); the body streams behind a skeleton
  (`HomeSkeleton`); images come from the media CDN only (the CSP's `img-src`), so advertisers upload creatives to the media
  service and paste the media address.

### How adverts and placements are created today

1. **Staff** open Marketing > *Placements & ads* > **New placement or ad**, choose the slot (the paid slots and the home
   modules are choices of the Slot list), fill the fields that appear (headline, text, image address, link, the product
   strip, up to three image ads, states, ZIP prefixes, position), and set **Advertiser** for a paid module.
2. A paid creative is a placement with `paid: true`: content's existing **four-eyes** rule applies (the approver must differ
   from the author and the submitter) before it goes live. The seed's demo advertisers are house-run placements marked unpaid.
3. **Sponsored products** come from sellers: Seller Center > Advertising campaigns (keywords, categories, ATINs, bid,
   budget). Staff only place the *rail* that shows what those campaigns win.
4. The schedule (Starts / Ends) and targeting apply as for any placement.

**Not built yet (follow-up, proposed design).** There is no self-serve advertiser (non-seller brand) tooling: no advertiser
account, no creative upload by the advertiser, no booking or billing for brand banners, advertorials and image ads (CPM or
flat fee), and no reporting screen. Proposed: an `advertiser` aggregate in content (org, billing contact, payment method) with
`ad_order` (slot, dates, targeting, price model, budget), creatives uploaded to the media service and checked by staff (the
existing review workflow), impressions and clicks from `placement_stat` charged against the order (flat fee invoiced, CPM
metered), a viewability and invalid-click filter, staff reporting (impressions, clicks, CTR per placement) on the Home page
layout list, and an advertiser report page. Until then every paid module is created by staff on the advertiser's behalf.

## Demo data for the composition

`make seed` (step `content`) now also places the modules of `HOME_MODULES`: sign-in band (95), deals of the day (90),
sponsored products (85), a Kingston Kitchen brand banner (75), the pre-order band (62), community spotlight (58), a partner
ad row (52), shop by occasion (47), seller spotlight (42), a Mama Put Pantry advertorial (40), services near you (38) and a
membership band (36), among the rails (60 to 35) and banners (45, 20). The step needs the content migration
`V18__home_ads_and_blocks.sql` first: `tools/dev/local.sh restart content`, then `tools/dev/local.sh restart gateway-bff`.
`make seed ARGS="--only content"` is idempotent: a module whose payload and priority are unchanged is left alone, a changed
one is replaced.

### Demo photos

The seeder picks a photo for a product only when its **title or categories contain the words of the product's own query** and
nothing about something else (a basketball player, a mosque, seashells): `tools/seed/seedlib/images/relevance.py`. Queries run
strictest first (`garri Nigerian food`, `garri food`, `garri`). A product with no relevant photo is skipped and reported, never
given a wrong one. `SEED_IMAGES_DIR` still comes first and is never filtered: name a file after the product key
(`ijebu-garri.jpg`, `ijebu-garri-2.jpg`). Each run writes `.local/seed/image-review.md` and `.csv` (every product, the photo
chosen, its source and licence, what was turned away) and prints a summary with the products to check. Wikimedia (CC BY, CC BY-SA,
CC0, public domain), Pexels and Unsplash photos keep their credit in `ATTRIBUTION.md`. The seeder never replaces the photos of a
product that already exists: to fix an earlier run, put your files in `SEED_IMAGES_DIR` and seed a fresh database, or replace the
photos in Seller Center. Generated category items are now named `AfroTransact Brand <Category>` instead of
`<Category> (AfroTransact demo item)`.
