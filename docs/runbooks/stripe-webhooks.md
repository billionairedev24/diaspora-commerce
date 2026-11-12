# Stripe webhooks: set-up, verification and troubleshooting

THE reference for the three Stripe webhook endpoints of AfroTransact: why each exists, how to create it in the Stripe
dashboard (both the current **Workbench** layout and the older **Developers > Webhooks** layout), which events to select,
where the signing secret goes, how to check that it works, and what to do when it does not. It covers the OVHcloud test
environment (`https://api.test.afrotransact.com` is the example host everywhere below), the real environments (dev, uat,
prod) and your laptop.

If you only want the short version, use the [checklist table](#checklist-copy-paste) and the
[quick route check](#2-quick-route-check-no-stripe-needed).

## 1. What the webhooks are and what breaks without them

Stripe tells our services what happened by sending an HTTPS `POST` (an *event*) to a URL we register. Nothing else tells
us: the customer's browser is not trusted to report a payment, and a bank debit finishes days after the click. Each
event carries a `Stripe-Signature` header; the service recomputes the signature with the endpoint's **signing secret**
(`whsec_...`) and answers `400` when it does not match. So an endpoint needs two things: a **route** (Stripe can reach
the service) and the **secret** (the service can verify it).

There are three endpoints, one per signing secret. Stripe gives every endpoint its own secret, so one endpoint = one
variable. Never put one secret into two variables.

| # | Endpoint (public URL) | Service | Secret variable | What it does | What breaks without it |
|---|---|---|---|---|---|
| 1 | `/payments/v1/webhooks/stripe` | payments | `STRIPE_WEBHOOK_SECRET` | Payment, refund, dispute, payout and saved-payment-method events of the **platform** account. | **Payments never confirm**: an order stays "payment pending", refunds, disputes and payouts are not recorded, saved cards do not appear. Required. |
| 2 | `/seller/v1/webhooks/stripe-connect` | seller | `STRIPE_CONNECT_WEBHOOK_SECRET` | Events of **connected accounts** (the sellers): verification status, capabilities, payout bank, failed payouts, disconnects. Drives the compliance status, reminders and payout holds. | Seller onboarding never completes (status stuck on "verifying"), payout holds and listing hiding do not react to Stripe. Required for sellers. |
| 3 | `/seller/v1/webhooks/stripe-billing` | seller | `STRIPE_BILLING_WEBHOOK_SECRET` | The outcome of **plan-fee PaymentIntents** that were still processing when we charged (a US bank debit settles days later). | Only those plan fees: an invoice paid by bank debit stays "processing" until the retry job notices. **Optional** for a test environment that pays plan fees by card only. |

**About "billing".** Stripe Billing (Stripe subscriptions and invoices) was replaced by our own billing: our own
invoices, charged with PaymentIntents. The endpoint name is historical. The code (`StripeWebhookAdapter`,
`StripeWebhookServiceImpl`) acts on exactly two events there, `payment_intent.succeeded` and
`payment_intent.payment_failed`, and only for PaymentIntents whose metadata says `purpose=plan_fee`. It ignores every
other event type with a `200`. The old `invoice.*`, `credit_note.*` and `customer.subscription.*` events are **not**
handled any more: do not select them.

The platform webhook (1) and the seller webhooks (2, 3) are separate on purpose: different services, different secrets,
different Stripe "Listen to" scope.

## 2. Quick route check (no Stripe needed)

An **unsigned** `POST` must answer **400** (route exists, signature enforced). `404` means the route is missing, `502/503`
means the service is down, a certificate error means TLS is not ready.

```bash
for p in payments/v1/webhooks/stripe seller/v1/webhooks/stripe-connect seller/v1/webhooks/stripe-billing; do
  printf '%s -> ' "$p"
  curl -s -o /dev/null -w '%{http_code}\n' -X POST "https://api.test.afrotransact.com/$p" \
    -H 'Content-Type: application/json' -d '{}'
done
# expected: three times 400
```

`make deploy-guide ARGS=--doctor` (and phase 8 "smoke" of `make deploy-guide`) runs the same check and points back here.

A `400` proves the route and the signature check only. It does **not** prove that the secret is set or right (a service
without a secret answers `400` too). That is proven by a **Send test event** returning `2xx` (section 6).

## 3. Prerequisites

1. **A Stripe account** (dashboard.stripe.com). Test mode needs no business verification.
2. **Test mode vs Live mode.** The toggle is at the top right of the dashboard ("Test mode", or **Sandbox** in newer
   accounts, which behaves the same way for this purpose). Test and live have **separate API keys, separate webhook
   endpoints and separate signing secrets**. A secret made in test mode never verifies live events and the other way
   round. Everything below is done in the mode that matches the environment: **test mode for the OVH test environment, dev
   and uat; live mode only for prod** (payments refuses live keys outside prod).
3. **Keys** in the same account and mode: `STRIPE_SECRET_KEY`, `STRIPE_PUBLISHABLE_KEY`
   (Developers > API keys). See [secrets-and-configuration.md](../engineering/secrets-and-configuration.md).
4. **Connect enabled** in the **platform account** (the account that owns the keys): **Settings > Connect > Get started**
   (in Workbench: search "Connect" or open Connect > Overview). Test mode needs no platform profile. Without Connect,
   sellers cannot be onboarded and endpoint 2 has nothing to report. Seller accounts are created with Stripe liable for
   negative balances and Stripe collecting verification (see "Seller accounts and liability" in
   [payments-idempotency.md](../engineering/payments-idempotency.md)).
5. **Check it from your laptop:** `make stripe-setup` reads `.local/secrets.env` (or the environment), verifies that the key is a
   test key and matches the publishable key, that Connect is enabled, and that Stripe accepts the seller account settings
   (it creates and deletes a throwaway test account). Fix whatever it reports before creating webhooks.
6. **The server exists and the three URLs answer 400** (section 2). You can only create the endpoints after the first
   deploy because Stripe may validate that the URL is reachable and you need the final domain.

## 4. Create the endpoints

Do the steps once per endpoint, in the right mode. The dashboard changed layout; use the one you see.

### Which layout do I have?

* **Workbench** (current): **Developers** opens a Workbench panel at the bottom of the dashboard (or a full page) with tabs
  *Overview, Webhooks, Events, API keys, Logs...*. The button is **Add destination**.
* **Older layout:** the left menu has **Developers > Webhooks** and the button is **Add endpoint**.

Both end the same way: you get a URL, an event list and a **Signing secret**.

### 4.1 Endpoint 1: platform payments webhook (`STRIPE_WEBHOOK_SECRET`)

URL: `https://api.test.afrotransact.com/payments/v1/webhooks/stripe` (real environments:
`https://api.<base>/payments/v1/webhooks/stripe`, for example `https://api-dev.afrotransact.com/...`).

| Setting | Value |
|---|---|
| Listen to / Events from | **Your account** |
| Payload style | **Snapshot** (NOT *Thin*) |
| API version | the default shown (the account's version); do not pick an older one |
| Events (15) | `payment_intent.succeeded`, `payment_intent.amount_capturable_updated`, `payment_intent.payment_failed`, `payment_intent.requires_action`, `payment_intent.canceled`, `charge.refunded`, `charge.dispute.created`, `charge.dispute.closed`, `transfer.reversed`, `payout.paid`, `payout.failed`, `setup_intent.succeeded`, `setup_intent.requires_action`, `payment_method.attached`, `payment_method.detached` |

**Workbench:** Developers > **Webhooks** tab > **Add destination** > *Events from*: **Your account** > *Payload style*:
**Snapshot** > *API version*: leave the default > **Select events**: use the search box, tick each event above (the dialog also
lets you tick the whole group, e.g. *Payment Intent*, then untick what is not in the list) > **Continue** > *Destination
type*: **Webhook endpoint** > **Continue** > *Endpoint URL*: paste the URL, *Name*: `afrotransact-payments` > **Create
destination**. On the destination's page, *Signing secret* > **Reveal** (or the copy icon) and copy `whsec_...`.

**Older layout:** Developers > **Webhooks** > **Add endpoint** > *Endpoint URL*: paste the URL > *Description*:
`afrotransact-payments` > *Listen to*: **Events on your account** > *Select events*: tick the events above >
**Add endpoint**. On the endpoint page, *Signing secret* > **Click to reveal**, copy `whsec_...`.

### 4.2 Endpoint 2: seller Connect webhook (`STRIPE_CONNECT_WEBHOOK_SECRET`)

URL: `https://api.test.afrotransact.com/seller/v1/webhooks/stripe-connect` (real: `https://api.<base>/seller/v1/webhooks/stripe-connect`).

| Setting | Value |
|---|---|
| Listen to / Events from | **Connected accounts** (NOT *Your account*) |
| Payload style | **Snapshot** |
| API version | the default shown |
| Events (9) | `account.updated`, `capability.updated`, `person.created`, `person.updated`, `account.external_account.created`, `account.external_account.updated`, `account.external_account.deleted`, `payout.failed`, `account.application.deauthorized` |

**Workbench:** Developers > Webhooks > **Add destination** > *Events from*: **Connected accounts** > *Payload style*:
**Snapshot** > default API version > **Select events**: tick the nine above > **Continue** > **Webhook endpoint** >
**Continue** > URL, *Name*: `afrotransact-seller-connect` > **Create destination** > copy the *Signing secret* (`whsec_...`).

**Older layout:** Developers > Webhooks > **Add endpoint** > URL > *Listen to*: **Events on Connected accounts** >
*Select events*: tick the nine above > **Add endpoint** > reveal and copy the *Signing secret*.

`account.application.deauthorized` is the event a connected account sends when it disconnects from the platform; it is
listed under connected-account events, so use the search box if you cannot find it in the group tree.

### 4.3 Endpoint 3: seller plan-fee webhook (`STRIPE_BILLING_WEBHOOK_SECRET`), optional

Create it if sellers may pay their plan by **US bank debit** (a PaymentIntent that is `processing` for days). Skip it for a
test environment that uses cards only; `STRIPE_BILLING_WEBHOOK_SECRET` may then stay empty and the endpoint answers `400`
to everything, which is harmless.

URL: `https://api.test.afrotransact.com/seller/v1/webhooks/stripe-billing` (real: `https://api.<base>/seller/v1/webhooks/stripe-billing`).

| Setting | Value |
|---|---|
| Listen to / Events from | **Your account** |
| Payload style | **Snapshot** |
| API version | the default shown |
| Events (2) | `payment_intent.succeeded`, `payment_intent.payment_failed` |

Click path as in 4.1 with these settings; name it `afrotransact-seller-plan-fees`. Endpoint 1 listens to the same two
event types: that is correct and intended. Stripe sends each event to every endpoint that selected it, each with its own
signature; payments ignores plan-fee intents it has no payment for, seller ignores everything that is not a plan fee.

### Payload style: Snapshot, never Thin

*Snapshot* events contain the full object. *Thin* events contain only ids and need a different parser: **every thin event
answers `400`**. If the dashboard asks (Workbench), choose **Snapshot**. Never use `stripe listen --all-thin`.

### Where the secret goes

The signing secret is shown on the endpoint's page (**Reveal**). One endpoint, one secret, one variable:

| Endpoint | Variable | Read by |
|---|---|---|
| 1 platform payments | `STRIPE_WEBHOOK_SECRET` | payments |
| 2 seller Connect | `STRIPE_CONNECT_WEBHOOK_SECRET` | seller |
| 3 seller plan fees | `STRIPE_BILLING_WEBHOOK_SECRET` | seller |

## 5. How the secret reaches the pod, and how to apply a change

**OVH test environment**

```
Stripe dashboard (whsec_...) -> deploy wizard phase 8 (make deploy-guide) or make ovh-gh-setup
  -> GitHub Environment "ovh-vps" secret  STRIPE_WEBHOOK_SECRET / STRIPE_CONNECT_WEBHOOK_SECRET / STRIPE_BILLING_WEBHOOK_SECRET
  -> workflow ovh-vps (read in .github/workflows/ovh-vps.yml)
  -> tools/ovh/render-config.sh writes secrets.local.env on the server
  -> infra/ovh-vps/secrets.sh services builds the Kubernetes Secrets  payments-secrets / seller-secrets
  -> pod environment variable (read once at pod start)
```

To apply a new or changed secret:

1. Store it: `make deploy-guide ARGS='--set STRIPE_CONNECT_WEBHOOK_SECRET'` (or re-run `make ovh-gh-setup`).
2. Run the workflow: **Actions > ovh-vps > Run workflow > action `services`** (approve it). This rewrites the Kubernetes
   Secrets.
3. **Restart the service that reads it.** A running pod keeps the value it started with, and `services` only rolls pods whose
   image tag changed, so restart explicitly (SSH to the server, see [ovh-vps.md](ovh-vps.md) step 7):
   ```bash
   sudo k3s kubectl -n afrotransact-dev rollout restart deploy/payments   # STRIPE_WEBHOOK_SECRET
   sudo k3s kubectl -n afrotransact-dev rollout restart deploy/seller     # STRIPE_CONNECT_WEBHOOK_SECRET, STRIPE_BILLING_WEBHOOK_SECRET
   sudo k3s kubectl -n afrotransact-dev rollout status deploy/seller
   ```
   (The wizard's phase 8 offers to run step 2 for you; do step 3 afterwards.)

**Real environments (dev, uat, prod on Kubernetes):** the secret goes into the environment's secret store with
`scripts/secret-set.sh <cloud> ENV payments STRIPE_WEBHOOK_SECRET=-` (and `ENV seller STRIPE_CONNECT_WEBHOOK_SECRET=-
STRIPE_BILLING_WEBHOOK_SECRET=-`); see [first-environment.md](first-environment.md). External Secrets syncs it into the pod's
Secret; then restart the pods: uat and prod run Argo Rollouts, so `kubectl argo rollouts restart payments -n afrotransact-ENV` (and `seller`); dev
uses plain Deployments, `kubectl -n afrotransact-dev rollout restart deploy/payments deploy/seller`. The public routes are in
`infra/k8s/platform/edge/overlays/<env>/routes.yaml`.

**Careful:** while `GIFT_CARD_CODE_KEY` and `REFERRAL_SIGNAL_KEY` (payments) are empty, payments derives them from
`STRIPE_WEBHOOK_SECRET`. Changing that secret on an environment that already has gift cards or referral data changes those
HMACs. Set both keys explicitly **before** the first rotation of `STRIPE_WEBHOOK_SECRET` in any environment with real data.

## 6. Verify that each endpoint works

1. **Route:** section 2, all three answer `400` to an unsigned `POST`.
2. **Stripe sends a test event** (this is the proof that the secret is right):
   * Workbench: Developers > Webhooks > click the destination > **Send test event** (top right) > pick an event type from its
     list (for example `payment_intent.succeeded` for endpoints 1 and 3, `account.updated` for 2) > **Send test event**.
   * Older layout: Developers > Webhooks > click the endpoint > **Send test webhook** > pick the event type > **Send test webhook**.
   * Open the **Event deliveries** (older: **Attempts**) tab: the attempt must show **200**. A test event has no real
     payment or account behind it: the service stores and acknowledges it (`outcome` `ignored`, or `unknown account`), which is
     correct. `400` here means wrong secret, wrong mode or Thin payload (troubleshooting below).
3. **Logs** (SSH to the server as in ovh-vps.md; real environments: your log tool):
   ```bash
   sudo k3s kubectl -n afrotransact-dev logs deploy/payments --tail=100 | grep -i -E "webhook|signature|stripe"
   sudo k3s kubectl -n afrotransact-dev logs deploy/seller   --tail=100 | grep -i -E "webhook|signature|stripe"
   sudo k3s kubectl -n afrotransact-dev logs -n ingress deploy/traefik --tail=50 2>/dev/null | grep webhooks   # request reached the edge
   ```
   A failed signature logs as `payments.invalid-signature` / `seller.invalid-signature` (HTTP 400).
4. **A real end-to-end event** (test mode): pay for an order with card `4242 4242 4242 4242`; the order leaves "payment
   pending" within seconds and the delivery list of endpoint 1 shows `payment_intent.succeeded` with 200. For endpoint 2, start a
   seller's Stripe verification in Seller Center and watch `account.updated` arrive.

## 7. Locally with `stripe listen` (no public URL needed)

On your laptop the Stripe CLI forwards events and prints its own `whsec_...`; no dashboard endpoint is needed. The exact
commands, event lists and ports are in [local-development.md](../engineering/local-development.md) ("Test the whole money
flow") and [secrets-and-configuration.md](../engineering/secrets-and-configuration.md) (Stripe, "Webhooks locally"). The
rules are the same as here: Snapshot (never `--all-thin`), `--events` with the lists from section 4, the secret printed by each listener
goes into the matching variable of `.local/secrets.env`, then restart the service. Locally the routes are the service ports
directly: `localhost:8105/v1/webhooks/stripe` (payments) and `localhost:8106/v1/webhooks/stripe-connect|stripe-billing` (seller).

## 8. Live mode (prod) differences

* Create **separate** endpoints in **Live mode** (toggle off Test mode). Same URLs pattern with the prod host
  (`https://api.<base>/...`), same events, same *Snapshot*; a **new** signing secret per endpoint.
* Live keys (`sk_live_`, `pk_live_`) are accepted only in prod (`require-live-key`); the secrets live in prod's own secret store, never in the
  test environment.
* Connect in live mode needs the platform profile and business verification of the platform account to be completed in
  the dashboard first.
* Never reuse a test `whsec_` in prod or the other way round: the symptom is `400` on every delivery while the route check still says `400`.
* Rotate prod secrets in a maintenance window (below) and watch the first deliveries.

## 9. Rotate a signing secret safely

Stripe can **roll** a secret and keep the old one valid for a while, so no event is lost:

1. Endpoint page > **Roll secret** (Workbench: the `...` menu or the *Signing secret* area; older layout: **Roll secret**) >
   choose an **expiry** for the old secret (24 hours is typical) > confirm. Stripe now signs each event with **both** secrets.
2. Copy the new `whsec_...`, store it (section 5, steps 1 and 2) and **restart** the service (step 3). Our code verifies
   against one secret only, so deliveries signed with both pass either way as long as the new one is the one deployed
   before the old one expires.
3. Send a test event (section 6). It must be `200`.
4. If it fails, the old secret still works until it expires: put it back and restart.

Rotate after a leak (a secret pasted in chat, a log or a screenshot) immediately, with expiry **now**. Remember the
`GIFT_CARD_CODE_KEY` warning in section 5.

## 10. Troubleshooting

| Symptom (Stripe "Event deliveries" / curl) | Cause | Fix |
|---|---|---|
| `404` (Traefik body `404 page not found`) | The route is missing or the URL is wrong: a typo, `/v1/webhooks/stripe` without the `/payments` or `/seller` prefix, or an old server deployed before the seller routes existed | Use the URLs of the checklist. Re-run **ovh-vps** action `edge` (or `deploy`). Real environments: the edge overlay `routes.yaml` must contain the route, then sync Argo CD. Run `python3 tools/ci/check_webhook_routes.py` |
| `400` on **unsigned** curl | Expected. It is the success answer of the quick route check | nothing |
| `400` on a **Stripe** delivery (`*.invalid-signature`) | Wrong secret (the endpoint's secret is in another variable, or an old value), wrong **mode** (test secret on a live endpoint or the reverse), the secret was changed but the pod not restarted, an empty secret, or a **Thin** payload | Reveal the endpoint's secret again and compare it with the variable of its row in section 4; section 5 step 3 (restart); check the Test/Live toggle; recreate the destination with **Snapshot** |
| `401` / `403` | Something in front of the service demands a login: a proxy or WAF rule, or a path other than the three webhook paths (the seller service answers `401` to every other path) | Check the exact path. The api host is DNS-only (grey cloud) in the OVH test environment, so Cloudflare is not in the way; if you proxied it, add a WAF skip for these paths |
| `502` / `503` | The service pod is down or restarting (`CrashLoopBackOff`), or (billing endpoint) `503 retry later` while a plan-fee settlement failed transiently | `kubectl -n afrotransact-dev get pods`, `logs deploy/seller`; Stripe retries automatically |
| Timeout (no answer in time) | Service overloaded or the server is stopped (`down` action) | `status` action; scale up (`up`); Stripe retries |
| Certificate error / "could not verify the TLS certificate" in Stripe | The api host has no valid public certificate yet (Let's Encrypt not issued), or the host is proxied through a different CA | `make deploy-guide ARGS=--doctor` (certificate check); ovh-vps.md row "Certificate stays False" |
| "Failed to connect" / DNS error | `api.<domain>` does not resolve to the server | DNS A record (grey cloud) pointing at the server IP |
| Same event twice | Stripe retries, and endpoints 1 and 3 both receive `payment_intent.*` | Harmless: each service stores the event id once and answers `200` with `duplicate` |
| Stripe says the endpoint is **failing** / **disabled** | Deliveries failed for days (Stripe retries with backoff, in live mode for up to 3 days, in test mode for a few hours), then emails you and may disable the endpoint | Fix the cause above, then in the endpoint page **Enable** it (and, for missed events, **Resend** them from the Events tab; they are idempotent) |
| Payments stay "pending", sellers stay "verifying", but deliveries show `200` | The event type is not selected on the endpoint, or it belongs to the other "Listen to" scope (Connect events on a *Your account* endpoint) | Compare the endpoint's events and *Listen to* with section 4 |
| Test event returns `200` but nothing changes | Test events carry fake ids, so there is nothing to update | Do the real end-to-end check in section 6 |

## Checklist (copy-paste)

Replace the host with your own (`api.test.afrotransact.com` is the OVH test environment).

| Endpoint | URL | Listen to | Events | Secret variable | Needed in the test env |
|---|---|---|---|---|---|
| Payments (platform) | `https://api.test.afrotransact.com/payments/v1/webhooks/stripe` | Your account | 15 (section 4.1) | `STRIPE_WEBHOOK_SECRET` | **Required** |
| Seller Connect | `https://api.test.afrotransact.com/seller/v1/webhooks/stripe-connect` | Connected accounts | 9 (section 4.2) | `STRIPE_CONNECT_WEBHOOK_SECRET` | **Required** for sellers |
| Seller plan fees | `https://api.test.afrotransact.com/seller/v1/webhooks/stripe-billing` | Your account | 2 (section 4.3) | `STRIPE_BILLING_WEBHOOK_SECRET` | Optional (bank-debit plan fees only) |

All three: **Snapshot** payload, default API version, in the mode (Test or Live) that matches the environment.

## Where this is defined (for maintainers)

| What | Where |
|---|---|
| Public routes, OVH | `infra/ovh-vps/deploy.sh` (`route api /payments/v1/webhooks`, `/seller/v1/webhooks/stripe-connect`, `/seller/v1/webhooks/stripe-billing`) |
| Public routes, Kubernetes | `infra/k8s/platform/edge/overlays/{dev,uat,prod}/routes.yaml` (Gateway API, prefix rewritten to `/v1/webhooks...`) |
| Route guard | `python3 tools/ci/check_webhook_routes.py` (part of `make env-check`) |
| Signature check | payments `StripeSignatureVerifier`; seller `StripeWebhookAdapter` (stripe-java `Webhook.constructEvent`, 5 minute timestamp tolerance (payments: `at.payments.stripe.webhook-tolerance-seconds`, default 300), HMAC-SHA256) |
| Idempotency | both store the event id once (`stripe_webhook_event` / payments inbox); duplicates answer `200` |
| Event handlers | payments `StripeEventHandlers.SUPPORTED`; seller `ComplianceServiceImpl` (Connect) and `StripeWebhookAdapter` (plan-fee PaymentIntents) |
| Security | `/v1/webhooks/stripe` (payments) and `/v1/webhooks/stripe-connect`, `/stripe-billing` (seller) are the only unauthenticated `POST`s of those services besides `/v1/launch-list`; signature is the authentication |
| Network | the chart's NetworkPolicy admits the `ingress` namespace; Cloudflare WAF/rate-limit rules in `infra/cloudflare/zone.tf` match none of these paths and the backend hosts are not proxied |
| Wizard check | `tools/deploy/guide.sh` (`chk_webhooks`), tested in `tools/deploy/test/test-guide.sh` |
| Secret catalogue | `tools/deploy/catalogue.conf` |
