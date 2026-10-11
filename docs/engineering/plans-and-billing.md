# Plans, trials, credits and rewards: ours; charges: Stripe Billing

AfroTransact owns the seller and provider **plan catalogue, trials, credits and rewards**, with its own screens (staff
console › Plans & billing, Seller Center › Plan & billing). Stripe Billing still **executes the charges**: automatic
collection, Smart Retries, invoices and their PDFs, card updates and 3DS. Nobody manages plans in the Stripe
Dashboard; the seller service creates and maintains the Stripe objects it needs.

Stories S292–S298 (epic E14). Code: `java/services/seller` (domain `Plan*`, `Trial*`, `Reward*`, `BillingGrant`,
`FreeDays`; use cases `PlanAdminService`, `PlanSyncService`, `TrialService`, `RewardService`,
`BillingAdjustmentService`, `EarningsCollectionService`); Flyway `V70__plan_catalog_trials_rewards.sql`.

## Plan catalogue and versions

| Table | What |
|---|---|
| `plan` | code, kind (SELLER / PROVIDER), name, tagline, features, limits, visibility (`PUBLIC`, `HIDDEN`, `INVITE_ONLY`), sort, status (`DRAFT`, `ACTIVE`, `RETIRED`), current version, Stripe Product id |
| `plan_version` | immutable prices and default trial: `DRAFT` → `PUBLISHED` (current) → `SUPERSEDED` |
| `plan_version_price` | amount per cadence (MONTHLY / QUARTERLY / ANNUAL), lookup key `<plan>_<cadence>`, Stripe Price id, last sync error |
| `plan_invite` | organisations that may choose an invite-only plan |
| `subscription.plan_version` | the version an organisation pays; it changes only by a plan change or a staff migration |

- Editing name, tagline, features, limits, visibility or sort changes the plan in place (optimistic locking).
- Changing a price or the trial saves a **draft version**; publishing it makes it current. Existing subscribers stay on
  their version. Staff migrate them with a preview (old and new price per organisation, effective at their next
  renewal), then `POST /v1/billing/plans/{code}/migration`. Migration swaps the subscription item's Price with
  `proration_behavior=none`, so the new price starts at the next renewal.
- `HIDDEN` plans are never listed; their subscribers keep them. `INVITE_ONLY` plans are listed to invited
  organisations only. `RETIRED` plans take no new subscribers.
- The six original plans are version 1 of the seeded catalogue. Their prices follow the old rule (quarterly = 3
  months less 5 %, annual = 10 months); from now on staff set each cadence's price.

## Stripe sync (never edit a Price)

On publish, at startup (`at.seller.billing.sync-on-startup`, default true) and from "Sync with Stripe" in the console,
the seller service compares each published version's prices with Stripe:

| State | Meaning | Sync does |
|---|---|---|
| `IN_SYNC` | the stored Price matches (amount, USD, interval) and is active | nothing |
| `ADOPTABLE` | no stored id, but an active Price under the lookup key matches (made by an older `make stripe-setup`) | stores its id (and its Product) |
| `MISSING` | no Price | creates one |
| `DRIFT` | the stored or looked-up Price differs, was archived or deleted | creates a new Price with `transfer_lookup_key=true`, archives the old one (`active=false`) |
| `FREE` | free plan | nothing (free plans have no Stripe subscription) |

A Price is never edited: amounts are immutable at Stripe and we never try. Archived Prices keep billing the
subscriptions on them until staff migrate those subscriptions. A version whose amounts did not change (only the
trial did) reuses the previous version's Price. Activation and plan changes call `ensurePrice`, so a Price that failed
to sync is created on first use.

| Call | Idempotency-Key |
|---|---|
| Product | `plan-product-<plan>` |
| Price | `plan-price-<plan>-v<version>-<cadence>-<amount>[-<replaced price id>]` |
| Subscription create | `subscription-<org>` (activation), `subscription-<org>-<plan>-<cadence>` (from a free plan) |
| Migration | `migrate-<org>-<plan>-v<version>` |

Subscriptions carry `metadata[plan_code]`, `metadata[plan_version]` and `metadata[cadence]`. Webhooks map a
subscription item back to our plan by Price id (lookup key as a fallback).

`make stripe-setup` no longer creates plan Prices. It checks the keys, Connect and the seller account settings, and
reports the plan Prices that already exist.

## Trials

The trial length is decided at activation and sent as the subscription's **`trial_end`** (a timestamp, not
`trial_period_days`):

1. a **per-seller override** by staff (billing:adjust, reason code) wins, even when shorter;
2. otherwise the **longest** of the plan version's default trial and the matching **campaigns**. A campaign matches by
   sign-up window (application submission time), kind, eligible plans, redemptions left and, when it has one, its
   **promo code** (entered on the onboarding Plan step, validated there).

While a subscription is trialing, staff can change its trial ("ends N days after it started"): the same Stripe call as
free days. Every change is recorded as a grant (`TRIAL_END`).

## Rewards

Rules (`reward_rule`, staff with plans:manage) say "reach `threshold` of `metric` (in the last `windowDays` days and/or
within `withinDaysOfApproval` days of approval) and get a reward". Limits: once per seller (unique `(rule_id,
org_id)`), `startsAt` / `endsAt`, eligible plans, kind, and `maxGrants` overall (counted under a row lock in the
same transaction as the grant).

| Metric | Source | Evaluated |
|---|---|---|
| `GROSS_SALES` (cents, USD) | `orders.shipment.delivered.totalMinor` | on the event, daily |
| `COMPLETED_ORDERS` | `orders.shipment.delivered` | on the event, daily |
| `COMPLETED_BOOKINGS` | `booking.completed` | on the event, daily |
| `ACTIVE_LISTINGS` | `offers.offer.created/updated/withdrawn` (ACTIVE and product live) | on the event, daily |
| `DAYS_SINCE_APPROVAL` | final approval time | daily |

Events are de-duplicated on their `event-id` (inbox consumer `seller.reward-metrics`). Sums only count days inside
the window; a gauge (listings, days) must reach the threshold while a "within N days of approval" window is open.
Rewards apply to organisations on a paid plan with a Stripe subscription; AfroTransact Retail is never billed.
"Preview" evaluates an unsaved or edited rule over every organisation.

### How rewards and adjustments map to Stripe

| Grant | Stripe | Why |
|---|---|---|
| **Free days** | `POST /v1/subscriptions/{id}` with `trial_end` = (trial end if trialing, else current period end) + N days and `proration_behavior=none` | Exact days whatever the cadence. A 100 %-off coupon on the next invoice would give a whole month, quarter or year (coupons work per invoice), and pausing collection still creates invoices and moves no dates. An active subscription keeps the period it paid for and gets the N days after it; Stripe creates no invoice until `trial_end` and restarts the billing cycle on that day. |
| **Credit** | customer balance transaction (`amount` negative) | Stripe applies the balance to the next finalised invoices automatically; the balance is mirrored (`subscription.credit_balance_cents`, from the transaction and every invoice's `ending_balance`). |
| **% off N invoices** | coupon `percent_off`, `duration=repeating`, `duration_in_months` = N × cadence months, `max_redemptions=1`, our own id `at_<grant>`; added to the subscription's `discounts` while keeping the discounts it already has | Repeating coupons are counted in months, so N quarterly invoices = 3N months. |
| **Waived invoice** (staff) | `POST /v1/invoices/{id}/void` (open invoices only) | No further retries, nothing owed. |

Every grant is written first (`billing_grant`, status `PENDING`) with who, what and why, then applied with its own
idempotency keys (`grant-<id>-trial|balance|coupon|discount|void`), then marked `APPLIED` with the Stripe object ids.
A Stripe failure leaves it `FAILED`; a job retries it with the same keys (`at.seller.jobs.grant-retry-interval`).
Applied grants publish `seller.billing.grant_applied` (at.seller-subscription.events); notifications emails the
owners (`seller.billing.reward_earned` for rules, `seller.billing.credit_granted` for staff adjustments). Staff
requests carry an `Idempotency-Key` header: a replay returns the first grant.

## Collection from earnings (flag, default off)

`at.seller.billing.collect-from-earnings` (`COLLECT_PLAN_FEES_FROM_EARNINGS`, default `false`), plus a per-organisation
opt-in by staff. When Stripe creates a renewal invoice (`invoice.created`, draft, `subscription_cycle`, about an hour
before it finalises), the seller service records it (`earnings_collection`, `REQUESTED`). A job asks payments to
withhold the amount from the organisation's next settlement transfer (`POST /v1/orgs/{orgId}/plan-fee-withholdings`,
`Idempotency-Key: plan-fee-<invoice>`). Once payments answers `WITHHELD`, the seller service posts a customer balance
credit for the same amount (`earnings-<invoice>`), so the invoice is paid from the balance and the card isn't
charged. If payments declines or doesn't answer in time, nothing changes and Stripe charges the card.

**Payments side (S298, not built):** the withholding endpoint, deducting withheld fees from the next transfer in the
ledger, and releasing a withholding when the invoice is voided. Keep the flag off until S298 ships.
