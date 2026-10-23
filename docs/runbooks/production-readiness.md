# Production readiness review

Reviewed 2026-10-23 on `main`. This separates what was **run and passed** from what **could not be checked here** and what is
**not built or not decided**. It is not a sign-off: a go-live needs the "Before go-live" items closed by the people named.

## Checked today (run, passing)

| Area | Result |
|---|---|
| Java, 16 modules | unit tests + checkstyle + ArchUnit pass (`./mvnw verify` with integration tests skipped) |
| Go, 10 modules | `go vet` + tests pass (gateway-bff includes OpenAPI conformance tests for the seller and staff contracts) |
| Web (storefront, seller center, staff console, packages) | `tsc` clean except one existing type error in `packages/api-client/test/seller-contract.test.ts`; ESLint clean; all unit tests pass |
| Database migrations | every service's Flyway chain (15 services, 130+ files) applies cleanly to an empty PostgreSQL 16; new payments V19, tax-returns V7 and identity V30 also ran their queries (adjustment journal passes the double-entry trigger) |
| Contracts | Redocly lint 0 errors (793 warnings), generated clients and the merged AsyncAPI are up to date |
| Environment guards | `make env-check`: no hard-coded hosts, every service has its profiles and sizing |
| Secrets in tracked files | gitleaks clean (two false positives annotated; one hit is in the git-ignored `build/` folder) |
| SLO alert rules | regenerated and current |

Fixed during this review: identity's copy of `tokens.css` was out of date (a CI test fails on that), 25 ESLint errors, a duplicate
OpenAPI `operationId`, two tests that depended on today's date, a Spinner colour-contrast failure on dark grounds.

## Could not be checked here (run these before go-live)

* **Java integration tests** (Testcontainers needs Docker): every repository, controller and flow test. CI runs them; read the last
  green run for `main` before tagging. The new reconciliation, adjustment, tax-method, transfer and settings code has unit tests and
  its SQL was run by hand, but has **no integration test yet**.
* **Helm charts and OpenTofu** (`make helm-lint`, `make tf-check`): the chart and registry downloads are blocked here.
* **Promtool rule check** (needs Docker) and Trivy / Semgrep / govulncheck scans (`make security`).
* **A real Stripe account**: payouts, transfers, Connect onboarding, webhooks, Stripe Tax, the Stripe lookup and the reconciliation
  against Stripe's balance transactions have only run against the local stand-in and mocks, apart from one test-mode payout.
* **Browser checks** of the newest screens (Settings, Payouts reconciliation, payout sheet) and phone widths.
* **Mobile apps** (Expo builds, store submission) and **carrier sandboxes** (S204).
* **The OVH single-server kit** has never run against a real OVHcloud/GitHub account.

## Not built, or not decided (go-live blockers or conscious deferrals)

Money and tax
* Tax collected is not yet *remitted*: no filing/remittance record, nexus tracker or seller tax statement screen; Stripe Tax
  transactions and refunds are not recorded. Tax rates, the code map and the seller wording need a **CPA and legal review**
  (`docs/engineering/tax-classification-design.md`).
* Ledger adjustments are four-eyes by *permission*: give `ledger:adjust` and `ledger:approve` to different people, or one person
  holding both can still not approve their own but the control is weaker.
* Valkey, Kafka and Temporal data are not backed up (Postgres and secrets are, on the single-server kit).
* Payments settings (hold days, minimum payout) are deployment configuration, read-only in Settings.

Security and access
* Open security stories: S201 (scanning), S246 (findings), S268 (remaining HIGH findings + Semgrep triage), S248/S266 (relationship
  checks and service tuples), S250 (media links), S016 (passkeys).
* Seed/demo switches must be off in uat/prod (`provisioning-enabled`, local profile). `make env-check` covers hosts and profiles,
  not this; confirm in the prod values.

Platform and process
* **S249: no real cloud `plan`/`apply` has been run for any environment.** Production infrastructure does not exist yet.
* Error tracking in the web and mobile apps (S178), full translations (S193/S253: the new Settings and finance screens are
  English only), exports service (S196), dashboards data feeds (S195).
* 46 of 316 backlog stories are not Done (29 in progress, 17 to do): `make backlog`.

## Before go-live (suggested order)

1. Green CI on `main`, including integration tests, `make helm-lint`, `make tf-check`, `make security`.
2. First real `plan`/`apply` of dev then uat (S249); smoke tests; restore drill of the database backups.
3. Stripe live checklist: Connect settings, webhook endpoints and secrets, Stripe Tax registrations if used, payout schedule;
   rehearse one order → delivery → transfer → payout → reconciliation in test mode and compare with the Stripe dashboard.
4. CPA and legal sign-off: tax code map and rates, seller terms (accuracy of tax-relevant listing information).
5. Close S246/S268 and re-scan; rotate and set every production secret; confirm demo switches are off.
6. Load test the checkout path (`make perf-k6-smoke`), confirm SLO alerts route to a person, name the on-call.
7. Pick the launch scope: the 17 To Do stories and the open ones above are either launched without or finished first.
