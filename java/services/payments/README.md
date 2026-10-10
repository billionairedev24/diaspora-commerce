# payments-service

Stripe PaymentIntents, captures, refunds, Connect transfers and payouts, the immutable double-entry ledger,
multi-seller settlement, fee schedules, chargebacks, reconciliation and shoppers' saved payment methods.
Spring Boot 4.1 · Java 25 · PostgreSQL `payments` · port **8105**. Architecture §5 (Transactions, Ledger), §7
(Stripe), §17b (Settlement & fees), §17d (webhooks). Stories S051–S057.

Wording: funds wait in a **settlement hold** (7 days after delivery / service completion). Stripe Connect accounts
use the **manual payout schedule**; sellers are paid **on request**.

## Model

| Table | What it holds |
|---|---|
| `payment` (+ `payment_line`) | One per order: Stripe PaymentIntent (manual capture), amount, status, fee-schedule version, lines (seller org, category, FBA/self, qty, items/shipping/tax, jurisdiction) with capture and refund state |
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
| `idempotency_record`, `reconciliation_run` | Idempotency-Key responses; daily reconciliation reports |

### Chart of accounts

`1000` Stripe clearing · `1100` Chargeback receivable · `2000:<org>` Seller payable · `2100:<jurisdiction>` Sales tax
payable (attributed to the seller) · `2200` Customer refunds payable · `2300:<org>` Chargeback reserve · `4000`
Commission · `4100` FBA fees · `4200` AfroTransact shipping · `5000` Stripe fees · `5100` Chargeback losses.

| Event | Journal |
|---|---|
| Capture (per shipment) | Dr 1000 (charge − fee), Dr 5000 (AfroTransact's fee share) / Cr 2000 sellers' net, Cr 2100 tax, Cr 4000, Cr 4100, Cr 4200 |
| Refund | Dr 2100 tax, Dr 2000 (items + own shipping − commission returned), Dr 4000 (commission returned), Dr 4200 / Cr 2200 |
| Refund paid by Stripe | Dr 2200 / Cr 1000 |
| Transfer after the hold | Dr 2000 / Cr 1000 · reversal: Dr 1000 / Cr 2000 |
| Chargeback opened | Dr 1100 + Dr 5100 (dispute fee) / Cr 1000; Dr 2000 / Cr 2300 per responsible seller |
| Won / lost | Dr 1000 / Cr 1100 and Dr 2300 / Cr 2000 · lost: Dr 2300 (+ 5100 platform part) / Cr 1100 |

Every money-moving use case runs in a **SERIALIZABLE** transaction retried on SQLSTATE 40001/40P01 and optimistic
conflicts (`MoneyTransactions`); `LedgerPoster` refuses to post outside one, and posts each journal once per
business key. Stripe calls inside use idempotency keys derived from business ids, so retries replay Stripe:
`<order>:create-intent`, `<order>:capture:<shipment>`, `<order>:cancel`, `<order>:refund:<refundId>`,
`<order>:reversal:<refund>:<transfer>`, `<order>:transfer:<org>:<n>`, `payout:<requestId>`, `customer:<id>`.

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
| `POST /v1/payments/{id}/capture` · `/cancel` · `/refunds` · `GET …/captures` · `…/refunds` | orders |
| `POST /v1/settlements/{orderId}/delivered` (`kind` PRODUCT / SERVICE) · `GET /v1/settlements/{orderId}` | orders, bookings, fulfilment |
| `GET /v1/orders/{orderId}/split` · `GET /v1/ledger/balances` · `/v1/ledger/orgs/{orgId}/balances` · `/v1/ledger/journals?orderId=` | staff `payments:read` |
| `GET /v1/fee-schedules` · `/active?at=` · `POST /v1/fee-schedules` | read / `payments:configure` (Super Admin, Finance) |
| `GET /v1/payouts/balance` · `POST /v1/payouts/requests` | org members / owner & finance roles with `payouts:request` |
| `GET /v1/payouts/requests?status=&orgId=` · `POST …/{id}/approve` · `/hold` · `/release` | Finance `payouts:approve` |
| `GET /v1/reconciliation/runs` · `POST /v1/reconciliation/runs {date}` | `payments:read` / `payments:configure` |
| `PUT/GET /v1/connected-accounts/{orgId}` | seller service or `payments:configure` |
| `GET /v1/customers/me/payment-methods` · `POST /v1/customers/me/setup-intents` · `POST …/{id}/default` · `DELETE …/{id}` | the shopper (CUSTOMER token relayed by gateway-bff) |
| `POST /v1/webhooks/stripe` | Stripe (public; `Stripe-Signature` verified) |

Payout rules: minimum USD 25, one request per day (failed payouts don't count), never more than available, only
`seller.owner/finance` or `provider.owner/finance` (or staff). Removing the default saved method is refused
(`409 payments.default-method-required`): choose another default first.

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
`payments.refunded`, `payments.transfer.created`, `payments.dispute.opened|closed`. `at.payout.events` (key org):
`payments.payout.requested|approved|paid|failed`.

Consumed (group `payments-service`, inbox-deduplicated): `seller.payouts.paused|resumed` on `at.seller-compliance.events`
set or clear the organisation's `payout_hold` (V4; older decisions never override newer ones). While paused, payout
requests and approvals fail with 422 `payments.payouts_paused`; balances keep accruing.

## Jobs & metrics

Webhook inbox (`WEBHOOK_INTERVAL`, 1 s) · settlement transfers (`SETTLEMENT_CRON`, every 15 min) · reconciliation
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
cd java && ./mvnw -B -f services/payments/pom.xml verify   # checkstyle, 40 unit + ArchUnit, 20 Testcontainers ITs
helm dependency build java/services/payments/deploy/helm
helm template x java/services/payments/deploy/helm -f java/services/payments/deploy/helm/values-prod.yaml \
  | kubeconform -strict -ignore-missing-schemas
tools/contracts/export_java_openapi.sh payments payments 8105   # after mvn package
```

Integration tests run PostgreSQL (`postgis/postgis:17-3.5`) and an in-JVM WireMock standing in for Stripe; they
assert the exact Stripe requests (amounts, idempotency keys, `transfer_group`, `Stripe-Account`), sign webhooks with
a test secret, and move an injectable clock across the settlement hold.
