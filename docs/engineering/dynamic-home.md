# Dynamic home: what shoppers see, and how it is decided

Status: backend built (S415 to S426, S430; see section 9 for what is done and what is not). Design background:
[dynamic-home-spike.md](dynamic-home-spike.md). Related: [home-page-content.md](home-page-content.md), [seo.md](seo.md),
[search-providers.md](search-providers.md).

## Part 1. For the owner (plain language)

### The idea

The home page is built from what exists. Every block of the page (a "module") asks one question before it appears: do we have
enough to show here that a shopper can actually buy? "Enough" is a setting staff can change, not code. A module that does not
have enough is simply not there: no empty box, no grey placeholder, no link to a page with no results.

What counts as something to show is the same everywhere (home, menu, footer, sitemap, community pages): a product that is live,
has a stored tax decision, is in stock and has a real photograph. No icon, no emoji and no placeholder ever stands in for a
photo. A product without reviews is never shown as "0 reviews" and is never put in "Top rated".

### What appears at 0 / 10 / 100 / 10,000 products

| Products | The page |
|---|---|
| 0 | A plain text-only brand headline (no photo, no promise of stock), the "Sell on AfroTransact" band, and, if any service provider exists, the Services block. No tiles, no rails, no communities. |
| 10 (a few departments) | The brand headline or a scheduled campaign, department tiles for the departments that have products (one real product photo each), "Just landed" when there are 8 or more, the Sell band, Services when providers exist. No deals, no top sellers, no communities. |
| 100 (about 10 sellers) | Adds Groceries, Top rated, Top in a department, Deals (when at least 6 products have 10 percent or more off and come from 2 or more sellers) and communities (only when 3 communities each have 8 products and 2 sellers). |
| 10,000 | Everything above, never more than 12 modules. Which departments, products and communities show rotates from visit to visit (next section). |

Services appears in the menu and on the home whenever any provider exists in the market. With a ZIP it says "Services near
{city}"; without one, or when none serves that ZIP, it says "Popular services".

### Different views every visit

Each module keeps a pool about 3 times bigger than what it shows (a rail that shows 8 products draws from 24). Each visit picks
a different 8 from that pool by a weighted shuffle: better products (in stock, real rating, newer, from sellers with fewer items
in the pool) are more likely, but not certain.

- **A visit** starts on a first load and ends after 30 minutes of inactivity. Inside one visit the page does not change when you
  refresh or press back. A new visit shows a different selection.
- **Memory of what you saw.** Products shown on the last 3 loads are down-weighted (a repeat is possible, just less likely).
  Signed-in shoppers: remembered on our side. Guests: remembered in their own browser (a first-party cookie).
- **Not what you already have.** Anything in your Recently viewed or in your cart is left out of discovery modules.
- **Module order** varies inside bands: the hero stays first, your own modules (Continue shopping, Recently viewed) next, the
  discovery modules rotate, promotions and the Sell band come last.
- **Fair to sellers.** No seller supplies more than 2 products of one module (a setting). No product appears twice on a page.
  If the whole marketplace has too few sellers to fill a module under that rule (a one-seller launch), the limit relaxes just
  enough to fill it.
- **Search engines** get one fixed, predictable page: no shuffle, no memory.

### Settings (all editable by staff, every change needs a reason and is logged)

Staff edit these in the staff console under Marketing, Home (settings, version history, revert).

| Setting | Default | What it does |
|---|---|---|
| Mode `home.dynamic` | on in local, dev, uat and trial; **off in production until you switch it** | off = today's page; shadow = today's page is served while the new engine is computed and compared; on = the new page |
| Per module: on/off, minimum products, minimum sellers, minimum departments or communities, product and seller minimums per department or community, newest-product age, weight, how many items it shows, band | see section 3 | when a module is shown and how it is ordered |
| Communities | 3 communities, each 8 products and 2 sellers | below this: not on the home, menu, footer or sitemap, and the community page answers "not found", never "0 results" |
| Items shown vs pool | pool is 3 times the shown count | variety |
| Items per seller per module | 2 | fairness |
| Sponsored modules | at most 1 per 3 organic modules; never on a page with no organic module | paid content never fills a thin page |
| Most / fewest modules | 12 / 3 | page length |
| Visit window | 30 minutes | when a visit ends |
| Remembered loads, and how strongly a remembered item is down-weighted | 3 loads, 60 percent | novelty |
| Hysteresis | 2 products | a shown module stays until it is 2 below its minimum, so it does not flicker at the boundary |
| Deal threshold | 10 percent off | what counts as a deal |
| Menu bar size | 7 departments | the header bar; "All departments" lists every department that has products |
| Department display labels | derived from the live catalog name ("Groceries & Food" becomes "Groceries") | staff can override per language |
| Community registry | none | one entry per community: key, name in five languages, aliases ("Naija"), optional editorial image (used only if it is a real photo) |

Staff can also schedule any module placement (start and end), pin a placement (a pin always wins over an automatic module of
the same kind), and preview the page as of any date, ZIP, language, signed-in state, and a simulated catalogue of 0, 10, 100 or
10,000 products. The preview says, for every module by name, shown or hidden, why, and the count against its threshold.

### Seasons

The seasonal calendar is a set of scheduled placements in the content service (for example a Diwali hero from 20 October to 3
November). Inside its window it is live and raises that content; outside it nothing changes; "preview as of next month" shows
what goes live then. Season copy is the placement's own text (English required, other languages optional, falling back to
English) and a season never promises stock: the module it boosts is still shown only when it has eligible products.

### Day 1 in production

1. The migration `V20__remove_demo_placements` removes the ten demo placements (the community tiles, four demo heroes, the
   Kente banner, the pantry rail, three announcement bars) from every environment, but only rows staff never edited. Demo
   content for local and dev now comes only from `make seed`, which refuses production, UAT and the trial environment.
2. Production starts with `home.dynamic` off. Run the search reindex (below), put the mode on shadow, watch the mismatch counter,
   then switch to on. If anything looks wrong, set it back to off: no deploy needed.
3. With zero products the page is honest: a text-only brand headline and the Sell band.

### Caching

- The page is built per visit and is not cached (HTML stays uncached, [seo.md](seo.md) decision 6).
- The data it draws from (counts and candidate pools) is shared and cached in the gateway for 60 seconds, served stale up to 300
  seconds if a refresh fails. A new product can take up to a minute to count.
- Search engines get the same page every time; their response may be cached for 60 seconds (stale up to 300).
- Menu, department, community and sitemap data: 60 seconds (sitemap: 5 minutes).
- Settings: a change reaches the site within 60 seconds.

### How to debug a hidden module

1. Marketing, Home, Preview: the module is listed by its name with the reason in a sentence ("Shop by community: needs 3
   communities with 8 products and 2 sellers each; 1 qualifies") and the live count.
2. The same decisions are on the live page for staff: open the storefront while signed in as staff and add `?debug=1` to the home
   address (the response carries each module's reason and the seed). `?seed=<value>` replays exactly that view.
3. Not in the list at all? A staff placement of the same kind may hold the slot (the decision says "pinned").
4. Counts too low? Check the product has a real photo, stock and a resolved tax decision: the menu counts and the home counts
   are the same numbers.
5. Still unexplained: the metric `home_module_eligibility{module,shown,reason}` shows the reasons over time.

## Part 2. Engineering

### 1. Pieces

| Piece | Where |
|---|---|
| `photo_ok` index field and `eligibleOnly` | `go/pkg/searchindex/photo.go` (one rule, `PhotoOK`), indexer projector, search (`eligibleOnly`, `distinctSellers`) on Meilisearch and Elasticsearch |
| Eligibility engine (pure) | `go/services/gateway-bff/internal/domain/home_eligibility.go` |
| Variety engine (pure) | `domain/home_variety.go` |
| Settings model, defaults, validation | `domain/home_settings.go`, content `domain/home/HomeSettings.java`; the defaults are asserted equal in both services against `contracts/settings/home-defaults.json` |
| Labels, communities | `domain/home_labels.go`, `home_dept_labels.go` (generated from `contracts/i18n/home-copy.json`, drift test) |
| Composition | `app/site_dynamic.go`, `site_dynamic_build.go`, `home_source.go`, `home_cache.go` |
| Nav, departments, communities, sitemap | `app/site_nav.go`, routes in `adapter/httpapi/handlers_home.go` |
| Staff settings, registry, preview | `app/staff_home.go`; content `HomeSettingsService`, `CommunityRegistryService` |
| Visit memory | `port.HomeMemory`: Valkey (`adapter/valkey/home_memory.go`) in production, in-process in tests |
| Metrics | `adapter/homemetrics` |

### 2. Request flow (`GET /v1/home`, mode on)

1. Settings (60 s cache) and the community registry (60 s) are read.
2. **Signals snapshot** (60 s cache per market): one eligible-only search for the totals, one per department and per
   community above its product threshold (counts, distinct sellers, best photo), the first-party count, and the services list.
3. Staff placements (hero slides, tiles, pinned rails, sponsored modules) are resolved by the existing `composeBody`; they claim
   their products first. Hero slides without a real photo are dropped; if none remain the text-only brand hero is used. Fixed
   tile cells without a real photo, or linking to a community that is not visible, are dropped.
4. **First eligibility pass** from counts only (with hysteresis against the previous answer). Pools are then fetched for the
   shown pool modules only (deals, new arrivals, top sellers, groceries, "Top in" rails); a module hidden by counts costs no
   upstream call (asserted with a counting fake). A second pass uses the pool's real counts (discounted, reviewed).
5. **Order**: staff placements and automatic modules are ordered by band (hero, personal, discovery, promo, brand); inside the
   discovery band by a seeded weighted shuffle of the module weights; pinned placements keep their priority first.
6. **Pick**: in page order each module picks from its pool with the seeded shuffle, excluding what the page already shows and
   the visitor's Recently viewed and cart, with the seller share and novelty. A module left below its minimum is hidden with
   the reason.
7. **Caps**: at most `maxModules`; sponsored at most 1 per 3 organic.

### 3. Module defaults

`hero` (placements) and `brand-hero` (text only, when no photo hero), `continue-shopping` and `recently-viewed` (visitor scope),
`deals` (6 products, 2 sellers, 10 percent off), `department-tiles` (2 departments with a product), `groceries-rail` (6),
`services-near-you` (any provider; near with a ZIP and a city, else popular), `new-arrivals` (8), `top-sellers` (8 reviewed, 2
sellers), `department-rail` ("Top in {department}" for up to 2 of the 4 biggest other departments, 6 each), `preorder` and `plus`
(staff placements), `communities` (3 x 8 x 2), `sell-band` (always). Values live in `DefaultHomeRules()`.

### 4. The seed and the visit

`seed = SHA-256(visitor, window, variant)`; per module `unit(seed, module + key)` gives a number in (0, 1) and the score is
`ln(u) / weight` (highest first; Efraimidis and Spirakis weighted sampling). Weight is quality (stock, a real rating that is
neutral when there are no reviews, recency when known) divided by `sqrt(items of that seller in the pool)`, times
`1 - noveltyPenalty` for a remembered item.

- Visitor id: signed-in shoppers use a hash of their subject; guests send `X-Visitor-Id` (cookie `at_vid`). No visitor id means
  the deterministic page.
- Window: `X-Visit-Window` (cookie `at_visit`, 30-minute sliding expiry, set by the storefront). Signed-in without it: the
  gateway keeps `{window, lastSeen}` per shopper. A guest without the cookie gets 30-minute clock buckets.
- Novelty memory: signed-in, last N windows stored in Valkey (hashed subject, 8-character item hashes, 30-day TTL; a refresh
  inside a window does not count itself). Guests: `X-Home-Seen` (cookie `at_hs`). **The storefront must replace, not append, the
  cookie entry when `visit.window` equals the previous response's window**, or a refresh would reshuffle.
- Exclusion: `X-Home-Exclude` (recently viewed and cart ATINs, request only, never stored or logged).
- Crawler: `X-Crawler: 1` (Worker) or a known bot user agent: `visit.deterministic` is true, no randomness, no memory.
- Staff debug: `?debug=1` (staff with Marketing) returns `debug.decisions` and `debug.seed`; `?seed=` replays. Ignored for others.
- Variant: `X-Home-Variant` (Worker cookie) is part of the seed and of the tracking; data caches are shared across variants today
  because a variant cannot yet change data. Per-variant weight overrides are not built (S428).

### 5. Endpoints

Public: `GET /v1/home`, `POST /v1/home/events`, `GET /v1/site/navigation|departments|communities|communities/{slug}|sitemap`.
Staff (Marketing VIEW to read, EDIT to write): `/v1/staff/home/settings` (GET, PUT), `/settings/versions`, `/settings/revert`,
`/communities` (GET), `/communities/{key}` (PUT, DELETE), `/preview`. Content: `/v1/home-settings`, `/v1/community-registry`,
`/v1/placements/resolve?at=`. Contracts: `gateway-bff.yaml`, `gateway-bff-staff.yaml`, `content.yaml`, `search.yaml`. Additive
changes made after the contract commit `331ddfb06`: request header `X-Home-Exclude`, and the clarified rule that guests replace
the `at_hs` entry within a visit window.

### 6. Metrics

`home_module_eligibility{module,shown,reason}`, `home_shadow_mismatch{module}`, `home_compose_seconds{mode}`,
`home_signals_age_seconds`, `home_module_events{module,event,variant,locale}`. All labels come from fixed sets (module registry,
reason enum, three modes, variant names `control, a, b, c, d` else `other`, five locales). Never a product, seller or shopper.

### 7. Reindex (S415 runbook step)

`photo_ok` is computed at index time, so documents indexed before this release lack it and `eligibleOnly` returns nothing for
them. Before switching production to shadow or on: run the reindex (`make reindex-search`, the `indexer-reindex` job in
Kubernetes, or the `reindex-search` action). It upserts in place; search keeps serving throughout, and ordinary searches never
filter on `photo_ok`, so only the new home signals are empty until the reindex finishes. Elasticsearch gets the field through
`LateMappings` without a new index; Meilisearch gets `photo_ok` as a filterable attribute on the next `EnsureIndex`. Verify with
the staff preview (catalog `live`): department counts match the catalog.

### 8. Tests

`go test ./internal/domain ./internal/app ./internal/adapter/httpapi` in `go/services/gateway-bff`: eligibility, hysteresis,
variety determinism within a visit and difference across visits, novelty, seller cap, no duplicates, crawler determinism,
fixtures 0 / 10 / 100 / 10,000 (live and replayed), counting-fake no-fetch, shadow mode, settings TTL with a fake clock,
contract tests (Home, SiteNavigation, StaffHomePreview and others against the embedded contracts), staff permissions and
validation, no English literal in generated fields. Content: `./mvnw -pl services/content test` (settings, diff, audit, revert);
`HomeSettingsIT` needs Docker. Seed: `python3 -m unittest tests.test_prod_content tests.test_guard` in `tools/seed`.

### 9. Status by story

See the backlog for statuses. Built and tested in the backend: S416 (shadow and on, flag), S417 (settings, audit, reason, revert),
S419 (taxonomy, labels, nav, tile-rule source endpoint; the storefront literals are the storefront agent's), S420, S421 (without
"Book again"), S422, S423 (migration, guard, CI test), S424 and S430 (as-of placements and preview; no dedicated calendar
module), S425 (server-side memory, guest local only, wishlist hook), S426 (keys and drafts; storefront rendering not here).
Not built or not verified here: S415 on real Meilisearch and Elasticsearch (the integration test needs a running instance),
"Book again", a freshness age (search hits carry no listing date, so `maxAgeDays` is inactive), per-variant weights, and
everything in the browser (S427 analytics, S428 budgets, S429 e2e).
