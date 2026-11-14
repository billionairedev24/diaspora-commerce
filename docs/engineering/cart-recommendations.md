# Cart rail and honest copy (S443)

Rule: never write social proof we have no data for. Owner complaint: an empty cart said "Customers who bought items in your cart also bought". The rail was a rating-sorted search.

## Cart rail (`CartView.tsx`, `lib/cart/related.ts`)

- Empty cart: no rail. Only the browser's own recently viewed ATINs (`at.recentlyViewed`, via `siteQueries.recentlyViewed`) may show, headed "Your recently viewed items", and only products that pass `recommendable()`.
- Cart with items: one organic search per cart department (max 3, `searchQueries.related`, `ads=false`, so no ad is shown or charged), merged round robin, then `pickRelated`: not in the cart or saved, `recommendable()` (in stock, real photo, same rule as sitemap and home rails, `src/lib/seo/eligibility.ts`, and not sponsored), sellers not yet in the cart first, nothing if what is left is one seller already in the cart.
- Heading: "More in {category}" (one department) or "You might also like". Nothing renders when nothing qualifies. Stars never shows "0 reviews".
- "Customers also bought" is not used and not built: orders-service has no co-purchase read. If one is added later it needs a contract-first endpoint with a minimum of 5 distinct orders per pair, no customer data, cached; only pairs over the threshold may use that wording.

## Audit of other claims

| Where | Was | Now |
|---|---|---|
| Storefront `cart.alsoBought` (5 locales) | Co-purchase claim | Removed; `cart.moreIn`, `cart.mightLike`, `cart.recentlyViewed` |
| Customer app home "Buy again / Based on your orders" | First 4 search hits | Only products from the shopper's own order lines, in stock, with a photo; nothing otherwise |
| Customer app "Popular right now" | Rating-sorted search | Removed |
| Customer app hero "Just landed / Fresh from Accra / back in stock today" | Hard-coded promo | Removed |
| `home.services.popular.title`, contract `home-copy.json` | "Popular services" (no popularity data) | "Services you can book" |
| `svc.offer.body` | "Customers near you are looking for..." | Reworded, no demand claim |
| `cart.emptyHint` | "Shop today's deals" | Neutral |
| Gateway default rail, staff rule label, mock content | "Popular right now", "Best sellers" (sorted by rating) | "Top rated" |
| "Only N left" (card, PDP, cart), deal %, deal countdown | Real stock, list price and end time | Kept (data backed) |
| Seller plan taglines "Most popular" (seller seed data) | Marketing label, no data | Left; owner decision |

`web/apps/storefront/test/no-unearned-claims.test.ts` scans the storefront and customer app `en.json` for banned phrases.

Screenshots: `docs/engineering/cart-recommendations/` (1280 and 390, empty cart and cart with items, mock BFF).
