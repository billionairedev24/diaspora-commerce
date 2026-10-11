# Missing pages audit (S280)

**Why.** Shoppers reported pages that still said "coming soon" although every design is done. This audit crawls the
running apps, lists every link that led to a placeholder, a 404 or an empty stub, maps each to its design, and records
what was built and what is left. Items that exist only to navigate the prototype (the design's page switcher, Theme
Studio links, "open the Legal prototype" jumps) are not routes and are not rendered.

**How.** A Playwright crawl of the local stack (storefront :3000, Seller Center :3001, staff console :3002, BFF :8200)
signed in as the S276 test users (`customer.houston`, `seller.owner`, `provider.owner`, `staff.admin`). It opens the
account menu, then collects every `<a href>` on the home page, a product page, search, services, cart, every account
page, orders, about, careers, legal and pre-orders (storefront) and every sidebar module (back offices), tags each link
with its landmark (header, department bar, menu, footer, side nav, main) and opens each one, classifying it as OK, 404,
the ComingSoon placeholder or a "not available yet / next release / coming soon" notice. Designs read:
`AfroTransact Shop.dc.html` + `at-site.js`, `AfroTransact Backoffice.dc.html` + `backoffice-config.js` +
`backoffice-data.js`, `AfroTransact Provider Hub.dc.html`, `InventoryHub.dc.html`, `AfroTransact Catalog.dc.html`,
`AfroTransact Mobile App.dc.html`, `AfroTransact Legal.dc.html`, `design/chats/` and `design/HANDOFF.md`.

## Gap list

| Route | Linked from | Design source | State before | Backend needed | Now |
|---|---|---|---|---|---|
| `/help` | footer "Help center" | Shop `view === 'info'`, `INFO.help` (Track a package, Returns, Contact us) | ComingSoon placeholder | help pages in content-service | **Built** — hub + help pages list (topics, infinite scroll) |
| `/help/shipping` | footer "Shipping & delivery" | Shop `INFO.shipping` (Carrier shipping, AfroTransact Delivery, Pickup) | ComingSoon placeholder | same | **Built** |
| `/help/press` | footer "Press" | Shop `INFO.press` (Media enquiries) | ComingSoon placeholder | same | **Built** |
| `/help/advertise` | footer "Advertise your products" | Shop `INFO.advertise` (Sponsored products & banners) | ComingSoon placeholder | same | **Built** (copy points to ads@ until Seller Center → Advertising exists, see below) |
| `/help/fulfilled-by-afrotransact` | footer "Fulfilled by AfroTransact" | Shop footer → `INFO.shipping` | ComingSoon placeholder | same | **Built** as its own FBA page (the design reused the shipping page under "Make money with us") |
| `/help/returns` | help center list | Shop `INFO.help` › Returns + account › returns | did not exist | same | **Built** (footer "Returns & refunds" still goes to `/account/returns`, as designed) |
| `/sell`, `/provide` | header, footer, account menu | Shop `startSelling()` | **404** in the local stack: the storefront process had `SELLER_CENTER_URL=http://localhost:3001` while Seller Center ran on :3010 | none (environment) | OK after the stack restart (Seller Center back on :3001). Root cause: `local.sh` exports the URL at start, so a storefront started before Seller Center moved ports keeps the stale one — reported to the owner of `tools/dev/local.sh` |
| Today's Deals, AfroTransact Brand, departments, communities (`/communities/<name>`) | department bar, footer, home tiles | Shop `subnav`, `runAction('brand'|'comm:…')` | OK — design maps them to search filters | none | unchanged |
| Drive for AfroTransact | footer | Shop → `careers` with `jobTeam: 'Delivery'` | OK (`/careers?team=Delivery`) | none | unchanged (no separate driver application is designed) |
| Account pages (`/account`, returns, addresses, payments, security, bookings, orders) | account menu, account home | Shop `acctTab` home/returns/addresses/payments/security | OK | — | unchanged. Notifications and Lola preferences are designed only in the Mobile App (built there: `settings/notifications`) |
| Staff console sidebar (20 modules) | side nav | Backoffice `P.modules` | OK — no placeholder or 404 (the crawler's only "notice" hit is the *Coming soon* market status on Markets) | — | + Site content → **Help center** tab |
| Seller Center / Provider Hub sidebar | side nav | Backoffice seller / provider portals | OK — no placeholder or 404 | — | unchanged |

`ComingSoon` and every `soon.*` string are removed; `soon.home` / `soon.cart` (used on the 404 page, orders and
checkout) became `common.backToShop` / `common.goToCart`.

## What was built

- **content-service** (contract first, `contracts/openapi/content.yaml`): `help_article` table (Flyway V10, seeded
  with the design copy), `GET /v1/help/articles` (topic, text, opaque cursor), `GET /v1/help/articles/{slug}` (public,
  cacheable), `GET /v1/help/admin/articles` (content:site.view) and `PUT /v1/help/articles/{slug}` with
  `expectedVersion` (content:site.edit, audited). Hexagonal: `domain.help`, `HelpCenterService(Impl)`,
  `HelpArticleRepository` + JDBC adapter, `HelpController`.
- **gateway-bff** (`gateway-bff.yaml`, `gateway-bff-staff.yaml`): public `GET /v1/help/articles[/{slug}]`; staff
  resource `help-articles` (list, sheet with sections, edit, create — the page is one Markdown text split into sections
  at `## ` headings, saved with the version read last).
- **api-client**: Zod schemas, client methods, `helpKeys` factory, `helpQueries.article` / `infiniteArticles`, mock BFF
  handlers and staff mock resource.
- **Storefront**: `/help` (hub + topic chips + article cards loading as you scroll) and `/help/[slug]` (Shop info view:
  820px column, black title, bold headings over muted text, breadcrumb); Markdown via the legal centre's safe renderer.
- **Staff console**: Site content → Help center tab (shared resource table).

## What is left (designed, not built, never linked — so no dead link today)

| Screen | Design | Backend today | Why not now |
|---|---|---|---|
| Seller Center → Advertising (`sAds`) | Backoffice seller portal | content-service ads campaigns API exists (`ads:manage`) | No BFF seller routes or UI yet; next in priority |
| Provider Hub → Reviews (`pReviews`) | Backoffice provider portal | none | needs a reviews service/model |
| Staff: Organisations (`orgs`), Fulfilment ops (`fulfil`), Plans (`plansCfg`), Coupons, Pickup points & zones, Storefront rules (`storeCfg`), Feature flags (`flags`), Shipping carriers, Stripe compliance, Module access (`access`) | Backoffice `P.modules` | flags: content-service has the API; others partly in seller/payments | Not in the console's module matrix yet, so nothing links to them; each is a module of its own |
| Staff record notes ("Internal notes aren't available yet" in every side sheet) | Backoffice sheets | none | a notes store per owning service |
| Storefront "Today's Deals" as its own page | Shop maps it to search sorted by price | — | the design itself uses search; no separate page designed |

## Crawl results

Before (first crawl, signed in as `customer.houston`): the header, department bar, account menu and home tiles were all
OK except `/sell` and `/provide` (404, environment). The five footer help links rendered the ComingSoon placeholder
("Coming in the next phase"); that crawl's classification of them was cut short by a browser crash and then the
container restart, so their state is confirmed from the route (`help/[[...topic]]/page.tsx` rendered `<ComingSoon>`).

After (same crawl, final run on the restarted stack):

| App (signed in as) | Links | 404 / placeholder / notice | Notes |
|---|---|---|---|
| Storefront (`customer.houston`) | 126 (39 in header, department bar, account menu, footer) | **0** | `/help`, `/help/shipping`, `/help/press`, `/help/advertise`, `/help/fulfilled-by-afrotransact`, `/sell`, `/provide` all 200 with their page title |
| Seller Center (`seller.owner`) | 12 | 0 | |
| Provider Hub (`provider.owner`) | 13 | 0 | |
| Staff console (`staff.admin`) | 52 | 0 | one false positive: Markets shows countries whose *status* is "Coming soon" |

Still visible in the back office (not a page, so not in the table above): record side sheets in the staff console say
"Internal notes aren't available yet" — record notes need a notes store in the owning services.
