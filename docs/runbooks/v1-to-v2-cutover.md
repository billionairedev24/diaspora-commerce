# v1 to v2 production cutover (same Stripe account)

Written 2026-11-12. Ordered plan for moving production from AfroTransact v1 to v2 while keeping **the same Stripe account**
(same platform account, same connected accounts, same customers). v1 is decommissioned afterwards, so v2's webhook endpoints
are new and v1's are switched off.

This runbook is the order of events and the Stripe, DNS, comms and go/no-go parts. The data movement (Keycloak users and
passwords, orders, payments, the seller) is specified in
[afrotransact-v1-to-v2-migration.md](afrotransact-v1-to-v2-migration.md); this runbook calls it "the data runbook" and does
not repeat it. The webhook reference is [stripe-webhooks.md](stripe-webhooks.md). The checklist to tick before launch is
[production-readiness.md](production-readiness.md).

**Launch stack this plan assumes:** OVH Managed Kubernetes, Layerbase Postgres, web apps on Cloudflare Workers, images on R2,
Resend email, staff run fulfilment by hand, trust-ops on, ai-gateway and mcp-server on, FGA off, no native apps (installable
web app only), invite-only beta.

Examples use the `stripe` CLI. They are examples only: nothing here was run against your account. Use a **restricted,
read-only live key** for the inventory steps (`--api-key`), never the platform secret key. Never paste a key, a `whsec_`
secret or a customer's card data into a ticket, chat or this repository.

## What I cannot know (and need from the owner)

The v1 repository and its production database are not available to the build sessions. Everything about v1 below comes from
the data runbook (written from a local copy of v1's database) and from reading v2's code. Please confirm or supply:

| # | Question | Why it matters |
|---|---|---|
| 1 | Who has **Stripe dashboard owner access**, and can you create a restricted read-only live key for the inventory? | Every inventory step needs it. |
| 2 | v1's **live webhook endpoints**: URLs, event lists, API version, whether one is a Connect endpoint (export from Workbench or `stripe webhook_endpoints list`). | To switch them off in the right order and to know what v1 depends on. |
| 3 | How v1 captures: **automatic capture or authorise-then-capture**? Any v1 PaymentIntent left in `requires_capture`? | v2 ignores PaymentIntents it did not create; an uncaptured v1 authorisation would lapse unseen after about 7 days. |
| 4 | Does v1 use **Stripe Billing subscriptions** for the seller plan (`seller.seller_subscriptions`)? | v2 bills plan fees itself with PaymentIntents. A live v1 subscription would keep charging the seller. |
| 5 | How the v1 connected account(s) were created: Standard, Express or Custom, or the newer `controller` settings (who bears losses, who collects requirements, which dashboard). | v2 creates its accounts with Stripe liable for negative balances, Stripe collecting verification and no Stripe dashboard for sellers. Existing accounts cannot be switched; v2's onboarding screens may not work for a different type. |
| 6 | Where v1 keeps the **Stripe customer id** and saved card ids (the data runbook names `profile.user_profiles.stripe_customer_id` and `payment.saved_payment_methods`, marked to confirm), and whether guest checkouts created customers. | Decides whether saved cards carry across (section 5). |
| 7 | The **current counts**: users, sellers (connected accounts), orders, open orders, saved cards. The data runbook says under 30 users, 1 seller, under 40 orders; it was written before today. | The plan scales down or up on these numbers. |
| 8 | v1's **domains and DNS**: which hostnames serve v1 (apex, `www`, `auth`, `api`, seller), which DNS provider holds them, what the TTLs are, whether Cloudflare is already in front. | Section 9. |
| 9 | Whether v1 customers have **refunds, returns or disputes open**, and the policy for refunding a v1 order after cutover (from Stripe by hand, or from v2). | Section 6. |
| 10 | The **invite-only beta rule**: who may sign in on v2 on day one (all migrated v1 users, or only invited ones), and how an invite is issued. I found no customer sign-up gate in identity (only organisation team invitations), so this may not exist yet; a backlog story is open for it. | Migrated customers must be able to sign in, and strangers must not. |
| 11 | The **freeze window** you can accept (hours, day of week) and who can approve a no-go. | Section 3. |
| 12 | Statement descriptor, payout schedule, Connect branding and payment methods currently set on the Stripe account, and whether v2 should keep them. | They are account-level and shared by v1 and v2. |

## Facts from v2's code that shape the plan

- **Three webhook endpoints, three secrets** (from `WebhookController` in payments and seller, and the edge routes in
  `infra/k8s/platform/edge/overlays/prod/routes.yaml`). Prod host is `https://api.afrotransact.com`.

  | # | URL | Service | Secret variable | Stripe "Events from" | Event types handled (from code) |
  |---|---|---|---|---|---|
  | 1 | `https://api.afrotransact.com/payments/v1/webhooks/stripe` | payments | `STRIPE_WEBHOOK_SECRET` | Your account | `payment_intent.succeeded`, `payment_intent.amount_capturable_updated`, `payment_intent.payment_failed`, `payment_intent.requires_action`, `payment_intent.canceled`, `charge.refunded`, `charge.dispute.created`, `charge.dispute.closed`, `transfer.reversed`, `payout.paid`, `payout.failed`, `setup_intent.succeeded`, `setup_intent.requires_action`, `payment_method.attached`, `payment_method.detached` (`StripeEventHandlers.SUPPORTED`) |
  | 2 | `https://api.afrotransact.com/seller/v1/webhooks/stripe-connect` | seller | `STRIPE_CONNECT_WEBHOOK_SECRET` | Connected accounts | `account.updated`, `capability.updated`, `person.created`, `person.updated`, `account.external_account.created`, `account.external_account.updated`, `account.external_account.deleted`, `payout.failed`, `account.application.deauthorized` |
  | 3 | `https://api.afrotransact.com/seller/v1/webhooks/stripe-billing` | seller | `STRIPE_BILLING_WEBHOOK_SECRET` | Your account | `payment_intent.succeeded`, `payment_intent.payment_failed`, only for PaymentIntents whose metadata says `purpose=plan_fee`; everything else gets `200` and is ignored. Needed only if sellers pay their plan by US bank debit. |

  Every other event type is stored or ignored with `200`. Use **Snapshot** payloads (not Thin). Each endpoint has its own
  secret; never reuse one. The platform account is the same, so these are **new endpoints** next to v1's, not edits of v1's.
- **API version.** v2 uses stripe-java 34.0.0, which pins Stripe API version `2026-09-30.endive` (read from the library in
  the local Maven cache). Create v2's endpoints with that `api_version` so payloads match what the code parses, even if the
  account's default version (set by v1) is older. Confirm in the test-mode rehearsal that a delivered event is accepted.
- **v2 ignores PaymentIntents it did not create** (`StripeEventHandlers.intent`: "not an AfroTransact order payment;
  ignored"). So while both stacks listen to the same account, v2 will not touch v1 payments, but it also will **never
  capture or cancel a v1 authorisation**. Anything v1 left uncaptured must be dealt with by hand before v1 stops (section 4).
- **Capture is a staff action in v2 at launch.** The capture follow-up task is queued when a shipment first becomes
  picked up (or ready, for pickup orders), so a staff status change moves money (`ShipmentTransitions.enqueueFollowUps`).
  The cutover rehearsal and the go-live smoke test must include one.
- **Connected accounts** live in two v2 tables: `seller.connect_account.account_id` (compliance, onboarding, health) and
  `payments.connected_account(org_id, stripe_account_id)` (where transfers go). Payments learns accounts from seller through
  a scheduled sync (`POST /v1/compliance/payments-sync` runs it on demand, permission `compliance:remind`) or directly with
  `PUT /v1/connected-accounts/{orgId}`.
- **Customers and cards** live in `payments.stripe_customer(customer_id, stripe_customer_id, default_payment_method_id)` and
  `payments.customer_payment_method`. `customer_id` is the identity user id as text. When a customer has no row, v2 creates a
  **new Stripe Customer** at first use (`WalletSync.ensureCustomer`), so unmapped users get a second customer and lose
  their saved cards.
- **Seller plan fees** are v2's own PaymentIntents (metadata `purpose=plan_fee`), not Stripe Billing.

## 1. Inventory v1 (T-14 to T-7 days, read-only)

Record every result in a private sheet (not in the repository). Dates and counts are what the go/no-go checklist is
measured against. All examples use a restricted read-only live key in `STRIPE_RO_KEY`.

1. **Webhook endpoints** in the live account, with their event lists and API versions:
   `stripe webhook_endpoints list --api-key "$STRIPE_RO_KEY" --limit 100`. Note which are v1's and which are anything else
   (accounting tools, fraud tools, a data warehouse). Anything not v1's stays on.
2. **Connected accounts:** `stripe accounts list --api-key "$STRIPE_RO_KEY" --limit 100`. For each, retrieve it
   (`stripe accounts retrieve acct_XXXX --api-key "$STRIPE_RO_KEY"`) and record: `type`, the `controller` block, `charges_enabled`,
   `payouts_enabled`, `requirements.currently_due`, `requirements.disabled_reason`, `capabilities`, payout schedule, external
   account (last four only). This answers owner question 5. Map each to the v1 seller row (`seller.sellers.stripe_account_id`).
3. **Open money, platform balance:**
   - `stripe payment_intents list --api-key "$STRIPE_RO_KEY" --limit 100` and filter for `status` in `requires_capture`,
     `processing`, `requires_action`, `requires_payment_method` created in the last 30 days. Each `requires_capture` one is a
     decision (capture or cancel) before the freeze ends.
   - `stripe balance retrieve --api-key "$STRIPE_RO_KEY"` (available, pending, and any reserve).
   - `stripe refunds list` for the last 30 days, `stripe disputes list` (any open dispute needs evidence from v1's data
     before v1 is archived).
4. **Pending payouts and transfers:** `stripe payouts list --limit 20` on the platform; per connected account
   `stripe payouts list --stripe-account acct_XXXX --limit 10`; `stripe transfers list --limit 100` for the last 90 days.
   Know which seller balances are still owed and which payouts are `pending` or `in_transit`. Do not start the freeze with an
   `in_transit` payout to a seller whose account you are about to touch.
5. **Customers and saved cards:** count with `stripe customers list --limit 100` (page through) and, for each v1 user with a
   Stripe customer, `stripe payment_methods list --customer cus_XXXX --type card`. Reconcile against v1's own tables
   (`profile.user_profiles.stripe_customer_id`, `payment.saved_payment_methods`). The result is the mapping input for section 5.
6. **Subscriptions and invoices (Stripe Billing):** `stripe subscriptions list --status active --limit 100`,
   `stripe invoices list --status open`. Any active subscription is a cutover task (section 6).
7. **Account settings:** statement descriptor, payout schedule, Connect branding, payment methods, Radar rules, tax settings,
   email receipt settings, API version default (Dashboard > Settings and Workbench). Screenshot or write them down; v2 inherits
   them.
8. **Keys:** list which services and tools hold the live secret key (v1's own, bookkeeping tools). v2 gets a **new restricted
   or secret key** from the same account; rolling v1's key at the end is part of decommissioning (section 11).
9. **A copy of v1's database** restored per the data runbook Part 2 (rehearsal copy), and the counts from owner question 7.

Exit criterion: a sheet with one row per connected account, one per open PaymentIntent, one per webhook endpoint, one per
Billing subscription, and a customer-to-card mapping. Anything surprising (an account type v2 cannot onboard, an unexplained
endpoint) goes to the owner before section 2.

## 2. Test-mode rehearsal (T-10 to T-3 days)

Test mode has its own keys, webhook endpoints and connected accounts, so it never touches v1's live data. It proves v2's
Stripe wiring; the data copy is rehearsed separately (data runbook Parts 2 to 5).

1. Deploy v2 to the production cluster's **test twin** (or the OVH test environment, `ovh-vps.md`) with test keys:
   `STRIPE_SECRET_KEY` (`sk_test_`), `STRIPE_PUBLISHABLE_KEY`, and three new test webhook secrets.
2. Create the three test-mode endpoints (Workbench steps in [stripe-webhooks.md](stripe-webhooks.md) section 4), or with the CLI:

   ```bash
   stripe webhook_endpoints create \
     --url "https://api.test.afrotransact.com/payments/v1/webhooks/stripe" \
     --api-version 2026-09-30.endive \
     -d "enabled_events[]"=payment_intent.succeeded \
     -d "enabled_events[]"=charge.refunded   # ... all 15 events from the table above
   # Connect endpoint: add  -d connect=true
   ```

   Copy each returned `secret` straight into the secret store. Check the three routes answer `400` unsigned
   (stripe-webhooks.md section 2) and `2xx` to **Send test event**.
3. Forward and trigger events locally if you prefer: `stripe listen --forward-to localhost:8103/v1/webhooks/stripe` (use the
   service's local port from `infra/local/PORTS.md`) and `stripe trigger payment_intent.succeeded`. Triggered events refer to
   objects v2 did not create and are ignored by design; real flows below prove the handlers.
4. **Run the money path with test cards**, in this order, ticking each in the go/no-go sheet:
   - Seller onboarding for a **test connected account** through v2's embedded onboarding until `charges_enabled` and
     `payouts_enabled` (proves endpoint 2 and the seller status sync).
   - Checkout as a customer: authorise with `4242 4242 4242 4242`; the order shows authorised, nothing captured.
   - As a staff user, set the shipment to picked up by hand. Confirm capture happened (PaymentIntent `succeeded`, order
     paid), and the shipment history shows who and when. This is the "a status change moves money" check.
   - Cancel a second authorised order before pickup: PaymentIntent cancelled, no charge.
   - Refund a captured line; note that Stripe's processing fee on it is not returned (known, see the readiness checklist).
   - A saved card: add one (`setup_intent.succeeded`, `payment_method.attached`), pay with it, detach it.
   - A 3-D Secure card (`4000 0025 0000 3155`) and a declined card (`4000 0000 0000 9995`).
   - A dispute (`4000 0000 0000 0259`) to see `charge.dispute.created` handled.
   - Settlement hold, then a transfer to the test connected account and a payout request approved in the staff console.
   - Replay an event from Workbench and confirm it is de-duplicated (no double posting).
5. **Unknown-object safety:** send an event for a PaymentIntent created outside v2 (create one with `stripe payment_intents
   create --amount 1000 --currency usd`, then confirm it) and check the payments service logs "not an AfroTransact order
   payment; ignored" and posts nothing. Do the same for a Connect event of an account v2 does not know and note what the
   seller service does; fix before the live endpoints are enabled if it errors.
6. **Dry run of the freeze steps** (section 3) against the test environment, with a stopwatch: the full runbook sequence
   from "freeze" to "smoke tests green" and one full rollback to "v1 live" (disable v2 endpoints, DNS back). Write down the
   actual minutes; the freeze window is set from this.
7. **Data rehearsal:** the data runbook's restore, user import, order script, Part 5 verification, with a real user signing in
   on the restored copy, and the mapping steps in section 5 below against the **test** account.
8. Record the rehearsal date and the person who ran it in the go/no-go sheet. A rehearsal older than 14 days, or from before
   the last v2 release, does not count.

## 3. Freeze window

Choose a low-traffic window long enough for the rehearsal's measured minutes plus 50 percent. Everything below happens
inside it. Between T-1 day and the end of the window, no other production change.

- **T-48 h:** announce the window to customers and sellers (section 10). Lower DNS TTLs to 300 s on every record that will
  move (section 9). Export the current DNS zone to a file and keep it (rollback).
- **T-24 h:** v2 production is deployed and verified with **no customers on it**: all services healthy, `APP_ENV=prod`,
  live Stripe keys loaded, the secret check (`tools/dev/secrets_check.py --check`) green, smoke tests (`tools/smoke`) green
  against the staff URL. Create the seller's organisation by hand in the v2 back office (data runbook "The seller, by hand").
  Staff accounts exist and have signed in once (staff MFA).
- **T-2 h:** go/no-go meeting (checklist in section 12). A single named person decides.
- **T-0:** freeze starts: v1 put in maintenance mode, **new checkouts and registrations blocked**; v1 admin stops
  fulfilling. From this moment v1 takes no new orders. Announce in the status banner.

## 4. Close out v1's money (inside the freeze, before any switch)

1. For every v1 PaymentIntent in `requires_capture` from the inventory: capture it (the order shipped) or cancel it (it did
   not). Re-list to confirm none remain. v2 will not do this for them.
2. Let `processing` PaymentIntents (bank debits) settle, or record them as "arrives after cutover, handled by hand". They
   will emit events that v1 no longer receives and v2 ignores; reconcile them in the Stripe dashboard.
3. Cancel v1's active Stripe Billing subscriptions **if** inventory step 6 found any, after the owner decides on proration:
   `stripe subscriptions cancel sub_XXXX`. v2 bills the plan itself; leaving the subscription live double-charges the seller.
   Note which seller customers and payment methods v2's plan billing should reuse (question 4) and set them up in v2.
4. Note the balances: platform available and pending, and each connected account's pending payouts. Record in the sheet.
   Do not change payout schedules during the window.
5. Take the **final v1 database dump** (data runbook 2.2, encrypted) after v1 has stopped writing. This is the migration source
   and the 30-day rollback source.

## 5. Move data and map Stripe ids to v2

Follow the data runbook Parts 3 to 5 against production v2, with these Stripe specifics.

| Thing | v1 source | v2 target | How |
|---|---|---|---|
| Users, passwords | Keycloak `user_entity`, `credential` | identity `user_account` | `tools/migrate/keycloak_to_identity.py report`, then `sql` (read it), then `apply`. The Keycloak user id carries over and becomes the token `sub`, which is the `customer_id` everywhere. |
| The seller organisation | `seller.sellers`, `stores` | seller `organisation`, `org_member`, business page, ship-from | By hand in the back office. |
| Connected account (seller side) | `seller.sellers.stripe_account_id` | `seller.connect_account.account_id` linked to the org | Copy the `acct_` id exactly from the inventory, never retype. Then fetch current status from Stripe (the first `account.updated` or the compliance sync refreshes it) and confirm `charges_enabled`/`payouts_enabled` match the Stripe dashboard. |
| Connected account (payments side) | same | `payments.connected_account(org_id, stripe_account_id)` | Run the payments sync (`POST /v1/compliance/payments-sync`) or `PUT /v1/connected-accounts/{orgId}`. Check by `GET /v1/connected-accounts/{orgId}`. Without this a payout transfer has nowhere to go. |
| Stripe customer | v1 `stripe_customer_id` (to confirm, question 6) | `payments.stripe_customer(customer_id = identity user id, stripe_customer_id, default_payment_method_id)` | Import one row per user who has a Stripe customer **before** that user's first v2 sign-in. Otherwise v2 creates a duplicate Stripe Customer at first checkout. A short SQL insert, read in full. |
| Saved cards | `payment.saved_payment_methods` and Stripe | `payments.customer_payment_method(id = pm_ id, customer_id, type, brand, last4, exp_month, exp_year, verified)` | Import from the inventory (Stripe is the truth for brand, last4, expiry). Fallback with less risk at this size: do not import cards and tell customers to add a card once; the old customers stay in Stripe unused. Decide in the go/no-go. |
| Order and payment history | v1 `orders.*`, `payment.*` | `customer_order`, `order_line`, `shipment`, `payments.payment`, refunds, transfers | The reviewed one-off SQL script (data runbook Part 4). Imported payments keep `stripe_payment_intent_id`. |
| Webhooks | v1 endpoints | v2 endpoints 1 to 3 | Section 7. |

Known mapping gaps to close with the owner, from the data runbook: guest orders (`customer_id` is not null in v2), per-seller
commission versus v2's plan-based fee schedule, v1 `ledger.*` (archive, do not migrate), and store credit, coupons and
referrals if rows exist. Products are being re-uploaded and are not migrated.

Verify (data runbook Part 5): counts, no orphans, order totals sum equal, then **read every order** side by side, and sign in
by hand as a customer with each password type, a social-only user, the seller owner and a staff admin. For Stripe:
open the connected account's health page in the seller center and the payments connected-account lookup, and confirm the
customer's saved card shows for a mapped user.

## 6. After-the-fact rules for v1 orders

- A v1 order imported into v2 shows its history. If a customer needs a **refund on a v1 payment**, do it in the Stripe
  dashboard by hand from the PaymentIntent for the next 30 days (the same window as the v1 database archive), and note it on
  the order. If you imported v1 payments and refunds into v2, a staff refund from v2 also works for those rows; rehearse one
  before relying on it. Pick one rule and tell support which.
- Disputes opened on v1 charges after cutover arrive as `charge.dispute.created` on v2's endpoint for a charge v2 has no
  payment for. Check what the handler does with an unknown charge during the rehearsal (step 5) and prepare the manual
  evidence path.
- A v1 transfer reversal or payout failure after cutover is handled in the Stripe dashboard.

## 7. Webhook endpoint change (the order that avoids double handling)

Both stacks are the same Stripe account, so both receive the same events as long as both endpoints are enabled. v1 and v2
must not both act on an event. The safe way is to create v2's endpoints early **disabled**, then swap inside the freeze.

1. **T-3 days** (before the freeze): create v2's three **live** endpoints with the API version above and **disabled**
   (`-d disabled=true` on create or update). Put the three new secrets in the production secret store as
   `STRIPE_WEBHOOK_SECRET`, `STRIPE_CONNECT_WEBHOOK_SECRET`, `STRIPE_BILLING_WEBHOOK_SECRET` (skip the third if sellers pay
   plans by card only; empty makes the route answer `400`). Roll payments and seller so they load the secrets.

   ```bash
   stripe webhook_endpoints create --api-key "$STRIPE_LIVE_KEY" \
     --url "https://api.afrotransact.com/payments/v1/webhooks/stripe" \
     --api-version 2026-09-30.endive -d disabled=true \
     -d "enabled_events[]"=payment_intent.succeeded    # ... the full list per endpoint
   ```

   Disabled endpoints get no events, so v2 sees nothing yet. Confirm the three URLs answer `400` unsigned
   (`stripe-webhooks.md` section 2); a signed test cannot be sent to a disabled endpoint, so the first proof is step 4.
2. **At T-0, after section 4:** disable v1's endpoints (`stripe webhook_endpoints update we_V1XXXX -d disabled=true`). Do
   **not delete them**: they are the rollback. Anything not v1's stays enabled.
3. **After the data load and verification (section 5):** enable v2's three endpoints (`-d disabled=false`).
4. **Prove them:** in Workbench use **Send test event** on each endpoint and expect `2xx`. Then make one real small payment and
   a refund (go-live smoke, section 8) and confirm the event was delivered and applied (payments `stripe_event` row, order
   status). Stripe's dashboard shows each delivery and the response code.
5. **Events lost during the gap:** between disabling v1 and enabling v2 nothing is delivered. After enabling, list the events
   of that window (`stripe events list -d "created[gte]"=<T-0 epoch>`) and re-deliver any that matter from Workbench (the
   event's "Resend" action for that endpoint). Payments are idempotent by event id, so a resend is safe.
6. Keep payments **reconciliation** (staff console, Payouts & ledger) as the safety net; run it manually at T+1 h and daily.

Which event does what in v2, in one line each, so you know what you are enabling: the payments endpoint confirms payments,
refunds, disputes and payouts and keeps saved cards in step; the Connect endpoint keeps each seller's verification and
payout holds in step; the billing endpoint settles plan-fee bank debits.

## 8. Go-live smoke test (inside the freeze, after the endpoints are on)

Real money, small, by staff, on production v2 with no customers yet:

1. Sign in as staff and as a migrated customer with a real password.
2. Buy one inexpensive item with a staff card. Order authorised; nothing captured.
3. In the staff console set the shipment to picked up by hand (the confirmation and a note). Confirm capture, the webhook
   delivery, and the order paid.
4. Refund it (full). Confirm the refund event and the customer notification (Resend email arrives, SPF/DKIM pass).
5. Seller opens the back office, health shows verified.
6. Open an imported historical order.
7. Check `/actuator/health`, error rate, payment webhook lag on the dashboards.

If any step fails, go to section 11 (rollback) rather than fixing live.

## 9. DNS switch

Hostnames, provider and TTLs are owner question 8. The shape, following [cloudflare-setup.md](cloudflare-setup.md) and the
"Storefront host rule" in the readiness checklist:

- Before the window: v2's Workers routes (`www.afrotransact.com/*` for the storefront, seller and admin hosts) exist but are
  **not yet routed** from the live names, or sit on a preview host; `api.afrotransact.com` points at the OVH load balancer
  with a valid certificate; apex "redirect to www" rule is prepared and disabled. Identity's issuer and the redirect URIs
  must be the production origins.
- Switch order, after section 8 passes: (1) `auth` and `api` hostnames, (2) seller and admin, (3) storefront `www`, then
  enable the apex redirect. Check with `curl -sI` per host and a real browser sign-in, a passkey sign-in and a sign-out round
  trip.
- Keep v1 reachable on a private hostname (not public) for the 30-day archive window, or stopped and restorable.
- **DNS rollback:** re-apply the exported zone file or restore each record by hand. With TTL at 300 s and Cloudflare-proxied
  records, expect minutes. The Worker route can be removed in the Cloudflare dashboard to give the host back to v1.
- Raise TTLs again 48 hours after a clean cutover.

## 10. Communications

| When | To | Content |
|---|---|---|
| T-7 days | Customers (v1 email list), sellers | A new AfroTransact is coming on DATE. Same email and password; old orders are kept; you may be asked to add a card again; the beta is invite-only, here is your invite. |
| T-48 h | Same, plus staff | The freeze window in the customer's time zone; no orders during it; who to contact. |
| T-0 | Status banner on v1, staff channel | "We are upgrading. Back by TIME." |
| After section 8 | Staff channel | Go or no-go with the reason. |
| T+0 to T+2 h | Customers, sellers | We are live; sign in; what changed (no mobile app: install from the browser). |
| T+1 week | Customers who failed to sign in | Use Forgot password (data runbook Part 6). |

Support has the two scripts ready: "I cannot sign in" and "where is my saved card". Prepare them in advance.

## 11. Rollback

Decide the **point of no return** in the go/no-go meeting. Recommended: the moment v2's endpoints are enabled **and** the first
real customer order is placed on v2. Before it, rollback is clean; after it, roll forward (fix v2) because v2 holds orders and
money that v1 does not know about.

**Before the point of no return (v1 still has all data):**

1. Disable v2's three webhook endpoints (`-d disabled=true`).
2. Re-enable v1's endpoints (`-d disabled=false`) and resend any events from the gap (section 7 step 5).
3. Put DNS and Worker routes back (section 9); take v1 out of maintenance mode.
4. If you cancelled v1 Billing subscriptions (section 4 step 3), recreate them or invoice by hand; note this cannot be
   undone silently and is the one irreversible Stripe change before the point of no return, so cancel them **last** in section 4
   if the owner prefers.
5. Any v1 PaymentIntent you captured or cancelled in section 4 stays so; this was real state.
6. Truncate v2's imported data and re-run the rehearsal; tell customers in the status banner and by email.

**After the point of no return:** do not point back to v1. Use maintenance mode on v2 (`maintenance-mode.md`), fix, and
redeploy the previous image tag. Staff can refund any v2 order from the Stripe dashboard if a service is down. Money is
never lost: Stripe is the ledger; the reconciliation screen lists any payment v2 and Stripe disagree on.

**Decommissioning v1 (T+30 days, after sign-off):** keep v1 stopped and restorable for 30 days; then delete v1's webhook
endpoints in Stripe, **roll v1's Stripe secret key** (or revoke the restricted keys v1 held), remove v1's DNS leftovers, delete
the database copy and the dump (keep the `ledger.*` finance archive), and shut down the v1 cluster. Record the date.

## 12. Go/no-go checklist

Tick every line; any "no" on a **blocker** is a no-go. Owner decides; an engineer reads the evidence out.

Inventory and rehearsal
- [ ] **blocker** Stripe inventory sheet complete (section 1) and every open `requires_capture` PaymentIntent has a decision.
- [ ] **blocker** Owner questions 1 to 11 answered.
- [ ] **blocker** Test-mode rehearsal done within 14 days of the cutover and after the release being deployed: onboarding,
  authorise, staff pickup capture, cancel, refund, saved card, dispute, settlement and payout, event replay (section 2).
- [ ] **blocker** Data rehearsal on a restored v1 copy: counts equal, totals equal, every order read, five sign-ins done.
- [ ] Freeze minutes measured; window set with 50 percent margin.

Stripe
- [ ] **blocker** v2 live endpoints exist (disabled) with the SDK API version and three distinct secrets in the secret store.
- [ ] **blocker** v1 endpoint list recorded; the ones to disable identified; nothing else depends on them.
- [ ] **blocker** Every connected account maps to a v2 org and has a `payments.connected_account` row; types are compatible
  with v2 onboarding (owner question 5).
- [ ] **blocker** Customer mapping decided and loaded (or "re-add card" chosen and support briefed).
- [ ] Billing subscriptions found and a cancel/keep decision made.
- [ ] Live key for v2 is a new key; platform account branding, descriptor and payout schedule reviewed.

Platform
- [ ] **blocker** Readiness checklist blockers are green ([production-readiness.md](production-readiness.md)): `APP_ENV=prod`,
  no mocks, secrets from the store, CI security workflow green, backup **and a restore drill** done.
- [ ] **blocker** Staff trained on the pickup step (it captures money) and on the confirmation and reason it asks for.
- [ ] Resend domain verified (SPF, DKIM, DMARC); a test email arrives.
- [ ] Legal documents published and reviewed by counsel; terms acceptance on first sign-in works.
- [ ] Invite-only rule decided and working (owner question 10).
- [ ] Maintenance mode tested; rollback steps rehearsed once with a stopwatch.
- [ ] DNS zone exported; TTLs lowered 48 h ago; certificates valid for every new host.

People
- [ ] One named decision maker, one engineer on the keyboard, one support lead, one finance person for reconciliation.
- [ ] Customer and seller emails sent on schedule; status banner ready.

After the switch
- [ ] Smoke test (section 8) all green; reconciliation run at T+1 h shows no unexplained difference.
- [ ] Watch error rate, checkout success, payment webhook lag and payout dashboards for 24 hours; review support tickets daily
  for a week.
- [ ] T+30 days: decommission v1 (section 11).
