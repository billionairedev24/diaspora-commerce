# storefront

Seller storefronts and service-provider public pages. Go 1.27.2 · PostgreSQL · local port **8206**.

- **Theme vetting on the server.** `internal/domain/color.go` is a faithful port of the Theme Studio engine
  (`design/at-theme.js`): OKLCH conversion, 50–900 ramps, WCAG 2.2 contrast, `Fit` (move lightness until a target ratio)
  and `OnColor`. Body text and button labels on primary/accent are **blocking**; primary/accent used as text or UI
  edges get an auto-fix suggestion. `POST /v1/themes/vet` powers the live studio; save and publish re-run it, so a
  theme that fails contrast can never be published whatever the client sends. Checkout, prices and the header always
  stay platform-styled.
- **Layout.** Sections per kind (seller: hero, featured products, categories, banner, about, reviews, pickup info;
  provider: hero, services, gallery, featured work, about, reviews, social), toggled and ordered, within platform limits.
- **Provider pages.** Layout (Classic, Gallery first, Story), header (cover photo or colour band), button shape, a
  gallery limited by plan (Starter 6, Growth 12, Pro 24) with a chosen display size, up to 6 featured-work posts
  (draft/published, optionally linked to a bookable service).
- **Social and catalogue links** (Instagram, TikTok, Facebook, YouTube, WhatsApp, X, LinkedIn, website) must point at
  the declared network over https, start as `PENDING`, and appear publicly only after staff approval
  (`storefront:moderate`). Editing an approved link sends it back to pending.
- **Draft and published snapshots** with optimistic versions; publishing purges the Cloudflare cache for the page.
  Unique store address (`slug`).

Contract: [`contracts/openapi/storefront.yaml`](../../../contracts/openapi/storefront.yaml).
Config: `DB_URL`, `PUBLIC_WEB_URL` (storefront origin for cache purges; shared setting injected by the Helm library), `CLOUDFLARE_ZONE_ID`, `CLOUDFLARE_API_TOKEN`, `AUTH_*`; limits and plan gallery sizes
in `config/<env>.yaml`.

```bash
cd go/services/storefront && GOTOOLCHAIN=go1.27.2 go test ./...   # colour maths vs WCAG values, rules, Postgres flow
```
