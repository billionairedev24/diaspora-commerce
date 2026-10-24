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
