# ADR 0007 — Delivery quotes live in orders-service

- **Status:** Accepted
- **Date:** 2026-11-11
- **Related:** listing rules D20, D21, section 3 (launch shipping); `docs/runbooks/delivery-quotes.md`; application review workflow

## Context

An address outside the delivery zones needs a negotiated delivery price: a shopper request, a staff case with an owner, an
SLA and escalation, an agreed amount, and an order that carries it. The application review workflow (seller-service) already
has claim, assign, release, escalate, SLA and "who has it".

## Decision

**orders-service owns the case.** The workflow is copied (same shape, same rules, same wording), not shared.

Why not seller-service, where the review workflow lives:

- Hexagonal boundaries. The case is bound to a shopper's cart snapshot, an address and an order. Its consumption must be atomic
  with checkout (single use, belongs to the shopper, matches cart and address, not expired) and restorable when the order does
  not complete. In orders that is one local SQL statement inside the checkout request and one compensation in the saga. In
  seller-service it would be a synchronous cross-service call inside the checkout saga with its own failure modes.
- seller-service's review code is application-specific (stages REVIEW and APPROVAL, a two-person rule, the applicant, documents).
  Its `ReviewSettings`, `ApplicationCase` and `ReviewAssignmentServiceImpl` are not a library; extracting one now would couple two
  domains for the sake of reuse of about 300 lines. What is reused is the pattern, the staff console framework
  (`staffResource`, queue tabs, `flagReason`, names on the timeline) and the settings shape; the staff roster still comes from
  identity (`GET /v1/internal/staff`).
- The shopper's identity, cart and address are already orders' inputs (`CartClient`, `AddressBook`).

Choices that follow:

- One stage, one decider: no two-person rule (the owner said it is not needed); every decision needs a reason and is audited.
- The agreed amount reaches the order through the delivery selection (`quoteId`), is used when the checkout request arrives
  (so the validation and the use are one atomic step) and is recorded on the shipment's fee rule snapshot
  (`source DELIVERY_QUOTE`), which needed no schema change on the order tables.
- The amount is charged once, on the first delivered shipment: one negotiated amount covers the whole delivery of the cart,
  whatever the number of packages. Tax follows the existing per-line allocation of shipping.
- Events go on the existing `at.order.events` topic (aggregate `order`), so notifications and the topic ACLs need no new
  channel.

## Consequences

- Two review-like workflows exist (applications, quotes). If a third appears, extract the assignment and escalation core to a
  shared library then.
- orders now depends on identity for the staff roster (service token scope it already has).
- A negotiated shipping amount is not a "fee rule" from delivery-runs; it is identified by its source on the shipment.
