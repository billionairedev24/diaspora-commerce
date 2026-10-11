# payments-service

Stripe PaymentIntents, captures, refunds, Connect transfers and payouts, the immutable double-entry ledger,
multi-seller settlement, fee schedules, chargebacks, reconciliation and shoppers' saved payment methods.
Spring Boot 4.1 · Java 25 · PostgreSQL `payments` · port **8105**. Architecture §5 (Transactions, Ledger), §7
(Stripe), §17b (Settlement & fees), §17d (webhooks). Stories S051–S057, S205, S214, S220, S226, S245, S248, S256, S259.

Wording: funds wait in a **settlement hold** (7 days after delivery / service completion). Stripe Connect accounts
use the **manual payout schedule**; sellers are paid **on request**.

## Model

| Table | What it holds |
|---|---|
| `payment` (+ `payment_line`) | One primary payment per order: Stripe PaymentIntent (manual capture), amount, status, fee-schedule version, lines (seller org, category, FBA/self, qty, items/shipping/tax, jurisdiction) with capture and refund state. Off-session follow-up charges of the same order have `parent_payment_id` (V5) |
| `payment_capture` | One per shipment: amount, Stripe fee (actual from the charge's balance transactions, or estimate), charge id, journal |
| `payment_refund` | Line-level refunds with commission returned per line; PENDING until Stripe confirms (`charge.refunded`) |
| `journal_entry` / `journal_line` | **Append-only double-entry ledger** (triggers reject UPDATE/DELETE/TRUNCATE; a *deferred constraint trigger* rejects any journal that does not balance per currency or has < 2 lines) |
| `fee_schedule` (+ `_rate`, `_override`) | Immutable, versioned commission % per category, seller overrides, card fee, FBA fee per unit, refund-commission policy, `effective_from` |
| `fee_schedule_cancellation` | Append-only withdrawal of a scheduled version before it takes effect (who, when, why; V7). The version row is kept and skipped when picking the version in force |
| `settlement`, `settlement_transfer`, `transfer_reversal` | Settlement hold per order, Connect transfers (`transfer_group` = order id) and reversals |
| `payout_request` | Payout requests (unique per org per day, failed excluded) |
| `dispute` | Chargebacks with per-seller allocation |
| `stripe_event` | Webhook inbox (event id unique) |
| `stripe_customer`, `customer_payment_method` | Stripe Customer per AfroTransact customer; saved methods (display data only) |
| `connected_account` | Stripe Connect account per organisation |
| `provider_fee` | Fees providers owe (late provider cancellations), one per booking: OUTSTANDING → COLLECTED by a Stripe account debit (V5) |
| `supplier_payment` | AfroTransact Retail supplier payments (S226, V10), one per purchase order released by retail-service: supplier, amount in the supplier's currency, invoices, `SCHEDULED → ON_HOLD ⇄ SCHEDULED → PAID`, due date, payable / payment journals, bank reference |
| `label_charge` | Shipping labels bought through AfroTransact and charged to the seller (S256, V8), one per carrier-gateway label id: carrier cost, handling, tracking number, shipment, CHARGED → CREDITED (or VOIDED when the void arrived first), charge / credit journals |
| `idempotency_record`, `reconciliation_run` | Idempotency-Key responses; daily reconciliation reports |

### Chart of accounts

`1000` Stripe clearing · `1050` Operating bank · `1100` Chargeback receivable · `1200:<org>` Provider fees receivable · `1400` Retail purchases released for payment · `2000:<org>` Seller payable · `2100:<jurisdiction>` Sales tax
payable (attributed to the seller) · `2200` Customer refunds payable · `2300:<org>` Chargeback reserve · `2400` Carrier
labels payable · `2500:<supplier>` Retail supplier payables · `4000` Commission · `4100` FBA fees · `4200` AfroTransact shipping (marketplace sellers' FBA and
AfroTransact Delivery lines) · `4250` Local delivery fees (sellers' AfroTransact Delivery pickup fees, S255) ·
`4300` Provider late-cancellation fees · `4400` AfroTransact Retail sales · `4500` Shipping label handling · `5000`
Stripe fees · `5100` Chargeback losses.

`4400` holds AfroTransact Retail's net (items + all its shipping − its Stripe-fee share); its lines carry the Retail
org id for attribution. Retail is the merchant of record, so there is never a `2000:<retail>` payable, a `2300` reserve,
a transfer, a reversal or a payout for it. Staff see Retail revenue as its own `4400` row in `GET /v1/ledger/balances`.

| Event | Journal |
|---|---|
| Capture (per shipment) | Dr 1000 (charge − fee), Dr 5000 (AfroTransact's fee share) / Cr 2000 sellers' net, Cr 4400 Retail's net, Cr 2100 tax, Cr 4000, Cr 4100, Cr 4200, Cr 4250 |
| Refund | Dr 2100 tax, Dr 2000 (items + own shipping − commission returned − delivery fee returned), Dr 4000 (commission returned), Dr 4200, Dr 4250 (delivery fee returned, customer fault) / Cr 2200; Retail lines: Dr 4400 (items + shipping), Dr 2100 / Cr 2200 |
| Refund paid by Stripe | Dr 2200 / Cr 1000 |
| Transfer after the hold | Dr 2000 / Cr 1000 · reversal: Dr 1000 / Cr 2000 |
| Chargeback opened | Dr 1100 + Dr 5100 (dispute fee) / Cr 1000; Dr 2000 / Cr 2300 per responsible seller (Retail's part stays in 1100) |
| Won / lost | Dr 1000 / Cr 1100 and Dr 2300 / Cr 2000 · lost: Dr 2300 (+ 5100 platform part, + 4400 Retail part) / Cr 1100 |
| Provider late-cancellation fee recorded | Dr 1200:<org> / Cr 4300 |
| Fee collected (Stripe account debit) | Dr 1000 / Cr 1200:<org> |
| Shipping label charged to a seller (S256) | Dr 2000:<org> (cost + handling, no order) / Cr 2400 (carrier cost), Cr 4500 (handling) |
| Label voided | Dr 2400, Dr 4500 / Cr 2000:<org> (once per label) |
| Label balance settled at a transfer | Dr 2000:<org> on the order / Cr 2000:<org> unattributed (recovery; the opposite for a credit owed back) |
| Retail supplier invoices released (S226) | Dr 1400 / Cr 2500:<supplier> (lines attributed to Retail's org) |
| Supplier paid by Finance | Dr 2500:<supplier> / Cr 1050 |
| Authorisation released | — (authorisations never reach the ledger; 1000 moves only at capture) |

Every money-moving use case runs in a **SERIALIZABLE** transaction retried on SQLSTATE 40001/40P01 and optimistic
conflicts (`MoneyTransactions`); `LedgerPoster` refuses to post outside one, and posts each journal once per
business key. Stripe calls inside use idempotency keys derived from business ids, so retries replay Stripe:
`<order>:create-intent`, `<order>:capture:<shipment>`, `<order>:cancel`, `<order>:refund:<refundId>`,
`<order>:reversal:<refund>:<transfer>`, `<order>:transfer:<org>:<n>`, `payout:<requestId>`, `customer:<id>`,
`<order>:release`, `provider-fee:<feeId>:<seq>` (seq changes only after a definitive 4xx refusal). An off-session
charge uses the prefix `<order>:os:<paymentId>` instead of `<order>` so its keys never collide with the parent's.

## Payment split (S054)

Per recipient: items, shipping (a marketplace seller's FBA or AfroTransact Delivery shipping → AfroTransact), tax
(collected and remitted by AfroTransact as marketplace facilitator, attributed to the seller), commission (category
rate, seller override first, half-up per line), FBA fee per unit, AfroTransact Delivery seller fee, and a share of the
Stripe fee pro rata to each recipient's part of the charge (largest-remainder; AfroTransact wins ties and takes any
leftover cent). Invariant, checked on every split and by 5 000 random property cases (all three fulfilment modes,
including Retail lines): `Σ nets + tax = charge − Stripe fee`.

Three fulfilment modes per line:

| `fulfilment` | Who ships | Customer's shipping | Seller pays |
|---|---|---|---|
| `FBA` | AfroTransact FC | AfroTransact (4200) | commission + FBA fee per unit (4100) + Stripe share |
| `SELF` | the seller (carrier / own) | the seller | commission + Stripe share |
| `AFROTRANSACT_DELIVERY` (S255) | the seller packs, the nearest AfroTransact driver collects from the seller's store / warehouse | AfroTransact (4200) | commission + the **seller delivery fee** quoted at checkout (`deliveryFeeMinor` on the line, 4250) + Stripe share; no FBA fee |

AfroTransact Retail keeps its shipping and pays no seller delivery fee in every mode.

**AfroTransact Retail** (`RETAIL_ORG_ID`, recipient kind `RETAIL`) is the merchant of record, not a marketplace
seller. Commission, FBA fees, seller fees, plans, metered billing and ads charges never apply to it (even with a
commission override). Its only cost is its Stripe-fee share, and it keeps the shipping of all its lines, FBA or not:
`Retail net = items + shipping − Stripe-fee share`, credited to `4400 Retail sales`. Tax still goes to
`2100:<jurisdiction>`. Settlement never transfers Retail money, refunds of Retail lines debit `4400` and never reverse a
transfer, Retail's payout balance is always zero (requests fail with `422 payments.retail-no-payouts`), a connected
account can't be registered for it (`422 payments.retail-no-connected-account`), `seller.payouts.paused` for it is
ignored, and so are provider late-cancellation fees. `GET /v1/fee-schedules/effective?orgId=<retail>` reports
`commissionBps 0`, `fbaPerUnitMinor 0`, source `RETAIL`.

Worked example (fee schedule: Groceries 8 %, Fashion 12 %, FBA $3.25/unit, card 2.9 % + 30¢; amounts in cents):

| Line | Org | Fulfilment | Qty | Items | Shipping | Tax |
|---|---|---|---|---|---|---|
| R1 Groceries | Retail | FBA | 2 | 2 400 | 400 | 198 |
| R2 Fashion | Retail | seller-shipped | 1 | 1 500 | 300 | 124 |
| A1 Groceries | org_a | seller-shipped | 2 | 2 000 | 500 | 165 |
| B1 Fashion | org_b | FBA | 3 | 3 000 | 600 | 248 |

Charge 11 435, Stripe fee 362 (2.9 % + 30¢).

| Recipient | Items | Shipping | Tax | Commission | FBA | Stripe share | Net | Booked to |
|---|---|---|---|---|---|---|---|---|
| AfroTransact | – | 600 (B1) | – | +520 | +975 | 19 | 2 076 | 4000 / 4100 / 4200, Dr 5000 19 |
| Retail | 3 900 | 700 | 322 | 0 | 0 | 156 | 4 444 | Cr 4400 |
| org_a | 2 000 | 500 | 165 | 160 | 0 | 84 | 2 256 | Cr 2000:org_a, transferred after the hold |
| org_b | 3 000 | 0 | 248 | 360 | 975 | 103 | 1 562 | Cr 2000:org_b, transferred after the hold |

Σ nets 10 338 + tax 735 = 11 073 = 11 435 − 362. Refunding R1 1 200 items + 400 shipping + 99 tax and A1 1 000 items
debits 4400 by 1 600, 2000:org_a by 920 (1 000 − 80 commission returned, Stripe reversal of 920 from org_a's transfer),
4000 by 80 and 2100 by 99. No reversal is attempted for Retail.

## AfroTransact Delivery from the seller's store (S255)

### Delivery pricing (fee schedule section)

Each fee-schedule version may carry a **delivery pricing** section (immutable with its version; `POST /v1/fee-schedules`
`deliveryPricing`; omitted = carried over from the latest live version; `enabled: false` = not offered):

- `maxRadiusMiles` (0 < r ≤ 200): beyond it the option is unavailable (`BEYOND_MAX_RADIUS`).
- `customerCharge` — what the shopper pays (booked to **4200**), and `sellerFee` — what the seller pays for the
  pickup-delivery (booked to **4250**), each a tariff; either can be zero:
  - `FLAT`: `flatPerPackageMinor × packages`;
  - `DISTANCE` (per trip, packages don't count): the fee of the band covering the miles (`bands: [{fromMiles,
    toMiles, feeMinor}]`, from inclusive, to exclusive, non-overlapping) else `baseMinor + perMileMinor × miles`
    (half up), then raised to `minMinor` and capped at `maxMinor` (omit = no cap).
- `sellerOverrides: [{orgId, sellerFee}]` — negotiated seller fees, like commission overrides.
- **AfroTransact Retail never pays a seller fee** (source `RETAIL`), whatever the overrides say.

The seed version (V9) carries the launch defaults: customer `DISTANCE` $4.99 + $0.85/mi, min $5.99, max $24.99, within
25 miles; seller `FLAT` $2.00 per package. A version without the section quotes `NOT_CONFIGURED`.

`POST /v1/delivery-pricing/quote {sellerOrgId, distanceMiles, packages?, at?}` → `{available, unavailableReason,
customerChargeMinor, sellerFeeMinor, sellerFeeSource (DEFAULT · OVERRIDE · RETAIL), scheduleVersion, scheduleId,
currency, maxRadiusMiles}` — orders-service at checkout (`payments:service`), services with `payments:fees.read`, staff
`payments:read`. `distanceMiles` is geo's `roadMiles` (routing provider: Google Routes API / OSRM; geodesic × detour only as the last resort). The version in force at `at` (default now) applies, so
checkout and the payment (`placedAt`) price under the same version. `GET /v1/fee-schedules` (and `/active`, `POST`)
return the section as `deliveryPricing` for the staff fee editor.

### Split, ledger and the worked example

orders sends AfroTransact Delivery lines with `fulfilment: AFROTRANSACT_DELIVERY`, the quoted customer charge as the
package's shipping (on its first line) and the quoted seller fee spread over the package's lines by items
(`deliveryFeeMinor`). Payments snapshots both on `payment_line`; capture credits the fee to 4250 (`deliveryFeesMinor`
per recipient and `deliveryFeeMinor` per line in `GET /v1/orders/{id}/split`).

Example (seed schedule, Groceries 8 %, card 2.9 % + 30¢). org_c's store is 7.4 road miles from the shopper: customer
charge 499 + 85 × 7.4 = **1 128**, seller fee **200**. org_a ships its own line.

| Line | Org | Fulfilment | Qty | Items | Shipping | Tax | Seller fee |
|---|---|---|---|---|---|---|---|
| D1 Groceries | org_c | AFROTRANSACT_DELIVERY | 2 | 3 000 | 1 128 | 248 | 200 |
| A1 Groceries | org_a | SELF | 1 | 1 500 | 500 | 124 | – |

Charge 6 500, Stripe fee 219 (189 + 30); weights 1 128 / 3 248 / 2 124 → shares 38 / 109 / 72.

| Recipient | Items | Shipping | Tax | Commission | Delivery fee | Stripe share | Net | Booked to |
|---|---|---|---|---|---|---|---|---|
| AfroTransact | – | 1 128 (D1) | – | +360 | +200 | 38 | 1 650 | Cr 4200 1 128, 4000 360, 4250 200; Dr 5000 38 |
| org_c | 3 000 | 0 | 248 | 240 | 200 | 109 | 2 451 | Cr 2000:org_c |
| org_a | 1 500 | 500 | 124 | 120 | – | 72 | 1 808 | Cr 2000:org_a |

Σ nets 5 909 + tax 372 = 6 281 = 6 500 − 219. No FBA fee anywhere (4100 untouched).

### Refunds: who pays for the trip (decision)

Lines are captured when the driver collects them, so the trip has been made by the time anything is refunded. It is
paid **once, by whoever is at fault** (`fault` on `POST /v1/payments/{id}/refunds` and `POST /v1/orders/{id}/refunds`;
other lines are unaffected):

- **`SELLER`** (default — wrong, damaged or missing item, seller cancellation after pickup): the customer can get the
  delivery charge back (Dr 4200), AfroTransact **keeps the seller fee** (4250 untouched). Full refund of D1:
  Dr 2100 248, Dr 2000:org_c 2 760 (3 000 − 240 commission returned), Dr 4000 240, Dr 4200 1 128 / Cr 2200 4 376.
- **`CUSTOMER`** (changed their mind, refused the delivery): the **delivery charge is not refundable** (`422
  payments.delivery-charge-not-refundable` if `shippingMinor` > 0 on such a line; the order-level allocator leaves it
  out of the spread) and the seller **gets its fee back in proportion to the items refunded**, cumulatively like
  commission (`deliveryFeeReversedMinor`). Half of D1: Dr 2100 124, Dr 2000:org_c 1 280 (1 500 − 120 − 100),
  Dr 4000 120, Dr 4250 100 / Cr 2200 1 624.

Why: the customer charge pays AfroTransact's driver; when the seller caused the failure the seller's fee covers the
wasted trip instead, so AfroTransact is never paid twice and the innocent party never pays. Nothing is charged for
shipments cancelled before pickup (no capture). Transfer reversals follow the seller debit as usual.

## API

RFC 9457 problems with `code` `payments.*`. Reference pages `/swagger-ui.html`, `/docs`, `/redoc`. Contract:
`contracts/openapi/payments.yaml`.

| Endpoint | Who |
|---|---|
| `POST /v1/payments` · `GET /v1/payments/{id}?refresh=` · `GET /v1/payments?orderId=` | orders (SERVICE, `payments:service`, Idempotency-Key) |
| `GET /v1/payments?orderId=` (S241) | also the order's shopper (CUSTOMER token whose subject is the order's `customer_id` metadata or owns its Stripe Customer); other shoppers get `403 payments.not-your-order`; staff `payments:read` and services unchanged |
| `POST /v1/payments/{id}/capture` · `/cancel` · `/refunds` · `GET …/captures` · `…/refunds` | orders, bookings |
| `POST /v1/payments/{id}/release` (Idempotency-Key) | bookings (`payments:service`): release the uncaptured rest after a deposit capture |
| `POST /v1/payments/{id}/off-session-charges` (Idempotency-Key) · `GET …/off-session-charges` | bookings (`payments:service`): charge lines on a saved card off-session |
| `POST /v1/settlements/{orderId}/delivered` (`kind` PRODUCT / SERVICE) · `GET /v1/settlements/{orderId}` | orders, bookings, fulfilment |
| `GET /v1/orders/{orderId}/split` · `GET /v1/ledger/balances` · `/v1/ledger/orgs/{orgId}/balances` · `/v1/ledger/journals?orderId=` | staff `payments:read` |
| `GET /v1/fee-schedules` · `/active?at=` · `POST /v1/fee-schedules` | read / `payments:configure` (Super Admin, Finance) |
| `POST /v1/fee-schedules/{id}/cancel {reason}` | `payments:configure`: only before `effectiveFrom` (`409 payments.fee-schedule-in-force` after, `409 payments.fee-schedule-cancelled` twice). Marks the version cancelled (`cancelledAt/By`, `cancelReason`), never deletes it; cancelled versions never apply and don't block the next version's date. Audited (row + `audit.payments.fee-schedules` log) |
| `POST /v1/delivery-pricing/quote` | orders (`payments:service`), services `payments:fees.read`, staff `payments:read`: AfroTransact Delivery customer charge + seller fee (S255) |
| `GET /v1/fee-schedules/effective?category=&orgId=&at=` | services (`payments:service`) or staff `payments:read`: commission bps (override / category / default / retail), card fee estimate |
| `GET /v1/payouts/balance` · `POST /v1/payouts/requests` | org members / owner & finance roles with `payouts:request`. The balance carries `deliveryFeesMinor` (S259): AfroTransact Delivery pickup fees the organisation paid on captured lines, net of fees given back by customer-fault refunds (from 4250 lines attributed to it), next to `labelChargesMinor` and `providerFeesMinor` |
| `GET /v1/payouts/provider-fees?orgId=` | org members (own org) / staff |
| `GET /v1/payouts/label-charges?orgId=&status=&from=&to=&limit=` | org members (own org) / staff: shipping labels charged to the organisation (S256) with tracking number, shipment, order, cost, handling, status; filters `status` CHARGED / CREDITED, purchased in `[from, to)`, `limit` ≤ 200 |
| `GET /v1/payouts/statement?orgId=&currency=&from=&to=&limit=&kind=` | org members (own org) / staff: the seller statement (`kind` repeatable, e.g. `kind=LABEL_CHARGE&kind=LABEL_CREDIT`) — movements of `2000:<org>` (captures, refunds, transfers, chargebacks, `LABEL_CHARGE` / `LABEL_CREDIT` with labelId, shipmentId, trackingNumber), signed (credit to the seller positive), newest first, default last 90 days |
| `GET /v1/payouts/requests?status=&orgId=` · `POST …/{id}/approve` · `/hold` · `/release` | Finance `payouts:approve` |
| `GET /v1/reconciliation/runs` · `POST /v1/reconciliation/runs {date}` | `payments:read` / `payments:configure` |
| `PUT/GET /v1/connected-accounts/{orgId}` | seller service or `payments:configure` |
| `GET /v1/customers/me/payment-methods` · `POST /v1/customers/me/setup-intents` · `POST …/{id}/default` · `DELETE …/{id}` | the shopper (CUSTOMER token relayed by gateway-bff) |
| `POST /v1/customers/me/ephemeral-keys {stripeVersion?}` → `{customerId, ephemeralKeySecret, expiresAt}` | the shopper: PaymentSheet (S241) for the shopper's Stripe Customer, created on first use; gateway-bff `POST /v1/account/payment-sheet` calls it. Checkout intents carry the same Customer (orders sends `shopperId`; payments attaches the shopper's existing Customer, S245), so the mobile PaymentSheet lists the saved cards |
| `GET /v1/supplier-payments?status=&supplierId=&dueBy=&limit=` · `GET …/{id}` | staff `payments:read` (platform-wide) and services: AfroTransact Retail supplier payments (S226); `dueBy` lists Finance's payment run |
| `POST /v1/supplier-payments/{id}/paid {paymentReference, paidAt?}` · `/hold {reason}` · `/release` | Finance `payouts:approve` (platform-wide): record the bank transfer (Dr 2500 / Cr 1050; same reference = replay; 409 `payments.supplier-payment-on-hold` while held) |
| `POST /v1/webhooks/stripe` | Stripe (public; `Stripe-Signature` verified) |

Payout rules: minimum USD 25, one request per day (failed payouts don't count), never more than available, only
`seller.owner/finance` or `provider.owner/finance` (or staff). Removing the default saved method is refused
(`409 payments.default-method-required`): choose another default first.

## Long-dated bookings (S220)

Card authorisations last ~7 days; bookings can be a year ahead. Flow for bookings-service:

1. **Create** the two-line intent (`deposit`, `balance`) with `shopperId` + `saveForOffSession: true`: payments creates
   (once) the shopper's Stripe Customer, sets `customer` and `setup_future_usage=off_session`, and the confirmed card
   lands in the wallet via `payment_method.attached`.
2. **Capture** the deposit (`finalCapture: false`).
3. **Release** the rest — `POST /v1/payments/{id}/release` — on cancellation / no-show, or right after the deposit
   capture when the appointment is beyond the authorisation window. Stripe: cancelling a `requires_capture`
   multicapture intent that has captures releases the remainder (the intent ends `succeeded`); if the card had no
   multicapture, Stripe already released it at the partial capture and nothing is sent. The payment becomes
   CAPTURED (`releasedMinor` = the rest; without captures it is cancelled). No journal: authorisations are off-ledger.
   Event `payments.authorization.released`.
4. **Charge the balance off-session** — `POST /v1/payments/{id}/off-session-charges {customerId, paymentMethodId?,
   lineIds?}` — only once the primary payment is final (CAPTURED / CANCELED, e.g. after release or a lapsed hold).
   Creates a manual-capture PaymentIntent with `off_session=true, confirm=true` on a saved **card** (default method
   unless one is named) as a new payment of the **same order** (`parentPaymentId`): it is captured, refunded and
   settled through the usual endpoints, its journals carry the order id, so the settlement hold and Connect transfer
   cover both payments. A line has at most one live off-session charge (`409 payments.lines-already-charged`).
   - `AUTHORIZED`: capture it at completion (`POST /v1/payments/{newId}/capture`).
   - `REQUIRES_ACTION` (`failureMessage = authentication_required`): the bank wants SCA. Stripe's 402 card error and
     the `payment_intent.payment_failed` webhook are not treated as a decline;
     `payments.intent.action_required` (with the customer id) lets notifications bring the customer back, the app
     confirms with the payment's `clientSecret` (Stripe.js / PaymentSheet), and the webhook moves it to AUTHORIZED
     (`payments.intent.succeeded` with `parentPaymentId`). Cancel it (`/cancel`) to retry with another card.
   - `FAILED`: declined; retry with another card and a new Idempotency-Key.

### Provider late-cancellation fees

Consumed from `at.bookings.events`: `booking.cancelled` with `cancelledBy = PROVIDER` and `providerFeeMinor > 0`
(inbox consumer `payments.bookings-events`; plus one fee per booking id). Event over API: bookings already publishes
the fee in the cancellation event through its outbox, so no new synchronous dependency (and no failure mode) is added
to the cancellation, and replay is safe.

Connect mechanism: an **account debit** (`POST /v1/charges` with `source = acct_…`), not a transfer reversal. The fee
belongs to no transfer — nets are transferred per order and the cancelled booking usually has nothing transferred
(its deposit was refunded) — so a reversal would have to pick an unrelated order's transfer, would book the money
back to that order's payable and is capped by that transfer's remainder. The account debit is its own platform
balance transaction, matched 1:1 by reconciliation with the collection journal. The fee is accrued when recorded
(Dr 1200 / Cr 4300); if it cannot be debited yet (no connected account, insufficient balance, Stripe down) it stays
OUTSTANDING, is retried with backoff after each settlement run, and is subtracted from the payout balance so it can
never be paid out first. Account debits need Express / Custom accounts (platform-controlled), which matches
AfroTransact's manual payout schedule.

### Shipping labels charged to sellers (S256)

Consumed from `at.carrier-shipment.events`: `carrier.label.purchased` / `carrier.label.voided` (listener
`payments-carrier-shipment-events`, inbox consumer `payments.carrier-shipment-events`, plus one `label_charge` row per
label id, so a redelivered purchase or void never charges or credits twice). Event over API, like provider fees:
carrier-gateway writes the event to its outbox in the purchase transaction, so buying a label gets no synchronous
dependency on payments and replays are safe.

- **What is charged:** the label's `amountMinor` = the carrier's negotiated charge (`costMinor`) + the handling markup
  (`handlingMinor`). The markup lives in **carrier-gateway config** (`billing.handlingPct` / `handlingFixedMinor`,
  default 0), not in the fee schedule: the fee schedule is an immutable, versioned commission table whose versions are
  created through `POST /v1/fee-schedules` and the Seller Center/staff screens, and adding a label rate there means a
  new column, API fields and UI; carrier-gateway already prices labels (account `adjustmentPct`) and puts the markup in
  the event, so the ledger books exactly what the seller saw at purchase.
- **Accounts:** Dr `2000:<org>` / Cr `2400` Carrier labels payable for the cost — the carrier bills AfroTransact's
  platform account, so the cost is a liability to the carrier, cleared when AfroTransact pays the carrier invoice — and
  Cr `4500` Shipping label handling for the markup (AfroTransact revenue). Charging the cost to revenue would overstate
  revenue by pass-through money; 2400 makes "labels billed vs carrier invoices" a reconcilable balance.
- **Who is charged:** only `billTo = SELLER` (carrier-gateway: an org member's outbound, seller-shipped label from its
  own origin). Re-checked here: **AfroTransact Retail** (`RETAIL_ORG_ID`, merchant of record, pays Stripe fees only) and
  **FBA / AfroTransact Delivery** shipments (AfroTransact's own shipping cost) are never charged, nor return labels,
  whatever `billTo` says.
- **Void / refund:** credits the charge back once (reverse journal, `CREDITED`). A void that overtakes its purchase is
  stored as `VOIDED`, so the late purchase charges nothing.
- **Insufficient balance:** the charge always posts. The lines carry no order, so they never change what an order's
  settlement owes; `2000:<org>` may go negative. `GET /v1/payouts/balance` shows them as `labelChargesMinor` and
  deducts them from `availableMinor`, which can go **negative**; payouts never exceed available, so nothing is paid out
  until the seller has earned it back.
- **Recovery:** before each Connect transfer of an order's payable, settlement nets the organisation's unattributed
  label balance against it (`LABEL_RECOVERY` journal, a reclass inside `2000:<org>`): the transfer is smaller by the
  outstanding charges, and larger by credits for labels voided after their charge was recovered. If the whole payable
  goes to labels, no Stripe transfer is made.

## Relationship checks (S248, OpenFGA)

`PaymentsAccess` checks the RBAC permission **and** a relationship through `ResourceAccessChecker`
(`PaymentsRelations`, `docs/engineering/authorisation.md`) when `at.security.fga.enabled=true`:

| Use case | Relation |
|---|---|
| Balance, statement, label charges, provider fees, `GET /v1/payouts/requests/{id}` | `can_view_payouts` on `organization:<org>` — members need `payouts:read` (owner, admin, manager, finance; catalog / ads / support members never see payouts), staff a Finance grant covering the org's market |
| `POST /v1/payouts/requests` | `can_request_payouts` (the org's finance role; owners inherit it); staff on behalf: `can_view_payouts` |
| Payout queue `?orgId=` / approve, hold, release one payout | `can_view_payouts` on the org / `can_view` on `payout:<id>` with the payout's org as a contextual tuple (Finance staff of the market) |
| Payout queue of every org, ledger balances / journals, order splits, reconciliation, supplier payments | `can_view_all` on `platform:afrotransact` (platform-wide staff, first-party services) |
| `GET /v1/ledger/orgs/{orgId}/balances` | `can_view_payouts` on the organisation |

Intents, captures, refunds and settlement stay service-only (`payments:service`, principal type `SERVICE`), saved
methods customer-only, fee schedules RBAC (platform configuration). With FGA off, behaviour is unchanged (RBAC and the
token's `org`). Profiles: `local` off, `dev` enforced, `uat` shadow, `prod` off until uat enforces; secret
`FGA_API_TOKEN`. `PayoutRelationshipsIT` runs against the local `openfga/openfga:v1.10.0` image.

## Webhooks (S052)

`POST /v1/webhooks/stripe` verifies the signature with `Webhook.constructEvent` (tolerance 300 s), stores the event
once by id (`stripe_event`), returns `200 {received, duplicate, handled}`; unknown types are stored as IGNORED.
A job (every 1 s) locks the oldest due event with `FOR UPDATE SKIP LOCKED` and processes it in a SERIALIZABLE
transaction; failures back off exponentially and park as FAILED after 12 attempts. Handled:
`payment_intent.succeeded / amount_capturable_updated / payment_failed / requires_action / canceled`,
`charge.refunded`, `charge.dispute.created / closed`, `transfer.reversed`, `payout.paid / failed`,
`setup_intent.succeeded / requires_action`, `payment_method.attached / detached`.

## Events (outbox, `contracts/asyncapi/fragments/payments.yaml`)

`at.payment.events` (key order id): `payments.intent.created|succeeded|failed|canceled`, `payments.captured`,
`payments.intent.action_required`, `payments.authorization.released`, `payments.refunded`, `payments.transfer.created`,
`payments.dispute.opened|closed`. Intent events carry `parentPaymentId` / `offSession` for off-session charges.
`at.payout.events` (key org): `payments.payout.requested|approved|paid|failed`,
`payments.provider_fee.recorded|collected`.

Consumed (group `payments-service`, inbox-deduplicated): `seller.payouts.paused|resumed` on `at.seller-compliance.events`
set or clear the organisation's `payout_hold` (V4; older decisions never override newer ones). While paused, payout
requests and approvals fail with 422 `payments.payouts_paused`; balances keep accruing. `GET /v1/payouts/balance`
shows the hold (S214): `payoutsPaused` and `payoutsPausedReason` (AUTO_PAST_DUE, MANUAL, DEAUTHORIZED,
CHARGES_DISABLED; null when not paused).

`booking.cancelled` on `at.bookings.events` (late provider cancellations, see above).

`carrier.label.purchased|voided` on `at.carrier-shipment.events` (shipping labels charged to sellers, see above; tracking
and pickup events on that topic are ignored).

`retail.payment.released` on `at.retail.events` (S226, listener `payments-retail-events`, inbox consumer
`payments.retail-events`, one `supplier_payment` per PO): AfroTransact Retail's purchase order passed the three-way
match. Retail is the **merchant of record**, so the debt is AfroTransact's own: Dr `1400` Retail purchases released
(the counterpart of retail-service's GL `2100 Accounts payable — suppliers`; inventory and AP stay in retail's GL) /
Cr `2500:<supplierId>`. The payment is scheduled for the event's `dueOn`, else the release date + 30 days
(`SUPPLIER_PAYMENT_TERMS_DAYS`). Finance pays suppliers from the **operating bank account** (`1050`, bank transfer /
ACH outside Stripe — suppliers are not Connect accounts) and records the reference with
`POST /v1/supplier-payments/{id}/paid`. Supplier money never touches a seller payable (`2000`), a Connect transfer, a
payout or Retail's `4400` sales. Events `payments.supplier_payment.scheduled|paid|updated` on `at.payout.events`
(key = supplier id). An automated bank-payment rail (e.g. Stripe Treasury / bank API) can be added behind a port
later; today the payment run is Finance's.

## Jobs & metrics

Webhook inbox (`WEBHOOK_INTERVAL`, 1 s) · settlement transfers, then outstanding provider fees (`SETTLEMENT_CRON`,
every 15 min) · reconciliation
of yesterday (`RECONCILIATION_CRON`, 03:10): ledger 1000 per Stripe object vs Stripe balance transactions
(platform payouts / top-ups excluded). Metrics: `payments_reconciliation_drift_minor`, `payments_webhooks_total`,
`payments_transfers_total`.

## Configuration

| Env | Purpose |
|---|---|
| `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET` | **Only from the environment.** prod requires `sk_live_`/`rk_live_`; every other profile refuses live keys |
| `STRIPE_API_BASE` | default `https://api.stripe.com` (tests point it at WireMock) |
| `DB_URL`, `DB_USER`, `DB_PASSWORD`, `KAFKA_BOOTSTRAP`, `AUTH_ISSUER_URL` | infrastructure |
| `RETAIL_ORG_ID` | AfroTransact Retail org (merchant of record: no commission, FBA fee, transfer or payout; sales to 4400) |
| `SUPPLIER_PAYMENT_TERMS_DAYS` | days from `retail.payment.released` to a supplier payment's due date when retail sends none (30) |
| `FGA_ENABLED`, `FGA_ENFORCE`, `FGA_API_URL`, `FGA_STORE_ID`, `FGA_STORE_NAME`, `FGA_MODEL_ID`, `FGA_API_TOKEN` (secret), `FGA_TIMEOUT`, `FGA_CACHE_TTL` | OpenFGA relationship checks (S248) |
| `SETTLEMENT_HOLD` (7d), `PAYOUT_MINIMUM_MINOR` (2500), `PAYMENTS_ZONE` | business settings |

Local: `local` profile uses Stripe **test mode** on api.stripe.com — export a real `sk_test_…` key and run
`stripe listen --forward-to localhost:8105/v1/webhooks/stripe` for the webhook secret. Test cards:
`4000 0027 6000 3184` (3-D Secure → `REQUIRES_ACTION`), `4000 0000 0000 9995` (declined → `FAILED`).

## Build, test, deploy

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
cd java && ./mvnw -B -f services/payments/pom.xml verify   # checkstyle, unit + ArchUnit, Testcontainers ITs
helm dependency build java/services/payments/deploy/helm
helm template x java/services/payments/deploy/helm -f java/services/payments/deploy/helm/values-prod.yaml \
  | kubeconform -strict -ignore-missing-schemas
tools/contracts/export_java_openapi.sh payments payments 8105   # after mvn package
```

Integration tests run PostgreSQL (`imresamu/postgis:17-3.5.4-alpine3.22`) and an in-JVM WireMock standing in for Stripe; they
assert the exact Stripe requests (amounts, idempotency keys, `transfer_group`, `Stripe-Account`), sign webhooks with
a test secret, and move an injectable clock across the settlement hold.
