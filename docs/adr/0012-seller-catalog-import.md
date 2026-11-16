# ADR 0012 — Seller catalog import ("Bring your catalog")

- **Status:** Proposed (spike result; needs the owner's decisions Q1 to Q10 in the spike, and counsel review of Q4)
- **Date:** 2026-11-16
- **Related:** `docs/engineering/catalog-import-spike.md` (research, options matrix, design, plan), `docs/engineering/bulk-upload-and-images.md`,
  `docs/engineering/listing-rules.md`, `docs/engineering/tax-gate-paths.md`, `docs/engineering/release-1-architecture.md` and ADR 0011 (modular monolith, on `release-1`),
  ADR 0010 (no OpenFGA), `docs/runbooks/ai-spend-and-mcp-access.md`

## Context

A seller refused to re-create her products because she already has a website. Sellers on Shopify, WooCommerce, BigCommerce, Squarespace, Wix, Amazon, Walmart, a custom site or a spreadsheet will say the same.
We need one answer for any platform, known or not, that never lets an import bypass the tax decision, photo, approval and stock gates, that is safe against SSRF and credential leaks, and that
works identically on the microservice lines (`main`, `develop`) and the modular monolith (`release-1`). Facts about platform APIs and terms were researched from official documentation (as search summaries; the
sandbox could not open the developer sites) and are tagged by confidence in the spike.

## Decision (proposed)

1. **Export- and authorisation-first.** The primary routes are the seller's own export or feed (works for any platform), an authorised connection (OAuth or a read-only key she creates), or staff-assisted import with her recorded
   consent. Public reading of a site is the **exception**: only for sources she proves she controls (DNS TXT, meta tag or well-known file; or, for a store inside someone else's domain, a code in her own store profile) and only where
   the source policy allows; `robots.txt` honoured; path-scoped. **Never scrape Amazon or Walmart listing pages** (official seller APIs or seller-downloaded exports only). Shopify storefronts are **denied** for automated public reading by default (terms), pending counsel.
2. **A new bounded context `catalog-import`**, Java 25 / Spring Boot 4.1, hexagonal, schema-per-module (`catalog_import`), Temporal workflows from Phase 1, outbox events on `at.catalog-import.events`. It owns connections, credentials, consent, staging,
   mapping rules, the external-id mapping and sync. It does **not** own products: it creates **drafts only** through a new published `ProductDraftApi` in catalog (`POST /v1/products/drafts:batch` on the microservice lines, the in-process `catalog-api` interface in `afrotransact-core`), which wraps `ProductWriter`
   and leaves `ProductLiveGate` the only way to LIVE.
3. **`SourceConnector` plug-in SPI** (spike 5.3): a new platform is a class or descriptor with a conformance kit; the core never changes. Connectors have no HTTP client, DB or secret handling of their own.
4. **One egress door**: third-party fetches happen only in the hardened Go fetcher (media's `urlfetch`, extracted and exposed to service callers as `POST /v1/fetch/document`), out of process from the importer, so SSRF cannot reach the importer's secrets.
5. **Mapping** is deterministic first (learned rules, synonyms, the existing search-first matching), AI second and capped (own feature budget under the USD/day cap; defer rather than fail), a person last; nothing is hard-coded per category.
6. **Sync** (Phase 1+): webhooks plus a reconciliation poll; field-level conflict rules (external wins for price and stock while linked, with a price guard and a stock buffer; AfroTransact wins for copy and images after first import; new items as drafts; removed items paused, never deleted).
   **No two-way inventory and no order or customer import before Phase 4**, and not promised before the owner decides.
7. **Credentials** are envelope-encrypted per connection (key from `CATALOG_IMPORT_DATA_KEY`, rotation with `..._PREVIOUS`), never logged or returned, read-only scopes in Phases 0 to 3, revoked on disconnect.

## Options considered

| Option | Outcome |
|---|---|
| A. Extend the existing bulk upload only (file import) | Best first slice and **kept** (Phase 0 reuses it), but it cannot hold credentials, run long syncs or survive platform changes alone |
| B. New `catalog-import` module (chosen), feeding catalog drafts | Isolates credentials, external HTTP and long jobs; reuses gates |
| C. Go service for catalog-import | Rejected: in `release-1` a Go module cannot call `catalog-api`/`offers-api`/`seller-api` in-process, forcing internal HTTP, against the monolith rule; Java has Temporal SDK and shared libs |
| D. Public scraping as the main route (`/products.json`, page crawl) | Rejected as primary: ToS risk (Shopify bars robots and scrapers), unreliable, no stock, ownership issues; kept only as a gated, policy-controlled exception |
| E. Egress: in-module Java fetcher behind a forward proxy (option A of spike 4.3) | Acceptable alternative if the owner does not want a new media route; weaker isolation inside `afrotransact-core` |
| F. Orchestration: existing resumable job (row lock + scheduler) instead of Temporal | Fine for Phase 0 files; insufficient for sync timers and backoff; Temporal chosen for Phase 1 |
| G. Third-party aggregators (feed or migration SaaS) | Not researched in the spike; adds a vendor with seller data; revisit only if connector maintenance becomes the bottleneck |

## Consequences

* New deployable on the microservice lines (the launch set grows from 14 to 15 Java services; **capacity on the one prod node must be re-checked**, `check_capacity.py`); a module in `afrotransact-core` on `release-1`, following the module checklist in
  `release-1-parity.md`. Both lines ship the same behaviour, contract and schema; parity gates apply.
* New contracts: `contracts/openapi/catalog-import.yaml`, additions to `catalog.yaml` and `media.yaml`, gateway-bff seller/staff routes, `at.catalog-import.events`; new permissions `catalog:import`, `catalog:import:assist`, `catalog:import:policy`; new scope `catalog:draft.write`, `media:fetch`; new ai-gateway feature `catalog-import`.
* The AI cap (USD 2/day global, 0.50/caller) constrains AI features; an import budget or an audited temporary raise is likely needed (Q5).
* Third-party approvals (Shopify app review, Amazon developer registration and roles, Etsy commercial access, Wix and Squarespace OAuth apps) are external lead times the plan cannot control.
* Legal: counsel must review public reading, photo rights and the Amazon and Walmart data terms before the corresponding phases.
* Review queue, not "approve all": regulated kinds need data no platform provides (ingredients, label photo, dangerous goods, package size), so "publishable without edits" will be low for beauty, health and food; the metric to improve is fields asked per item.
* No MCP tool is added (one proposal, I1, is recorded as proposed only).
