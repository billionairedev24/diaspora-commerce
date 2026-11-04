# Membership: configurable tiers with Prime-style benefits (S323)

Implements ADR 0006 section 4. Shoppers join a **tier** that staff define in Settings; the tier gives benefits from a fixed
menu. Billing, the credit ledger and the fee rules already live in payments, so membership is a new aggregate there.

## Why payments owns it

The seller plan billing in `java/services/seller` is the template for the machinery (period, trial, retry, lapse, audit), but
a shopper subscription is a different aggregate: the payer is a customer, the charge is off-session on a saved Stripe card
through the `StripeGateway` port, the revenue is booked by `LedgerPoster`, cashback and the monthly credit go into the
append-only credit ledger (source `MEMBERSHIP`), and the fee waivers extend the fee rule resolution payments already does for
checkout (ADR 0005). Putting it anywhere else would need a second ledger writer or a synchronous call per benefit.
Code: `java/services/payments` (`domain/Membership*`, `domain/FeeWaivers`, `application/Membership*`, Flyway `V22__membership.sql`).

## Model

| Thing | Notes |
|---|---|
| `membership_tier` | code, name, status (`DRAFT`, `PUBLISHED`, `RETIRED`), sort order, monthly and yearly price, trial days, benefits (jsonb, validated per code), optimistic `version`. Every change goes to `membership_tier_change` with actor, reason, before and after. A published tier never goes back to draft; retiring stops sign-ups and keeps members. |
| `membership` | one live membership per customer. Status `TRIALING`, `ACTIVE`, `PAST_DUE`, `CANCELED`, `LAPSED`, `EXPIRED`; source `PAID` or `COMPLIMENTARY`. The **price is locked** per membership (at sign-up or the last plan change), so a later price edit never changes what a member pays. |
| `membership_charge` | each fee charge (`JOIN`, `RENEWAL`, `UPGRADE`) with attempt, status, failure code and refund. Deterministic idempotency keys per membership and period. |
| `membership_benefit_use` | every benefit applied: fee waived, cashback, monthly credit. `unique nulls not distinct (benefit, order_id, ref)` makes each one idempotent. |
| `membership_trial` | one trial per customer and per card (a hash of brand, last four and expiry). |
| `membership_settings` | refund window and retry days, with history. |
| `membership_grant` | idempotency of `MembershipGrants.grantDays`. |

Seeded tier: **Plus**, published, $9.99 a month or $99 a year, 14-day trial, free AfroTransact delivery and free carrier
shipping on orders of at least $25.00. Everything is editable without a release.

## Benefits menu

| Code | Takes | How it is applied |
|---|---|---|
| `FREE_DELIVERY`, `FREE_SHIPPING` | order minimum, per-order cap | The fee rules produce the fee (ADR 0005); `FeeWaivers` removes it when the order's goods reach the minimum, capped per order. Pickup and express are never waived. Applies from the first day, trial included. |
| `CASHBACK` | percent (basis points), optional minimum and cap | Credit on a delivered order, once per order. Hooked on settlement `markDelivered`; a 2-day sweep picks up any miss. |
| `MONTHLY_CREDIT` | amount | Granted once a month by the hourly job, from the first paid period. |
| `BOOSTED_REWARDS` | percent | Applied by the referral program: when it grants a reward to a paying member, credit and free days are raised by this share (a 50% boost turns 10.00 into 15.00 and 14 days into 21), within the sanity bounds of a reward. Also exposed as `rewardBoostBps` in the entitlements. Gift cards are deliberately not boosted: a card is paid for, and a boost would create unbacked credit. |
| `EARLY_ACCESS` | hours | Pre-order windows (eligibility) and delivery runs (availability and slot reservation) open this many hours earlier for members. |
| `PRIORITY_SUPPORT` | none | A flag in the entitlements; the staff Customer 360 shows it so support handles the member's cases first. |
| `MEMBER_PRICES` | none | A flag only. Catalog and search do not read it yet: see "Not built". |

**Credit benefits (cashback, monthly credit, boosted rewards) start with the first paid period**, never during a trial, so a
trial cannot be used to harvest credit. Store credit can only buy products and services: no route pays it out.

## Billing

- Join is on a saved card (the default one unless named), off-session intent with immediate capture, `Idempotency-Key`
  required. A first-time member on a card that never had a trial starts the tier's trial and pays nothing; any other join
  charges the first period. A declined card changes nothing.
- **Renewal job** (every 5 minutes, `FOR UPDATE SKIP LOCKED`, one transaction per membership): a canceled membership ends,
  complimentary days expire, a scheduled plan change applies, and a paid one is charged for its next period. A failure makes it
  `PAST_DUE` and retries on the staff-set schedule (default 1, 3 and 5 days); after the last failure it **lapses** and the benefits stop.
  Benefits stay on while `PAST_DUE`.
- **Upgrade** takes effect at once and charges the difference for the unused part of the period. **Downgrade or another
  interval** is scheduled for the period end. Choosing the current plan again takes a scheduled change back.
- **Cancel** stops renewals; the benefits run to the end of the paid period. `resume` takes it back before then.
- **Revenue** is booked Dr 1000 (Stripe clearing) and Dr 5000 (processing fee) / Cr 4700 (membership revenue); a refund is
  Dr 4700 / Cr 1000.

### Refund rule for the membership fee

A member who cancels **within the staff-set window after a charge (default 7 days) and before any benefit was used** may
take the whole fee back to the card, and the membership ends at once. Otherwise there is no refund and the benefits run to the period end.
Staff can refund any fee with a reason (`payments:configure`). Credit is never refunded to a card. The storefront shows
whether the rule applies and why not (`NOT_PAID`, `WINDOW_PASSED`, `BENEFIT_USED`).

### Trial abuse guard

One trial per customer (any earlier membership counts) and one per card. A second attempt on the same card joins and
charges the first period.

## Fee waivers on the order

Checkout (orders) asks `POST /v1/memberships/waivers` for the waivers of the shipments' fees, passes them with the payment, and
payments records them on the order snapshot with **tier and benefit** (`orders.shipment.membership_waiver`, V9). The order
total is lower by the waiver; the fee as priced stays visible. The BFF previews the same with
`POST /v1/memberships/me/savings` (cart and checkout), and for a shopper who is not a member returns the upsell "Join Plus to save $X on this order".
The same quote returns `cashbackMinor`, the credit the member will earn when the order is delivered (0 during a trial), which the cart and checkout show as "credit earned"; the shopper can join from the upsell box without leaving the page. Orders fails open: if payments cannot answer, the fee is charged as priced and never wrongly waived.

## Early access

Orders passes `earlyAccessHours` (from the entitlements) to delivery-runs. A run or pre-order window that is announced but not
yet open becomes available to members that many hours before its opening; the reservation honours the same hours.

## Referral
The referral program (S322) grants membership days through its `MembershipGrants` port; the adapter `MembershipProgramGrants`
calls `MembershipRewardGrants`, which gives days of the entry tier (the published tier with the lowest sort order) through
`MembershipGrants.grantDays(customerId, tierCode, days, reason, idempotencyKey)` (in process) and
`POST /v1/memberships/grants` (`payments:credit.grant`). A customer without a live membership gets a complimentary one that
ends on its date and needs no card; a member has the days added to the current period. Idempotent per key, so the reward
job can retry. With no published tier the reward stays `DEFERRED`. The `BOOSTED_REWARDS` benefit makes a member's reward bigger.

## Emails
`payments.membership.changed` drives three emails in the member's language (en, fr, es, ar, yo): a renewal or trial
conversion receipt, a failed payment (retrying, or the last try when it lapses) and "your free trial ends on ..." three days
before the trial ends (the hourly job records `TRIAL_ENDING` once per membership). Templates: `payment.membership.renewed`,
`payment.membership.payment_failed`, `payment.membership.trial_ending` (notifications migration 0022).

## Staff

Settings, Money, **Membership**: the tier editor (price, trial, benefit toggles and numbers, publish and retire; Save is
disabled until something changes; a reason of at least 15 characters is required and is kept with who changed it) and the
billing rules. Payouts and ledger: **Members** (by name, tier, status) and the **Membership report** (members, churn, cost of
the benefits, credit granted, fees waived, MRR, ARR). Support's customer view shows the member, priority support.

## API

Customer: `/v1/memberships/{tiers,me,me/join,me/change,me/cancel,me/resume,me/uses,me/entitlements,me/savings}`. Services:
`waivers`, `customers/{id}`, `customers/{id}/entitlements`, `grants`. Staff: `admin/{tiers,settings,members,charges/{id}/refund,report}`.
BFF (contract `gateway-bff.yaml`, tag Membership): `getMembershipOffer`, `getAccountMembership`, `joinAccountMembership`,
`changeAccountMembership`, `cancelAccountMembership`, `resumeAccountMembership`, `listAccountMembershipBenefits`,
`getCartMembershipSavings`. Events: `payments.membership.changed`, `payments.membership.tier_changed`.

## Not built (documented gaps)

- **Member prices**: the benefit can be switched on and is in the entitlements, but catalog, search and the cart do not apply a member price yet. It needs a price list per offer.
- **Annual fee deferral**: a yearly fee is booked as revenue when charged, not spread over twelve months.
- **Mobile apps** have no membership screens.
- Strong customer authentication: a card that needs a bank confirmation is declined with `MEMBERSHIP_AUTHENTICATION_REQUIRED` and the shopper tries another card.
