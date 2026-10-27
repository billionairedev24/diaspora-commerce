# ADR 0006 — AfroTransact credit, gift cards, referrals and membership

- **Status:** Accepted (defaults; every number below is staff-configurable and can be changed without a release)
- **Date:** 2026-10-27
- **Deciders:** platform owner
- **Related:** S320 credit, S321 gift cards, S322 referral, S323 membership; ADR 0005 (fees and checkout)

## Context

Customers can only pay by card or wallet, get refunds to the original method, and get free shipping through coupons. The
owner wants an Amazon-like set: AfroTransact credit, gift cards, a referral program and a membership with free delivery.

## Decision

One shopper **credit ledger** is the base of everything; the other three programs only grant or spend credit or waive fees.

1. **Credit** is a USD balance per customer kept as an append-only ledger in the payments service (grant, spend, refund,
   adjustment). The balance is the sum of entries; nothing is updated in place. Spending is atomic and idempotent per order.
   Credit does not expire, cannot be cashed out and cannot be transferred, except by gift card purchase.
   Refunds go to the original payment method by default; the customer (or staff, with a reason) can choose credit instead.
   Credit spent on an order returns as credit when that order is refunded.
2. **Gift cards** are bought with the normal payment, delivered by email on a chosen date, and redeemed into the recipient's
   credit (one redemption, hashed codes, rate limits). Amounts $10–$500, no expiry. Unredeemed cards are a liability, not
   revenue.
3. **Referral:** the referrer and the new customer each receive **$10** credit after the new customer's first order of at least
   **$30** is delivered. Caps per customer and abuse checks (self-referral, same device, address or payment method) hold
   suspicious referrals for staff. AfroTransact funds the reward.
4. **Membership:** **$9.99 a month or $99 a year**, 14-day trial, free AfroTransact delivery and carrier shipping on orders of
   at least **$25** (the fee rule resolution of ADR 0005 applies the waiver and records it on the order). Cancel any time; the
   benefit runs to period end. Failed renewals retry, then lapse.
5. **Money rules.** Credit is stored value: terms (no cash-out, non-transferable) are shown at the point of use; legal review of
   stored-value and gift card rules per state is a launch checklist item (docs/runbooks/production-readiness.md). Finance sees
   credit and gift card liabilities as separate reports.

## Consequences

- One ledger gives finance a single liability figure and gives every program one audit trail.
- Maker-checker applies to staff grants above a limit, as for ledger adjustments.
- Numbers (rewards, limits, prices, minimums) live in staff settings, not code.
