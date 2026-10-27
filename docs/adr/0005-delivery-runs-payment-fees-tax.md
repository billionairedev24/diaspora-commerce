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
  marked delivered by the driver app, fulfilment or staff in orders-service; a run's `completed` does not do it. Since S264 the
  link is closed end to end: the driver delivers (with the customer's code at a pickup point too), orders records
  `deliveredAt` and calls payments' settlement, and the same `orders.shipment.delivered` event completes the run once every
  order is delivered or returned.
- **Delivery fee rules (§3).** `delivery_fee_rule` holds one versioned rule per method (`AFROTRANSACT_DELIVERY` bands by
  distance, `CARRIER` handling / fallback / free-above, `PICKUP` fee) and currency; `delivery_fee_rule_change` is the audit
  trail with a required reason. Windows, runs and checkout resolve a fee through one code path (`DeliveryFees`), a window may
  override the door or pickup fee, and beyond the last band a door-only window is routed to carrier shipping. Orders store a
  snapshot (`shipment.delivery_fee_rule`), so edits never change past orders. Staff manage all of it on one screen
  (Settings, Money, Delivery fees). See the delivery-runs README. Not built: carrier-gateway live quotes in orders (the
  fallback fee applies), express on rules, and a dedicated permission (`preorders:manage` is used).
- **Batched seller pickups and dispatch (S264).** The run's confirmed event starts fulfilment's planning: one route per
  capacity chunk, a pickup stop per seller before the drops, bags per seller, pickup-point drops on the same handover code and
  proof of delivery. Sellers get the pickup time and a per-product and per-order pick list (Seller Center, Orders), staff a
  Delivery runs tab in Dispatch. Orders' shipment events move the run (collecting, out for delivery, completed) idempotently.
  Orders gained a per-line seller and lets the run's courier act on PREORDER pickup-point shipments. See the delivery-runs and
  fulfilment READMEs. Not built: printable bag labels for sellers, coordinates for seller pickup stops and pickup points, a
  dedicated pick-list permission and an audit trail for the manual run override.
- **Not built.** A dedicated permission for deciding (`preorders:manage` is used), a staff e-mail / chat recipient for
  `decision_needed`, and capture at order time (§1 says "charged at checkout").

## Implementation notes (run economics, announcements and advertorials: S262, S263, S265)

- **Run economics are staff-set, like fees.** The driver cost model (cost per hour and per mile, the minutes a sweep, a stop and
  a handover take, round trip) is one versioned row in delivery-runs, edited in Settings, Money, Run economics with a required
  reason and an append-only change log; the environment variables are only the defaults until the first save. Break-even is
  shown beside the minimum order count while a window is created, a window's overview shows its orders, revenue, delivery fees,
  estimated cost and margin, and a run has a P&L before and after it. A cancelled window or run reports the orders refunded in
  full (delivery fee included), not revenue.
- **Announcements** go through notifications from the delivery-runs events (window and run open, the cutoff reminder, dispatch,
  confirmed and cancelled), per channel the shopper consented to. WhatsApp messages are behind
  `DELIVERY_RUNS_WHATSAPP_ENABLED` (off) because they are sent only from templates Meta must approve; consent and the community
  link work without it.
- **Advertorials** live in delivery-runs because their audience, expiry (the close or cancellation) and attributed orders are all
  this service's data. Featured products (up to four, chosen by name, no price kept) and the orders credited to a run's advertorial
  are built; UTM visits and sign-ups are first-party counters, and what product analytics can consume and what WhatsApp needs from
  Meta are in `docs/engineering/run-attribution.md`. Not built: a mobile app screen, and pushing UTM parameters to an analytics
  sink (the repository has none).

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

## Implementation notes (run lifecycle in the staff console: S260)

- **Staff schedule and decide on runs.** Pre-orders, Delivery runs creates a run for a location (its area, a geo polygon or a
  radius around the town, comes from the location), changes it before it opens, announces and opens it, moves its cutoff,
  confirms it and cancels it. Extending and cancelling need a reason; confirming a run below its minimum is an override and
  needs one too (§4 applies to runs staff schedule as it does to windows). Each decision is logged in delivery-runs with who
  (the cutoff job is `system`), when, why, the orders and the minimum at that moment.
- **Cancelling refunds everyone (§1).** `delivery-runs.run.cancelled` lists every order and customer on the run; orders-service
  cancels their PREORDER packages through the customer-cancellation path (refund in full with the delivery fee, or release the
  authorisation, and release stock where inventory holds any) and customers are told. A run cancelled along with its window
  keeps the window's reason and the customers get one message, not two.
- **Fee choice.** A run's fee is the delivery fee rule for its location's distance unless staff set a custom fee (§3); an
  open run keeps the fee its customers were quoted.
- **Not built.** A run staff schedule receives no customer orders until a service reserves slots on it (checkout orders into
  windows, and a closing window creates its own run); a dedicated permission (`preorders:manage` is checked in the console,
  `delivery-runs:manage` by delivery-runs); creating and editing locations in the console.
