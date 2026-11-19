# Off-platform diversion: keeping sales on AfroTransact

Policy (shown beside the fields and to be added to the seller terms): **Off-platform sales and steering buyers away from AfroTransact are not allowed.**

This page says what is built, what it reduces, what it cannot stop, and what is still open. Backlog story S640 (In Review: not every criterion is verified).

## What is in place

| Area | Behaviour | Where |
|---|---|---|
| One detector | Rejects web addresses and domains, email addresses, phone numbers (digits with separators, Arabic and full-width digits, `5O5` look-alikes, digit words such as "seven one three"), `dot com` / `at gmail` disguises, `@handles`, and phrases (WhatsApp, Telegram, DM me, call/text me, order direct, cheaper off the site, Zelle, Venmo, Cash App). A strict mode adds bare "DM", "contact us", 7-digit runs and two spelled digits in a row. | Go `go/pkg/diversion`, Java `com.afrotransact.platform.web.diversion` (platform-web). Both run `contracts/diversion/vectors.json` (117 vectors, including the must-pass legit text: "5 kg", years, "9 am to 5 pm", "St. Ives", street addresses, ZIP+4, prices). Change a rule in one, the vectors and the other. |
| Store text | Tagline, About heading and body, shipping and returns text, announcement and opening hours are scanned on save and publish. The message says what to remove and why, politely. | `gateway-bff` `domain.ValidateStoreConfig` |
| Listings | Title and description are scanned on create and update. | catalog `ProductWriterImpl` (field errors `title`, `description`) |
| Review replies | A seller's reply to a review is scanned. | `gateway-bff` `SellerReviews.Reply` |
| Metric | `diversion_text_rejected_total{field}`. Field is a path ("about.body", "review.reply"), never the text. | `adapter/diversionmetrics` |
| Website network | Not a choice any more. A link stored earlier can stay or be removed, never added. It is **hidden on the public page** unless the store has earned it. Default: all hidden. | `domain.SocialLink`, `ValidateStoreConfig`, `publicConfig` |
| Earned rule | `domain.EarnedWebsite` with `WebsiteThresholds`: gate off by default; when on, >= 25 completed orders, >= 60 days since first sale, dispute rate <= 1%, late-shipment rate <= 4%, no open diversion flag. A staff grant or revoke overrides the numbers (a flag or the gate being off still hides). | `domain/website_earned.go`, tested. |
| Login email | The "Show my email" switch is gone. `showEmail` is accepted from old clients and ignored (always false). `contactEmail` is always null on the public storefront and business page, in the gateway and in the seller service. | gateway-bff, `PublicStorefrontController`, `BusinessPageController` |
| Phone | Stored for the seller, never on the public page (stripped in `publicConfig`; not in JSON-LD, which only carries name, description, logo, images, sameAs and rating). | gateway-bff, storefront `StorefrontView` |
| Seller Center | Warning text with the policy beside the story, hours and tagline; no email switch; no Website choice. | `StorePageView` |

### Existing content that would now fail

Nothing is deleted. On the public page the offending field falls back to empty (the page keeps working); the stored text is untouched and the seller is told to fix it on the next edit (the save refuses until it is clean). The staff flag list for these stores is NOT built yet (below).

## Pickup address today (for the owner)

- The public store page shows only the 5-digit ZIP of the first active seller pickup place, and only when the seller switches "show pickup" on. No street address.
- The full pickup address, hours and ready time reach the customer on the order (`OrderPickup`: name, address, hours), after the purchase.
- To verify and flag: whether the checkout pickup choice (before payment) shows the street address of a seller's place. It was not traced in this slice.

## What this reduces, and what it cannot prevent

Reduces: casual and accidental contact details in text a customer reads; copy-paste of links and numbers; the obvious phrases; a stored website link as a free ad; the login email being shown.

Cannot prevent:
- Obfuscation beyond the rules ("seven-1-3 five five five", words in other languages, images of text, a phone number inside a product photo or the logo, unusual spellings, homoglyphs not in the fold table).
- Word of mouth, a business card in the parcel, a repeat customer who knows the seller (Pick up especially: the customer meets the seller).
- A customer who asks the seller for contact through a message and is answered.
- Social profile links, which stay allowed: they are one click from a phone number or a shop.
- Text in fields not scanned yet (store name, Q&A answers: there is no Q&A module today).

False positives accepted: "order directly from our farm" is refused; 10 or more digits in a row are refused (a long code in a description); "off the site" / "outside the platform" are refused. Strict mode is more aggressive and is for flagged stores.

## Not done yet (open for the next slices)

1. **Phone on the order**: the design is: the seller's phone appears only on the storefront order detail (and the order email) of a customer who placed an order with that seller, and only for a shipment of Pick up or Fulfilled by AfroTransact coordination. The data path (seller service lookup by organisation for the order page) is not built.
2. **Message the seller**: no customer-to-seller channel exists in the repo (reviews and reports only). The relay (customer message, seller inbox and notification, no raw addresses, rate limited) is not built.
3. **Staff review queue** (store names, field, excerpt; clear, ask to fix, hide field, suspend selling), `diversion:manage` permission, thresholds as a DB setting, per-store grant and revoke with audit, and **Report this store** for customers. The suspend action already exists in trust-ops (`/v1/seller-enforcement/{orgId}/suspend`); the report and queue reuse trust-ops moderation.
4. The gate (`WithWebsiteGate`) is not wired to real order statistics: until it is, every website link stays hidden (the safe default).
5. Terms: the policy sentence is in the UI; the seller terms document still needs it.
6. Store name is a business-profile field and is not scanned yet.
7. Translations: the Seller Center store page uses English literals today (no keys), so no fr/es/ar/yo drafts were added; add when the page moves to keys.

## Tests

Go: `go/pkg/diversion` golden vectors and message; gateway-bff domain (store text, website and email rules, `EarnedWebsite`), storefront service (no email, phone or unearned website), seller review reply refusal, metric. Java: `DiversionDetectorTest` (same vectors), `ProductWriterDiversionTest`. Web: seller-center `store-page.test.tsx`, storefront `storefront.test.tsx`. Integration tests that need Docker (Testcontainers) were not run.
