# payments-service

Stripe PaymentIntents, captures, refunds, Connect transfers and payouts, the immutable double-entry ledger,
multi-seller settlement, fee schedules, chargebacks, reconciliation and shoppers' saved payment methods.
Spring Boot 4.1 · Java 25 · PostgreSQL `payments` · port **8105**. Architecture §5 (Transactions, Ledger), §7
(Stripe), §17b (Settlement & fees), §17d (webhooks). Stories S051–S057, S205, S214, S220.

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
| `settlement`, `settlement_transfer`, `transfer_reversal` | Settlement hold per order, Connect transfers (`transfer_group` = order id) and reversals |
| `payout_request` | Payout requests (unique per org per day, failed excluded) |
| `dispute` | Chargebacks with per-seller allocation |
| `stripe_event` | Webhook inbox (event id unique) |
| `stripe_customer`, `customer_payment_method` | Stripe Customer per AfroTransact customer; saved methods (display data only) |
| `connected_account` | Stripe Connect account per organisation |
| `provider_fee` | Fees providers owe (late provider cancellations), one per booking: OUTSTANDING → COLLECTED by a Stripe account debit (V5) |
| `idempotency_record`, `reconciliation_run` | Idempotency-Key responses; daily reconciliation reports |

### Chart of accounts

`1000` Stripe clearing · `1100` Chargeback receivable · `1200:<org>` Provider fees receivable · `2000:<org>` Seller payable · `2100:<jurisdiction>` Sales tax
payable (attributed to the seller) · `2200` Customer refunds payable · `2300:<org>` Chargeback reserve · `4000`
Commission · `4100` FBA fees · `4200` AfroTransact shipping · `4300` Provider late-cancellation fees · `5000` Stripe
fees · `5100` Chargeback losses.

| Event | Journal |
|---|---|
| Capture (per shipment) | Dr 1000 (charge − fee), Dr 5000 (AfroTransact's fee share) / Cr 2000 sellers' net, Cr 2100 tax, Cr 4000, Cr 4100, Cr 4200 |
| Refund | Dr 2100 tax, Dr 2000 (items + own shipping − commission returned), Dr 4000 (commission returned), Dr 4200 / Cr 2200 |
| Refund paid by Stripe | Dr 2200 / Cr 1000 |
| Transfer after the hold | Dr 2000 / Cr 1000 · reversal: Dr 1000 / Cr 2000 |
| Chargeback opened | Dr 1100 + Dr 5100 (dispute fee) / Cr 1000; Dr 2000 / Cr 2300 per responsible seller |
| Won / lost | Dr 1000 / Cr 1100 and Dr 2300 / Cr 2000 · lost: Dr 2300 (+ 5100 platform part) / Cr 1100 |
| Provider late-cancellation fee recorded | Dr 1200:<org> / Cr 4300 |
| Fee collected (Stripe account debit) | Dr 1000 / Cr 1200:<org> |
| Authorisation released | — (authorisations never reach the ledger; 1000 moves only at capture) |

Every money-moving use case runs in a **SERIALIZABLE** transaction retried on SQLSTATE 40001/40P01 and optimistic
conflicts (`MoneyTransactions`); `LedgerPoster` refuses to post outside one, and posts each journal once per
business key. Stripe calls inside use idempotency keys derived from business ids, so retries replay Stripe:
`<order>:create-intent`, `<order>:capture:<shipment>`, `<order>:cancel`, `<order>:refund:<refundId>`,
`<order>:reversal:<refund>:<transfer>`, `<order>:transfer:<org>:<n>`, `payout:<requestId>`, `customer:<id>`,
`<order>:release`, `provider-fee:<feeId>:<seq>` (seq changes only after a definitive 4xx refusal). An off-session
charge uses the prefix `<order>:os:<paymentId>` instead of `<order>` so its keys never collide with the parent's.

## Payment split (S054)

Per recipient: items, shipping (FBA shipping → AfroTransact), tax (collected and remitted by AfroTransact as
marketplace facilitator, attributed to the seller), commission (category rate, seller override first, half-up per
line; **AfroTransact Retail pays none**), FBA fee per unit, and a share of the Stripe fee pro rata to each recipient's
part of the charge (largest-remainder; AfroTransact wins ties and takes any leftover cent). Invariant, checked on
every split and by 5 000 random property cases: `Σ nets + tax = charge − Stripe fee`.

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
| `GET /v1/fee-schedules/effective?category=&orgId=&at=` | services (`payments:service`) or staff `payments:read`: commission bps (override / category / default / retail), card fee estimate |
| `GET /v1/payouts/balance` · `POST /v1/payouts/requests` | org members / owner & finance roles with `payouts:request` |
| `GET /v1/payouts/provider-fees?orgId=` | org members (own org) / staff |
| `GET /v1/payouts/requests?status=&orgId=` · `POST …/{id}/approve` · `/hold` · `/release` | Finance `payouts:approve` |
| `GET /v1/reconciliation/runs` · `POST /v1/reconciliation/runs {date}` | `payments:read` / `payments:configure` |
| `PUT/GET /v1/connected-accounts/{orgId}` | seller service or `payments:configure` |
| `GET /v1/customers/me/payment-methods` · `POST /v1/customers/me/setup-intents` · `POST …/{id}/default` · `DELETE …/{id}` | the shopper (CUSTOMER token relayed by gateway-bff) |
| `POST /v1/customers/me/ephemeral-keys {stripeVersion?}` → `{customerId, ephemeralKeySecret, expiresAt}` | the shopper: PaymentSheet (S241) for the shopper's Stripe Customer, created on first use; gateway-bff `POST /v1/account/payment-sheet` calls it |
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
| `DB_URL`, `DB_USER`, `DB_PASSWORD`, `KAFKA_BOOTSTRAP`, `AUTH_ISSUER_URI` | infrastructure |
| `RETAIL_ORG_ID` | AfroTransact Retail org (no commission) |
| `SETTLEMENT_HOLD` (7d), `PAYOUT_MINIMUM_MINOR` (2500), `PAYMENTS_ZONE` | business settings |

Local: `local` profile uses Stripe **test mode** on api.stripe.com — export a real `sk_test_…` key and run
`stripe listen --forward-to localhost:8105/v1/webhooks/stripe` for the webhook secret. Test cards:
`4000 0027 6000 3184` (3-D Secure → `REQUIRES_ACTION`), `4000 0000 0000 9995` (declined → `FAILED`).

## Build, test, deploy

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
cd java && ./mvnw -B -f services/payments/pom.xml verify   # checkstyle, 49 unit + ArchUnit, 27 Testcontainers ITs
helm dependency build java/services/payments/deploy/helm
helm template x java/services/payments/deploy/helm -f java/services/payments/deploy/helm/values-prod.yaml \
  | kubeconform -strict -ignore-missing-schemas
tools/contracts/export_java_openapi.sh payments payments 8105   # after mvn package
```

Integration tests run PostgreSQL (`postgis/postgis:17-3.5`) and an in-JVM WireMock standing in for Stripe; they
assert the exact Stripe requests (amounts, idempotency keys, `transfer_group`, `Stripe-Account`), sign webhooks with
a test secret, and move an injectable clock across the settlement hold.
