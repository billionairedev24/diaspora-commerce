# Backlog audit, October 2026

Read-only review of every story not marked Done in `docs/backlog/backlog.csv`. Statuses and Remaining text in the CSV
were updated to match. "Blocker" says what has to happen for the story to close: **code** (more engineering),
**browser pass** (a person looks at the screen), **live Stripe** (a Stripe test or live account), **devices** (phone or
tablet testing), **CI run** (a pipeline run to confirm), **external accounts** (third-party sandboxes or projects),
**counsel** (legal or tax-advisor sign-off), **human sign-off** (finance or native reviewers).

## Changed in this audit

| Story | Was | Now | Why |
|---|---|---|---|
| S201 Security scanning | In Progress | Done | Critical findings fail the build; SBOMs are attached to images. Follow-up: ZAP baseline on previews does not run yet. |
| S066 Inventory hub | In Progress | Done | Quarantine, release and write-off with reasons and per-lot traceability are met. Description reworded to the tabs that exist. |
| S257 Send stock to AfroTransact | In Progress | Done | All criteria met in code and tests; the storefront renders "Fulfilled by AfroTransact" (`ProductView.tsx`, `SearchBox.tsx`) and `web/apps/storefront/e2e/smoke.spec.ts` asserts it. |
| S291 Identity admin console | To Do | In Progress | Most of the console exists; the status said To Do. |

## Stories with audit evidence

| Story | Status | Met | Left | Blocker |
|---|---|---|---|---|
| S030 Image upload and moderation | In Progress | Presigned uploads, metadata returned | Show the moderation flag per product image in the product form | code |
| S103 Plans and own billing | In Progress | Proration, renewal-time downgrades, invoice list and printing, metered lines, collection waterfall with retry and dunning | A test that metered usage lands on a generated invoice | code |
| S235 Returns and tax integration | In Progress | Checkout tax from tax-returns, eligibility from order events, refunded amounts per line, return pickups with fulfilment. Customers can no longer start returns; they contact support with evidence | Finance confirms the seeded tax rates | human sign-off (finance) |
| S245 BOTH orgs, billing usage, payment sheet | To Do | Stripe metered prices criterion removed as obsolete | BFF routes and console UI for the billing usage statement and metering run; BOTH-org nav test; mobile PaymentSheet saved cards | code; devices |
| S248 Relationship checks | In Progress | Rollout in catalog, orders and payments; identity publishes market on role events | S266 runbook, then flip dev to enforce; uat/prod shadow review | CI run / environment run (runbook), then human review of shadow logs |
| S250 Media long-lived links | In Progress | Media-hosted redirect links | BFF résumé purpose `career-resume`; export store media in env configs plus test; confirm `LINK_SIGNING_KEY` is deployed | code; deploy check |
| S268 Java HIGH findings, Semgrep | To Do | Jackson pinned (S246) | Manage json-smart >= 2.5.2; `project.build.outputTimestamp`; base images by digest; double-build digest check; Semgrep registry triage in CI | code; CI run |
| S291 Identity admin console | In Progress | See the story | Second-factor step-up for dangerous actions; a device/user-agent column on sessions | code |
| S311 Reconciliation, four-eyes adjustments | In Progress | Domain and service tests, BFF conformance, Testcontainers `LedgerAdjustmentIT` | Run against a Stripe test account; browser pass | live Stripe; browser pass |
| S312 Payout request sheet | In Progress | BFF unit and conformance tests | Tests for the Stripe mismatch flag and the Check with Stripe action; browser pass | code; browser pass |
| S314 Settings hub | In Progress | One grouped hub, no second sidebar; console tests, gateway conformance | Tests for role-hidden sections and the read-only notice; browser check; i18n check | code; browser pass |
| S315 Tax provider switch | In Progress | Service and calculator tests, Testcontainers `TaxSettingsIT` | Live Stripe test account; browser pass | live Stripe; browser pass |
| S316 Stripe transfer ids | In Progress | Unit and gateway tests | Tests for the transfer list endpoint and organisation guard; browser pass | code; browser pass |
| S317 Maintenance mode | In Progress | Gateway unit and contract tests, web tests | Maintenance web test; browser pass; mobile apps do not render the message yet | code; browser pass |
| S318 Tax remittance record | In Progress | Unit tests, Testcontainers `TaxRemittanceIT`, BFF contract tests | Browser check. No filing is sent to any state | browser pass |
| S319 Nexus tracker, seller tax statement | In Progress | Unit tests, Testcontainers `TaxNexusIT`, BFF contract tests, web tests | Browser check; the default nexus threshold is a placeholder for a tax advisor | browser pass; counsel (tax advisor) |

## S239 Staff console upstream gaps (To Do)

Nine concrete gaps. Blocker for all: code in the named upstream service or the BFF.

1. Internal notes always answer 503 (there is no notes service).
2. Staff bookings lookup per customer needs the upstream route `GET /v1/bookings?customerId=`.
3. Export files are not stored in media (the local file store is the default).
4. The resend-notification support action is not audited.
5. Resend needs `notifications:send`, which platform roles lack.
6. Trust-ops case filter by customer is done client-side over 200 cases.
7. The dashboard samples the first 2000 orders (no analytics feed).
8. Identity user lookup is one read per user (no bulk).
9. There are no helpdesk, feature-flags or API-keys modules.

## Other non-Done stories (not examined in depth)

These were not part of the evidence review; status and criteria are as in the CSV. Blocker is taken from the story text.

| Story | Status | Blocker |
|---|---|---|
| S016 Passkeys | In Progress | code; devices (mobile platform passkeys) |
| S178 Front-end and mobile error tracking | In Progress | external accounts (a real Sentry project), CI run |
| S193 Internationalisation | In Progress | code; human sign-off (native review) |
| S204 Carrier sandbox integrations | To Do | external accounts (carrier sandboxes, credentials) |
| S246 Remediate scan findings | In Progress | CI run (scans clean) |
| S249 First real plan and apply per cloud | To Do | external accounts (cloud, Cloudflare, Temporal); CI run |
| S253 Finish translations | To Do | code; human sign-off (native reviewers); devices (VoiceOver, TalkBack) |
| S262 Location subscriptions | To Do | code; external accounts (WhatsApp templates) |
| S266 Seed service-client tuples | To Do | code; CI run (runbook `docs/runbooks/fga-enforce-check.md`); gates S248 |
| S310 Image library, bulk image matching | In Progress | code |
| S313 Seller payouts sync | In Progress | code (seller integration test with real payments service) |
| S322 Referral programme | To Do | code |
| S323 Membership tiers | To Do | code (see `docs/engineering/membership.md` for what is built) |
| S324 Verified-purchase reviews | To Do | code |

Dev FGA stays in shadow mode until the S266 runbook passes (see `docs/engineering/authorisation.md`).
