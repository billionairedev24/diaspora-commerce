# Business pages (no store builder)

AfroTransact has no store builder, themes or page editor. Every seller and provider gets one plain public page, made from
their application. It exists so that Stripe always has a website to look at: nobody is asked for one.

| | |
|---|---|
| Address | `<PUBLIC_WEB_URL>/b/<slug>`, the slug from the business name (`ades-kitchen`, then `-2`, `-3` when taken); never changes |
| Made | when the Stripe connected account is created (seller service, `StripeOnboardingServiceImpl`), table `business_page` |
| Content | business name, description, logo and one brand colour from the application's business step; a seller's live products (search, `sellerId` filter) or a provider's services |
| Look | the brand colour fills the header and the text on it is whichever of light or dark contrasts more; everything else is platform-styled |
| Search engines | kept out (`noindex`) until the organisation is approved |
| Stripe | `business_profile.url` = the page address and `product_description` = the description, both set at account creation |
| API | seller `GET /v1/public/business-pages/{slug}` (no login); BFF `GET /v1/business-pages/{slug}` adds a provider's services |

Search also matches the seller's keywords and description, and a text search returns the services that match its words.

The business step asks for a name, what they sell (up to 500 characters) and an optional brand colour; the logo upload is
not built yet. Nothing about a business page can be edited after the application except through the application itself.

Removed with the store builder: the `storefront` Go service (and its database, port 8206, workflow, Helm chart and
contract), the Seller Center "Storefront & theme" and "Public page" modules, `/v1/seller/storefront*`, and the designed
provider page (layouts, galleries, featured work, social links). The media purpose `storefront` stays: it is the public
image purpose used for logos and pre-order pictures.
