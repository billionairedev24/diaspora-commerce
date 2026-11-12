# SEO and Google Search Console

How the storefront is kept out of the index everywhere except production, how it is put into Google, and how to watch it.
Design and decisions: [engineering/seo.md](../engineering/seo.md). Hosts: `infra/cloudflare/hosts.json` (one map, no `www`).

**The one rule.** Only `https://afrotransact.com` (the prod storefront) is indexable. uat, dev, the 8-day trial on `test.afrotransact.com`,
`workers.dev`, PR previews, the seller / admin / api / auth hosts and the media origin's non-image responses are `noindex`.
The trial MUST stay `noindex`: never verify it in Search Console, never submit a sitemap for it, never set `APP_ENV=prod` on it.

## What is in place (nothing to do)

| Layer | What | Where |
|---|---|---|
| Worker | `APP_ENV` per environment (only the prod Worker says `prod`); a request is indexable only when `APP_ENV=prod` **and** it arrived on the host `PUBLIC_WEB_URL` names. Every other case: `X-Robots-Tag: noindex, nofollow, noarchive` on every dynamic response, `<meta name="robots" content="noindex">`, `robots.txt` = `Disallow: /`, no sitemap (404) | `web/apps/storefront/wrangler.jsonc`, `src/lib/seo/policy.ts`, `src/middleware.ts` |
| Edge | Cloudflare response-header rule: `X-Robots-Tag: noindex` on every host of every non-prod environment (static assets included), on prod seller / admin / api / auth / grafana / mcp, and on prod media for everything that is not an image | `infra/cloudflare/zone.tf` (from `hosts.json`) |
| Prod robots | allow crawling (`/_next/static/`, images, scripts), disallow cart, checkout, account, orders, sign-in, `/api/`, internal search `?q=`, sort and filter parameters, tracking parameters; names `https://afrotransact.com/sitemap.xml` | `GET /robots.txt` |
| Sitemaps | `/sitemap.xml` (index) and `/sitemaps/{products,categories,services,content}-N.xml` (children of at most 50,000 URLs and 49 MB), generated from live data, cached 15 minutes in the Worker, `s-maxage` 15 min at the edge | `src/lib/seo/sitemap*.ts`, `src/app/sitemap*` |
| Drift tests | unit and e2e tests on every PR; a daily probe of the deployed hosts | `web/apps/storefront/test/seo-*.test.ts`, `e2e/seo.spec.ts`, `tools/ci/seo_probe.py`, workflow `seo-probe` |

## Owner actions (once, at launch)

1. **Verify the domain in Google Search Console** (domain property, covers every subdomain).
   1. <https://search.google.com/search-console> > Add property > **Domain** > `afrotransact.com`.
   2. Google shows a TXT value `google-site-verification=<TOKEN>`. Copy only `<TOKEN>`.
   3. Put it in `infra/cloudflare/envs/prod.tfvars`: `google_site_verification = "<TOKEN>"` (a public DNS value, not a secret) and apply the **prod** run
      (`tofu apply -var-file=envs/prod.tfvars`). It creates the TXT record at the apex. Or add the TXT record by hand in the Cloudflare dashboard (DNS > Records, type TXT, name `@`).
   4. Back in Search Console press **Verify** (DNS can take a few minutes). Keep the record forever: removing it un-verifies the property.
2. **Submit the sitemap index**: Search Console > Sitemaps > `https://afrotransact.com/sitemap.xml` > Submit. Status must be *Success*; the five child files appear under it. Do not submit child files separately.
3. **Bing Webmaster Tools**: <https://www.bing.com/webmasters> > **Import from Google Search Console** (no second verification), then Sitemaps > submit the same index. Bing also picks the sitemap up from `robots.txt`.
4. Optional: set the repository variable `SEO_MIN_SITEMAP_URLS` (Settings > Variables) to about 70% of the launch count so the daily probe fails when the sitemap shrinks.
5. Optional: set the Worker secret `SEO_REVALIDATE_TOKEN` (prod) so a catalog or content job can `POST /api/seo/revalidate` with `Authorization: Bearer <token>` to regenerate the sitemaps on change; without it the 15 minute cache is the only refresh.

Do **not** add a "Disallow" for anything to hide it from Google: use `noindex`. A URL blocked in robots.txt cannot show its `noindex`.

## Pre-launch checklist (run on the day prod goes live)

- [ ] `https://afrotransact.com/robots.txt` shows `Allow: /` and `Sitemap: https://afrotransact.com/sitemap.xml`, and does not show `Disallow: /` on its own line.
- [ ] `curl -sI https://afrotransact.com/ | grep -i x-robots` prints nothing; `curl -sI https://afrotransact.com/cart | grep -i x-robots` prints `noindex`.
- [ ] `https://afrotransact.com/sitemap.xml` answers 200 and lists the child sitemaps; open one: product URLs, `<image:image>` entries, no cart / checkout / account / `?q=` / `?sort=` URL.
- [ ] A product URL from the sitemap returns 200, has exactly one `<link rel="canonical">` (itself), a title and description unique to the product, `og:image`, and `Product` JSON-LD in "view source" (not only in the inspected DOM).
- [ ] `https://www.afrotransact.com/` is not a host we serve; if a DNS record for it exists it 301s to the apex.
- [ ] Every non-prod host: `python3 tools/ci/seo_probe.py --strict` prints `OK` (robots `Disallow: /`, no sitemap, `noindex`). **Especially `test.afrotransact.com` (the trial).**
- [ ] `APP_ENV` is `prod` only in the `prod` block of `web/apps/storefront/wrangler.jsonc`; `web/apps/storefront/test/seo-env-safety.test.ts` is green on `main`.
- [ ] Rich Results Test (<https://search.google.com/test/rich-results>) on one product, the home page and one category: Product / Breadcrumb / Organization detected with no errors (warnings for optional fields such as `itemCondition`, `shippingDetails`, `hasMerchantReturnPolicy` are expected: we only emit facts the catalog really has).
- [ ] Search Console property verified, sitemap index submitted, Bing imported.
- [ ] The `ovh-web` / trial workflows do not set `APP_ENV=prod` (they do not today: the OVH Workers are noindex by default).

## After launch: checks and monitoring

**URL Inspection** (Search Console, top bar): paste a product URL > *Test live URL* > *View tested page*: HTML must contain the JSON-LD and the canonical; *Screenshot* must show the product with its photo (proves `/_next/static` and images are not blocked). *Request indexing* for the home page and a few categories only; the sitemap does the rest.

**Weekly (first month), then monthly**

| Where | Look for | Act |
|---|---|---|
| Indexing > Pages | "Indexed" grows towards the sitemap count; reasons under "Not indexed" | *Crawled - currently not indexed* on products: check photo / description quality. *Duplicate without user-selected canonical*: a facet or tracking URL is being linked (see the canonical rules in engineering/seo.md). *Blocked by robots.txt* on a `/s?` URL: expected. *Excluded by noindex* on `/cart`, `/account`: expected; on a product: a bug |
| Indexing > Sitemaps | status Success, "Discovered URLs" close to the count in the daily probe, last read < 2 days | *Couldn't fetch* / *Has errors*: open the URL, check `curl -sI https://afrotransact.com/sitemap.xml` (200? 503 = the gateway was down at first build) |
| Experience > Core Web Vitals, Page experience | LCP < 2.5 s on product and category pages | main photo is `loading="eager" fetchpriority="high"`; large images come from the media origin |
| Enhancements > Products / Breadcrumbs / Merchant listings | valid items = product pages; "missing price" or "invalid availability" | the page's offer data; never add tax, carrier or delivery claims to fix a warning |
| Settings > Crawl stats | 5xx share, average response time | Worker or gateway health (HighErrorRate / HighLatency runbooks) |
| Security & manual actions | none | |

**Automated**: workflow `seo-probe` runs every morning (`python3 tools/ci/seo_probe.py --min-urls $SEO_MIN_SITEMAP_URLS`) and fails (GitHub notification) when a non-prod host is indexable or serves a sitemap, a back-office host lacks `noindex`, the prod robots / sitemap index / a child is invalid, a sampled sitemap URL is `noindex` / has two canonicals / lacks Product JSON-LD, or the URL count drops below the floor. In the Worker's logs (Workers observability, Sentry): `sitemap.generated` (URL counts per kind), `sitemap.kind_failed` (a source failed, the previous version is served), `sitemap.count_drop` (a kind shrank by more than half and is held back for three generations, then accepted).

**When a sitemap kind is missing or tiny**: `sitemap.kind_failed` points at the failing source (products: search; services: bookings; content: help / legal). The previous version keeps being served until it recovers. If the Worker restarted while the gateway was down there is no previous version and the index answers 503 with `Retry-After`: fix the gateway, then `POST /api/seo/revalidate` (if the token is set) or wait 5 minutes.

## Removing a URL

- A product that is gone: the catalog answers 404, the page answers 404 and the URL leaves the sitemap with the next generation (at most 15 minutes). For urgent removal use Search Console > Removals > Temporary removal (6 months), and let the 404 do the permanent part.
- A product that is sold out stays at 200, indexable, availability `OutOfStock`, and is not in the sitemap (engineering/seo.md, "Out of stock").
- Never use Removals for a page that should stay: it hides it from results for six months.

## Trial-safe note (the 8-day trial on `test.afrotransact.com`)

The trial runs the prod profile on real hosts but must never be indexed. It is safe because four independent layers fail closed: its Worker has `APP_ENV=trial` (anything but `prod` is noindex), its host is not the prod host, the zone's edge rule adds `X-Robots-Tag: noindex` to every `*-test` host and `test.<zone>`, and robots.txt is `Disallow: /` with no sitemap. Do not add the trial to Search Console, do not link to it from public pages, and run `python3 tools/ci/seo_probe.py --env trial --strict` before and after the trial. If the trial is moved to another zone (`env_hosts.py set-trial-zone`), re-run `web/apps/storefront/test/seo-env-safety.test.ts`: it reads the host map, so a new zone is covered by the Worker layer; the edge layer covers only the shared zone.

## Localised pages (hreflang)

The five locales (en, fr, es, ar, yo) are served from the **same URL** (cookie / `Accept-Language`), so there are no distinct localised URLs and **no hreflang is emitted** (Google ignores hreflang between identical URLs, and crawlers without a language header always see English). Non-English strings are machine drafts. When localised paths (`/fr/…`) are introduced, add them to the sitemap `alternates` (the renderer already supports `xhtml:link`) and emit `alternates.languages` in the page metadata in the same change.
