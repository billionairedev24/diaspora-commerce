# ADR 0013: Fulfilled by AfroTransact means AfroTransact collects from the seller; seller self-shipping behind a switch

Status: accepted (owner decision, 2026-11-17). Corrects [ADR 0011 (two fulfilment modes)](0011-two-fulfilment-modes.md).

## Decision

1. **Pick up**: the customer collects the packed product from the seller's pickup location. No delivery fee.
2. **Fulfilled by AfroTransact (FBA)**: AfroTransact goes to the seller's location (or the agreed arrangement), takes the product and delivers it.
   The customer pays the normal AfroTransact delivery fee (free when their membership plan waives it: existing rules). **AfroTransact keeps no seller
   stock** and has no warehouse for it. The seller pays **nothing**: no per-unit FBA fee, no seller delivery fee.
3. **Seller ships** exists only behind a staff switch that is off by default (see `docs/engineering/seller-self-shipping-options.md`).

## Compatibility and migration (nothing destructive)

- Wire and database values stay: offer `AFROTRANSACT_DELIVERY` and shipment mode `afrotransact_delivery` now mean FBA from the seller's location;
  `FBA_AFROTRANSACT` and offer `AFROTRANSACT` (stock held by AfroTransact) remain only for AfroTransact Retail and for rows that already exist.
- No row is rewritten. Payments no longer apply the per-unit FBA fee or the seller pickup-delivery fee to any **new** payment; the fee schedule keeps its
  fields and every stored line keeps its recorded fees, so refunds of old orders still return them pro rata.
- A marketplace seller can no longer create or move an offer into the stock-held channel (`offers.seller-stock-not-held`); a product has no shipping charge of
  its own; sending stock to AfroTransact answers 410 `FBA_INBOUND_RETIRED`; reading earlier inbound plans, stock and lots still works.
- Seller Center: no "Inventory & FBA" stock-send flow, no fulfilment-centre wording; the modes are explained in tooltips.
- Open data question for the owner: stock that sellers already sent to AfroTransact (return it, or buy it). Nothing here touches it.
