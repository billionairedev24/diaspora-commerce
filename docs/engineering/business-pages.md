# Business pages

> **Providers too (S493)**: a provider who publishes Settings > Store page gets the branded page at the same address; unpublished providers keep the plain page below.
>
> **Sellers now have a branded storefront at the same address** ([seller-storefront.md](seller-storefront.md), S490): the plain page described below remains for providers, and a seller's page is the storefront once it is published (unpublished: a 404 page).

The plain page: AfroTransact has no provider page editor. Every seller and provider gets one plain public page, made from
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

The business step asks for a name, what they sell (up to 500 characters), an optional brand colour and an optional logo.
Nothing about a business page can be edited after the application except through the application itself (the page's own
settings, Settings > Business page, change the logo, colour and description later).

**Brand colour.** Sellers and providers do not know hex codes, so every place that asks for one (the business step and
Settings > Business page, in both Seller Center and Provider Hub) uses one control, `BrandColourPicker` in
`web/packages/ui`: a radio group of named swatches from `BRAND_PALETTE` (`@afrotransact/tokens`, each AA-checked for button
text), "Platform default" (no colour, the default and shown as such), and "Custom" with the native colour picker plus a hex
field kept in sync (`#rgb` / `#rrggbb`, with or without `#`, stored as `#rrggbb` lower case). A preview button on the choice
uses whichever of light or dark text contrasts more. Labels come from the app's catalogue (`sc.brandColour.*`, fr/es/ar/yo
are machine drafts pending review). The seller service accepts the same spellings and stores `#rrggbb`.

**Logo.** The wizard uploads the image to the media service (purpose `storefront`) and keeps the address the media service
returns. The seller service accepts that address when it is `https`, or `http` on a host that cannot be reached from the
internet (`localhost`, `*.localhost`, loopback and private-network IPs, `host.docker.internal`): locally the media service
answers `http://localhost:9000/media/...`, while dev, uat and production answer `https://media*.afrotransact.com` or the CDN
host. Anything else is refused with the field error `business.branding.logoUrl`. The wizard maps that onto the logo field and
shows it beside the logo with a "Continue without a logo" action, because the logo is optional and never has to block the
step.
Removed with the store builder: the `storefront` Go service (and its database, port 8206, workflow, Helm chart and
contract), the Seller Center "Storefront & theme" and "Public page" modules, `/v1/seller/storefront*`, and the designed
provider page (layouts, galleries, featured work, social links). The media purpose `storefront` stays: it is the public
image purpose used for logos and pre-order pictures.
