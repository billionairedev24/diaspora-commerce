# Commission and connection fees

Sellers and service providers pay AfroTransact in two ways:

1. a **plan fee**, the subscription they choose in Plan & billing (seller-service, see
   [plans-and-billing.md](plans-and-billing.md));
2. a **percentage of each sale**, taken by payments-service when the money is captured:
   - **commission** on product sales (ledger `4000`);
   - **connection fee** on service bookings (ledger `4050`).

Both percentages can be configured. They depend on the category, the organisation's plan and any rate negotiated with
that organisation. AfroTransact Retail is the merchant of record and pays neither.

## Where each rate lives

| Rate | Stored in | Edited in |
|---|---|---|
| Default and category rates (commission) | `fee_schedule` / `fee_schedule_rate`, one immutable version each | Staff › Fees & commissions › Fee schedules or Product commission |
| Plan rates (commission) | `fee_schedule_plan_rate` (section `COMMISSION`): plan code → category → bps. Category `*` is the rate for the plan's other categories | Staff › Fees & commissions › Product commission (category × plan matrix) |
| Connection fee: default, category and plan rates | `fee_schedule_connection_fee`, `fee_schedule_connection_fee_rate`, `fee_schedule_plan_rate` (section `CONNECTION_FEE`) | Staff › Fees & commissions › Service connection fees |
| Negotiated rates | `negotiated_rate`. Each has a type (`COMMISSION` or `CONNECTION_FEE`), an optional category (`*` = all), the rate, `starts_at`/`ends_at`, a **required reason**, `negotiated_by` and an optional reference. Lifecycle events go to `negotiated_rate_audit` (append-only): CREATED, ENDED, EXPIRED | Staff › Fees & commissions › Negotiated rates, and the seller detail (Plans & billing panel) |
| Legacy seller overrides | `fee_schedule_override` (per version, products only) | Fee schedules › Overrides. They still count as negotiated rates |
| Organisation → plan | `org_plan`, payments' projection of `seller.subscription.changed` | seller-service, through Plan & billing |

Fee-schedule sections are immutable once their version is saved. To change a plan or connection-fee rate, staff save a
new version, from now or from a future date, exactly as for category rates. A version saved without `planBps` or
`connectionFee` keeps the latest live version's sections.

A version with no connection-fee section prices services as before: at the `services` commission rate. This keeps
versions created before V15 meaning what they meant.

## Precedence: first match wins

`FeeSchedule.rateFor(kind, org, category, plan, negotiated, at, retail)` in payments works through these steps in
order and stops at the first match. Its unit tests are in `CommissionPrecedenceTest`.

| # | Source (`RateSource`) | Rule |
|---|---|---|
| 0 | `RETAIL` | AfroTransact Retail pays 0 |
| 1 | `NEGOTIATED` | An active negotiated rate of the right type (COMMISSION for products, CONNECTION_FEE for services) for this category, then for `*`. For products only, a version's legacy override also counts here. "Active" means `starts_at ≤ at < ends_at` and not ended |
| 2 | `PLAN` | The plan rate for the organisation's current plan and this category, then for the plan's `*` |
| 3 | `CATEGORY` | The section's category rate |
| 4 | `DEFAULT` | The section's default rate |

How the kind is chosen:

- `PRODUCT` uses the commission section. `SERVICE` uses the connection-fee section.
- When a caller sends no kind, the category `services` counts as `SERVICE`, for older callers.
- Bookings sends `kind: SERVICE` with the offering's own service category, for example `hair & beauty`.

**Finding the organisation's plan.** payments consumes `seller.subscription.changed` into `org_plan` (inbox consumer
`payments.seller-subscription`). `CANCELED` and `PENDING_ACTIVATION` mean no plan.

Before the first event arrives, payments reads `GET /v1/orgs/{orgId}/billing` from seller-service once (`SELLER_URL`,
identity client `payments-service`, scope `seller:billing`). It stores that answer as if it occurred at the epoch, so
any later event replaces it. If both are unavailable, the plan step is skipped and the category or default rate
applies. A plan lookup never fails a payment.

**Reading the effective rate.**

- Services call `GET /v1/fee-schedules/effective?orgId=&category=&kind=PRODUCT|SERVICE&at=`. The response gives the
  rate, its source, the plan code, the negotiated rate id and when it ends, and the fee-schedule version.
- `GET /v1/orgs/{orgId}/rate-card?kind=` lists every category's rate with its source, plus the negotiated rates. Seller
  Center and Provider Hub use it.

## Snapshot on every line

Each order line and each booking line records the rate when its payment intent is created. The `payment_line` columns
are:

- `fee_kind`, `rate_bps`, `rate_source`, `rate_plan_code`;
- `negotiated_rate_id`, `negotiated_until`;
- the version already recorded on the payment.

Captures, refunds and statements use the snapshot, never today's rate. A negotiated rate that ends, a plan change or a
new fee schedule therefore never re-prices an order or booking already placed.

Lines created before V15 have no snapshot. They keep the version's legacy commission: its override, category or
default rate.

## Seeded plan rates

V15 seeds every existing fee-schedule version with rates that match what each plan's features advertise. Staff can
change them in a new version. The staff screens warn when a plan's feature text, for example "8–12% referral fee by
category", differs from its configured rates (`planWarnings` / `FeePlan.warning`).

**Product commission (section `COMMISSION`)**

| Plan | Groceries | Beauty, books, health, party | Fashion, home & art | Other (`*`) |
|---|---|---|---|---|
| Seller Essentials (`seller_essentials`) | 15% | 15% | 15% | 15% |
| Seller Growth (`seller_growth`) | 8% | 10% | 12% | 10% |
| Seller Pro (`seller_pro`) | 6% | 8% | 10% | 8% |

The splits follow margin:

- Groceries have thin margins and high volume, so they take the low end of each plan's range.
- Fashion and home & art have the highest margins and take the high end.
- Everything else takes the middle.

Essentials is flat, as its feature says.

**Service connection fee (section `CONNECTION_FEE`)**

| Plan | Rate |
|---|---|
| Provider Solo (`provider_solo`) | 10% |
| Provider Studio (`provider_studio`) | 8% |
| Provider Business (`provider_business`) | 6% |
| No plan / other plans | The section default: the version's former `services` commission, else its default |

## Connection fee: deposits, cancellations, no-shows and refunds

**Decision:** the connection fee is a percentage of the service money AfroTransact actually captures for the provider.
It is charged at the moment of capture, on that captured amount.

| Situation | Connection fee |
|---|---|
| Deposit captured at confirmation | Charged on the deposit |
| Balance (or full price) captured at completion | Charged on the balance |
| Customer cancels late or doesn't show, and the provider keeps the deposit or no-show amount | Charged on the amount captured. The provider earns it, so the fee applies |
| Booking cancelled or declined before anything was captured (the authorisation is released) | None |
| Refund with `reverseCommissionOnRefund = true` | Returned pro rata on the refunded amount: Dr 4050 / Cr seller payable `2000:<org>` |
| Refund with `reverseCommissionOnRefund = false` | Kept by AfroTransact |
| Provider late cancellation | The provider late-cancellation fee (`4300`, `provider_fee`) is separate and unchanged. If the cancellation refunds a captured deposit, the connection fee on it follows the refund rule above |

Settlement transfers only the net (captured amount − connection fee − card fees). Seller statements and exports
report `connectionFeeMinor` separately from `commissionMinor`.

Provider Hub shows "You keep $X after the 8% connection fee" on each request before the provider accepts it:
price − connection fee − estimated card fees on the deposit and the balance. Earnings shows the fee as its own
column and total.

## Negotiated rates

| Action | Rules |
|---|---|
| Create | `POST /v1/negotiated-rates` (`payments:configure`). The reason is required and at most 500 characters; the staff screens also ask for at least 3. A rate starts now or later: a past start returns `payments.negotiated-rate-backdated`, because placed orders keep their snapshot. A rate that overlaps an active or scheduled one of the same organisation, type and category returns `409 payments.negotiated-rate-overlap`. Audited as CREATED |
| End | `POST /v1/negotiated-rates/{id}/end {reason}`. Takes effect immediately, keeps the row, audited as ENDED. Orders and bookings already placed keep their snapshot |
| Expire | Job `NEGOTIATED_RATE_EXPIRY_CRON` (every 5 minutes) records EXPIRED once `ends_at` passes. The rate already stopped applying at `ends_at`; the job only writes the audit trail |

Seller Center shows the source of each rate:

- "Negotiated rate until 31 Dec 2026";
- "Your plan: Seller Growth";
- "Category rate";
- "Standard rate".

**Negotiate plan price.** Staff › Plans & billing › seller detail › "Negotiate plan price" has gateway-bff:

1. read the base plan;
2. create a private `INVITE_ONLY` copy `<base>_<org>` at the negotiated price;
3. invite the organisation;
4. publish the copy.

The seller then chooses it in Plan & billing. Staff can't switch a seller's plan directly, because seller-service's
plan change is owner-only. Direct assignment is backlog story S306.

## Compatibility

Bookings at or after this change still works against an older payments-service:

- When the effective-fees response has no `kind`, bookings treats connection fees as unsupported. It asks for the
  `services` commission and keeps `services` as the line category.
- On a 404 or 5xx from payments, the configured fallback rate applies.

Neither case fails a booking or a settlement. Unit test: `ConnectionFeeCompatibilityTest` in bookings.
