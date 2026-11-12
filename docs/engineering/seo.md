# Storefront SEO and indexing

Scope: `web/apps/storefront` (Next.js 16 on Cloudflare Workers). Operations (Search Console, Bing, monitoring, launch checklist):
[runbooks/seo-and-search-console.md](../runbooks/seo-and-search-console.md). Tests: `web/apps/storefront/test/seo-*.test.ts`, `e2e/seo.spec.ts`,
`tools/ci/test_seo_probe.py`.

## 1. Audit (what existed on 2026-11-12) and the gap list

| # | Area | Found | Gap | Status now |
|---|---|---|---|---|
| 1 | robots.txt | none | prod and every other host answered the same (no file, so crawl allowed everywhere) | built |
| 2 | sitemap | none | no sitemap at all | built (live data, chunked) |
| 3 | Environment safety | only the seller center / staff console had `robots: noindex` meta; the storefront had none; no `X-Robots-Tag` anywhere; `APP_ENV` was not set on any Worker | uat, dev, the 8-day trial on `test.<zone>`, workers.dev and previews were fully indexable | built (3 layers) |
| 4 | Metadata | root title/description only; product page had `title` + bullets as description and a relative canonical; business and help pages had canonicals; account pages `noindex` | no OG/Twitter cards, no canonical on about / membership / services / careers / runs / pre-orders / home, services and provider pages had a generic title, search page `noindex` only for `sort != relevance` | built |
| 5 | JSON-LD | Product on the PDP only; `InStock` hard-coded; price formatted `/100`; seller name | no BreadcrumbList, Organization, WebSite, Service; out-of-stock shown without a price; no images | built |
| 6 | hreflang | none | locales share one URL (cookie / `Accept-Language`): hreflang is impossible and not emitted | documented, not emitted |
| 7 | SSR | PDP, search, help, legal, business pages server-rendered with dehydrated data; service detail and provider pages were client-only (skeleton in the first HTML) | services and providers not indexable content | fixed (server prefetch + 404 when gone) |
| 8 | 404 / 410 | PDP and business pages 404 correctly (`notFound()`); service detail rendered a 200 "not offered" message | soft 404 on services | fixed; **410 is not produced** (the catalog answers 404 for removed and never-existed items alike; Google treats both as gone) |
| 9 | Redirects | legal and terms aliases 301; `/search` 307; no www/apex rule in code (stale comments said www) | no single-host enforcement | www to canonical 301 in middleware (prod); comments fixed; trailing slash handled by Next (308) |
| 10 | Image alt | product cards use the title; PDP main image fell back to the title only when `alt` was `null`, an empty alt made it decorative; service rows, order thumbs, logos use `alt=""` next to adjacent text | main product photo could be unnamed | fixed (empty alt falls back to the title); others are decorative duplicates of adjacent text, left |
| 11 | Core Web Vitals | images lazy by default; `ImagePlaceholder` already reserves the aspect ratio (no layout shift) | PDP main photo (LCP) was lazy with no priority | `loading="eager" fetchpriority="high"` on the main photo |
| 12 | Caching | HTML `no-store` (per-visitor: location, cart, ads), static assets immutable | no cache policy for robots / sitemaps | robots + sitemaps `s-maxage`; HTML deliberately left uncached (see 3.6) |
| 13 | Pagination | search is infinite scroll; `page=` in the URL was ignored (always page 1) | no crawlable links to page 2 | `page=` honoured; server-rendered Previous / Next links on category pages |
| 14 | Facets | `/s?...&sort=` noindex, other facets and free-text indexable | duplicate content | every `/s` except one department or one community is noindex with the category as canonical; robots blocks the parameter forms |

## 2. Environment safety (fail closed)

`src/lib/seo/policy.ts`: a request is indexable only when **`APP_ENV` is `prod` and the request host equals `PUBLIC_WEB_URL`'s host**. Anything else is noindex:
uat, dev, trial, a missing or misspelled `APP_ENV`, workers.dev, previews, `www`, a look-alike host, local runs.

| Layer | Behaviour | Covers |
|---|---|---|
| Worker middleware (`src/middleware.ts`) | `X-Robots-Tag: noindex, nofollow, noarchive` on every response it sees (pages, 404s, route handlers, the BFF proxy); in prod, on private paths (`/account`, `/cart`, `/checkout`, `/orders`, `/signin`, `/signup`, `/api/`, `/sell`, `/gift-cards`...) | dynamic responses |
| Markup (`layout.tsx`, `pageMetadata`) | `<meta name="robots" content="noindex, nofollow">`; a page cannot opt back in on a non-prod host | HTML |
| robots.txt / sitemap routes | `Disallow: /` with no `Sitemap:`; `/sitemap.xml`, `/sitemaps/*`, `/api/seo/revalidate` answer 404 | crawlers that read robots |
| Edge (`infra/cloudflare/zone.tf`, from `hosts.json`) | `X-Robots-Tag: noindex` on every host of every non-prod environment (static assets too), on prod seller / admin / api / auth / grafana / mcp, and on prod media for non-image content | assets, origins, back offices |
| Seller center, staff console | `robots: noindex` meta (existing) + edge rule | back offices |

`wrangler.jsonc` sets `APP_ENV` per environment (dev, preview, uat, **prod**, trial). The hosts are never written in code: `PUBLIC_WEB_URL` comes from the
per-environment Worker vars, which `tools/ci/env_hosts.py check` already proves equal `infra/cloudflare/hosts.json`.
`test/seo-env-safety.test.ts` parses both files and asserts for every environment and every host of the map that only prod's own storefront host is indexable, that only
the `prod` Worker block says `APP_ENV=prod`, that prod robots.txt does not block `/_next/static` or images, and that every response of a non-prod Worker carries noindex.
The OVH test Workers (`ovh-web.yml`) and the trial (`trial.yml`) write their own vars without `APP_ENV`, so they are noindex by default.

## 3. Decisions

1. **Canonical.** One per page, set in page metadata (relative path resolved against `metadataBase` = `PUBLIC_WEB_URL`), never carrying tracking, sort or filter parameters. Product variants canonicalise to the variant of the group with the **smallest ATIN** (the product detail has no explicit parent id: story S399). Category pages are `/s?dept=X` or `/s?community=X` (`&page=N` from page 2, each page its own canonical).
2. **Faceted URLs** (free text `q`, sort, price, rating, fulfilment, seller kind, delivery, department + community together) are `noindex, nofollow` with the category as canonical when there is one, and robots.txt blocks the parameter forms. A category with no products is noindex.
3. **Out of stock** (the product exists but `buyBox` is null or the offer is `OUT_OF_STOCK`): the page stays **200 and indexable** with `availability: OutOfStock` and the last known price of another offer (no price known: no Offer, no invented one) so ranking and inbound links survive a restock; it is **not in the sitemap**. A product the catalog no longer has: 404, gone from the sitemap on the next generation (at most 15 minutes). A service the provider no longer offers: 404 likewise.
4. **Sitemap eligibility** = the home / rails rule (`site_home.go` `eligibleCard`, ported to `src/lib/seo/eligibility.ts`): in stock, real photograph (no icon, emoji, svg, placeholder), not sponsored. "Live with a stored RESOLVED tax decision" is guaranteed upstream: only listings that passed `ProductLiveGate` are in the search index the sitemap reads (runbook CatalogLiveWithoutTaxDecision); held / in-review items are not.
5. **Structured data** states only facts the catalog holds: name, sku, brand, real photos, price and currency of the winning offer, availability, seller name. `AggregateRating` only with `reviewCount > 0` and `rating > 0` (never "0 reviews"); no `Review` items (reviews load client-side). **Never** tax claims, carrier names, delivery or "AfroTransact collects / delivers" (`test/seo-builders.test.ts` scans the output). `itemCondition`, `shippingDetails`, `hasMerchantReturnPolicy` are **not emitted** because the catalog has no structured data for them (free text only): stories S399 / S400. The builders support `condition`.
6. **HTML caching.** HTML stays uncached: every page carries per-visitor data (deliver-to location, cart count, sponsored slots, consent), and caching it at the edge risks serving one visitor's state to another. Crawl efficiency comes from fast server rendering, not from an edge cache. Robots and sitemaps are cacheable (`s-maxage` 15 to 60 minutes + `stale-while-revalidate`).
7. **Sitemaps.** `/sitemap.xml` is an index of `/sitemaps/{products,categories,stores,services,content}-N.xml`. Each child holds at most 50,000 URLs and 49 MB (limits enforced and tested; image entries counted). Generated in the Worker from the gateway (anonymous client, organic search so no ad impressions), cached 15 minutes per isolate with single-flight; a failing source keeps its previous version (`sitemap.kind_failed`), a kind that shrinks by more than half is held back for three generations (`sitemap.count_drop`); `POST /api/seo/revalidate` (bearer `SEO_REVALIDATE_TOKEN`) regenerates on change. Empty kinds are not listed. `lastmod` is emitted only where the source has a real update time (help articles, legal documents); products, categories and services have none yet (S398), and a missing `lastmod` is better than an invented one.
8. **Localisation.** en / fr / es / ar / yo share one URL, so there is no hreflang and no per-locale sitemap alternates (the renderer supports `xhtml:link`; turn it on with distinct `/fr/...` URLs). Titles, descriptions and pagination text are translated (`seo.*` keys, five locales; the non-English catalogs are machine drafts marked `needsReview`). Crawlers without `Accept-Language` index English.
9. **Hosts.** The host map has no `www`; prod is the bare zone. A request to `www.<canonical host>` 301s to the canonical origin (prod Worker only). Trailing slashes redirect (Next, 308).
10. **Home** carries `Organization` (logo `/icons/icon-512.png`) and `WebSite` with a `SearchAction` (`/s?q={search_term_string}`). Google retired the sitelinks search box in 2024; the markup stays valid schema.org. The target is a blocked, noindex URL on purpose.

## 4. What is not done (backlog)

| Story | Gap |
|---|---|
| S398 | Search index and sitemap feed carry no update time: real `lastmod` for products, categories, services; a keyset-paged `GET /v1/seo/sitemap-entries` feed beyond the search engine's result window (the current reader partitions by department and community and caps at 3,000 per partition) |
| S399 | Product detail lacks a parent id (variant canonical), item condition (new / used / refurbished), structured shipping and return terms, and slug history (product URLs are ATIN-only, so moved slugs do not exist today; 301s for renamed items need a history table if slugs are introduced) |
| S400 | No store / seller listing endpoint: the `stores` sitemap is empty; seller business pages (`/b/<slug>`) are indexable (approved only) but not discoverable from the sitemap; no physical-store pages, so `LocalBusiness` is built and tested but not wired |
| S401 | Localised URLs (`/fr/...`) and hreflang; Review items from stored reviews |

## 5. Observability

Worker logs (one JSON line each): `sitemap.generated` (URL counts per kind), `sitemap.kind_failed`, `sitemap.count_drop`; the last two also go to Sentry as warnings. `tools/ci/seo_probe.py` (workflow `seo-probe`, daily) probes the deployed hosts and fails when a non-prod host is indexable, a back office lacks noindex, prod robots / sitemap / a sampled URL is invalid, or the URL count falls below `SEO_MIN_SITEMAP_URLS`.
