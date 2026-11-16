# ADR 0011: two fulfilment modes, no seller self-shipping

Status: accepted (owner decision, 2026-11-16). Supersedes the third mode ("Seller ships") of
[delivery and fulfilment modes](../engineering/delivery-fulfilment.md).

## Decision

Only two ways to get an order exist:

1. **Seller pickup**: the customer collects from the seller, no delivery charge for anyone.
2. **Fulfilled by AfroTransact (FBA)**: AfroTransact picks up from the seller (or sends from its own stock) and delivers. The delivery
   fee is paid by the **customer** and is **100 % AfroTransact revenue**; it is never part of the seller payable. The seller pays
   nothing for FBA unless staff explicitly configure a seller-side fee (fee schedule: per-unit FBA fee, the seller pickup-fee
   tariff, negotiated FBA fee; all versioned, staff-only and audited). The defaults are 0 (payments V24).

Seller self-shipping (`SELLER_SHIPS`, and seller carrier labels) is not supported. A package whose seller offers neither mode is
**not purchasable**: orders refuses it (`orders.fulfilment-unavailable`), gateway-bff flags it (`noFulfilment`, order blocked) and
Seller Center tells the seller what to enable. A mode is never guessed.

## Consequences

- Marketplace commission rules are unchanged. The tax of the delivery fee is unchanged (tax is the line's own and does not move with the fee).
- Existing `SELLER_SHIPS` / `CARRIER` shipments stay as **read-only history** (`LEGACY_SELLER_SHIPPED`): no migration of rows, no
  seller action, never in a queue; staff finish an open one with `orders:override-shipment`.
- `orders:override-shipment` is kept (no permission migration) and re-scoped to Fulfilled by AfroTransact and legacy history,
  forward only, reason required, audited.
- The staff "seller carrier shipping" switch no longer creates anything (kept so the shipping settings record stays compatible).
