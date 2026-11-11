# Delivery quotes: asking for a price when the address is too far

Spec: `docs/engineering/listing-rules.md` D20, D21 and section 3 (launch shipping). Decision record:
[ADR 0007](../adr/0007-delivery-quotes-in-orders.md). The staff queue copies the
[application review workflow](application-review-workflow.md) (claim, assign, release, escalate, SLA, names on the timeline).

## What it is

An address outside the delivery zones gets no flat-fee delivery. Checkout never dead-ends: it shows "Too far for standard
delivery. Ask us for a quote". The shopper asks, customer care agrees a delivery amount (with a reason), the shopper is emailed a
link back to checkout and pays it there. The order carries that amount as its shipping, taxed like any other shipping.

```
shopper          storefront / BFF          orders-service                 customer care
  | ask for a quote --> POST /v1/me/delivery-quotes --> case REQUESTED ---> queue "Unassigned"
  |                                                      |  claim / assign / release / escalate (SLA clock)
  |                                                      v
  |                                          agree amount (reason, bounds) or decline (reason)
  | <-- email "quote ready" (pay link /checkout?quote=<id>) -- orders.delivery-quote.ready
  | checkout with quoteId --> orders validates and USES the quote in one statement --> shipping = agreed amount
```

## Statuses (what the shopper sees)

| Status | Shown as | Meaning |
|---|---|---|
| `REQUESTED` | Requested | asked; nobody on staff holds it |
| `IN_PROGRESS` | Being quoted | a staff member holds it |
| `READY` | Quote ready | an amount is agreed; valid until `expiresAt` (default 48 hours) |
| `DECLINED` | Declined | customer care declined; the shopper is not shown the reason |
| `EXPIRED` | Expired | not used in time (a ready quote past its time reads as expired at once) |
| `USED` | Used | an order carries the amount |

Account page: **My quote requests** (`/account/delivery-quotes`). Signed-out shoppers sign in first (401 from the BFF).

## The request

`POST /v1/me/delivery-quotes` (gateway-bff, relayed to orders `POST /v1/delivery-quotes` with the shopper's token):

- Snapshot: cart lines, the packages they form, the address's city, state and ZIP (the street is only hashed, `ship_to_key`),
  preferred contact (EMAIL, PHONE, SMS), optional note up to 500 characters.
- **Idempotent**: the same shopper, cart (variant, seller, quantity) and address returns the same request while it is live
  (requested, being quoted, ready), and for `duplicate-window` minutes (default 15) after any outcome. A unique partial index
  (`delivery_quote_one_live`) closes the race between two tabs.
- **Rate limited**: at most `requests per shopper per hour` (default 5) new requests; repeats are not counted. 429
  `orders.quote-rate-limited` with `Retry-After`. The BFF also rate-limits the route per client address.
- The shopper's email and phone are never stored on the case or put in events: notifications resolves the shopper from
  `customerId`.

## The staff queue (staff console, Delivery quotes, under Commerce)

Tabs: **My queue**, **Unassigned**, **Waiting on customer**, **Closed**, plus the chips **Escalated**, **Overdue** and **All**.
Columns: reference, customer, deliver to, items, with, waiting, SLA, escalation, charge, asked, status. The sheet leads with
where it is (status, who holds it, how long), then request, cart, who has it, escalation, the agreed amount, and the timeline
(names, never ids).

| Action | Who | Rule |
|---|---|---|
| Claim | `delivery-quotes:agree` | case unassigned; repeating it is a no-op |
| Assign / take over | `delivery-quotes:assign`, or the person an escalation went to | assignee must hold `delivery-quotes:agree` |
| Release | the assignee, or a manager | back to the pool |
| Escalate now | the assignee, or `delivery-quotes:assign` | reason required, one level at a time |
| Agree a charge | `delivery-quotes:agree` | amount within the bounds, **reason required** (5+ characters); the holder, or a manager if someone else holds it |
| Decline | `delivery-quotes:agree` | reason required; never shown to the shopper |

There is **no two-person rule**: whoever holds the case decides it. Every transition (requested, claimed, assigned, released,
escalated, agreed, declined, expired, used, restored) writes a `delivery_quote_event` row in the same transaction, with the
person's name: that is the timeline and the audit trail. Settings changes are in `delivery_quote_settings_audit` (before, after,
who, why).

### Permissions (identity migration V41)

| Permission | Roles |
|---|---|
| `delivery-quotes:view` | Customer Support, Operations Manager, Auditor, Super Admin |
| `delivery-quotes:agree` | Customer Support, Super Admin |
| `delivery-quotes:assign` | Operations Manager, Super Admin |

The staff console module `deliveryQuotes` is derived from these (VIEW with view, EDIT with agree or assign).

## SLA, escalation and settings

**Delivery quotes > SLA, escalation and amounts** (needs `delivery-quotes:assign`, a reason, audited). Defaults:

| Setting | Default |
|---|---|
| Answer within | **1 business day** (Monday to Friday, `at.orders.zone`; a weekend request starts Monday 00:00) |
| Due soon after | 75 % of the time |
| Escalate a request nobody has taken after | 4 hours |
| Level 2 after the SLA plus | 24 hours |
| Auto-assignment | off (round robin and least loaded available) |
| Smallest and largest amount | $5.00 and $500.00 |
| A quote can be used for | 48 hours |
| Requests per shopper per hour | 5 |
| Repeat window | 15 minutes |

Escalation reuses the review workflow's machinery: `DeliveryQuoteJobs.escalate` runs every 5 minutes
(`at.orders.jobs.quote-escalation-interval`), each request is checked under its row lock and moves up one level per run, so
replicas and re-runs escalate once. A level with nobody set is skipped and retried. Events `orders.delivery-quote.assigned`
and `.escalated` carry `recipientUserIds` (notifications does not consume them yet; staff find cases in My queue and Escalated).
`DeliveryQuoteJobs.expire` runs every minute (`quote-expiry-interval`) and turns a ready quote past its time into `EXPIRED`.

## Using the quote at checkout

The pay link is `/checkout?quote=<id>` (in the email and on My quote requests). Checkout shows "Delivery quote applied" and sends
`deliverySelections[].quoteId` on every delivered package (never pickup or a pre-order package).

orders validates and uses the quote **in one SQL statement** (`consume`), when the checkout request arrives:

1. it belongs to this shopper (another shopper's quote is reported exactly like a missing one: `orders.quote-not-found`);
2. its cart hash matches the selected cart lines and its ship-to hash matches the address (else `orders.quote-changed`:
   "Your cart or delivery address changed since this quote was agreed, so it no longer applies. Ask for a new quote.");
3. it is `READY` and not expired (`orders.quote-expired`), not used (`orders.quote-used`), and ready at all (`orders.quote-not-ready`).

The BFF turns all of these into one 422 `DELIVERY_QUOTE_VOID` with `extra.reason` (changed, expired, used, not_ready,
not_found) and the plain message.

The agreed amount becomes the shipping of the order's **first delivered shipment, once**, whatever the number of packages or
fulfilment nodes (later shipments carry 0). The shipment keeps `delivery_fee_rule.source = DELIVERY_QUOTE` with the quote
reference, so the quote can be traced from the order. A membership never waives it, and Retail, FBA and seller packages are
treated alike. **Tax**: `shippingMinor` goes through the same per-line allocation as any shipping
(`at.orders.tax.shipping-allocation`, checkoutTax), so shipping tax follows the item; nothing is guessed. The estimate on the
checkout page says tax on the delivery charge is added when the order is placed.

**Retries and failures**: a retried checkout (same Idempotency-Key) is the same order and finds its own use of the quote. If
the order does not complete (card declined, abandoned, superseded, saga failed) the compensation `abandon` restores the quote
(`RESTORED` on the timeline) while it is still valid; a paid order keeps it used.

## Events and notifications

On `at.order.events` (key = quote id; contracts/asyncapi/fragments/orders.yaml): `orders.delivery-quote.requested`, `.ready`,
`.declined`, `.expired`, `.assigned`, `.escalated`. Payloads carry ids, reference, amount and city only. notifications sends
`delivery_quote.ready` and `delivery_quote.declined` emails in en, fr, es, ar and yo (migration 0027). **needsReview**: the
fr, es, ar and yo copy is machine translated. Redeliveries are safe: the outbox is idempotent per event id and the consumer
de-duplicates on it.

## Restart and migration order

1. identity (V41 permissions), 2. orders (V11 tables; new jobs start with it), 3. notifications (templates 0027, rules),
4. gateway-bff, 5. staff console and storefront. orders asks identity for the staff roster with its service token (scope
`users:read`, already held by `orders-service`).

## FAQ

- **A shopper says the quote "doesn't apply".** Their cart or address changed after it was agreed (or it expired or was used).
  Ask for a new one; the old one stays on the timeline. Do not edit rows by hand.
- **Nobody is escalated.** No target is set for that level, or identity was unreachable. Set the chain in the settings; the
  Overdue chip still lists the request.
- **A quote shows Used but the order was not paid.** `abandon` restores it when the checkout fails. If the saga history is
  gone, the order is voided by `CheckoutService.supersede`, which also calls it. Check the timeline for `RESTORED`.
- **Pre-orders and pickup.** Not affected: a quote is never applied to them, and a checkout that names a quote on a pickup
  package is refused (and the quote restored).
- **Free-shipping coupons.** A coupon's shipping discount reduces the shipping of the order like any shipping, quote included.
  This is a decision for the owner (see the final report of the feature).
