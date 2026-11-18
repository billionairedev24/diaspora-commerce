# Seller self-shipping: the multi-product problem and four options

Status: for the owner to choose. Only the switch and the minimum rule (option A) are built, and the switch is OFF.

## What is built (and not live)

- Staff switch `sellerSelfShippingEnabled` in orders' shipping settings (Staff console > Fees > Flat delivery fee). Default **off**, off in dev,
  uat and prod (column default false, orders `V15`). Version, who and why are logged like every change to that record.
- With the switch **on**: a seller sets **one flat shipping charge per order package** in Settings > Shipping (never per product). Checkout shows
  "Shipped by the seller" at that charge for that seller's package; the seller moves the order (shipped with tracking or details, delivered).
- With the switch **off**: no screen shows the field or the option, the BFF answers `SELF_SHIPPING_DISABLED` (409) to a change, checkout answers
  `SELF_SHIPPING_UNAVAILABLE` (422) to the option, orders refuses both, and a product never carries a shipping charge of its own
  (`offers.invalid`: field `shippingMinor`).
- Money: the charge is the **seller's revenue**. Payments books it to the seller (`Fulfilment.SELF`), AfroTransact takes **no commission** on shipping
  (commission is on items only, existing rule, pinned by `FulfilledByAfroTransactMoneyTest`), no membership plan waives it. The card processing fee is
  still shared by gross as for every line (existing rule, unchanged). Tax on the charge follows the existing shipping-tax rules.
- Existing `SELLER_SHIPS` and `CARRIER` shipments stay read-only history (`legacy_seller_shipped`); new ones use `SELLER_SELF_SHIPS` so the two are never confused.

## The problem

A cart holds several items from one seller, each with a different shipping fee. Adding them up charges a price nobody chose; taking the highest
punishes small items; any per-product fee invites sellers to hide margin in shipping or to set fees buyers cannot compare.

## Options

| | A. One flat fee per seller per order (built behind the switch) | B. Seller shipping profiles by weight or price band | C. Free over a threshold, flat below | D. Highest item fee plus a small add-on per extra item | E. Carrier-calculated rates through a provider |
|---|---|---|---|---|---|
| Seller UX | One number in Settings | Small table: bands and fees, per seller | Two numbers: charge and "free over" | A fee per product plus one add-on number | Weight and size per product; nothing to price |
| Checkout UX | "Shipped by the seller $7.50" | Same line; the band is chosen from the cart | Same line, or "Free" with a nudge to reach the threshold | Same line | A live quote, may take a second |
| Revenue and accounting | Seller revenue, no commission on it | Same | Same | Same | Same, plus label or provider fees to settle |
| Abuse risk | Low: one visible number; a seller can still overcharge, so the wording makes it their responsibility | Medium: bands can be gamed | Low | Medium: per-product fees return | Low for the buyer, provider dependence |
| Effort (rough) | Done behind the switch | 1 to 2 weeks | 2 to 3 days on top of A | 1 week and a per-product field (rejected today) | 3 to 5 weeks, provider contract, parcel data on every product |

## Recommendation

Choose **A now, then C** (free over a threshold) when sellers ask for it. It is the only rule a buyer can understand at a glance, it needs no new
product data and it removes the per-product fee that caused the problem. Take B only if sellers with very different parcels ask for it, and E only
if AfroTransact wants to sell labels again (it retired carrier labels). D is not recommended: it brings back a per-product fee.

Owner decisions needed: (1) A, C or something else; (2) a ceiling on the seller charge (today $100); (3) when to turn the switch on in uat and prod
(staff, with a reason, no deploy); (4) whether a seller-shipped order that goes missing is covered by AfroTransact buyer protection.
