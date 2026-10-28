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
   **Store credit can never be converted to cash**: it can only buy products and services. No cash-out, payout, card refund of
   credit or transfer exists in the product or the API; credit does not expire. A refund returns each part to what paid it
   (credit portion as credit, card portion to the card); the customer, or staff with a reason, may choose credit for the card
   portion, which is one way.
2. **Gift cards** are bought with the normal payment, delivered by email on a chosen date, and redeemed into the recipient's
   credit (one redemption, hashed codes, rate limits). Amounts $10–$500, no expiry. Unredeemed cards are a liability, not
   revenue.
3. **Referral is the platform's responsibility.** AfroTransact funds it. What the referrer earns and what the referred earns
   are configured separately in staff Settings (credit amount or membership days, qualifying event, minimum order, caps,
   validity, on/off), each change with a reason. Defaults: $10 credit to each after the friend's first order of at least $30 is
   delivered and its return window has passed. Abuse checks (self-referral, same device, address or payment method) hold
   suspicious referrals for staff.
4. **Membership works like Amazon Prime, with configurable tiers.** Staff define tiers (for example Plus and Premium) in
   Settings: monthly and yearly price, trial days and which benefits the tier has, from a menu: free AfroTransact delivery and
   carrier shipping (order minimum and cap), a percent of spend returned as credit, member prices or deals, early access to
   pre-order windows and runs, priority support, free returns, bigger referral or gift rewards, a monthly credit grant. Defaults
   to seed: Plus $9.99 a month or $99 a year with 14-day trial and free delivery and shipping on orders of at least $25.
   Benefits are applied through the fee rule resolution of ADR 0005 and the credit ledger and recorded on every order. Cancel
   any time; the benefit runs to period end; failed renewals retry, then lapse.
5. **Money rules.** Credit is stored value: terms (no cash-out, non-transferable) are shown at the point of use; legal review of
   stored-value and gift card rules per state is a launch checklist item (docs/runbooks/production-readiness.md). Finance sees
   credit and gift card liabilities as separate reports.

## Consequences

- One ledger gives finance a single liability figure and gives every program one audit trail.
- Maker-checker applies to staff grants above a limit, as for ledger adjustments.
- Numbers (rewards, limits, prices, minimums, tier benefits) live in staff settings, not code.
