# Launch plan: from today to production

Written 2026-10-29 from the backlog (288 of 324 stories Done, 22 In Progress, 14 To Do) and everything verified or not verified in
the build sessions. This plan sequences the work; the checklist to tick is
[production-readiness.md](production-readiness.md), the test server steps are [ovh-vps.md](ovh-vps.md), and the legal
documents are in [docs/legal](../legal/README.md).

Rule used throughout: a story is Done only when every criterion is met **and verified**. Things that cannot be verified from the
build machine (no Docker, no live Stripe, Kafka, Elasticsearch, browsers or devices) are listed as such, never assumed.

## Phase 0: finish the build (in flight or next)

| Item | State | Needs |
|---|---|---|
| Legal pages work + loader + new document keys (12 new keys, legal center, acceptance flows) | agent running | merge, run `tools/legal/build_seed.py`, then the written documents show in the app |
| Staff category management (create/edit/retire categories, product types, attributes; request queue) | agent running | merge, browser check |
| S263 location advertorials: stop counting views/clicks for ended or paused ones, count window and run orders alike, end run advertorials at cutoff, ZIP-only window lookups, tests | next | one agent, then verify |
| S265 run economics: P&L after a run from real fees and orders, cost/margin/break-even in location history, real refunded amounts, test with real data | after S263 | one agent, then verify |
| Reverse-transfer failure: when Stripe refuses a transfer reversal, the refund must still go through with the recovery flagged; add a Stripe test for reversal after payout | to do | small, in payments |
| Guard test that fails if a seller could get a Stripe dashboard (`stripe_dashboard: none`) | to do | small, in payments |
| S322 referral: customer mobile app "Refer a friend" screen | to do | mobile |
| S323 membership: member prices applied to offers, annual fee revenue spread over the year, mobile screens | to do | decide scope first |
| S324 reviews: search/listing-card propagation verified end to end, photo picker in the mobile app | verify | needs Elasticsearch/Meilisearch + Kafka (OVH environment) |
| S262: WhatsApp (Meta approval), mobile subscription screen | blocked / to do | external |
| Stale items: S016 passkeys, S030 uploads, S066 inventory hub, S103 (Stripe Billing, now replaced by our own billing: close it with the new design), S178 error tracking, S193 i18n, S235, S250, S257, S253 translations review | audit | BL1 audit of each against its criteria |

## Phase 1: the OVH test environment (this week)

1. Follow [ovh-vps.md](ovh-vps.md) (account, project, images, secrets script, pipeline, DNS and certificates).
2. Run the same stack as production: real PostgreSQL per service, Kafka, Elasticsearch, OpenFGA + authz-sync, Temporal, media storage.
3. Demo data must stay out: seeding refuses prod/uat and non-local URLs unless `SEED_ALLOW_REMOTE=1`; use that deliberately here only.
4. Stripe test mode, Twilio test credentials, carrier sandboxes (you own the accounts: [carrier-sandboxes.md](carrier-sandboxes.md), S204).

## Phase 2: your browser pass and the live checks (on the OVH environment)

- S311 to S319 (finance, payouts, settings, tax, maintenance mode, remittance, nexus): the list you will walk through. Staff payouts now list newest first with real status chips.
- S266: [fga-enforce-check.md](fga-enforce-check.md). Pass, then flip dev to enforce.
- The customer journeys: sign-up, search, cart, checkout with a saved card and with a new one, pre-order and run orders, cancel before shipping, delivery codes and proof, help request with photos, gift card buy and redeem, credit, membership, referral, rating a delivered item.
- Seller journeys: onboarding with Stripe verification, add a product (new page), bulk upload and image library, ship and mark delivered, payout request and approval, billing.
- Staff journeys: applications review, payouts approval, reconciliation, refunds/claims, settings, categories.
- Not verified until then: Kafka event flows end to end, search indexing of ratings, Docker-only integration tests (`OutboxRelayIT`, relationship ITs, Stripe mock contract), real Stripe Connect transfers and reversals, a real mobile device, Playwright prod-build runs (dev server only so far).

## Phase 3: external setup (long lead times, start now)

| Item | Owner | Notes |
|---|---|---|
| Counsel review of every document in docs/legal; fill every `[[LEGAL: …]]` (entity, address, governing law, arbitration, liability caps, DMCA agent, state rules) | you + counsel | [open-questions-for-counsel.md](../legal/open-questions-for-counsel.md), [open-questions-business.md](../legal/open-questions-business.md) |
| Read the providers' own terms (Stripe, Twilio, WhatsApp/Meta, OpenRouter, Google, Smarty, Resend and the rest) and confirm the matrices | you / re-run with network access | the drafting sessions could not reach those sites |
| Stripe live: Connect platform application, confirm which agreement applies to our connected accounts, webhooks, Tax settings, statement descriptor | you | verify liability settings (`losses: stripe`) with Stripe |
| Twilio: A2P 10DLC (or toll-free) registration with the real campaign, sender numbers, STOP handling; Meta: WhatsApp Business account and template approval (names and bodies in notifications README) | you | approval takes days to weeks |
| Email: sending domain, SPF/DKIM/DMARC | you | |
| OpenRouter: logging and training off, ZDR policy, DPAs | you | checklist items in production-readiness section 10 |
| Sales tax: registrations in each launch state, remittance process (S318/S319 tools), marketplace facilitator position | you + accountant | |
| Carriers: production accounts and rates after sandbox passes (S204) | you | |
| Domains, DNS, Cloudflare, email addresses named in the legal text | you | |

## Phase 4: hardening before real money

- Security: S246 and S268 (remaining Java HIGH findings, Semgrep triage), S201 scanners (Trivy, Semgrep, dependency scans have not run on the build machine), one more dependency audit (the accepted node-forge exception is documented), an external penetration test of auth, payments and seller isolation.
- Reliability: backup and **restore** test, load test of checkout and search, alert routing and on-call, the runbooks in this folder walked once.
- Money: reconciliation run daily with a person assigned, payout approval rota, the seven-day hold reviewed with counsel (reserves, chargebacks), refund and reversal paths tested against Stripe (including a reversal after payout).
- Data: production database migrations dry run, confirm no demo or seed data, secrets only through the secret store, `APP_ENV=prod`.

## Phase 5: staged launch

1. **Staff-only** on production: real accounts, small real orders, refund each, payout one seller.
2. **Friends and family** in one state and city (launch is enabled per state and city); watch reconciliation, delivery codes, notifications and support requests daily.
3. **Open** that market; add states and cities one at a time through the staff location settings. Pre-order windows and runs follow the owner rules (minimum set by staff, cancel-or-proceed at the end date).
4. Launch-day list: production-readiness section 11. Keep maintenance mode ready ([maintenance-mode.md](maintenance-mode.md)).

## What comes after launch (not blocking)

- Instant or same-day payouts as a paid seller option; revisit Stripe Treasury (seller wallet, yield) when volume justifies it.
- Native-speaker review of fr, es, ar and yo for the legal documents and notification templates; translations of the legal pages after counsel approves the English.
- Gift card self-service cancel, mobile photo picker for evidence and reviews, mobile membership screens.
