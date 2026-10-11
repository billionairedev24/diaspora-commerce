# Local development

`make up` (= `tools/dev/local.sh up`) starts the infrastructure in Docker, the default services as host processes on
their fixed ports ([infra/local/PORTS.md](../../infra/local/PORTS.md)) and the three web apps, then prints every link.
`make dev-status`, `make dev-logs SERVICE=x`, `make dev-restart SERVICE=x` and `make down` manage what it started.

**Provider keys.** Your own Stripe test, Twilio, Resend, OpenRouter, … credentials go in `.local/secrets.env`
(gitignored; template `infra/local/secrets.env.example`). `make up`, `restart` and `mobile` load it, `make secrets-check`
shows what is set, missing or malformed (values masked), and each provider switches from its local fallback (Mailpit,
the log) to the real one when its keys are present. Every variable, where to create it, and where it lives in
dev/uat/prod: [secrets and configuration](secrets-and-configuration.md).

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
| `bookings` | bookings | Settings, a team member and services for the providers (bookings runs by default with `make up`; skipped when it is not running). |
| `users` | identity | Checks that every [test user](#test-users) signs in (and that the seller, provider and retail personas act for their organisation), then prints the table. |

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

## Test users

Identity's **local profile** creates ready-made accounts for every persona at start-up (`TestPersonas.java`, S276), so
nobody has to sign up or dig a code out of a log to try a role. They all use **`@afrotransact.test`** (a reserved domain:
never a real mailbox) and one password, **`Test1234!Local`**. Restarting identity puts back anything a test changed
(password, verified email, disabled account, roles, membership, address).

| Email | Persona | App | What they have |
|---|---|---|---|
| `customer.houston@afrotransact.test` | Customer | Storefront | Default address 10200 Richmond Ave, Houston TX **77042** |
| `customer.lubbock@afrotransact.test` | Customer | Storefront | Default address 1500 Broadway, Lubbock TX **79401**: the open Lubbock pre-order |
| `seller.owner@afrotransact.test` | `seller.owner`, Mama Put Pantry | Seller Center | Mama Put Pantry's offers, stock and orders |
| `seller.staff@afrotransact.test` | `seller.catalog`, Mama Put Pantry | Seller Center | Same organisation, catalog manager's modules |
| `provider.owner@afrotransact.test` | `provider.owner`, Aminata's Braids | Seller Center (Provider Hub) | The provider's services, team and calendar |
| `retail.operator@afrotransact.test` | `seller.admin`, AfroTransact Retail | Seller Center | The first-party (1P) organisation's offers and stock |
| `staff.admin@afrotransact.test` | `platform.super_admin` | Staff console | Every module (20) |
| `staff.ops@afrotransact.test` | `platform.operations_manager` | Staff console | Every module but Users & roles and Developers |
| `staff.trust@afrotransact.test` | `platform.trust_safety` | Staff console | Orders, Listing moderation, Applications, Customer support, Disputes, Audit log, Site content |
| `staff.finance@afrotransact.test` | `platform.finance` | Staff console | Orders, Inventory, Pre-orders, Applications, Payouts & ledger, Fees & tax, Disputes, Audit log |
| `staff.support@afrotransact.test` | `platform.customer_support` | Staff console | Orders, Listing moderation, Dispatch, Pre-orders, Applications, Customer support, Payouts, Disputes, Site content, Notifications |
| `staff.marketing@afrotransact.test` | `platform.marketing` | Staff console | Listing moderation, Pre-orders, Ads & announcements, Site content, Notifications |
| `staff.catalog@afrotransact.test` | `platform.catalog_manager` | Staff console | Orders, Listing moderation, AfroTransact Retail (1P) |
| `staff.retail@afrotransact.test` | `platform.retail_buyer` | Staff console | Orders, Listing moderation, Inventory, AfroTransact Retail (1P), Pre-orders |
| `staff.fulfilment@afrotransact.test` | `platform.fulfilment_ops` | Staff console | Orders, Inventory, Retail (1P), Dispatch, Pre-orders, Disputes, Markets & locations |
| `staff.inventory@afrotransact.test` | `platform.inventory_manager` | Staff console | Orders, Inventory, Retail (1P), Delivery dispatch |
| `staff.dispatch@afrotransact.test` | `platform.dispatcher` | Staff console | Orders, Inventory, Delivery dispatch |
| `staff.product@afrotransact.test` | `platform.product_owner` | Staff console | Most modules (no Customer support, Site content, Notifications) |
| `staff.developer@afrotransact.test` | `platform.developer` | Staff console | Orders, Fees & tax, Audit log, Markets & locations, Developers |
| `staff.auditor@afrotransact.test` | `platform.auditor` | Staff console | Every module but Site content and Notifications, read-only |
| `driver@afrotransact.test` | `platform.driver` | Driver app | Driver role (fulfilment learns it from `identity.role.granted`) |

The staff console shows each role what identity's module matrix gives it (every staff persona also has Dashboard and
Exports; checked by signing each one in). The bootstrap Super Admin
(`admin@afrotransact.com` / `LocalAdmin2026!`) is still there. `tools/dev/local.sh links` (and the end of `make up`)
prints the same list with each app's local address.

**Where they come from.** Identity owns accounts, passwords, roles, organisation memberships and the address book, and
no public API creates a password account without an emailed code, so the accounts are created inside identity, like the
bootstrap admin. Organisations come from the seller service: the seller, provider and retail personas are linked as
soon as `make seed` creates their organisation (identity mirrors `seller.org.approved` and links the waiting personas);
AfroTransact Retail exists from identity's migrations. The seeder's `users` step then signs every account in through the
first-party API to prove it (`make seed ARGS="--only users"`). There is no seeded cart or past order: locally the cart
service runs without authentication (every cart is a guest cart) and checkout needs a Stripe test key.

**No second step for customers, sellers, providers and the driver**: they have no two-step method enrolled, so email +
password is the whole sign-in. **Staff keep the two-step policy** (it is mandatory for staff roles): the code is a text
message to the account's fictional `+1 713 555 01xx` number, and locally identity delivers it to **Mailpit**
(http://localhost:8025, tagged `sms`, addressed to the account's email; the subject carries the code). Tick *Trust this
browser for 30 days* to skip it afterwards. The same goes for the bootstrap admin and for sign-up email codes. This is
`at.identity.verification.sender=mailpit` (the log line is still written; `VERIFICATION_SENDER=log` keeps the log only).

**Never outside local.** The test users and the Mailpit/log code senders are decided at start-up from the active
profile by `LocalDevelopmentPolicy`: on `uat` or `prod` identity refuses to start if either is configured
(`LocalDevelopmentConfigurationTest`, `DefaultLocalDevelopmentPolicyTest`); `dev` can opt in to test users only with
`IDENTITY_TEST_USERS=true` and a password of its own (`IDENTITY_TEST_USERS_PASSWORD`; the published local one is
refused), and dev codes always go through the notifications service. `IDENTITY_TEST_USERS=false` turns them off locally.
