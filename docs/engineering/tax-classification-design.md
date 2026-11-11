# Sales tax classification — design spec (draft for CPA review)

> **Superseded in part (draft):** [category-tax-design.md](./category-tax-design.md) replaces the mapping table, the listing questions,
> the "safe default: general taxable" principle (now: never guess, block) and the build order below. Still valid: tax code separate from browse
> category, sellers never type a code, provider neutrality, the staff review/reclassify/report ideas, never-taxable codes, the seller
> responsibility wording (needs legal review) and the out-of-scope list.


**Status:** the V5 design below (13 coarse codes, rule table, "safe default") is **legacy**. It stays in the database and behind its staff endpoints (`/v1/tax/codes`, `/v1/tax/classification-rules`, `/v1/tax/review-queue`), but **the quote path no longer uses it**: a quote line is taxed from the variant ATIN's approved classification record under the category tax profiles (V10 to V12, [category-tax-design.md](./category-tax-design.md)); only a caller-given `taxCategory` still bypasses it. The legacy order-line tag (`order_line_snapshot.tax_code`, the by-code liability report) is still written by the old classifier until orders sends the internal code on `orders.order.placed` lines. See "What the engine does today" at the end of this file. Open: Stripe tax transactions and refunds, nexus tracker, remittance record, seller statement screen, making the listing question required. **Owner:** finance + tax-returns. **Not legal or tax advice:** a CPA must approve the code map, the state list and the seller wording before launch.

## Problem

The tax a customer pays depends on *what the item is* (a tax code) and *where it ships*. Today tax-returns maps the catalog **product type** to `GENERAL`, `GROCERY` or `PREPARED_FOOD`. That is too coarse (a `grocery` type holds exempt rice and taxable soft drinks), the config names do not match the catalog types (`grocery` and `perishable` fall through to `GENERAL`), and `custom` carries no information. See [shipping-and-tax-data.md](./shipping-and-tax-data.md).

## Principles

1. **Tax code is separate from browse category.** Search and navigation keep the catalog tree; tax uses its own code.
2. **Derived, not typed.** Most listings get a default from their leaf category; for food and similar, plain answers to listing questions decide it. Sellers never type a tax code.
3. **Safe default.** Anything not positively classified is *general taxable*. Under-collecting is our liability; over-collecting is refunded.
4. **One engine, one place.** Rates and state rules live in the tax provider (Stripe Tax or Avalara behind the `TaxCalculator` port; the rules engine stays for local development and as a fallback). Sellers and the BFF never hold rates.
5. **Provider-neutral.** Our codes map to each provider's codes; switching providers changes one mapping column.

## Design

**Mapping table** (`tax_code_map`, staff-maintained, versioned): leaf category (and, where needed, attribute values) → our tax code → provider code (e.g. Stripe `txcd_…`). A shortlist of roughly 30 to 60 codes covers the catalog: general goods, clothing, packaged food, fresh produce, prepared food, beverages, supplements, books, personal care, children's goods.

**Listing questions** (only where the answer changes the tax): sold hot or prepared; carbonated or sweetened drink; candy; dietary supplement; alcohol; medicated or over-the-counter product. Answers are stored on the product and resolve the code. `custom` listings stay *general taxable* until staff classify them.

**Quote line:** `taxCode` (ours) and `providerTaxCode` are sent on every quote and stored on the order line snapshot, so refunds reverse with the code the sale used.

**Staff tools:**
- *Review queue:* new or edited listings in tax-sensitive categories (food, drinks, supplements, children's goods, `custom`) and every seller attribute change that lowers the tax.
- *Reclassify:* change a mapping or a listing's code with a reason; shows the orders affected. History is kept.
- *Reports:* sales and tax by tax code and state; the existing liability report (net to remit) stays the finance source.

**Seller statement:** each month, tax collected on the seller's behalf per jurisdiction (the endpoint exists; a Seller Center screen is needed).

**Known non-taxable items.** A tax code can be marked *never taxable* (gift cards, donations) or *exempt in named states*
(`exempt_states`). Staff set these with the CPA. Such lines are charged no tax and are never sent to the tax provider, so we neither
over-charge customers nor pay per-call fees for them; an order of only such lines makes no provider call. Unknown is not exempt:
the default stays general taxable.

## Seller responsibility (to be added to the seller terms)

Sellers must make sure the information they give about their products is accurate and complete, including category, product type and every tax-relevant answer (such as whether an item is hot or prepared, a soft drink, a supplement or an over-the-counter product). AfroTransact derives the tax treatment from that information. Sellers are responsible for it being correct, must correct it promptly when it is not, and agree that AfroTransact may reclassify listings, recalculate tax and recover from the seller any tax, interest or penalties caused by inaccurate listing information, as the seller terms allow. This wording needs legal review.

## Out of scope for this spec

Nexus and registration tracker, filing and remittance record, tax-exempt buyers and resale certificates, services and bookings taxability, and AfroTransact Retail's own sales tax (it sells as the seller, not as facilitator). Each needs its own note.

## Build order

1. Fix the product-type mismatch in config (`grocery`, `perishable`).
2. `tax_code_map`, the code on quote lines and snapshots, and the listing questions.
3. Stripe Tax adapter behind `TaxCalculator`; keep the ledger and liability report.
4. Staff review queue, reclassify tool, reports; seller statement screen.
5. CPA sign-off on the mapping and sample items before go-live.

## What the engine does today (tax-returns, category tax build)

Implemented in `java/services/tax-returns` (the design is [category-tax-design.md](./category-tax-design.md); this section says where the code is).

| Piece | Where |
|---|---|
| Profile model and evaluator (no Spring): conditions, status gate, exactly-one-row property | `domain/profile` (`ProfileEvaluator`, `Condition`, `TaxProfile`); pinned by `CategoryTaxGoldenTest` (105 scenarios from `tools/taxonomy/scenarios.json`, plus the exhaustive uniqueness check) |
| Data: facts, 73 profiles, 105 outcome codes and decision rows, provider code map, rule version history | `V10__category_tax_profiles.sql` (schema), `V12__seed_category_tax_profiles.sql` (generated by `tools/taxonomy/gen_seed_sql.py`, drift-checked by `make taxonomy-check` and `SeedMigrationTest`) |
| Variant record, audit, evidence, stored decision per (variant ATIN, behaviour hash), shadow table | `V11__variant_classification.sql` |
| Resolve, profiles, code map, review queue, approve, reclassify, shadow report | `TaxClassificationApiController`; services `ClassificationResolveService`, `TaxProfileService`, `ProviderCodeService`, `VariantClassificationService`, `TaxShadowService` |
| Records from catalog | `CatalogTaxFactsListener` (`catalog.product.tax-facts-changed` on `at.product.events`); a quote for a variant with no record asks catalog's public variant endpoint once and queues it for review |
| Quote path | `TaxServiceImpl` + `LineDecisionService`: per line the record (approved) plus the seller's behaviour, the ship-to state and the sale date, resolved under today's rules; no definite decision gives status MISSING_FACTS, BLOCKED, NEEDS_APPROVAL, REFINE_CODE or CONFLICT_OPEN, `blockedReason` and no rate |
| Providers | `SwitchableTaxCalculator` over `StripeTaxCalculator`, `AvalaraTaxCalculator` (skeleton) and `RulesTaxCalculator`; a provider that cannot take a quote answers through the visible `RULES-FALLBACK` |
| Rates | `JurisdictionResolver` port, default `ZipTableJurisdictionResolver` (precision `ZIP`, the TX-03 launch limitation, see [texas-sales-tax-rates.md](./texas-sales-tax-rates.md)) |
| SHADOW mode | `TaxShadowService`: the shadow provider is asked in the background, compared per line, stored in `tax_shadow_quote`, reported at `GET /v1/tax/shadow/report`; never charged, never blocks checkout |
| Cutover | [docs/runbooks/tax-provider-cutover.md](../runbooks/tax-provider-cutover.md) |

### Rule version

`tax_rule_version` keeps every version. The dataset's version (`tx-2026.11.09-draft.1`) is the base; every profile status change or
code map change starts the next one (`...draft.1+1`, `+2`), in the same transaction as the change and its audit row. Resolve results,
stored decisions, quote lines and audit rows carry it; the resolve cache is keyed by it, so a cached answer never outlives its rules.

### Records and review

A record is created or refreshed from the facts catalog reports. It is `MISSING_FACTS` while a required fact is empty, `PENDING_REVIEW`
once the facts are complete (the review queue), `APPROVED` after an admin (`tax:classify-admin`) approves it. A later fact edit keeps the
approval only when the decision (outcome, internal code, status) is unchanged; a new category or new evidence always returns it to review.
`at.taxreturns.tax.classification.approval-policy=SENSITIVE` lets the system approve plain taxable, evidence-free records in ACTIVE profiles;
the design default `ALL` reviews everything. Anything exempt, evidence-based or gated always needs a person under that policy.
The staff setting `taxReview.mode` (V13, OFF = `ALL` by default, changed at runtime by `tax:classify-admin`) is separate: `RISK_BASED` lets the system approve any ACTIVE, RESOLVED record with no missing required evidence outside the always-review list, and samples 5% for audit ([category-tax-design.md](./category-tax-design.md) section 9). When it is `RISK_BASED` it replaces `approval-policy`; when it is `ALL` the policy above applies as before.

### Never guess, as built

No default rate, no default outcome. A ship-to outside `rule-states` (TX) is blocked. A required seller behaviour fact that the quote line
does not carry (for example `seller_is_bakery`) is MISSING_FACTS, so callers must send the behaviour (see the contract's `sellerBehaviour`).
The exemption window (tax holiday) is false until a calendar exists, so car seats are taxed as ordinary sales.
