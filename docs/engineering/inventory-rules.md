# Inventory rules: AfroTransact stock, seller stock, and walk-in conflicts

Status: **DRAFT for owner review. Not approved. Nothing here is built unless it says "today".**
Sources: code map of this repo (2026-11), web research (Amazon Seller Central, Stripe docs, Shopify, Square, eBay).
Web claims were read from search digests only; Stripe and Amazon pages could not be opened from the build environment, so every
external claim below is marked **[verify]** until someone reads the page. Do not treat [verify] items as confirmed.

## 1. How it works today (from the code)

| Topic | Today |
|---|---|
| AfroTransact-held stock (Retail and seller FBA) | `inventory` service. `inventory_position(sku, node_id, owner_org, on_hand, reserved, inbound, damaged)`, ledger `stock_movement`, lots, inbound shipments. Reserve at checkout (15 min hold), commit at pickup, release on cancel. |
| Seller-held stock | One integer, `offer.seller_quantity`, in `offers`. No reserved split, no ledger, no reason stored, no location. |
| Seller stock at checkout | **Nothing is reserved.** Decremented after `orders.order.placed`, clamped to 0. Two buyers can take the last unit. |
| Seller updates | Absolute quantity per SKU (0 to 99,999) in the Inventory page or listing quick-stock. Reasons validated, not stored. No stock CSV import. Low-stock thresholds hard-coded (10 inventory, 5 offers). |
| Seller cannot fulfil | **No seller cancel action.** Only the customer (until packed) or staff can cancel. No reason codes, no reroute. |
| Seller accept step | `placed -> confirmed` is a manual click that gates nothing, has no timer, and does not touch payment. Ship-by date is informational. |
| Payment | Manual capture. Authorise at checkout, capture when the **first shipment is picked up** (all lines of the order, since multicapture is off). Cancel before capture = PaymentIntent cancelled, no fee. After capture = line refund; Stripe's fee share is not returned (`Postings.java:62`). |
| Authorisation expiry | ~7 days for cards. **Not handled for ordinary orders** (capture retries 12 times then parks as FAILED). A design exists in `docs/spikes/preorder-delivery-runs.md` section 7. |
| Seller cancel metric | trust-ops counts 90-day cancellation rate vs 2.5% target, but only cancels by SELLER or SYSTEM, and sellers cannot cancel, so it reads ~0. |

## 2. How Amazon does it (summary; official pages need reading [verify])

- Two fulfilment channels per product: seller-fulfilled (`DEFAULT`) and Amazon-fulfilled (`AMAZON_NA` etc.). The same product on both channels uses **separate
  SKUs; their counts are never shared** [official API docs].
- FBA stock lives in Amazon's ledger (inbound, fulfillable, reserved, unfulfillable). Seller-fulfilled stock is a number the seller sets per SKU (optionally per location). Amazon
  does not know about walk-in sales; sellers on several channels keep counts in sync themselves [UNCONFIRMED guidance].
- The consequence is on the seller: **Pre-fulfillment Cancel Rate under 2.5%**, or seller-fulfilled offers can be deactivated [official help page, via digest]. Other targets (late shipment 4%, valid
  tracking 95%) are from forum posts only [secondary].
- The buyer is charged when the seller confirms shipment; a cancel before then leaves only a temporary bank hold [secondary, not official].

Industry patterns for sellers with a shop (Shopify, Square, eBay [official docs, via digest]): per-location available/committed counts; reserve on order; sellers warned
that self-computed counts miss offline sales and must be reconciled with physical counts; out-of-stock cancels count as defects (eBay 2%); zero-stock listings hidden.

## 3. Proposed model

### 3.1 Two separate inventories (D1)
1. **AfroTransact-held inventory** (Retail and FBA): we own the truth. Ledger-based, as today. Sellers cannot edit it; they ship it in (inbound) and see it read-only.
2. **Seller-held inventory**: the seller owns the number. The platform only ever *reserves against it* and *shows what it sold*.

They never share a count. The same product can have both an FBA offer and a seller-held offer; each draws only from its own stock (as Amazon's separate SKUs).

### 3.2 Seller-held stock has three numbers (D2)
| Number | Who sets it | Meaning |
|---|---|---|
| Online quantity | Seller | What the seller promises to set aside for online orders. This is the only number shoppers can buy against. |
| Reserved | Platform | Units held for carts at checkout and orders not yet shipped. |
| Available online | Derived | Online quantity minus reserved. Shown to shoppers. |

The seller's shop stock is theirs to track; the platform never needs it. Physical count is optional ("in my shop"), informational only. The guidance shown in the form:
"List only what you will set aside for online orders. Keep the rest for your shop."

### 3.3 Reserve at checkout for seller stock too (D3)
Same 15-minute hold as FBA, so two buyers cannot take the last unit. Reservation, commit (at shipment) and release (cancel, expiry) are recorded in a ledger with the reason.
Seller adjustments store their reason code (stock take, damaged, found, returned, shop sale, other).

### 3.4 Walk-in conflicts: a seven-layer defence (D4)
No single feature fixes this; layers reduce it to rare, cheap, and fair:
1. **Online quantity is separate** from shop stock, so a walk-in sale cannot oversell unless the seller counted wrongly.
2. **Buffer**: the form suggests a safety buffer ("list 2 fewer than you have"). Staff can set a category default. Seller sets their own; never enforced.
3. **Stale-count nudge**: if a seller's count has not been updated in N days (default 7) and an order arrives, ask the seller to confirm stock in the order notification.
4. **Seller confirms within a window (D5)**: the seller must tap "Confirm, I have it set aside" within 12 business hours (staff-editable). Confirming does not capture money. It means the units are physically held.
5. **One-tap "Can't fulfil"** with reasons (out of stock, damaged, wrong listing, other). Result: full refund of that shipment, stock for that SKU set to 0, listing flagged for a stock check, customer told in plain words.
6. **Auto-cancel**: no confirmation in the window -> cancel, release hold, notify customer. Counts as the seller's cancel.
7. **Metrics with consequences (D6)**: pre-fulfilment cancel rate on a rolling window (we already compute 90 days vs 2.5%). Graduated: warning at 1.5%, buy-box penalty above 2.5%, listings paused above 5% until the seller completes a stock check. All thresholds staff-editable and published to sellers.

Later (not launch): POS sync (Square, Shopify POS, Clover) that sets online quantity from the shop's count; stock CSV import is cheap and **should be launch**; suggest alternative sellers of the same product to the customer.

## 4. Money: what a seller cancel costs (Stripe)

| Case | Cost to customer | Cost to platform | Cost to seller |
|---|---|---|---|
| Seller cancels **before capture** (single-seller order, or all shipments) | Nothing charged; the bank hold clears in days (bank-dependent, 3 to 10 days is typical [verify]) | Nothing | Metric only |
| Auth expires before capture | Nothing charged | Order must be recreated; handle in section 4.2 | Metric |
| Seller cancels **after capture** (multi-seller order, other shipment already picked up) | Refund; shown in 5 to 10 days | **Stripe's processing fee on that part is not returned** (2.9% + 30c share) [verify, Stripe docs] | None today |
| Dispute | n/a | Dispute fee, non-refundable [verify] | Per policy |

Today the platform bears the lost fee on post-capture cancels. At launch volume this is small, but the rule should be explicit.

### 4.1 Where "no liability" is true and where it is not
- True: any cancel while the PaymentIntent is **uncaptured** costs nobody anything (Stripe says cancelling an uncaptured payment is free [verify]).
- Not automatically true: after capture, and in multi-seller carts where one pickup captures every line (multicapture is off by default; multicapture is cards-only and has limits [verify]).
- Therefore the rule is: **never capture until every seller in the order has confirmed they have the stock.**

### 4.2 Closing the gaps (decisions)
- D7: Capture waits for all sellers' confirmations (section 3.4 step 4). Capture remains at pickup of the first shipment, so every confirmed shipment is safe.
- D8: Authorisation expiry: confirm window (12 business hours) plus pickup SLA must fit inside the card's authorisation window (read `capture_before`, never hard-code 7 days).
  If a hold would lapse, re-authorise or cancel and notify. Extended authorisation (up to 30 days) is limited by card brand and category and is not relied on [verify].
- D9: Payment methods that do not support manual capture (ACH, iDEAL [verify]) are not offered at launch. Cards, Apple Pay and Google Pay [verify], PayPal, Klarna, Affirm, Afterpay, Cash App Pay support it [verify].
- D10: Fee on seller-fault post-capture cancel: platform absorbs at launch (option: charge the seller the lost fee later). Owner decision.

## 5. Acceptance sketch (for the backlog once approved)
- Reserve, commit and release for seller stock with a ledger and reason codes.
- Online quantity, buffer, and derived available online in seller-center; shopper view shows the derived number.
- Seller confirm window, auto-cancel, "Can't fulfil" with reasons; staff-editable settings; notifications.
- Capture gated on all-seller confirmation; authorisation-expiry handling; cancel metric counts all seller-fault cancels (including staff-entered with a seller reason).
- Stock CSV import; stale-count nudge; seller cancel-rate page; consequences by threshold.
- Reconciliation alert for seller stock (reuse `InventoryReconciliationDrift`).

## 6. Open decisions for the owner
1. Confirm window length (proposed 12 business hours) and who can cancel on the seller's behalf.
2. Penalty ladder (1.5% warn, 2.5% buy-box, 5% pause) and whether sellers pay any cancel fee.
3. Platform absorbs the lost Stripe fee on seller-fault post-capture cancels at launch?
4. Per-shipment capture vs all-sellers-confirmed gating for multi-seller carts (gating is simpler; per-shipment needs multicapture, cards only).
5. Buffer default (suggest 0 enforced, suggestion shown) and stale-count days (7).
6. Which payment methods at launch (all manual-capture capable ones, no ACH).
7. POS sync: later, and which POS first.
