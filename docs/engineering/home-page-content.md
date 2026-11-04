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

## Demo data

`make seed` (`tools/seed`, step `content`) creates the community and kitchens tiles with fixed images, and the
product-driven tiles **Top rated by department**, **New in the shop** and **Get it tomorrow** from rules only, with no
image pinned per category (`TILE_RULES` in `tools/seed/seedlib/dataset.py`). The earlier per-category "Shop by
department" tile is ended on the next run.

Deploying the rule schema needs the content-service migration `V16__home_tile_rules_and_hero_schedule.sql` (slot payload
schemas accept `source` on tile cells and `startsAt`/`endsAt` on hero slides); existing placements stay valid.
