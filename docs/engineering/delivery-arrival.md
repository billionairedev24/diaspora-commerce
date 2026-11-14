# Delivery dates: real data or none

The cart, checkout, product page and home rails never show a delivery date that was not computed from real inputs. There is no constant, no "today plus N business days" and no invented cut-off.

## Where a date comes from

One place: `ArrivalService` (`go/services/gateway-bff/internal/app/arrival.go`) asks the **promise service** (`POST /v1/promises`, `contracts/openapi/promise.yaml`) and shows its window. Inputs per line:

| Fulfilment | Origin sent to promise | Basis |
|---|---|---|
| Seller ships (`SELLER`) | `SELLER`, the offer's **handling days** and **ship-from ZIP** (offers `PublicOffer.handlingDays`, `shipFromZip`), method `CARRIER` `GROUND` | Both must be on the offer. Seller cut-off is the promise service default (12:00): there is no seller cut-off setting yet. |
| Fulfilled by AfroTransact | FC chosen by `fcFor(zip)` (north-east Edison, south-east Atlanta, else Houston), method by option (delivery zone, pickup, carrier) | A regional rule, not stock-aware (see owner decisions). |
| AfroTransact Delivery from a seller's store | none | No basis: a store is not a fulfilment centre. No date. |
| Pre-order | its own window | unchanged |

The destination ZIP is the shopper's place in the same priority order as the tax estimate (`PlaceResolver`: saved address, chosen, detected, default). At checkout it is the chosen address. The promise service applies weekends and US federal holidays.

A package is dated only when **every line** has a window; its window then covers the slowest line (`mergeArrivals`). Any missing input gives no date and a reason.

## What the client gets

`DeliveryPromise` (and `DeliveryOption`): `basis` `PROMISE` with `label` "Nov 18 – 20" (a range; one day only when the service says one day), `earliestDate`, `latestDate`, `cutoff`; or `basis` `NONE` with an empty `label` and `reason` `NO_PLACE`, `NO_ORIGIN`, `NO_SERVICE` or `UNAVAILABLE`. `hasDeliveryWindow()` (api-client) is the one client rule: without `basis: PROMISE` there is no date (an older server never gets one). The cart title is "Arrives Nov 18 – 20" or just "Delivery"; "Delivery date shown at checkout" appears only for `NO_PLACE`. "Package n of m" appears only with several packages. "Shipped by {seller}" is used only for seller-shipped packages; Fulfilled-by-AfroTransact packages say "Sold by {seller}"; store deliveries say "Delivery from {city}". Customer copy never says AfroTransact collects or delivers; no carrier is named.

## Every place that used the old estimator (audit)

| Surface | Before | Now |
|---|---|---|
| Cart package header and options (`cart.go present`) | `Estimator.Options/Promise(.., 0, now)`: constants | `dateAndPlace` via `datePackages` |
| Checkout options and package tag (`shopper_checkout.go`) | FC packages only; sellers kept constants; "Same or next day" for store delivery | `dateCheckoutPackages` via `datePackages` for every option; no text without a window |
| Product page buy box and other sellers (`product.go`) | offer `deliveryDays` (handling + staff transit setting) or old promise client | `windows` via `ArrivalService` |
| Search cards (`common.go summaryFromHit`) | `Promise(.., h.DeliveryDays, now)` | undated (no ship-from on a card) |
| Home rails "deliverable-fast" and `{day}` titles (`site_local.go`) | old promise client shape (did not match the real service) | `withinDays` via `ArrivalService`; only dated products qualify |
| Product page without offers service, `site.go` tiles | constants | undated / FC only |
| Order pages | stored promise (`promisedBy`) | unchanged: stored at order time |
| Customer app (cart, product, home, checkout) | `delivery.label`, `eta` | same rule through `hasDeliveryWindow` |

`Estimator` (`domain/delivery.go`) now owns only the method, fee and options; it has no `DayLabel`, no default days and no cut-off text. Offers' `deliveryDays` (handling + staff transit estimate) still ranks the buy box but is never shown as a date.

## Production note

The OVH launch deployment has no promise service (`upstreams.promise.disabled`): every package is `NO_SERVICE`, so no date shows anywhere until the service is deployed.
