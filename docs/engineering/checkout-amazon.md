# Checkout structure and the Lola launcher (shopper web)

Owner feedback: the checkout was "still not a standard match for Amazon", the tax page carried explanatory copy, and Lola was in the navbar twice. Screenshots: `docs/engineering/img/checkout-amazon-1280.png`, `checkout-amazon-390.png`, `lola-launcher-1280.png`, `lola-panel-1280.png`, `lola-panel-390.png`.

## Checkout (`web/apps/storefront`, route group `(checkout)`)

- **Chrome.** `app/(checkout)/layout.tsx` renders only a minimal header (logo, `Checkout (N items)` centred, secure label) and a slim footer (legal links, copyright), plus the cookie banner. No navbar, department bar, big footer, WhatsApp or Lola. `/checkout/complete` stays in the `(shop)` group.
- **Sections.** 1 Delivery address, 2 Payment method (saved cards, Payment Element, AfroTransact credit, promo code as a link), 3 Review items and delivery. Only the first incomplete section is open; the others are a one-line summary with Change (`CheckoutStep`). The Payment Element stays mounted (hidden) while section 2 is collapsed. A new card whose details are complete keeps section 2 open and opens section 3 beside it.
- **Items and delivery.** Packages are grouped by seller and delivery method, headed by the real arrival date (`cart.arrives`, only from a delivery window, never invented), with photo, title, quantity selector (the bin takes the item out of this order, it stays in the cart) and price. Delivery options are radios only where more than one is valid (AfroTransact delivery, pickup, seller ships); a single valid option is plain text. No carrier is named.
- **Item problems** (tax gate `blockedLines`, out of stock, quantity above stock) are said on the item with the way out and stop the order; a placement refused with `STOCK_LIMIT`, `OUT_OF_STOCK`, `STOCK_CHANGED`, `CHECKOUT_ITEM_BLOCKED` or a cart mismatch shows a plain line under the button.
- **End column.** Place your order first (disabled, with the reason, until address and payment are valid), one terms line, then Order summary: Items, Delivery, Total before tax, Estimated tax, Order total (promotion, fee waiver and credit rows when they apply). Below 1000px it is one column and Place order repeats in a sticky bar (safe-area aware, 44px).
- **Behaviour kept.** ZIP prompt without a tax place, idempotency key, pickup windows, order confirmation redirect, total-changed re-confirmation (`The total changed. New total $X`, Accept / Cancel, shown only when it changes).
- **Tax copy.** See `checkout-tax-estimate.md`: only the value of Estimated tax is shown.

### Design prototypes

`design/AfroTransact Shop.dc.html` already has "Secure checkout", "Review items and delivery" and "Place your order", which agree with this pattern. It differs in the full site header above the checkout (here: minimal header) and in Lola in the header (here: launcher only).

## Lola

One entry point: `LolaFab` in `ShopChrome`, a round launcher fixed to the bottom end corner (logical properties: bottom-left in right-to-left languages), above the cookie banner (`--at-cookie-banner-h`/`-gap`) and any bottom bar (`--at-bottom-bar-h`). The first-visit label is dismissible and remembered in `localStorage` (`at_lola_label_seen`, try/catch). It opens `ChatPanel` (`@afrotransact/ui`, a dialog with focus trap, Esc and return focus; a card from 641px, a full-height sheet on phones). Removed: header button, department-bar item, All menu row, account menu item, account hub tile, product page button, orders link. Hidden on `/checkout`, absent on `/b/*` store pages (they have no marketplace chrome). A capped or down gateway reads "Lola is unavailable right now"; there is never a made-up answer.

Opening chips and the placeholder are capability hints, and the composer has a dictation mic: see `lola-voice.md`.

## Tests

Vitest: `test/lola-fab.test.tsx`, `checkout-*.test.tsx`, `shopper.test.tsx`, `stripe-elements.test.tsx`. Playwright: `e2e/checkout-amazon.spec.ts`, `e2e/lola.spec.ts`, `e2e/checkout-stripe.spec.ts`. Screenshots: `CHECKOUT_SHOTS_DIR=docs/engineering/img pnpm e2e`.
