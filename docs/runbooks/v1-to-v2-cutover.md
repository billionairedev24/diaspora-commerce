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
web app only). It is a **full cutover**: there is no invite-only beta and no sign-up gate (v1 sign-ups stay open until the
freeze, and every v1 user moves to v2).

Examples use the `stripe` CLI. They are examples only: nothing here was run against your account, and **nobody is asked for a
live Stripe key**. The owner reads the v1 state in the Stripe dashboard (checklist in section 1); the CLI lines are
equivalents for someone who already holds a key. Never paste a key, a `whsec_` secret or a customer's card data into a
ticket, chat or this repository.

Stripe behaviour in this runbook that I could not read in Stripe's documentation from the build machine is marked
**UNVERIFIED**. Treat each such line as a question to prove in test mode or to ask Stripe, not as fact.

## Top risk: v1's connected accounts are not shaped like v2's

**Evidence (from the owner's Stripe Connect Platform setup screen, not from code):** v1's connected accounts were created with
the direct charges business model, losses collector = Stripe, fees collector = the account, dashboard = **Full** (the seller's
own Stripe dashboard), and Stripe-hosted onboarding. Stripe shows an integration warning that the platform currently creates
**destination charges**.

**What v2 does** (`StripeConnectAdapter.createAccount`, `java/services/seller/.../adapter/out/stripe/StripeConnectAdapter.java`
lines 39 to 66): creates accounts with `controller.fees.payer = ACCOUNT`, `controller.losses.payments = STRIPE`,
`controller.requirement_collection = STRIPE`, `controller.stripe_dashboard.type = NONE`, capabilities `card_payments` and
`transfers`, and a payout schedule of `MANUAL` set by the platform. Payments uses **separate charges and transfers**: the
platform owns the charge, handles refunds and disputes, and later transfers each seller's net after the hold
(`createTransfer` with a destination account).

**Side by side:**

| Controller property | v1 accounts (owner's screen) | v2 accounts (code) | Same? |
|---|---|---|---|
| Who pays fees | the account | the account | yes |
| Who bears losses | Stripe | Stripe | yes |
| Who collects requirements | Stripe (Stripe-hosted onboarding) | Stripe (platform embeds Stripe's account onboarding component) | same collector, different surface |
| Dashboard | **Full** | **None** | **no** |
| Charge model | direct or destination (screen and warning disagree) | separate charges and transfers | differs |

Controller properties on an existing account cannot be changed (the owner's brief; I could not check Stripe's page, so
**UNVERIFIED** as a general rule), so v1's accounts stay Full-dashboard accounts. Consequences to prove or disprove
(**all UNVERIFIED**):

1. v2 may be **unable to set the payout schedule** on a Full-dashboard account (`settings.payouts.schedule` update), so
   `MANUAL` cannot be forced and the seller can pick their own schedule and bank payout timing in their dashboard. The 7-day
   platform hold is unaffected, because v2 only creates the transfer after the hold; what changes is when the seller's balance
   leaves Stripe.
2. The `transfers` capability may not be requestable by the platform on such an account, or may already be on. A v2 transfer
   to an account without it fails and the seller's net is stuck in the platform balance.
3. Sellers keep their own dashboard: they can see payments, payouts and balance, change payout and bank settings, and
   could **disconnect** the platform (`account.application.deauthorized`, which v2's Connect endpoint already handles by
   marking the seller disconnected). With separate charges and transfers the disputes and refunds belong to the platform; the
   seller's dashboard shows transfers, not the customer's charge. How a Full-dashboard seller sees or disputes things is
   unclear.
4. v2's embedded onboarding and account-health screens (account sessions, `POST /v1/orgs/{orgId}/stripe/account-session`)
   may not accept a Full-dashboard account, or may show different requirements.
5. The charge-model mismatch: v1's accounts were set up for direct charges, yet the platform created destination charges.
   Past v1 charges are not affected by v2's model, but refunds of **v1** charges (section 6) must follow the model they were
   made under (reverse the transfer on a destination charge; refund on the connected account for a direct charge).

The decision is the next section. Do not start the freeze until it is made and proven.

## Existing connected accounts: keep or re-onboard

First fact to establish: how many connected accounts exist, and each one's real controller properties. The setup screen
describes how the platform was configured, not each account. Read each account's page (checklist, section 1) and record the
dashboard type, who collects requirements, `charges_enabled`, `payouts_enabled`, capabilities and any `requirements.currently_due`.

### Option A: keep and test

Keep each v1 account and link it to a v2 organisation. Before deciding, prove in **Stripe test mode** that v2's flows work on
an account built like v1's. Test mode has its own accounts, so this touches no live data.

1. Create a test account shaped like v1's (examples only):

   ```bash
   stripe accounts create --country US --business-type company \
     -d "controller[fees][payer]"=account \
     -d "controller[losses][payments]"=stripe \
     -d "controller[requirement_collection]"=stripe \
     -d "controller[stripe_dashboard][type]"=full \
     -d "capabilities[card_payments][requested]"=true \
     -d "capabilities[transfers][requested]"=true
   stripe account_links create --account acct_TEST -d type=account_onboarding \
     -d refresh_url=https://example.com/r -d return_url=https://example.com/d   # Stripe-hosted onboarding
   ```

   Complete onboarding with Stripe's test data until `charges_enabled` and `payouts_enabled` are true.
2. Link it to a test v2 organisation (`PUT /v1/connected-accounts/{orgId}` on payments and the seller-side row), then run the
   **v2 flows** against it and write down exactly what Stripe allows or refuses, with the error code:
   - a **separate charge** on the platform and a **transfer** of the net to the account (with `transfer_group`);
   - a **refund** of the charge with and without reversing the transfer (v2 reverses; see `TransferReversalRefused.md`), and a
     refund after the account's balance has been paid out;
   - a **payout-schedule update** by the platform (`stripe accounts update acct_TEST -d "settings[payouts][schedule][interval]"=manual`)
     and a platform-initiated **payout** on the account;
   - `transfers` and `card_payments` capability state, and a request to add a capability from the platform;
   - v2's **embedded onboarding / account health** (account session) for the account;
   - the **account.updated**, **capability.updated** and **account.application.deauthorized** events reaching endpoint 2;
   - a **dispute** (`4000 0000 0000 0259`) on a charge whose net was transferred to it.
3. Record the results in a table (flow, allowed or refused, error, what the seller sees in their dashboard). Behaviour in
   test mode may differ from live; the live answer comes from Stripe (questions below).
4. **Decide:** keep only if every v2 flow the business needs works (charge, transfer, refund with reversal, health sync) and
   the platform can live with the seller controlling payout timing. A dashboard the seller owns is also a policy question for the owner.

*Cost:* little work and no seller action. *Risk:* a refused transfer in production strands a seller's money; the seller keeps a
dashboard v2's design assumed they would not have.

### Option B: re-onboard each seller to a v2-style account

Create a new v2-style account for every seller through v2's onboarding (new KYC, the seller redoes verification), link it to
the organisation, and leave the old account untouched. Payments from then on transfer to the new account.

- Before the switch, settle the old account: pay out its balance, confirm nothing is pending, note any open dispute or
  recent refund that may need to reverse a transfer on the old account (the old account stays usable for that).
- The old account is **not deleted or rejected**; it keeps its history and any balance for refunds of v1 orders. Decide with
  Stripe how long it stays open and who closes it.
- Sellers are asked to redo onboarding and bank details: an email, a deadline, and a rule for what happens to listings
  before they finish (v2 hides listings and blocks payouts until the account is verified).
- Two accounts per seller exist in Stripe afterwards; the `payments.connected_account` row points at the new one only.

*Cost:* seller effort, a day or more of KYC delay per seller (UNVERIFIED), and the cutover cannot sell for a seller whose new
account is not verified. *Benefit:* identical behaviour to everything v2 tested; platform controls payout schedule.

### Option C: hybrid

Keep v1 accounts that pass the Option A tests **for the sellers who exist today**, and onboard every **new** seller with v2-style
accounts. Re-onboard (Option B) only a seller whose old account fails a test, or at a time the seller chooses. This keeps launch
smooth and makes the long-term state uniform. It needs code that tolerates both shapes (for example reading the account's
controller before trying to set a payout schedule), which is backlog story S378 if Option A (story S379) shows a refusal.

### Recommendation

I recommend running the Option A test in test mode first (it is a few hours), then choosing: A or C if every flow works, B if
the transfer, refund-with-reversal or health sync is refused. If the owner has **one** connected account (the data runbook says
one seller), B is cheap and removes the uncertainty; the cost is one seller's KYC. This is a business decision for the owner;
I have not made it.

### What to ask Stripe support (exact questions)

Send from the account owner's dashboard (Help, then Contact support, topic Connect). Number them so the answers can be matched.

1. Our platform (account ID acct_PLATFORM, owner's to fill) has connected accounts created with: direct charges business model,
   losses collector Stripe, fees collector account, dashboard Full, Stripe-hosted onboarding. We are moving to **separate charges
   and transfers** in which the platform owns the charge. Is this supported for connected accounts with a **Full dashboard**? Please
   confirm in writing.
2. Can the platform create a **transfer** to a Full-dashboard connected account? Does the account need the `transfers`
   capability, and can the platform request it on an existing account? What does Stripe do if the capability is inactive?
3. Can the platform **update `settings.payouts.schedule`** (for example to `manual`) or create **payouts** on a Full-dashboard
   account? If not, which payout settings does the seller control?
4. Can any **controller property** (dashboard, losses, fees, requirement collection) of an existing account be changed, by us,
   by Stripe on request, or by the seller (for example by moving it to a platform-controlled setup)? If not, what is Stripe's
   recommended path to a different configuration without losing history?
5. The Connect setup screen shows an integration warning: "the platform currently creates destination charges". Which
   charge model does Stripe treat as active for our platform (direct or destination), and does this setting affect how
   **refunds, disputes and fees** work when we create separate charges? Can the warning be cleared?
6. For **separate charges and transfers**, on a Full-dashboard account: who is the merchant of record on the customer's
   statement, who is liable for **disputes** and **refunds**, and what does the seller see in their dashboard for a transferred
   amount?
7. Can **embedded onboarding** and **account management** components (account sessions) be used for a Full-dashboard account, and
   for Stripe-hosted onboarding accounts that were already verified?
8. Can a Full-dashboard seller **disconnect** from our platform in their dashboard, and what happens to their balance, pending
   transfers and open disputes when they do?
9. If we onboard a **new** account for an existing seller (same legal entity, same bank), will Stripe reuse their verification
   or ask for everything again? Is there a supported way to link or migrate the verified identity?
10. Is a **mixed** platform (some Full-dashboard accounts, some with no dashboard) supported, with the same webhook endpoints and the same
    transfer code?
11. Is there any limit on how many connected accounts a platform can have with each configuration, and any fee difference
    (account fees versus platform fees) between them?
12. For our account-level settings shared by v1 and v2 (statement descriptor, payout schedule, branding, API version), is
    anything changed for the connected accounts when we change the platform's Connect settings or API version?

Record each answer with the date and the support case id. Mark an answer UNVERIFIED until it is in writing.


## Owner answers (2026-11-12) and what is still open

Answered by the owner:

| Topic | Answer | Effect on this plan |
|---|---|---|
| Capture mode | v1 charges **capture immediately** (no authorise-then-capture). | No lingering `requires_capture` PaymentIntents. The risk is removed; section 1 keeps a one-line check. |
| Stripe Billing | v1 has **no subscriptions** and none are planned. | The subscription inventory and cancel steps are removed; a one-line check remains. |
| Customer ids and saved cards | The owner **has the Stripe customer ids in the v1 databases and will migrate them**. | The mapping steps stay (section 5). |
| Webhooks | The owner creates v2's endpoints **fresh**; v1's endpoints are **stopped and deleted at the freeze step, not before**. | Section 7 follows this. |
| Sign-up gate | **None**: v1 sign-ups stay open until cutover; v2 is a full cutover, no invite-only beta. | Every v1 user migrates and signs in on v2 with no invite. Registrations on v1 are blocked at the freeze. The gate story (S355) is closed as not needed. |
| AI spend | **2 US dollars a day** ceiling, cheapest model. | Readiness item and story S376 use that figure. |
| MCP | The MCP server is **public, with authenticated tools**. | Readiness item and story S377 follow this. |
| Sign-offs | Tax professional and counsel review are **handled internally by the owner**. | Marked OWNER internal and not blocking engineering. |
| Live Stripe key | **Not requested.** | The owner reads the state in the dashboard (section 1). |

Still open (needed from the owner, with the connected-account decision above):

| # | Question | Why it matters |
|---|---|---|
| 1 | The **number and real controller properties of every connected account** (read each account page; checklist in section 1) and which option (A, B, C) the owner chooses after the test. | The top risk. |
| 2 | v1's **live webhook endpoints**: URL, events, API version, for the record before deletion at the freeze. | Recreating them on rollback (section 11). |
| 3 | The **current counts**: users, sellers, orders, saved cards. The data runbook says under 30 users, 1 seller, under 40 orders; it predates today. | The plan scales on these numbers. |
| 4 | v1's **domains and DNS**: which hostnames, which provider, TTLs, whether Cloudflare is in front. | Section 9. |
| 5 | **Refunds, returns or disputes** open on v1, and the policy for refunding a v1 order after cutover. | Section 6. |
| 6 | The **freeze window** you can accept and who approves a no-go. | Section 3. |
| 7 | **Statement descriptor, payout schedule, Connect branding and payment methods** on the account, and whether v2 keeps them. | Shared by v1 and v2. |
| 8 | Stripe's **answers to the support questions** above. | Proves or disproves option A. |

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
  ignored"). So while both stacks listen to the same account, v2 will not touch v1 payments. v1 captures immediately, so
  there should be no v1 authorisation left to capture or cancel; section 1 keeps a one-line check.
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
- **Seller plan fees** are v2's own PaymentIntents (metadata `purpose=plan_fee`), not Stripe Billing. v1 has no Stripe Billing
  subscriptions, so nothing needs cancelling.

## 1. Inventory v1 (T-14 to T-7 days, read-only)

Record every result in a private sheet (not in the repository). Dates and counts are what the go/no-go checklist is
measured against. **No live key is requested**: the owner reads the dashboard.

**What to look at in the Stripe dashboard (live mode), owner, about 30 minutes:**

1. **Connect, Accounts:** how many connected accounts exist; for each, open its page and note the account type or dashboard
   access (Full or none), who collects requirements, whether charges and payouts are enabled, any "restricted" banner or
   requirements due, its balance and the next payout. This is the data for the top risk above and the section "keep or
   re-onboard".
2. **Connect, Settings (platform profile and integration warnings):** copy the text of the warning that says the platform creates
   destination charges, and the business model, losses, fees, dashboard and onboarding settings shown.
3. **Payments:** filter by status for anything **Uncaptured** (expect none, because v1 captures immediately), **Processing** or
   **Requires action**, created in the last 30 days.
4. **Developers, Webhooks:** every endpoint with its URL, the events it listens to, its API version, and whether it is for
   your account or connected accounts. Mark which are v1's and which belong to anything else.
5. **Balances, Payouts and Transfers:** platform available and pending balance; payouts pending or in transit; recent transfers
   to connected accounts.
6. **Customers:** the count, and whether each v1 user's customer id matches the v1 database; saved cards on a sample of five.
7. **Disputes and Refunds:** anything open or recent.
8. **Settings:** statement descriptor, payout schedule, Connect branding, payment methods enabled, API version default.
9. **Subscriptions:** confirm none (v1 has no Stripe Billing).

The CLI lines below are the same reads for someone who already holds a key (read-only, in `STRIPE_RO_KEY`).

1. **Webhook endpoints** in the live account, with their event lists and API versions:
   `stripe webhook_endpoints list --api-key "$STRIPE_RO_KEY" --limit 100`. Note which are v1's and which are anything else
   (accounting tools, fraud tools, a data warehouse). Anything not v1's stays on.
2. **Connected accounts:** `stripe accounts list --api-key "$STRIPE_RO_KEY" --limit 100`. For each, retrieve it
   (`stripe accounts retrieve acct_XXXX --api-key "$STRIPE_RO_KEY"`) and record: `type`, the `controller` block, `charges_enabled`,
   `payouts_enabled`, `requirements.currently_due`, `requirements.disabled_reason`, `capabilities`, payout schedule, external
   account (last four only). This is the input to the connected-account decision. Map each to the v1 seller row (`seller.sellers.stripe_account_id`).
3. **Open money, platform balance:**
   - `stripe payment_intents list --api-key "$STRIPE_RO_KEY" --limit 100` and filter for `status` in `requires_capture`,
     `processing`, `requires_action`, `requires_payment_method` created in the last 30 days. One-line check: no
     `requires_capture` should exist, because v1 captures immediately; if one does, capture or cancel it before the freeze ends.
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
6. **Subscriptions (one-line check):** `stripe subscriptions list --status active --limit 100` returns nothing, as the owner
   says v1 has no Stripe Billing.
7. **Account settings:** statement descriptor, payout schedule, Connect branding, payment methods, Radar rules, tax settings,
   email receipt settings, API version default (Dashboard > Settings and Workbench). Screenshot or write them down; v2 inherits
   them.
8. **Keys:** list which services and tools hold the live secret key (v1's own, bookkeeping tools). v2 gets a **new restricted
   or secret key** from the same account; rolling v1's key at the end is part of decommissioning (section 11).
9. **A copy of v1's database** restored per the data runbook Part 2 (rehearsal copy), and the current counts (open question 3).

Exit criterion: a sheet with one row per connected account, one per open PaymentIntent, one per webhook endpoint, and a customer-to-card mapping. Anything surprising (an account type v2 cannot onboard, an unexplained
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

1. One-line check: list v1 PaymentIntents in `requires_capture`; there should be none (v1 captures immediately). If one exists,
   capture or cancel it now. Re-list to confirm none remain.
2. Let `processing` PaymentIntents (bank debits) settle, or record them as "arrives after cutover, handled by hand". They
   will emit events that v1 no longer receives and v2 ignores; reconcile them in the Stripe dashboard.
3. Billing: v1 has no Stripe subscriptions, so there is nothing to cancel. One-line check: the subscription list is empty.
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
| Connected account (seller side), **only after the keep or re-onboard decision** | `seller.sellers.stripe_account_id` | `seller.connect_account.account_id` linked to the org | Copy the `acct_` id exactly from the inventory, never retype. Then fetch current status from Stripe (the first `account.updated` or the compliance sync refreshes it) and confirm `charges_enabled`/`payouts_enabled` match the Stripe dashboard. |
| Connected account (payments side) | same | `payments.connected_account(org_id, stripe_account_id)` | Run the payments sync (`POST /v1/compliance/payments-sync`) or `PUT /v1/connected-accounts/{orgId}`. Check by `GET /v1/connected-accounts/{orgId}`. Without this a payout transfer has nowhere to go. |
| Stripe customer | v1 `stripe_customer_id` (to confirm, question 6) | `payments.stripe_customer(customer_id = identity user id, stripe_customer_id, default_payment_method_id)` | The owner has the customer ids in the v1 databases and migrates them: import one row per user who has a Stripe customer **before** that user's first v2 sign-in, otherwise v2 creates a duplicate Stripe Customer at first checkout. A short SQL insert, read in full; check ten rows against the Stripe dashboard. |
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

Both stacks are the same Stripe account, so both receive the same events as long as both endpoints exist. v1 and v2
must not both act on an event. The owner creates v2's endpoints **fresh** (new URLs, new secrets) and **stops and deletes v1's
endpoints at the freeze step, not before**. Creating v2's endpoints early **disabled** and enabling them after the data load
keeps v2 quiet until it is ready.

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
2. **At T-0 (the freeze step), after section 4:** first copy each v1 endpoint's URL, event list, API version and Connect flag
   into the sheet, then stop and delete v1's endpoints (`stripe webhook_endpoints delete we_V1XXXX`, or the dashboard).
   **Not before the freeze**: v1 is still serving customers until then. Anything not v1's stays enabled. Deleting removes the
   rollback shortcut, so the sheet is how v1's endpoints are recreated (section 11).
3. **After the data load and verification (section 5):** enable v2's three endpoints (`-d disabled=false`).
4. **Prove them:** in Workbench use **Send test event** on each endpoint and expect `2xx`. Then make one real small payment and
   a refund (go-live smoke, section 8) and confirm the event was delivered and applied (payments `stripe_event` row, order
   status). Stripe's dashboard shows each delivery and the response code.
5. **Events lost during the gap:** between deleting v1's endpoints and enabling v2's nothing is delivered. After enabling, list the events
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

Hostnames, provider and TTLs are open question 4. The shape, following [cloudflare-setup.md](cloudflare-setup.md) and the
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
| T-7 days | Customers (v1 email list), sellers | A new AfroTransact is coming on DATE. Same email and password; old orders are kept; you may be asked to add a card again. |
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
2. Recreate v1's webhook endpoints from the sheet (URL, events, API version). They are new endpoints with **new signing
   secrets**, so put the new secrets into v1's configuration and restart v1 before taking it out of maintenance. Re-deliver
   any events from the gap (section 7 step 5).
3. Put DNS and Worker routes back (section 9); take v1 out of maintenance mode.
4. If a seller was re-onboarded (option B), the old account is untouched and still the one v1 knows; nothing to undo.
5. Any v1 PaymentIntent you captured or cancelled in section 4 stays so; this was real state.
6. Truncate v2's imported data and re-run the rehearsal; tell customers in the status banner and by email.

**After the point of no return:** do not point back to v1. Use maintenance mode on v2 (`maintenance-mode.md`), fix, and
redeploy the previous image tag. Staff can refund any v2 order from the Stripe dashboard if a service is down. Money is
never lost: Stripe is the ledger; the reconciliation screen lists any payment v2 and Stripe disagree on.

**Decommissioning v1 (T+30 days, after sign-off):** keep v1 stopped and restorable for 30 days; then (v1's webhook endpoints were already
deleted at the freeze) **roll v1's Stripe secret key** (or revoke the restricted keys v1 held), remove v1's DNS leftovers, delete
the database copy and the dump (keep the `ledger.*` finance archive), and shut down the v1 cluster. Record the date.

## 12. Go/no-go checklist

Tick every line; any "no" on a **blocker** is a no-go. Owner decides; an engineer reads the evidence out.

Inventory and rehearsal
- [ ] **blocker** Stripe inventory sheet complete (section 1) from the dashboard; one-line check shows no `requires_capture`.
- [ ] **blocker** Connected accounts: keep, re-onboard or hybrid **decided**, the test-mode option A run recorded, and Stripe's written answers filed (top risk).
- [ ] **blocker** Open owner questions answered.
- [ ] **blocker** Test-mode rehearsal done within 14 days of the cutover and after the release being deployed: onboarding,
  authorise, staff pickup capture, cancel, refund, saved card, dispute, settlement and payout, event replay (section 2).
- [ ] **blocker** Data rehearsal on a restored v1 copy: counts equal, totals equal, every order read, five sign-ins done.
- [ ] Freeze minutes measured; window set with 50 percent margin.

Stripe
- [ ] **blocker** v2 live endpoints created fresh (disabled until the data load) with the SDK API version and three distinct secrets in the secret store.
- [ ] **blocker** v1 endpoint list (URL, events, API version) recorded in the sheet before deletion at the freeze; nothing else depends on them.
- [ ] **blocker** Every connected account maps to a v2 org and has a `payments.connected_account` row; types are compatible
  with v2's flows (charge, transfer, refund with reversal, health sync) or have been re-onboarded.
- [ ] **blocker** Customer ids and saved cards migrated by the owner and ten rows checked against Stripe.
- [ ] Live key for v2 is a new key; platform account branding, descriptor and payout schedule reviewed.

Platform
- [ ] **blocker** Readiness checklist blockers are green ([production-readiness.md](production-readiness.md)): `APP_ENV=prod`,
  no mocks, secrets from the store, CI security workflow green, backup **and a restore drill** done.
- [ ] **blocker** Staff trained on the pickup step (it captures money) and on the confirmation and reason it asks for.
- [ ] Resend domain verified (SPF, DKIM, DMARC); a test email arrives.
- [ ] Legal documents published after the owner's internal counsel review; terms acceptance on first sign-in works.
- [ ] Every v1 user can sign in on v2 with no invite (no sign-up gate); v1 registrations blocked at the freeze.
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
