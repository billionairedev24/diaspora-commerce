# Plans, trials, credits and rewards: ours; charges: Stripe Billing

AfroTransact owns the seller and provider **plan catalogue, trials, credits and rewards**, with its own screens (staff
console › Plans & billing, Seller Center › Plan & billing). Stripe Billing still **executes the charges**: automatic
collection, Smart Retries, invoices and their PDFs, card updates and 3DS. Nobody manages plans in the Stripe
Dashboard; the seller service creates and maintains the Stripe objects it needs.

Stories S292–S298 (epic E14). Code: `java/services/seller` (domain `Plan*`, `Trial*`, `Reward*`, `BillingGrant`,
`FreeDays`, `PlanFeeCollection`; use cases `PlanAdminService`, `PlanSyncService`, `TrialService`, `RewardService`,
`BillingAdjustmentService`, `EarningsCollectionService`, `PlanFeeCollectionService`); Flyway
`V70__plan_catalog_trials_rewards.sql`, `V71__plan_fee_collection.sql`; payments `PlanFeeService`,
`V14__plan_fee_withholdings.sql`.

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

## How sellers pay their plan: from their sales or by card (S298)

Each organisation chooses how it pays its plan (`subscription.plan_fee_collection`):

| Choice | Seller Center label | What happens to a renewal invoice |
|---|---|---|
| `EARNINGS_FIRST` | "Pay your plan from your sales" | taken from pending earnings (payments withholds it from the next transfers); the card pays only what earnings don't cover |
| `CARD` | "Charge my card" | charged to the card, separate from payouts: for sellers who book plan fees as their own card expense |

- **Who chooses:** owners and finance members in Seller Center › Plan & billing
  (`PUT /v1/orgs/{orgId}/billing/plan-fee-collection`), and applicants on the onboarding Plan step (`planFeeCollection`
  of the plan step; omitted = the platform default). Staff set the **platform default for new sellers** in the staff
  console › Plans & billing › Settings (`PUT /v1/billing/settings`, plans:manage; table `billing_setting`) and
  **override one seller** in its billing panel (`PUT /v1/orgs/{orgId}/billing/plan-fee-collection/override`,
  billing:adjust, a billing reason code, OTHER needs a note). Every change is audited in `plan_fee_collection_change`
  (who, source ONBOARDING / SELLER / STAFF, reason, note, the invoice it applies from); the subscription keeps the
  source (DEFAULT when the platform default applied at activation).
- **From when:** a change applies from the **next invoice** (`appliesFromInvoiceAt` = the next renewal, shown in Seller
  Center). An invoice Stripe already created keeps the choice it was created with: the decision is taken once, when
  `invoice.created` arrives.
- **Kill switch:** `at.seller.billing.collect-from-earnings` (`COLLECT_PLAN_FEES_FROM_EARNINGS`): on in the `local` and
  `dev` profiles and Helm `values-dev.yaml`, off in uat and prod until operations switch it on there. Off: the choice is
  hidden (Seller Center, onboarding), changes answer `409 seller.earnings-collection-off`, every invoice is charged to
  the card; organisations keep their stored choice for when it is on again. `tools/ci/check_env_config.py` checks it is
  on in local / dev and that wherever it can be on the seller-service client holds `payments:plan-fees` and the chart
  sets `PAYMENTS_URL`.
- AfroTransact Retail is never billed, so never withheld from.

### Timing: `invoice.created`, collected before Stripe finalises

Stripe creates a renewal or trial-end invoice (`billing_reason = subscription_cycle`) as a **draft** and finalises it
one hour after our endpoint acknowledged `invoice.created` (it keeps waiting, up to 72 hours, while the endpoint fails).
That window is used, rather than a scheduled pre-renewal withholding, because the draft has the exact amount (metered
usage lines, prorations, coupons), a per-invoice id to make every step idempotent, and nothing to undo when a
subscription is cancelled, upgraded or given free days before renewal. First invoices (`subscription_create`, no
trial) and upgrades (`subscription_update`) are charged at once, so the card pays them; a new seller has no earnings
yet anyway.

1. **Claim** (inside the webhook transaction): an `EARNINGS_FIRST` organisation's draft `subscription_cycle` invoice is
   claimed in `earnings_collection` (REQUESTED) for its amount **less the customer's credit balance** (rewards and staff
   credits pay first; nothing is asked when they cover it).
2. **Collect** right after the webhook transaction commits, holding the collection's row lock:
   - read the invoice back from Stripe; if it is no longer a draft, the card paid it: MISSED, and whatever payments
     holds is released;
   - `POST /v1/orgs/{orgId}/plan-fee-withholdings` (Idempotency-Key `plan-fee-<invoice>`): payments withholds **up to**
     the amount from delivered, undisputed, untransferred earnings → WITHHELD (possibly less) or DECLINED (card pays);
   - credit the Stripe customer balance by what was withheld (`POST /v1/customers/{id}/balance_transactions`, amount
     negative, Idempotency-Key `earnings-<invoice>`) → CREDITED. Stripe applies the balance when it finalises the
     invoice, so the card is charged only for the rest (or nothing).
3. **Retry:** a transient failure (payments or Stripe unavailable) rolls the step back and counts an attempt; the
   webhook answers **503**, so Stripe redelivers `invoice.created` and keeps the invoice a draft, and the redelivery
   (a duplicate in the inbox) collects again. The job (`at.seller.jobs.earnings-interval`, default 1 minute) retries
   too, with back-off (1, 2, 4, 8, then every 10 minutes). After 12 attempts the collection is FAILED, whatever
   payments may hold is released and the card pays. Every remote call replays under its key, so a retry never
   withholds or credits twice.

### Voids and refunds

- `invoice.voided` / `invoice.deleted`: when the credit was posted (CREDITED), Stripe returns an applied balance on a
  void (and a deleted draft never used it), so the seller service first takes the credit back
  (`balance_transactions`, amount positive, Idempotency-Key `earnings-plan-fee-release-<invoice>`), then releases the
  earnings at payments (`POST …/plan-fee-withholdings/{invoice}/release`, Idempotency-Key
  `plan-fee-release-<invoice>`) → RELEASED. Before the credit: release only (MISSED).
- `credit_note.created` on an invoice paid from sales: the part refunded **out of band** (`out_of_band_amount`) is
  released at payments (`plan-fee-release-<credit note>`); refunds to the card or to the customer balance are Stripe's
  own and release nothing. To refund a fee paid from sales, issue the credit note out of band.
- Releases are queued in `earnings_release` (one per key) and sent right after the webhook, then by the job.

### What payments does

See `java/services/payments/README.md` › Plan fees paid from earnings. In short: Dr `2000:<org>` (no order) /
Cr `4600` Seller plan fees collected from earnings; the organisation's next transfers recover it (`PLAN_FEE_SETTLED`,
one "Plan fee (invoice …)" deduction per settlement); a release posts Dr `4600` / Cr `2000:<org>` and is paid back
with the next transfer; money under an open dispute, undelivered orders and money already claimed by label charges or
earlier plan fees are never withheld.

### What sellers and staff see

- Seller Center › Plan & billing: the choice (owner / finance can change it) with the invoice it applies from, and on
  each invoice "Paid from your sales: $X · Card: $Y". Payouts: "Plan fees paid from your sales" with each
  "Plan fee (invoice …)" and the orders it was deducted from.
- Staff console › Plans & billing › Seller billing: the choice, who set it and the change log, the override, the
  invoices paid from earnings (asked, withheld, released, status, last error); › Settings: the kill switch (read-only)
  and the default for new sellers.

| Step | Idempotency-Key | Dedupe |
|---|---|---|
| invoice.created | Stripe event id | webhook inbox; `earnings_collection` primary key = invoice id |
| withhold at payments | `plan-fee-<invoice>` | `plan_fee_withholding.invoice_id` unique; replay answers the first decision |
| balance credit | `earnings-<invoice>` | Stripe (24 h); CREDITED is terminal |
| balance debit on void | `earnings-plan-fee-release-<invoice>` | Stripe replays the key; `earnings_release` is DONE once payments released |
| release at payments | `plan-fee-release-<invoice or credit note>` | `plan_fee_release` primary key = the key |
