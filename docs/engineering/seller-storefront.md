# Seller storefront (`/b/<slug>`)

The seller's public page is their own branded mini-store, not a marketplace page. Story S490. It replaces the plain
page of [business-pages.md](business-pages.md) for sellers, and since S493 **providers have the same builder** (see below). A provider who has not published keeps the old plain page (Stripe always has a website); an unpublished seller is a 404.

## What a shopper gets

Own header (logo, name, search in the store, category tabs from the seller's live categories, cart and account links that go to the
marketplace `/cart` and `/account`), a hero, then the sections the seller switched on in the order they chose (featured, categories,
story, shipping and returns, reviews), **All products** (search, category, price, sort, "Show more"), quick view, **Add to cart** and
**Buy now** on every tile (same cart and checkout as everywhere), and a minimal footer (contact, social, "Sold on AfroTransact" linking
home). The marketplace navbar, department bar, deliver-to picker, big footer and support chat are not rendered: the `(store)` route group
has its own layout (`web/apps/storefront/src/app/(store)`), and a test fails if it imports them.

Rules kept from the owner: flat (no cards), only in-stock products with a real photo, never "0 reviews" (no rating row, no summary without
reviews), no invented claims (the facts are "Verified seller" for an approved seller and "On AfroTransact since <year>" from the organisation's
creation date; the review summary is a weighted average of real product reviews), no carrier names, nothing about AfroTransact collecting or
delivering, white canvas by default, fonts only Inter and Fraunces (heading font per look).

## Data model

One JSON document, `StoreConfig` (`contracts/openapi/gateway-bff*.yaml`, schema version 1): `theme {preset, accent}`, `hero {style image|colour|split,
tagline, bannerUrl, focal}`, `announcement`, `grid {3|4}`, `sections` (order + enabled of announcement, featured, categories, about, policies,
reviews), `featured` (ATINs, at most 8), `categories {auto|chosen}`, `about`, `policies {shipping, returns}`, `contact {showEmail, phone, hours}`,
`pickup {show}`, `social` (at most 6). The logo and brand colour stay in Business profile (one source).

Stored by the seller service (`V92__store_page.sql`): `store_page` (draft, published, `version` for optimistic locking, who/when) and
`store_page_audit` (DRAFT_SAVED, PUBLISHED, UNPUBLISHED, actor, version). The seller service stores opaque JSON (max 32 KB); **gateway-bff owns the schema**.
Saves send the draft `version`; a stale one is 409. Publishing needs an approved organisation (422 `STORE_NOT_APPROVED`) and copies the draft to the
published slot. Seller endpoints `/v1/seller/store-page` (GET, PUT draft), `/publish`, `/unpublish`, `/audit`, `/preview` (the storefront built from the
draft), `/featured-search`; public `GET /v1/storefronts/{slug}`, `/products`, and `GET /v1/storefronts` (sitemap list). Members read with Team view; the
owner and admins change (Team edit; the seller service re-checks). Sellers only.

## Themes (`@afrotransact/tokens`, `storefront-theme.ts`)

Six looks, each a token set: Clean, Warm market, Bold, Midnight, Heritage, Fresh (palette, radius, heading font and weight, default hero and grid, tile ratio).
The page root gets CSS variables (`--sf-*`, and the platform `--at-color-*` re-pointed so shared pieces re-theme); no component holds a colour.
The seller's accent is **data**: the picker outputs `nearestSafeAccent` (same hue, darker or lighter until AA, 4.5:1) against the look's canvas **and** raised
surface; text on the accent (`onAccent`) is computed, never chosen; the business brand colour is the default accent (made safe). The BFF refuses an accent that
fails (422 `STORE_ACCENT_LOW_CONTRAST`) using the same palette numbers (`presetCanvas`/`presetSurface` in Go; a test fails if they drift from TypeScript).

## Security

- **Text**: plain text only (line breaks; blank line = paragraph), length-capped; text that looks like markup is refused on write (422) and dropped on read; React escapes it anyway. No rich text.
- **Links**: https only, no credentials, no IP/localhost, social links on the network's own hosts; the announcement may link to a page of the store or an https URL.
- **Images**: only AfroTransact media. The allowed prefixes DEFAULT to the media public URL, the same `MEDIA_PUBLIC_URL` the media service builds asset URLs from (Helm derives it from `global.mediaHost`; local `http://localhost:9000/media/`). `STORE_MEDIA_PREFIXES` (comma-separated) only adds extra prefixes. gateway-bff logs the list at startup. Anything else is refused on write and removed on read.

| Environment | Media host (`MEDIA_PUBLIC_URL`) | Set in |
|---|---|---|
| local | `http://localhost:9000/media/` | `go/services/media/config/local.yaml` (what uploads return) and gateway-bff `config/local.yaml` |
| dev / uat | `https://media-dev.afrotransact.com`, `https://media-uat.afrotransact.com` | `global.mediaHost` of the environment |
| prod | `https://media.afrotransact.com` | `global.mediaHost`, `infra/argocd/envs/prod/platform-values.yaml` |

See [r2-buckets.md](../runbooks/r2-buckets.md).
- **Both directions**: the BFF validates on save and re-sanitises the stored config before every public response (a bad stored value is replaced by its default, never an error).
- Public responses are cached 60 s; preview and seller endpoints are never cached. No per-person data (the BFF searches as an anonymous caller).

## 404, redirects, SEO

An unknown, unpublished, unapproved or suspended store is a 404 page (`store-not-found`) with links home and to search. `/b/<Slug>`, `/b/<slug>/`, `/store|stores|seller|sellers|shop|business/<slug>`
301 to `/b/<lowercase slug>` (middleware). Indexing follows [seo.md](seo.md): only prod on the canonical host, only when the store is approved, published **and has at least one sellable product**,
never a filtered or sorted view (canonical is the plain page). JSON-LD `Store` (name, url, logo, description, social profiles, `aggregateRating` only with real reviews). The sitemap `stores` kind lists
`/b/<slug>` for published stores with live products (`lastmod` = publish time).

## Provider page (S493)

Same route, same `StorefrontView`, themes, accent rules, publish/unpublish, preview and history; the BFF picks the sections by organisation kind (`StorePolicy.Kind`):
a provider has `announcement, services, book, schedule, team, about, reviews` and none of the product sections (featured, categories, policies, grid, pickup, search, cart).
A seller config with provider sections (or the reverse) is 422. `Storefront.kind` and `Storefront.provider` carry the data, all from bookings' public provider view: services (in person or at home, no virtual),
weekly hours (the bookable team's hours merged; empty = none set, the schedule is hidden, never "closed all week"), the service area (base city, radius, served cities; no address, ZIP or coordinates), the bookable team, the cancellation policy.
**Booker**: pick a service, then the marketplace's own booking panel (real free days and times, add-ons, in person or at home, deposit now, Stripe); availability needs no ZIP; staff pick is not offered because the booking flow does not offer it today. The preview shows an inert description.
Reviews: bookings' aggregate over approved reviews, shown only when count >= 1. Indexing: only a published page with at least one service; JSON-LD is `ProfessionalService` with no invented hours or rating. Seller Center: Settings > Store page for provider orgs (Team view reads, Team edit changes), with a link to Provider Hub for services, hours and team.

## On sale, ratings, featured (S493)

- **On sale**: the index has `list_price_minor` and `on_sale` (variant `compareAtMinor` above the price of the variant the indexed price comes from); `onSale=true` on search and `/v1/storefronts/{slug}/products`. Tiles show the was-price only when above the price. The filter uses the catalog's value and the tile the offer's list price, so a rare mismatch shows a sale item without a was-price. Existing documents get the fields when re-projected: reindex after deploy. Elasticsearch gets the mapping through `LateMappings`; Meilisearch through its settings. Providers have no products, so it does not apply.
- **Rating**: catalog `GET /v1/public/sellers/{orgId}/rating` (average, count, breakdown over every published review of the seller's products); the page reads no products for it. Never shown below one review.
- **Featured** picks are looked up by ATIN (search `atins`, still the seller's own, sellable products), so none is lost beyond the first pages. The "Showing products in stock now" line is gone; in-stock-only stays.

## Seller Center: Settings > Store page

Own route, own save, one column on narrow screens (preview as a tab), editor and sticky preview side by side from 1280. The preview renders the real `StorefrontView` with the unsaved draft and
the seller's own sellable products (desktop or 390 px phone; the layout answers to the storefront's own width via container queries). The preview is built from the caller's own organisation and draft, never looked up by address, so it works before the page address exists (made when the payout account is set up; `slug` is empty) and before the first publish, for sellers and providers, with no featured products or no hero image (empty sections are simply absent); it is labelled "Preview, not published" and its buttons are off. Publishing still needs the address and an approved organisation. A failed preview shows a plain message with Try again. Status, Save changes, Publish (changes), Unpublish, View store page and History.
The banner has a focal point (what stays in view when each screen crops) rather than a server crop; pictures are validated (type, 8 MB, minimum size) before upload.

## Mock stack and tests

Mock BFF (`api-client/src/mocks/storefront.ts`): the demo seller **Laserfeet** (`/b/laserfeet`, mock data only, registered as unlisted products so cart and checkout work) and stand-in "studio shots" as SVG drawn by the mock BFF
(`/v1/mock-photos/...`); nothing of this exists outside the mock runtime. `MOCK_STOREFRONT_STATE_FILE` lets the Seller Center mock and the storefront mock share published stores across processes.
Tests: `tokens/test/storefront-theme.test.ts`, `gateway-bff` domain/app/httpapi tests, seller `StorePageServiceTest` and tenant matrix, `storefront/test/storefront.test.tsx`, `seller-center/test/store-page.test.tsx`,
`storefront/e2e/storefront.spec.ts`. Screenshots (viewed and fixed): `img/provider-storefront-{1280,390}.png`, `img/storefront-{warm-market,bold,midnight,heritage}-{1280,390}.png`.

## Not done / decisions

See the report in the pull request; the open items are in S490 and S409 (physical-store `LocalBusiness`).
