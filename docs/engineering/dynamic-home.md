# Dynamic home: what shoppers see, and how it is decided

Status: backend built (S415 to S426, S430; see section 9 for what is done and what is not); storefront and staff console built (Part 3). Design background:
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

## Part 3. Frontend and staff console

Built on the contract commit `331ddfb06` (storefront, staff console, `@afrotransact/api-client`). The storefront draws the page from
the module list the gateway returns; it never decides which modules exist.

### 1. Pieces

| Piece | Where |
|---|---|
| Module registry (block type to renderer), localisation of every block, impression and click wrapper | `web/apps/storefront/src/components/home/HomeBlocks.tsx`, `src/lib/home/copy.ts`, `components/home/modules/ModuleFrame.tsx` |
| New modules: text-only brand hero, department and community tiles, service cards, sell band | `components/home/modules/DynamicModules.tsx`, `HomePhoto.tsx`, `DynamicHome.module.css` |
| Home page, flat hero, visit memory | `app/(shop)/page.tsx`, `components/home/Hero.tsx`, `HomeVisitMemory.tsx` |
| Visit cookies and headers | `src/middleware.ts`, `src/lib/home/visit.ts`, `src/lib/api/server.ts`, `@afrotransact/api-client/home-visit` |
| Department bar, All menu, search departments, footer communities | `components/shell/Header.tsx`, `AllMenu.tsx`, `SearchBox.tsx`, `Footer.tsx`, `lib/nav/labels.ts` |
| Community routes | `app/(shop)/communities/[[...slug]]/page.tsx` |
| Sitemap categories | `src/lib/seo/sitemap-source.ts` (`collectCategories`) |
| Module analytics | `lib/home/moduleEvents.ts` |
| Staff Home settings, history, community registry, Home preview | `web/apps/staff-console/src/components/marketing/home/*` (two tabs of Marketing) |
| Contract schemas, client, mock engine | `web/packages/api-client/src/{schemas/home.ts,schemas/site-nav.ts,staff-home.ts,home-visit.ts,mocks/home-dynamic.ts,mocks/staff/home-settings.ts}` |

### 2. How the storefront draws the page

- `GET /v1/home` is read on the server. `version: 2` means the gateway composed it: the storefront then ignores `tiles`, does not
  add its own pre-order band and draws `hero` (the campaign carousel, if any slide has a real photo) followed by `blocks` in the
  order sent (DOM order is visual order, nothing is reordered with CSS). `version: 1` (home.dynamic off, an older gateway) keeps the
  classic composition, so the kill switch needs no deploy.
- Each `blocks[].type` maps to a renderer in `MODULE_REGISTRY`; an unknown type is ignored, a module with nothing to show renders
  nothing (no heading, no empty state). Paid modules are labelled and counted by placement exactly as before.
- Copy: `titleKey`/`subKey`/`ctaKey`/`bodyKey` + `titleParams` are resolved from the storefront catalogues; the five locales hold
  every key of `contracts/i18n/home-copy.json` verbatim (a test fails on drift). An unknown key falls back to the `title` the gateway
  also sent, never to the raw key. Department labels: the gateway's label for the locale, else the catalogue `dept.<key>`
  translation, else the English label.
- Photos: `isRealPhoto` is the same rule as the gateway (`http(s)` or site-relative, not svg, ico, data: or a placeholder path).
  A tile, card, hero slide or banner without one is text only, never a striped box, icon or emoji (`HomePhoto`). A photo that fails
  to load keeps its flat, aspect-ratio box (no layout shift).
- LCP: exactly one eager, `fetchpriority=high` image: the first hero slide's photo, else the first department tile's photo (the
  brand hero is text). Every other image is lazy. Image boxes carry an aspect ratio, the brand hero a minimum height, and hidden
  modules are decided before the first byte, so nothing pops in.
- Flat design on a white canvas: new modules have no cards, shadows or rounded boxes and use tokens only (a test fails on raw hex,
  `box-shadow`, `border-radius` or physical left/right properties in the new CSS). The Arabic page is right to left through logical
  properties.
- A11y: each module is a `section` named by its `h2`, one `h1` (hero slide or brand hero), tiles are links with text labels and
  decorative photos, the All menu is a disclosure button (Escape closes and returns focus). The product rail carousel is now a
  labelled `group` around a real `ul` (axe: no `listitem` outside a list).

### 3. The visit (variety) in the browser

| Cookie | Set by | Life | Holds |
|---|---|---|---|
| `at_vid` | middleware (HttpOnly) | browser session | random visitor id |
| `at_visit` | middleware (HttpOnly) | 30 minutes, slides on every page load | random id of this visit |
| `at_hs` | the page (`HomeVisitMemory`) | session | `window~keys of this visit~earlier visits` (short keys only) |
| `at_hm` | the page, on a module click, analytics consent only | 24 hours | `module:position` of the last click |
| `at_variant` | the Worker (not built here) | | experiment variant |

- The server relays them as `X-Visitor-Id`, `X-Visit-Window`, `X-Home-Seen` (earlier visits only), `X-Home-Variant`. A crawler
  (known user agent, or the `X-Crawler` request header the middleware sets itself and a client cannot fake) gets no visit at all and
  therefore the gateway's deterministic page. Page responses for `/` are `Cache-Control: private, no-store`.
- Refresh and back keep the page: the keys of the CURRENT visit are stored apart (`recordLoad` is idempotent for one window) and are
  never sent back; only the earlier visits' keys travel in `X-Home-Seen`. A new window moves the previous visit into the earlier
  ones (3 visits, 120 keys). This is the "replace, not append, within a window" rule of section 4.
- Privacy: these ids are random, first-party, session-scoped and identify nobody. They are on until a shopper explicitly refuses
  Functional cookies (no choice yet counts as on); a refusal clears them and gives the default page. `at_hm` and every module event
  need the Analytics choice.
- Not built: `X-Home-Exclude` (Recently viewed and cart ATINs). Recently viewed lives in the browser's local storage, so the server
  render cannot send it; excluding it would need those ATINs in a first-party cookie (an owner decision, below).

### 4. Navigation, footer, routes, sitemap

- The department bar, the All menu, the search box department list and the Services, Deals and Brand entries read
  `GET /v1/site/navigation` (prefetched in the shop layout, so the first HTML has them). `DEPARTMENTS_LIST` and the footer's
  community list are gone (a test greps for them); with no data nothing is linked. A department with nothing eligible is not
  linked; Services shows whenever `services.available`.
- The footer's "Shop by community" column exists only when `communities` is non-empty.
- `/communities/<key>` asks `GET /v1/site/communities/<key>`: 404 (the storefront 404 page, never a zero-result page) when hidden
  or unknown, a permanent redirect to the canonical key for a case variant or alias, else the community's products
  (`/s?community=<English name>`). `/communities` goes home.
- Sitemap: `collectCategories` reads `GET /v1/site/sitemap` (the same snapshot as the home and the navigation) and maps each
  community route to its indexable category page `/s?community=<English name>` through the navigation list; `lastmod` is the
  gateway's. If the endpoint fails, the `categories` kind fails and the previous version is kept (no fallback to facet pages).

### 5. Analytics (S427)

`ModuleFrame` wraps each organic module: one `impression` per page view when half of the module (or half of the screen for a tall
one) has been visible for 500 ms, and a `click` per link with `target` (product, department, community, service, other). The body
is `{event, module, position, variant[, target]}` to `POST /v1/home/events`: no shopper identity. Nothing is sent without the
Analytics choice. Paid modules keep their per-placement counters.

### 6. Staff console (S418)

Marketing gets two tabs.

- **Home settings.** Mode (environment default, off, shadow, on); every module by its name with its rule in a sentence built from the
  values on screen, enable switch, order band, weight, items shown and only the thresholds that module understands; page limits
  (maximum and minimum modules, sponsored cap, seller share, variety pool, remembered loads, penalty, visit window, hysteresis,
  deal percent, bar size); department names in five languages (English required once another is set, an empty language keeps the
  built-in translation); the community registry (names in five languages, spellings, editorial photo, shown or hidden now with
  counts); the version history with "go back to this version". Every save lists what changed in words and needs a reason of 10
  characters or more (the server accepts 3); a stale save is a plain "saved by someone else" and the screen reloads.
- **Home preview.** ZIP, signed in or guest, language, "as of" date, live or simulated catalogue (0, 10, 100, 10,000), variety seed
  ("another visit", replay by pasting a seed, or the default order crawlers get). Left: the page as a numbered list of modules by name
  with what each shows (product, department, community and service names, never ids). Right: every module with Shown or Hidden,
  its place, the reason, the sentence, "now N of M needed", the qualifying names, "a staff placement won this slot", and a link to
  its rule. A season active on the date is named. It only reads.
- Permissions: viewing needs Marketing at VIEW; the fields are disabled and Save absent below EDIT.
- Scheduling and seasons are placements in the existing Placements and ads tab (start and end checked on every request, S424,
  S430): the Home settings page points there, and the preview's date shows what goes live.
- Tour: the Marketing chapter has two more steps (6 steps); the drift guard count in `tests/e2e/specs/staff-console.tours.spec.ts`
  is 6.

### 7. Mock BFF and fixtures

`api-client/src/mocks/home-dynamic.ts` is a TypeScript model of the gateway engine with the same contract shapes, used by the
Playwright mock BFF, vitest and `pnpm dev:mocks`. Pick a catalogue with `MOCK_HOME_CATALOGUE=0|10|100|10000` (dev server) or the
`at_mock_catalogue` cookie (per browser context, what the e2e specs use); unset keeps the classic mock home (`version: 1`). It is
not the gateway: the real thresholds, pools and shuffle are in Go (Part 2).

### 8. Tests and how to run them

- `cd web/packages/api-client && pnpm test` (engine fixtures, variety, headers, staff routes, contract alignment).
- `cd web/apps/storefront && pnpm test` (`home-dynamic.test.tsx` renders the home for 0 / 10 / 100 / 10,000 in five languages,
  `home-modules.test.tsx`, `home-visit.test.ts`, `community-route.test.tsx`, `home-contract.test.tsx`, `seo-sitemap.test.ts`).
- `cd web/apps/staff-console && pnpm test` (`home-settings.test.tsx`, `tours.test.ts`).
- Browser: `cd web/apps/storefront && E2E_SKIP_BUILD=1 pnpm exec playwright test e2e/home-dynamic.spec.ts` after a mocks build
  (`NEXT_PUBLIC_API_MOCKS=1 next build`). `E2E_CHROME_PATH=/usr/bin/google-chrome` runs it in an installed Chrome when the
  Playwright browsers are not downloaded. It checks the fixtures, every link, visits (stable on refresh, different across
  visits, crawler default, no cookie after a refusal), LCP at most 2.5 s, CLS at most 0.05, home-only JavaScript at most 90 KB
  gzip beyond a content page, axe on the main region, Arabic, and the analytics events in a real browser.

### 9. Frontend status and gaps

- Built and tested here (against the mock engine; the Go engine has its own tests): S418, S419 (storefront literals), S420
  (routes, footer, nav, sitemap, registry screen), S421 (cards and nav entry), S422 and S426 (rendering and copy), S423 (photo
  rules on the home), S427 (events), S428 (budgets in the browser test), S429 (fixtures).
- Not verified: anything against the real gateway and real search (the mock search is not tied to the fixture catalogues, so
  "every linked department returns a product" is checked as "every link answers and the navigation counts are above zero");
  the `at_hm` order attribution (the storefront stores it, the gateway does not read it yet); the Worker's `at_variant` cookie
  and per-variant weights (S428); "Book again" (S421); the CI wiring of the e2e budgets; native review of the machine translations.
