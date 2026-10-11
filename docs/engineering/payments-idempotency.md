# Payments idempotency: never charge a customer twice

Customers pay only on AfroTransact's own pages, with Stripe Elements. A retry, a double click, a lost answer, a
second device or a late webhook must never lead to a second charge. This page lists every hop between a click and
Stripe, the key each hop uses and how it removes duplicates. If you change one of these hops, update this page and
its tests.

## No Stripe-hosted pages

- Checkout (web): the Payment Element and the Express Checkout Element use the deferred-intent flow. Orders creates
  the PaymentIntent with manual capture, and `stripe.confirmPayment({ elements, clientSecret, redirect: 'if_required' })`
  confirms it. Only redirect-based methods leave the page, and they come back to `/checkout/complete`.
- Mobile: the PaymentSheet confirms the same order PaymentIntent.
- Late pre-order adds paid by link: customer service texts a **pay-now link** to our own page,
  `<storefront>/orders/<number>/pay?token=<token>`. That page confirms the order's existing manual-capture
  PaymentIntent with the same Elements components and Appearance as checkout.
  - The token is an HMAC (`PAY_LINK_KEY`) over the order id, the order number and the expiry: `1.<exp base36>.<128-bit mac>`.
    Nothing is stored, so a replay re-derives the same link.
  - Answers:
    - wrong token or unknown order: 404;
    - expired or voided order: 410;
    - paid order: the page shows "paid" without a client secret, so a link can only pay once.
  - The SMS (`preorder.late_order.payment_link`) fits one GSM-7 segment.
- payments has no Checkout Session or Payment Link code.
  - V12 keeps the old `payment_link` rows read-only as `legacy_checkout_session`.
  - If a legacy session is still paid, the authorisation is cancelled (`<intentId>:cancel-orphan`).
  - `next_action.redirect_to_url` is never returned to clients.
- Out of scope: the B2B seller billing invoices (`hosted_invoice_url`, seller service) are Stripe-hosted invoices
  for sellers, not customer payments.

## Every hop, its key, its dedupe

| Hop | Key | Dedupe / guarantee |
|---|---|---|
| Storefront checkout → BFF | `Idempotency-Key` (UUID made when checkout mounts) | The key is kept across retries until the order is placed or definitely failed (`definitiveFailure`: a 4xx about this request; network errors, timeouts, 5xx, `CHECKOUT_IN_PROGRESS` and Stripe confirmation errors keep it). After an error the known order is read (`getCheckoutStatus`) first. A ref set before `elements.submit()` makes Place order, saved cards and Express Checkout single-flight. Express Checkout confirmations are refused (`paymentFailed`) while busy. |
| Mobile checkout → BFF | `co_<uuid>` | Same policy (`keyAfterFailure`): kept until placed or definitely failed. |
| BFF → orders `POST /v1/checkout` | Relayed key | BFF `orders.slowTimeout` is 25s, above orders' `checkout-response-timeout` (20s), in every environment. `check_env_config.py` enforces it, so the BFF never reports a failure for a checkout that completes. |
| orders checkout claim | `(customer, key)` primary key on `checkout_request` | It fixes the order id, the number and the workflow id. A replay, even a concurrent one, joins the same saga. The same key with another cart is 409. |
| orders: one open checkout per customer | Partial unique index on `checkout_request(customer_id) where open_checkout` | Another key with the **same cart** resumes the open checkout (same order, same PaymentIntent). Another key with **another cart** first cancels the open checkout's PaymentIntent (payments' `<orderId>:cancel`) and signals its saga, which releases the stock and voids the order; only then may it start. A paid open checkout is placed instead and the attempt is refused (409 `PREVIOUS_CHECKOUT_PAID`). A payment still processing is 409 `CHECKOUT_IN_PROGRESS`; retry with the same key. Late adds never hold the slot. |
| orders saga → payments `POST /v1/payments` | `Idempotency-Key` = order id (scope `payment-create`) | Plus one payment per order (`payment.order_id` unique, deterministic payment id). A replay with other amounts or lines is 409. |
| payments → Stripe create PaymentIntent | `<orderId>:create-intent` | Stripe replays the same intent for 24 h. Late adds charged off-session use the same key. |
| payments → Stripe capture / refund / cancel / release | `<prefix>:capture:<shipmentId>`, `<prefix>:refund:<refundId>`, `<prefix>:cancel`, `<prefix>:release` (`prefix` = order id, or `<orderId>:os:<paymentId>` for off-session follow-ups) | A shipment is captured once (`payment_capture` unique per shipment). Over-capture is refused (`capture-exceeds-authorised`), and refunds are bounded per line. |
| payments transfers / reversals / payouts | `<prefix>:reversal:<refundId>:<transferId>`, settlement and payout keys | Each ledger journal has a unique key (`capture:<id>`, `refund:<id>`, …), so a replay never posts twice. |
| Stripe webhooks → payments | Stripe event id | Signature verified. The inbox stores each event once (`stripe_event` primary key = event id) and processes it asynchronously. |
| Webhook ordering | — | An authorised payment (AUTHORIZED / PARTIALLY_CAPTURED) is never moved back by an older `payment_failed` or `requires_action`. The handler re-reads the intent instead and publishes no `INTENT_FAILED`. An authorisation that reaches a cancelled payment is cancelled at Stripe (`<prefix>:cancel-late-authorization`). |
| Temporal checkout saga | Workflow id `checkout-<orderId>` | Activities are deterministic and every side effect is keyed by the order id (reservation, payment, capture). Compensations (cancel payment, release stock) are idempotent. |
| Staff late add: console → BFF | `late-<uuid>` made when the dialog opens | Required (422 without it). It is reused on every retry until the order is added. After a timeout or 5xx the dialog first looks the late add up (window orders, same phone and items) and only then offers Retry with the same key. |
| BFF → delivery-runs | Relayed key | `deliveryRuns.slowTimeout` is 20s, above delivery-runs' 15s orders timeout. |
| delivery-runs → orders `late-preorder` | Relayed key (required, 422 `delivery-runs.idempotency-key-required`) | The UUID fallback is gone. A replay records the order once and texts the link once (`preorder.late_order.added` only for a new record). |
| orders late add | `(customer, key)` claim | Business guard: a second live late add of the same window and items for the customer within 10 min under **another** key is 409 `orders.late-add-duplicate` (advisory lock + `late_add_guard`). Saved card: payments `<orderId>:create-intent` off-session, so the card is charged once. |
| Pay-now page → BFF → orders | Signed token | Lookup is read-only. Confirm reads the intent through payments and places the order once (`place` is a no-op once placed). The webhook path does the same. |
| Late add expiry | — | The job cancels the PaymentIntent (`<orderId>:cancel`), then voids the order. The pay page refuses the link as soon as it expires. |
| Booking deposits (web and app) | `bk-<uuid>` / `bk_<uuid>` per attempt | The key is kept after timeouts and deposit errors and changes only after success or a refusal. The BFF's minute-bucket key is used only when a client sends none. |

## Tests that hold these guarantees

| Area | Tests |
|---|---|
| orders | `CheckoutIT`: same cart under two keys gives one order and one intent; retry with the same key; a different cart supersedes the open checkout; the open-checkout constraint. `PreorderCheckoutIT`: pay-now link to our page, expiry cancels the intent, a paid link places once and is then refused, key replay charges once, duplicate under another key is 409, key required. `HmacPayLinkSignerTest`. |
| payments | `WebhookInboxIT`: a stale `payment_failed` after AUTHORIZED; a late authorisation of a cancelled payment is cancelled. `PreorderPaymentsIT`: pay-now late adds create a PaymentIntent, never a session; cancelling one cancels the intent; an off-session replay charges once. `PaymentTest`. `StripeMockContractIT` (stripe-mock). |
| delivery-runs | `PreorderIT`: a late add without a key is 422; a replayed key gives one order, one charge and one SMS; orders' 409 stays 409. |
| gateway-bff | Late add key required and relayed; `TestPayLinksServeOurPayPageWithoutASession`; `TestSlowCallsUseTheSlowTimeout`. |
| Web and mobile | Storefront `payments-idempotency.test.tsx` (fake Stripe.js): key kept after a network error, a lost answer and a confirmation error; a double click places once; Express Checkout ignored while busy; the pay-now page confirms with Elements and refuses expired, paid and invalid links. `e2e/paynow.spec.ts` (mock mode). Staff console `late-add-idempotency.test.tsx`. Mobile `checkout.test.ts`. api-client `idempotency.test.ts`. |
