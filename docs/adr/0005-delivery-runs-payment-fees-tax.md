# ADR 0005 — Delivery runs: charge at order, configurable delivery fees, AfroTransact as tax collector

- **Status:** Accepted
- **Date:** 2026-10-24
- **Deciders:** platform owner
- **Related:** S260–S265, S306, S318, S319, `docs/engineering/payments-idempotency.md`

## Context

Delivering one small order to a far location is expensive. A delivery run batches the orders of one place into a single trip.
Runs are only offered for goods that are already in stock, so a preorder is an ordinary order with a scheduled delivery.

## Decision

1. **Payment is the same as any order.** The customer is charged at checkout. A run never holds or delays capture. If a run is
   cancelled, every order on it is refunded in full automatically and the stock is released.
2. **What a run delivers.** AfroTransact's own inventory, where the address is inside a covered location. Addresses beyond
   coverage are shipped by carrier instead. Seller goods move by FBA (stock already with AfroTransact), by the seller's own
   delivery, or by pickup.
3. **Delivery fees are configuration, not code.** Staff configure fee rules by method: a flat fee by distance band, a carrier
   rate for shipped orders, and a pickup fee (often zero). The rule that applies is shown at checkout and recorded on the order.
4. **Minimums are set by staff.** When the minimum is reached the run may close early. If the end date passes without
   reaching it, staff choose to cancel (refund everyone) or proceed, and the decision is recorded with the reason.
5. **Seller payout** happens at delivery, after the existing complaint hold window.
6. **Launch scope** is switched on per state and city by staff; nothing outside an enabled location is offered a run.
7. **Currency.** Customers pay in US dollars. Inventory sourcing and costs are recorded in the currency paid (USD, MXN, NGN, GHS …)
   with the exchange rate used, and valued in USD for reporting.
8. **Tax.** AfroTransact collects sales tax as marketplace facilitator and remits it. The tools (rates, nexus tracking, returns,
   remittance record, seller statement) are gated by role and permission.

## Consequences

- No second charge, no card holds that expire, no dunning for runs.
- Refund-on-cancel must be reliable and idempotent, and visible to the customer and to staff.
- Fee rules, run minimums and coverage need staff screens with audit and reasons.

## Implementation notes (pre-order windows)

Pre-order windows are the one customer-facing offer; a delivery run is how a closed window's paid orders are delivered.

- **Minimum and decision (§4).** `PreorderWindow.minOrders` is staff-set (default `PREORDERS_DEFAULT_MIN_ORDERS`). A window that
  closes below it becomes `AWAITING_DECISION`: nothing is cancelled or handed to a run, and `preorder.window.decision_needed`
  tells staff. Staff cancel or proceed with a required reason; each decision is logged (who, when, why) and published as
  `preorder.window.decided`. Reaching the minimum early lets staff close the window early.
- **Cancellation (§1).** `preorder.window.cancelled` and `delivery-runs.run.cancelled` make orders-service cancel the PREORDER
  shipments through the customer-cancellation path: a captured payment is refunded in full with the delivery fee, an
  authorisation that was never captured is released (orders authorise at checkout and capture when a package leaves, so a
  cancel before pick-up usually *releases* rather than refunds; the customer is whole either way). Cancelling is idempotent
  per shipment; orders reports `orders.preorder.refund_settled` / `refund_failed` and staff see the progress per order.
- **Payout (§5).** Settlement is not window specific: delivering a package starts payments' settlement hold (7 days by
  default), then the seller net is transferred; the delivery fee stays with AfroTransact (ledger 4250). Packages are
  marked delivered by the driver app, fulfilment or staff in orders-service; a run's `completed` does not do it.
- **Not built.** A dedicated permission for deciding (`preorders:manage` is used), a staff e-mail / chat recipient for
  `decision_needed`, and capture at order time (§1 says "charged at checkout").

## Implementation notes (currency, §7)

- **Where money is recorded.** Inventory lots keep the unit landed cost in the currency paid, the rate to USD used at receiving
  (with its date and source: entered, or the latest staff-managed rate on or before the day) and the USD value fixed then. Orders,
  payments and fees are untouched: customers pay in USD.
- **Valuation, COGS and margin are USD.** The inventory overview totals in USD with the amounts paid alongside; a shipment's COGS is
  the USD value of the lots picked (`cogsUsdMinor`), which is what retail-service posts to the GL.
- **Retail purchase orders** already carry the supplier's currency and the rate captured on the PO; receiving a foreign-currency PO
  now sends the lots to inventory in that currency with the PO's rate and the exact USD cost retail booked, so the lot shows what was
  paid and ties to the GL.
- **Rates are a default, not a rule.** The table (Settings → Exchange rates, `inventory:adjust`, a reason on every change, audited)
  only prefills the receiving form; the receiver can enter another rate.
- **Not built.** Components in more than one currency on a single lot (freight paid in dollars on pesos goods is entered in the lot's
  currency at the same rate), automatic rate feeds, and re-valuing stock when a rate changes (by design, a lot keeps its receiving rate).
