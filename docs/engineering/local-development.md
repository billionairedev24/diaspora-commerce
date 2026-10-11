# Local development

`make up` (= `tools/dev/local.sh up`) starts the infrastructure in Docker, the default services as host processes on
their fixed ports ([infra/local/PORTS.md](../../infra/local/PORTS.md)) and the three web apps, then prints every link.
`make dev-status`, `make dev-logs SERVICE=x`, `make dev-restart SERVICE=x` and `make down` manage what it started.

## Demo data: `make seed`

A fresh stack has an empty catalogue. `make seed` (`tools/seed/seed.py`, Python 3 standard library only) creates a demo
marketplace **through the services' public APIs**, so every event flows like real traffic (catalog → indexer →
Elasticsearch, offers buy boxes, inventory availability, content placements, pre-order windows):

| Step (`--only` / `--skip`) | Service | What |
|---|---|---|
| `sellers` | seller `POST /v1/orgs` | 13 marketplace sellers (Mama Put Pantry, Lagos Larder, Accra Market Co., Bonwire Weavers Collective, Kente & Co., Tamale Naturals, …) and 3 service providers, approved; offers, cart and the search indexer learn their names from `seller.org.*` events. AfroTransact Retail is the platform org. |
| `products` | catalog, media, offers, inventory | 75 products in every department of the nav (Groceries, Fashion, Beauty, Home & Art, Books, Health, Party; AfroTransact Brand items), tagged with a community (Nigerian, Ghanaian, Ethiopian, Indian, Jamaican, …), 2–4 photos each, variants, approved; offers per seller (some products have several sellers, some have list-price deals) and stock received into the fulfilment centres (FBA lots with dates for food). |
| `content` | content placements | Hero carousel, home tiles (communities, departments), display banners, product rails (deals, AfroTransact Brand, spices, fashion, beauty) and category banners, all with images. They replace the image-less placements of content's V2 migration. |
| `ads` | content ads campaigns | Sponsored-product campaigns for three sellers. |
| `preorders` | delivery-runs | Coverage for Lubbock and El Paso, an **open** Lubbock window and an **upcoming** El Paso one, with hero and PDP creatives. Set *Deliver to* **79401** to see the pre-order ribbon, hero and `/pre-orders` page. |
| `bookings` | bookings | Settings, a team member and services for the providers — only when bookings is running (`tools/dev/local.sh restart bookings`), skipped otherwise. |

`make up` runs it automatically when the catalogue is empty (`--if-empty`; `SEED=0 make up` skips it, the log is
`.local/logs/seed.log`). Every step is idempotent: records are looked up by a stable key (org id, product title,
placement / campaign / window name) before anything is created, so `make seed` can be re-run any time; a changed
placement is replaced (the old one ended), never duplicated. `make seed ARGS="--only products,content"` runs a subset;
`make seed-test` runs the seeder's unit tests.

Locally every Java service runs with security off, so the seeder needs no token. Against dev, pass `SEED_TOKEN` (an
access token) or `SEED_CLIENT_ID` / `SEED_CLIENT_SECRET` (client credentials at `AUTH_TOKEN_URL`, default
`AUTH_ISSUER_URL/oauth2/token`), and the service URLs (`CATALOG_URL`, `OFFERS_URL`, `INVENTORY_URL`, `MEDIA_URL`,
`CONTENT_URL`, `DELIVERY_RUNS_URL`, `SELLER_URL`, `BOOKINGS_URL`). Staff provisioning of seller orgs
(`POST /v1/orgs`) is on only in the local and dev profiles (`at.seller.provisioning-enabled`).

Known local limits: the catalogue owner of every seeded product is AfroTransact Retail (local security off means the
catalog sees a staff caller; sellers sell them through their offers, which is what cards, buy boxes and PDPs show,
but the search facet "Sold by" counts the catalogue owner); Stripe Connect accounts are not created (payouts need a
Stripe test key and onboarding in Seller Center); products have no ratings (there is no product-review service yet).

### Photos: where they come from

Images are real, openly licensed photos, downloaded at seed time and uploaded through media's signed-upload flow
(`POST /v1/uploads` → `PUT` to MinIO → `POST /v1/assets/{id}/complete`, which strips metadata and moderates), so
products and placements reference real media assets (`http://localhost:9000/media/...` locally). For every product or
placement the seeder tries, in order, and prints which source each image came from:

1. **`SEED_IMAGES_DIR`** — your own folder. A file is used when its name is the product slug (`ofada-rice.jpg`,
   `ofada-rice-2.jpg`, `ofada-rice_3.png`, or any file in `ofada-rice/`) or one of its keywords (`jollof rice.jpg`).
   Slugs are the `key`s in `tools/seed/seedlib/dataset.py`; placements use keys such as `hero-jollof`, `tile-ghanaian`,
   `banner-kente`, `preorder-lubbock-hero`. The main product image must be at least 1000 × 1000 px (catalog rule).
2. **The cache** — `.local/seed/images` (+ `manifest.json`): once downloaded, re-runs work offline (`SEED_OFFLINE=1`
   never downloads).
3. **Pexels** (`PEXELS_API_KEY`) and **Unsplash** (`UNSPLASH_ACCESS_KEY`), when set: usually better product shots, same
   keyword lists. Their own licenses apply (the Unsplash API guidelines also expect attribution and the download ping,
   which the seeder sends).
4. **Wikimedia Commons** (no key): `action=query&generator=search` in the File namespace, photos only
   (JPEG/PNG/WebP, no logos, maps or drawings), licenses **CC0, public domain, CC BY, CC BY-SA** only (NC, ND, GFDL-only
   and fair use are skipped). `SEED_WIKIMEDIA=0` turns it off.

A photo is never used twice. Attribution (title, author, license, source page) for every image used is written to
**`.local/seed/ATTRIBUTION.md`** and `.local/seed/attribution.json`, and each product carries an `imageCredits`
attribute. CC BY and CC BY-SA require that credit wherever the images are shown; keep the file with any export of the
demo data. Media's asset metadata has no attribution fields.

If no source has a usable photo (offline, nothing cached, no `SEED_IMAGES_DIR`), the product is skipped and reported —
the seeder never invents images.

### Image URLs in the storefront

Product cards, the PDP gallery, the home hero, tiles and banners render the media URLs as plain `<img>` (Next.js image
optimisation is off). `next.config.ts` still allows the media origin in `images.remotePatterns`, derived from
`MEDIA_PUBLIC_URL` (default `http://localhost:9000/media`) so no environment host is hard-coded. MinIO's `media`
bucket is public-read locally (`infra/local/minio/create-buckets.sh`); `tools/dev/local.sh` gives media the MinIO
credentials. Content's slot schemas require `https://` images; the local profile accepts the local MinIO origin
(`at.content.placements.local-image-origins`).
