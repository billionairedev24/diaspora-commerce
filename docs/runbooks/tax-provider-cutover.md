# Tax provider cutover (Stripe Tax or Avalara)

**Not tax or legal advice.** The rules, the code map and the cutover itself need the Texas tax professional's sign-off. Design:
[category-tax-design.md](../engineering/category-tax-design.md) section 8. Engine: `java/services/tax-returns`.

The in-house engine decides the outcome of every line (EXEMPT, TAXABLE or blocked) and, by default, the rate. A provider is only ever
told *which of its codes* describes the outcome, through the per-**outcome** code map. A cutover therefore changes the map and the
method switch (`RULES | STRIPE_TAX | AVALARA`), never the rules. **The risk to manage:** the dataset uses one Stripe code for several
outcomes (for example `txcd_41053001` on four outcomes of one category; `txcd_40040000` on exempt dairy, exempt frozen meals and exempt gift food),
and the provider decides by its own rules for a code, so it can silently disagree with our outcome. SHADOW mode exists to find those lines
before any shopper is charged by the provider.

## Prerequisites

1. The profiles you cut over are `ACTIVE` with the tax professional's written sign-off on this dataset (`ruleVersion`), or the quotes are blocked anyway.
2. Provider credentials are in the secret store, never in the repo: Stripe `STRIPE_SECRET_KEY` (Stripe Tax set up, registrations done); Avalara
   `AVALARA_ACCOUNT_ID`, `AVALARA_LICENSE_KEY`, `AVALARA_COMPANY_CODE` (and `AVALARA_BASE_URL` for production). **Avalara is a skeleton**: the AvaTax call is not written yet, so
   it can be configured and mapped but not used until it is (see "Avalara status" below).
3. In production set `TAX_REQUIRE_VERIFIED_CODES=true` so a provider only ever receives codes a person verified.
4. Staff with `tax:classify-admin` (code map) and `tax:configure` (settings) exist; the tax professional can read `tax:read` screens.
5. The orders service sends `orderId` and each line's `sellerBehaviour` on checkout quotes (SHADOW compares only quotes that carry an order id).

## Steps

### 1. Fill the provider column of the code map

`GET /v1/tax/provider-code-map?provider=AVALARA&unmappedOnly=true` lists what is missing; `PUT /v1/tax/provider-code-map/{internalTaxCode}` sets one entry
(`{provider, providerCode, verified, reason}`). One entry per outcome code (105), never per category. Stripe starts filled from the source document, **unverified**.
Blocked outcomes (the unitemised bundle) stay unmapped on purpose. Every change is versioned, audited and starts a new rule version.

For categories in `REFINE_CODE` (generic `txcd_99999999`: cleaning and paper, kitchenware, furniture and small appliances, toys and games, pet grooming and accessories, handmade) the
specific code is recorded per variant at review (`refinedProviderCodes` on approve), not here.

### 2. Run SHADOW

`PUT /v1/tax/settings {"method":"RULES","shadowProvider":"STRIPE","reason":"..."}`. The method keeps charging; for each real order quote the shadow provider is asked the same
lines (including lines we call exempt, which is where an outcome disagreement shows) in the background with a short timeout, and both answers are stored in `tax_shadow_quote`.
Nothing the provider says is charged, and a provider error, timeout or overload only produces a stored `PROVIDER_ERROR` row and a counter (`tax_shadow_lines_total`, `tax_shadow_failures_total`).
Let it run for **at least one full filing month** and a minimum number of orders **per internal code** agreed with the tax professional (suggested floor: 30 lines per code, or every code
that sold at all plus a hand-made test order for the rest, because a code that never sells is never proven).

### 3. Review the report

`GET /v1/tax/shadow/report?from=YYYY-MM-DD&to=YYYY-MM-DD&provider=STRIPE`: totals, then disagreement rates by internal code (with the provider code), category, outcome, state and kind.

| Kind | Meaning | What to do |
|---|---|---|
| `OUTCOME` | one engine taxes the line, the other does not | resolve every one: re-map to another provider code, ask the provider for a custom or refined code, accept with the tax professional's written note, or keep the in-house engine for that outcome |
| `RATE` | both tax it at a different rate or amount | ZIP versus exact address is expected and is the reason to cut over; explain every other one |
| `PROVIDER_ERROR` | no answer (timeout, error, no mapped code, not configured) | fix the cause; a code with only errors is unproven |
| `NO_DECISION` | we had no decision (blocked line) | nothing to compare; fix the facts or approval |

### 4. Verify each mapping

Once a code shows no unexplained OUTCOME or RATE difference over the agreed volume, mark it `verified` (`PUT .../provider-code-map/{code}` with `verified: true` and the reason). Changing a code resets `verified`.

### 5. Dry run in a lower environment

Set `method` to the provider and re-run the golden scenarios (`tools/taxonomy/scenarios.json`, 105) through the provider adapter; all 105 must agree on outcome. Quote them with the
matching `sellerBehaviour` and read `decisionStatus`, `outcome`, `providerTaxCode` and `provider` on each line. Any `RULES-FALLBACK` means a line had no usable code.

### 6. Switch

`PUT /v1/tax/settings {"method":"STRIPE_TAX","shadowProvider":null,"reason":"..."}`. Validation refuses a provider that is not configured (422 `tax.stripe-not-set-up` or `tax.avalara-not-set-up`) and a provider with
no mapped codes (422 `tax.provider-not-mapped`). It applies to **new quotes**; placed orders keep what they were charged. The settings history records who and why.

After the switch watch for `RULES-FALLBACK` (below). The contract's shadow enum is `STRIPE | AVALARA`, so the in-house engine cannot yet be shadowed behind a provider method (a follow-up: allow `RULES`
as a shadow provider). Until then, compare after the switch by re-running the dry run, or shadow the *other* provider.

### 7. Optionally resolve exact addresses

Adding a provider-backed `JurisdictionResolver` adapter (precision `ADDRESS`) gives the in-house engine exact local rates and closes the TX-03 limitation without changing the method.

### 8. Sign-off

The tax professional signs off the cutover; attach the signed note to the settings history entry (reference in the `reason`).

## Risk-based tax review and a cutover

`taxReview.mode` (Sales tax → *Review settings*, `GET|PUT /v1/tax/review-settings`) decides who approves a variant's tax
classification: the system approves when the category is ACTIVE, the rules resolve and the category is not on the always-review list
(`RISK_BASED`, the default, with a 5% audit sample) or every variant waits for a reviewer (`ALL`). It does not change which provider
calculates the amount. For a cutover: record the tax professional's sign-off reference in the change reason when the list or the rate
changes, do not change the mode in the same window as the method switch (a difference in the shadow report would then have two
possible causes), and keep the **Audit sample** tab clear. A reclassification from the audit sample re-closes the gate and re-resolves
checkout lines, so its effect shows in the next shadow quotes. Rolling it back is a `PUT` with mode `ALL`: new events then go to
review again; items the system already approved keep their approval until their facts change or a reviewer reclassifies them. A `PUT`
back to `RISK_BASED` approves the waiting items the rules decide.

## Rollback

`PUT /v1/tax/settings {"method":"RULES","reason":"..."}` (or `shadowProvider: null` to stop shadowing). Immediate for new quotes (other replicas follow within about 10 seconds); placed orders keep their tax. Mappings stay; re-verification is only needed if a code changed.

## Watching a cutover

| Signal | Where | Means |
|---|---|---|
| `provider` on the quote is `RULES-FALLBACK`, with `fallbackLines` | quote response, `tax_provider_fallback_total{provider,why}`, `tax_provider_fallback_lines_total` | the chosen provider could not take the quote (`why`: `no-code`, `not-configured`, `not-implemented`, `unavailable`, `non-us`) and the rules engine answered; **never silent**; fix the map or the provider |
| `decisionStatus` other than `RESOLVED` on a line | `tax_quote_lines_total{status}` | the line is blocked; checkout must block it |
| `tax_shadow_failures_total{why}` | Prometheus | `timeout`, `error`, `overloaded`, `rejected` |
| `tax_classification_changes_total{kind}` | Prometheus | profile, code map and variant changes |

## Avalara status

`AvalaraTaxCalculator` is a skeleton: config keys, `configured()`, the request mapping to an AvaTax `SalesOrder` (`request(...)`), and a clear answer when asked
(strict quote: "not configured" or "not implemented"; charging quote: visible fallback). To finish it: implement the `POST /api/v2/transactions/create` call and response parsing in
`quoteStrict`, keep `quote` as strict-then-visible-fallback like Stripe, add a contract test against the Avalara sandbox, then run steps 1 to 8 for AVALARA.

## Identity setup

Included in this change: `java/services/identity/.../V38__tax_classification_permissions.sql` creates the permissions `tax:classify` and `tax:classify-admin`, the
role **Tax Reviewer** (`platform.tax_reviewer`: `tax:read`, `tax:classify`, `tax:classify-admin`), grants `tax:classify-admin` + `tax:classify` to Super Admin, `tax:classify-admin` + `tax:read` to Catalog Manager,
`tax:classify` to Auditor, and gives Catalog Manager and Tax Reviewer **Fees & commissions: VIEW** (where the Tax screens live). The **catalog-service** client scope `tax:classify` is added in the four
`application-*.yml` files of identity (restart identity). The orders-service and gateway-bff clients keep `tax:quote`. Until V38 runs, tax-returns accepts `tax:configure` holders (Super Admin, Finance) for the admin actions.
