# Home page content and placements

The storefront home page is assembled by the gateway-bff (`GET /v1/home`, `SiteService.Home`). Staff set the content in
the staff console (Marketing > Placements & ads); the gateway turns it into products. Clients hold no home content.

| Section | Source | Product rules |
|---|---|---|
| Announcement bar, banners | content placements (`announcement_bar`, `display_banner`) | none (fixed copy and images) |
| Hero carousel | `hero_carousel` placements | optional per-slide schedule |
| Tiles | `home_tiles` placements | each cell is a fixed image or a **rule** |
| Product rails | `home_product_row` placements | resolved live from search or catalog |
| Based on your orders | gateway, signed-in shoppers only | past orders' departments |
| Recently viewed | the browser (local storage), cards from the gateway | the shopper's own views |

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
category or ZIP).

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
