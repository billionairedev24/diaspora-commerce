# Dynamic customer home (spike)

Status: research and design, 2026-11-12. Nothing in the live home changed. One proof is in code: a pure module-eligibility function with
fixture tests (section 7). Related: [home-page-content.md](home-page-content.md) (how the home is composed today),
[seo.md](seo.md), [search-providers.md](search-providers.md).

**Owner feedback.** The home shows fixed "Shop by community" and "More kitchens to discover" modules (Nigerian, Ghanaian, Ethiopian, Jamaican,
Indian, Senegalese, Kenyan, Caribbean). Those communities may have no products on day 1 in production. Amazon assembles its home from what exists and
what is relevant; services, groceries and every department must stay reachable.

**Short answer.** The gateway already does most of the hard part (every rule product is in stock with a real photo, rails drop when thin, the community
spotlight picks from live facets). The rigidity comes from four things: fixed content that bypasses those rules (community tiles, heroes), fixed lists
in the storefront and staff console (department bar, footer, tile-rule departments), no shared "what exists" signal, and no way for staff to see why a
module is hidden. The recommendation is to keep the current composition and add a signals snapshot plus an eligibility pass in front of it
(section 4), then layer taxonomy-generated departments, an optional data-driven communities facet, a first-class Services module and personalisation.

## 1. Audit: what is hard-coded or seed-dependent

### How the page is assembled today

`web/apps/storefront/src/app/(shop)/page.tsx` fetches `GET /v1/home` (`siteQueries.home`, `no-store`) and renders, in order: pre-order band (unless staff
placed one), delivery-run advertorials, `Hero` (content `hero_carousel` slides), `Tiles` (content `home_tiles`), then `HomeBlocks`
(the typed `blocks` list). The gateway (`go/services/gateway-bff/internal/app/site.go` `Home`, `site_compose.go`, `site_home.go`) loads content
placements for market + ZIP, resolves tile rule cells, composes the body by placement priority, drops modules with nothing to show, de-duplicates
products across the page, and appends "Based on your orders" and "Recently viewed" when staff did not place them.

Already data-driven and correct (keep): `eligibleCard` (in stock and a real photo, no svg/ico/placeholder) on every rule; rail minimum 4 items; deals
need a 10 percent discount and 3 items; community spotlight picked from the `communities` facet with at least 4 products; seller spotlight needs
reviews; occasions need 3 cells; hero slide schedule; ZIP targeting; "near you" and "get it by {day}" rules; sitemap categories only for facet counts above 0.

### Hard-coded or seed-dependent (findings)

| # | Where | What is fixed | Effect on an empty or thin catalogue |
|---|---|---|---|
| 1 | `java/services/content/.../V2__seed_slots_and_placements.sql` | Flyway migration inserts LIVE placements in every environment, including `plc_tiles_communities` (Nigerian, Ghanaian, Ethiopian, ...), four hero slides (party jollof, Diwali table, a sponsored Kingston jerk slide, braider), a kente banner, three announcement bars ("Diwali and Detty December stock is landing") | Production ships demo-flavoured, product-claiming content on day 1, with `imageUrl: null` |
| 2 | `tools/seed/seedlib/dataset.py` `TILES` | "Shop by community" and "More kitchens to discover": 8 fixed cells with stock photos and `/communities/<slug>` links ("Caribbean" links to jamaican) | Dev and demo only (seed is guarded against prod), but it is the screenshot the owner saw and it is the shape staff copy |
| 3 | `site_home.go` `resolveTiles` | A cell without a rule source is "fixed" and a tile of fixed cells "is never dropped for lack of products" (home-page-content.md) | Fixed community cells render whether or not the community has a product |
| 4 | `ImagePlaceholder` (ui package) | A missing or failed image becomes a striped box instead of removing the cell | Blank striped tiles and a striped hero image area; also breaks "no icons or placeholders as images" for editorial cells |
| 5 | `app/(shop)/communities/[[...slug]]/page.tsx` | Redirects to `/s?community=<Capitalised slug>` without checking that the community has products | Dead end: "0 results" page for Ethiopian (noindex, since empty categories are noindex) |
| 6 | `src/lib/api/queries.ts` `DEPARTMENTS_LIST` (used by `Header.tsx`, `SearchBox.tsx`) | Department bar is 7 literal English names: Groceries, Fashion, Beauty, Home & Art, Books, Health, Party | Catalog top-level departments are 13 (`Groceries & Food`, `Fashion & Accessories`, `Beauty & Personal Care`, `Home & Household`, `Electronics`, `Baby & Kids`, `Pets`, `Services`, ...). The search filter is an exact match on the indexed department (`department = "..."` in `meilisearch/index.go`), so the short names very likely return nothing; to be confirmed on a staging catalogue. Electronics, Baby & Kids, Pets, Beverages are unreachable from the bar; not localised |
| 7 | `staff_placement_rules.go` `tileDepartments` | The same 7 stale names are the only choices when staff scope a tile rule to a department | Staff cannot target most real departments; "Event & party" exists here but nowhere in the catalog |
| 8 | `components/shell/Footer.tsx` | "Shop by community": Nigerian, Ghanaian, Ethiopian, Indian, Jamaican as literals | Footer links to empty communities on every page |
| 9 | Community model | There is no community entity. It is a free-text product attribute (`community` / `communities`) copied to the search index facet `communities` (ES facet size 20). The slug is the capitalised attribute value | No canonical key, no label per locale, no image, spelling variants ("nigerian" / "Nigerian") become separate facets |
| 10 | `site_compose.go`, `site_home.go` | English strings in Go: "Based on your orders", "Deals of the day", "Services near {city}", "Spotlight: the {community} kitchen", "4.8 average from 120 reviews", "Browse services" | Not translated in fr / es / ar / yo; the 5-locale rule is broken for module copy. Content placement titles are single-language too |
| 11 | `site_compose.go` `serviceCount` | Services module needs a valid 5-digit ZIP and at least one listing near it; it is a text band (title + button), not providers | A visitor with no ZIP never sees Services on the home even if 40 providers exist; no "popular services" and no "book again" |
| 12 | `Header.tsx` | "Services" is always in the nav; "Deals" and "AfroTransact Brand" too | Dead entries when there are no providers, no deals, no first-party products |
| 13 | `Hero.tsx` and hero placements | Hero slides are content; only the schedule is checked. A slide with a null image shows a 16:9 striped box under the headline | Day-1 hero looks unfinished; a slide can promise stock that does not exist (Diwali basmati) |
| 14 | `GET /v1/home` | `no-store`, per visitor, and every module resolves (one search each) before empty ones are dropped | Cost scales with the number of configured modules, not with what is shown; hidden modules still cost an upstream call |
| 15 | `staff_home_layout.go` | Layout preview describes each module's rule in words ("hidden when it has fewer than 4 products") | Staff cannot see whether a module is hidden right now or why; drops are an `Info` log line |
| 16 | `adTracking.ts`, `/v1/home/ad-events` | Impressions and clicks only for paid modules, by placement id | No organic module-level impressions, clicks or conversion |
| 17 | `sitemap-source.ts` | Community category URLs for every facet bucket with count above 0 (good, data-driven) but the threshold is 1 and counts include products without a real photo | Thin community pages are indexable; counts disagree with what the home shows |

### Failure modes (screenshots by description)

1. **Day-1 production, 12 products, 3 sellers, no Ethiopian items.** Under the hero a band titled "Shop by community" shows four equal cells, each a grey
   striped square with a label (Nigerian, Ghanaian, Ethiopian, Jamaican). Below it a second band, "More kitchens to discover", repeats the pattern with
   four more. Tapping Ethiopian lands on a search page reading "0 results" with department filters that list nothing.
2. **Same page, rails.** "Pantry staples", "Get it by {day}", deals and the seller spotlight each need 3 to 4 eligible products, so all vanish silently. The
   page is hero, two empty community bands, a sign-in band, a membership band, footer: a tall page with no product on it.
3. **Department bar.** Seven names; "Party" and "Books" lead to empty results; Electronics and Pets are not offered at all; "Services" opens a services page
   that says no providers when none serve the visitor.
4. **Broken or slow image.** A tile whose photo fails after hydration swaps to the striped box (the cell stays, now blank); the server-rendered
   image that failed before hydration is only caught by the mount check.
5. **Thin catalogue with a hero.** Hero slide "Light up the table" (Diwali) with no image and a link to `/communities/indian` which is empty.
6. **Guest without ZIP.** Services band and "near you" rails absent even though the market has providers; page looks product-only.

## 2. Design options

All options keep the current invariants: only products live with a stored RESOLVED tax decision, in stock, with a real photo; one product once per page;
paid modules labelled; no carrier names; no claim that AfroTransact collects or delivers; five locales.

| Option | What it is |
|---|---|
| **A. Module registry with eligibility** | Every module (hero, deals, continue shopping, recently viewed, department tiles, groceries rail, services, communities, new arrivals, top sellers, pre-order, Plus) is a registry entry with rules: minimum in-stock products with real photos, minimum distinct sellers, freshness, signal needed. A signals snapshot (counts by department / community / seller / services) is read once; modules that fail are never fetched or rendered; ordering is by weight with fallbacks |
| **B. Staff-curated slots over automatic ones** | Staff pin, schedule and preview modules and campaigns (content placements, mostly built); automatic modules fill the gaps. Add: schedule windows and "preview as of date / ZIP / signed-in" for every module, not only heroes |
| **C. Personalisation and relevance** | Recently viewed, continue shopping, orders, location, language, wishlist (if it exists), seasonality (holiday calendar), with cold-start defaults for guests (regional popularity, new arrivals) |
| **D. Coverage rules from the live taxonomy** | Nav, "Shop by department" and category tiles generated from catalog departments and live counts; Services first-class; communities an optional facet over the product attribute, shown above a threshold, image from a real top product, empty ones hidden from nav, footer, sitemap and routes |
| **E. Client-side composition** (rejected baseline) | The page is a shell; the browser fetches modules and decides what to render |

Combined variants compared:

| Criterion | 1. Harden current (gate fixed cells, delete seed, fix lists) | 2. A + D (registry, taxonomy coverage, communities facet) | **3. A + D + B + C phased (recommended)** | 4. E client composition |
|---|---|---|---|---|
| Day-1 viability | Good: stops the worst empty states | Excellent: page is always built from what exists | Excellent | Good, but layout jumps |
| Cost to build | Low (about 1 sprint) | Medium (about 3 sprints) | Medium-high, phased; each phase ships alone | Medium, then permanent complexity |
| Complexity | Low; thresholds stay scattered constants | Medium; one rule table, one snapshot | Medium-high; settings, preview, signals, calendar | High; two renderers, hydration cost |
| SEO | Neutral | Good: guest home SSR with real modules; sitemap and nav share the same counts | Good, same as 2 (personal modules are islands, not in the guest HTML) | Poor: modules absent from first HTML, CLS |
| Caching | None (per visitor) | Shared composition cacheable as data (60 to 300 s) | Same, plus visitor islands | Cacheable shell, but waterfalls |
| Observability | Logs only | Eligibility metrics and reasons | Plus module-level analytics and A/B | Browser-only, consent-gated |
| A/B-ability | None | Per-module thresholds and order | Per-module variant keys, assigned at the edge | Easy, at SEO cost |
| Staff explainability | None | Preview shows shown/hidden with reason | Same, plus schedule preview | None |

Why not 1 alone: it fixes the screenshot but leaves every future module with its own copy of the thresholds and no way for staff to see why something is
missing. Why not 4: the home is the SEO landing page, the LCP element must be in the first HTML, and `seo.md` decision 6 already rejects edge-caching
HTML because of per-visitor data.

## 3. Recommendation

**Option 3, built in this order: A (eligibility, shadow mode first) with D (taxonomy and communities), then B (schedule and preview), then C
(personalisation and seasons).** Principles:

- **One signals snapshot, many consumers.** The home, department nav, footer, sitemap and `/communities/<slug>` all read the same eligible-only counts
  (department, community, seller count, service providers). "Hidden from the home" and "hidden from nav and sitemap" can never disagree.
- **Decide first, fetch second.** The eligibility pass runs before any module resolves; hidden modules cost nothing.
- **Staff own thresholds, not code.** Rules are settings rows with defaults, audit-logged and versioned.
- **Explain every decision.** Each module yields shown / hidden, a reason code and a sentence in staff words (module names, never ids).
- **Keep what works.** Placements, priorities, ads rules, de-duplication and `eligibleCard` stay; the registry wraps them.

### 3.1 Module catalogue

| Module | Source | Default eligibility (staff-editable) | Notes |
|---|---|---|---|
| Hero / banner | content hero placements | at least 1 approved slide in schedule with a real image, else the text-only brand hero (flat, no photo, no promise of stock) | Slide with null image is not shown as a picture; scheduled by window |
| Continue shopping | signed-in orders (existing "Based on your orders") | 1 or more bought product, 2 or more eligible results | First row for signed-in with signal |
| Recently viewed | browser history (`at.recentlyViewed`) | 1 or more eligible remembered product | Client island; no empty state |
| Deals | discount rule (10 percent or more off list) | 6 eligible deals, 2 or more sellers | Countdown only when staff set an end |
| Department tiles | live taxonomy + counts | 2 or more departments with 1 or more eligible products; tile photo = best eligible product of that department | Biggest departments first, capped at 8, link to the department page |
| Groceries rail | department Groceries | 6 eligible products | Same mechanism generates "Top in {department}" for the biggest departments |
| Services near you | bookings (providers by ZIP) | 1 or more provider serving the place; else "Services you can book" (not "popular": we have no popularity data) with 3 or more providers market-wide; "Book again" for signed-in with past bookings | Cards are services (provider, from price, real photo), not products |
| Communities (conditional) | product attribute facet | 3 or more communities, each with 8 or more eligible products and 2 or more sellers | Never shown with fewer; photo = a real top product |
| New arrivals | newest eligible products | 8 products, newest not older than 30 days, 1 or more seller | Doubles as the cold-start rail |
| Top sellers | rating-weighted | 8 eligible products with a real rating, 2 or more sellers | Never shows "0 reviews" |
| Pre-order | existing pre-order windows | 1 or more open window | Existing rule |
| Plus | membership | on sale in the market | Flat band |
| Sign-in, sell with us, sponsored | existing | existing | Sponsored never counts toward eligibility of an organic module |

### 3.2 Eligibility thresholds as settings

Stored in content's site config as `home.modules.<key>` with fields `enabled`, `minProducts`, `minSellers`, `minUnits`, `unitMinProducts`,
`unitMinSellers`, `maxAgeDays`, `weight`. Defaults are `DefaultHomeRules()` in the prototype. Edits are audited, validated (no negative values, weight
0 to 100), applied within the cache TTL, and rolled back by reverting to the previous version. To avoid flapping at a boundary a module that is shown
stays shown until it falls two products below its minimum (hysteresis).

### 3.3 Ordering logic

1. Hero first. 2. Signed-in with a signal: continue shopping then recently viewed. 3. Shown modules by `weight` (staff), then a relevance bump (seasonal
calendar match, shopper's top department, location rules), then freshness. 4. Hard rules: at most one sponsored module per three organic ones; paid modules
never fill a thin page; no two product rails of the same department adjacent; minimum 3 modules where possible, maximum 12; no product twice (existing
`used` map). 5. Staff-pinned placements keep their priority and win ties. A/B variants change weight or thresholds for one module key.

### 3.4 What the page looks like at 0 / 10 / 100 / 10,000 products

| Catalogue | Page |
|---|---|
| 0 products, 0 providers | Hero (approved campaign or the text-only brand hero), "Sell on AfroTransact" band, sign-in and Plus bands. No tiles, no rails, no communities. Search box and nav stay; nav lists only Services if providers exist, plus Sell. Valid, honest page, no demo data |
| 0 products, some providers | The above plus Services module (providers near the visitor, else popular) |
| 10 products, 2 or 3 departments | Hero, department tiles for the 2 to 4 departments that have products (one real photo each), "Just landed" rail only if 4 or more, Services if any. No deals, top sellers or communities |
| 100 products, 10 sellers | Adds groceries rail, deals (if 6 qualify), new arrivals, top sellers (rated), communities (only if 3 qualify), "Get it by {day}" and "near you" rails |
| 10,000 products | Full page capped at 12 modules; per-department "Top in {department}" for the biggest departments; personalisation and seasons drive order; below-the-fold modules stream in |

### 3.5 Department coverage and communities

- **Nav and tiles from the taxonomy.** `GET /v1/site/departments` (new, from catalog top-level + eligible counts) returns `[{key, name, labels, count, href}]`.
  The bar shows the 7 largest by count plus an "All departments" menu listing every department with 1 or more eligible product, and Services whenever any
  provider exists in the market. A department with 0 eligible items and 0 live listings is not linked (no dead links); `/s?dept=` for it answers an
  empty-state with "Be the first seller" only if staff pin it as launching (open decision). Display names come from i18n keyed by slug, falling back to the catalog name.
- **Services first-class.** Module and nav entry driven by bookings counts; independent of any physical product.
- **Communities are an optional facet, not a module of the page's identity.** Source: the `community` attribute facet. An optional content "community
  registry" (slug, labels in 5 locales, aliases such as "nigerian" to "Nigerian", optional editorial image) cleans the keys; with no registry the value is
  used as is. A community is **visible** when it has 8 or more eligible products and 2 or more sellers (settings). Below that: not on the home, not in the
  footer or nav, absent from the sitemap, and `/communities/<slug>` answers 404 (or redirects to `/s` with a notice) instead of "0 results". Tile photo is the best
  eligible product of that community; an editorial image (staff upload) overrides it, and only when it is a real photo.
- **Existing community photos** are demo assets: not shipped to production. Keep them only if staff upload them into the registry as editorial images.

### 3.6 API shape (gateway-bff home composition)

Additive to `GET /v1/home` (the `blocks` list stays; tiles become `blocks` of type `department-tiles`, `community-tiles`, `service-cards`):

```json
{ "version": 2, "generatedAt": "2026-11-12T10:00:00Z", "locale": "fr",
  "deliverTo": {"city": "Houston", "state": "TX"},
  "blocks": [
    {"id": "department-tiles", "type": "department-tiles", "order": 1, "scope": "shared",
     "titleKey": "home.departments.title", "titleParams": {},
     "cells": [{"label": "Groceries & Food", "labelKey": "dept.groceries-food", "href": "/s?dept=Groceries+%26+Food", "imageUrl": "https://...", "atin": "AT00000P14"}],
     "tracking": {"module": "department-tiles", "position": 1, "variant": "control"}}
  ] }
```

Staff only: `GET /v1/staff/home/preview?zip=77042&signedIn=false&locale=en&asOf=2026-12-01` returns the same blocks plus `decisions`:

```json
{ "decisions": [
  {"module": "communities", "label": "Shop by community", "shown": false, "reason": "not-enough-products",
   "detail": "Shop by community needs 3 communities with 8 eligible products and 2 sellers each; 1 qualifies.",
   "metrics": {"qualifying": 1, "required": 3}, "units": ["Nigerian"]} ] }
```

`scope` is `shared` (same for every visitor of market + region + locale) or `visitor` (continue shopping, recently viewed, targeted by signed-in). Contract
goes in `contracts/openapi/gateway-bff.yaml` first. Reasons are codes plus a sentence naming modules and units, never ids.

### 3.7 Caching and invalidation

- HTML stays uncached (`seo.md` decision 6). Cache **data**: the shared composition keyed by `market + region + locale (+ variant)`, 60 s fresh and
  300 s stale-while-revalidate at the gateway (in-process single flight, Valkey when more than one replica) and through the Worker's `fetch` cache for the
  gateway call. Visitor modules are fetched separately (server island in Suspense for signed-in, client for recently viewed) so one visitor's state never
  enters the shared entry.
- The signals snapshot has its own 60 s cache. Invalidation: any of `catalog.product.live`, stock-out, offer change or content publish events (Kafka) bumps a
  version key; TTL alone is acceptable at launch because a 60 s lag only delays a module appearing.
- Region granularity is state (not ZIP) for shared modules; ZIP-level rules (near you, get it by) stay visitor-scoped.
- Compose cost: one facet query for signals plus one fetch per shown module. Target compose p95 of 350 ms uncached and 60 ms cached.

### 3.8 Staff "Home preview"

In the staff console, Marketing > Home: left, the page as shoppers see it for a chosen ZIP, signed-in state, language and date; right, every module by its
name with shown or hidden and the sentence ("Communities: hidden. Needs 3 communities with 8 eligible products and 2 sellers each; 1 qualifies"), the live count
against the threshold, and a link to edit the rule. A "simulate a catalogue" switch replays the 0 / 10 / 100 / 10,000 fixtures so staff can see the thin
states before launch. Reads only; no PII.

### 3.9 Analytics events

`home_module_impression` (module key, position, variant; fired once per page view when 50 percent is visible for 500 ms), `home_module_click` (plus target
kind: product, department, community, service), `home_module_add_to_cart`, and module-level conversion by attributing an order to the module of the last
click in the same session within 24 hours (the link carries `hm=<module>:<position>`). Consent-gated like other analytics; anonymous aggregate counters
(module, day, locale, variant) with no shopper identity, as the paid-module counters do today. Gateway metrics: `home_module_eligibility{module,shown,reason}`,
`home_compose_seconds`, `home_signals_age_seconds`; a shadow-mode mismatch counter during rollout. Alert when shown modules fall below 3 for 15 minutes.

### 3.10 Accessibility, i18n, performance

- **Accessibility.** Each module is a `section` named by its `h2`; one `h1` (hero or brand line); DOM order equals visual order (no CSS reordering); tiles
  are links with text labels (photos decorative, `alt=""`); carousels keep keyboard, pause and reduced-motion behaviour; RTL for Arabic with logical
  properties; hidden modules are not rendered at all (no empty landmarks); focus is never moved by late-loading islands; contrast per tokens.
- **i18n (en / fr / es / ar / yo).** The gateway sends keys and params (`home.deals.title`, `{city}`, `{department}`), not English sentences; the storefront
  resolves them. Staff-authored copy is a locale map with English required and fallback to English. Department labels by slug in i18n; community labels from the
  registry. Seasonal names are keys too. No hard-coded English in Go response fields.
- **Performance budget.** LCP at or under 2.5 s p75 on mobile (goal 2.0 s) with the LCP element being the first hero or tile photo (`fetchpriority=high`, sized,
  from the image resizer), one eager image only; CLS at or under 0.05 (hidden modules are decided server-side before the first byte, so nothing pops in; visitor
  islands render only when a cookie hint says a signal exists, in a reserved-height slot); route JS for the home at or under 90 KB gzip beyond the shared
  shell (CI-enforced); below-the-fold modules stream (Suspense) and lazy-load images; at most 12 modules.

### 3.11 Migration path from the rigid modules

1. Ship eligibility in **shadow mode** (`home.dynamic=shadow`): compute decisions, log differences, show nothing new.
2. End the Flyway-seeded community tile, demo heroes and festive announcements for production with a new content migration (only rows never edited by
   staff); dev and demo keep them through `make seed`. Add a CI test that production seed data contains no fixed community cells.
3. Fixed cells and hero slides must pass the same photo check or render text-only; communities and department tiles switch to generated cells.
4. Replace `DEPARTMENTS_LIST`, the footer list and `tileDepartments` with the departments endpoint; delete the literals.
5. Turn on `home.dynamic=on` per environment (dev, uat, then prod at 10, 50, 100 percent), with the old composition as the kill switch for one release.
6. Existing community images: dropped from production; kept in seed for demos; staff may upload them as optional editorial images in the registry.

## 4. Comparison notes by concern

- **Cost.** Option 3's runtime cost is lower than today's: the snapshot is one query and hidden modules are not resolved.
- **Search / indexer.** The index must carry a boolean `photo_ok` (real photo, computed at index time from the same rule as `eligibleCard`) so facet counts equal
  what the home can show; today counts include photo-less products. Seller distinct counts need a `seller_id` facet or a cardinality aggregation
  (Meilisearch and Elasticsearch differ; see search-providers.md).
- **SEO.** The guest home HTML contains the same shared modules every time within the TTL; hidden communities leave no nav, footer or sitemap trace and 404
  instead of thin pages; internal links point only to pages with products.
- **Observability and A/B.** Variant assignment at the Worker (cookie) so the cache key includes the variant; keep experiments to threshold, weight and order.

## 5. Risks

| Risk | Mitigation |
|---|---|
| Thresholds too strict leave a bare page | Floor of 3 modules where possible; New arrivals and department tiles have low minimums; shadow mode and the simulator before launch |
| Facet counts overstate (photo-less, sponsored duplicates) | `photo_ok` index field; story gates everything else on it |
| Spelling variants split communities | Community registry with aliases; case-insensitive keys; staff merge tool later |
| Modules flap at a boundary | Hysteresis of 2 products and a 60 s snapshot |
| Cache shows one visitor's data to another | Shared composition contains no visitor fields by construction; contract test that `scope: shared` blocks carry no personal data |
| Personalisation without consent | Server-side history only for signed-in shoppers with consent; browser history stays local |
| Services card claims | Copy says what the provider offers and where; no claim that AfroTransact performs the service |
| Seasonal copy promises stock | Season boosts ordering only; copy comes from staff and is shown only with eligible products |
| Production seed content already live | Migration in step 2 of section 3.11; verify prod content before release |

## 6. Open decisions for the owner

1. Communities: confirm "hidden until 3 communities each have 8 products and 2 sellers" as the default, and that footer, nav and sitemap follow it.
2. Existing community photos: drop from production and allow optional staff-uploaded editorial images only?
3. Departments with nothing in them: not linked at all, or a "launching soon" page with a seller call to action?
4. Department names: keep the short bar names ("Groceries", "Beauty") as display labels over the catalog's full names, or use the catalog names?
5. Services: show it in the nav and home whenever any provider exists in the market, or only when one serves the visitor's ZIP?
6. At 0 products, is a text-only brand hero plus "Sell on AfroTransact" acceptable, and what wording?
7. Approve ending the Flyway-seeded demo placements (community tiles, heroes, announcements) in production.
8. Seasonal calendar: who owns it (staff), and which seasons first (Diwali, Detty December, Eid, back to school, Black Friday)?
9. Personalisation: server-side history for signed-in shoppers only (recommended), or also guests with consent?
10. Sponsored cap: one sponsored module per three organic modules as the default?
11. A/B platform: use the edge cookie approach in this spike, or adopt a third-party experiment tool?
12. Wishlist signals: is a wishlist planned (it would feed relevance), or should it be left out of scope?

## 7. Prototype (proof only, not wired)

`go/services/gateway-bff/internal/domain/home_eligibility.go` is a pure function (`HomeEligibility.Evaluate`) from a `HomeSignals` snapshot and a rule table to
per-module decisions with reason and a staff sentence; `DefaultHomeRules()` holds the proposed thresholds. `home_eligibility_test.go` has fixture tests for
0 / 10 / 100 / 10,000 product catalogues, the community threshold (including a nameless and a below-threshold community), staleness and staff switch-off, and
guest versus signed-in ordering. Run: `cd go/services/gateway-bff && go test ./internal/domain`. Nothing calls it; live home behaviour is unchanged.

## 8. Rollout

| Phase | Content | Exit criterion |
|---|---|---|
| 0 | `photo_ok` index field, signals snapshot, shadow eligibility, metrics | Shadow decisions match the live page in 95 percent of sampled loads, mismatches explained |
| 1 | Content cleanup migration; generated department tiles, nav and footer; communities facet with 404 and sitemap rules; Services module | No dead community or department links in e2e; 0 / 10 / 100 fixtures green |
| 2 | Staff settings and Home preview; schedule and preview as-of date | Staff can explain every hidden module without engineering |
| 3 | Personalisation, seasons, module analytics, caching split, A/B | LCP and JS budgets green in CI; analytics dashboards live |

Stories: S415 to S430 in `docs/backlog/backlog.csv` (E04, E15, E20, E23, E25, E28, E31, E33).
