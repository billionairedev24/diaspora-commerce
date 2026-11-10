# Category tax design: department > category > product type, outcome-level tax codes, provider-neutral engine

**Status:** DRAFT design for the build (Phase A delivered: dataset, contracts, golden scenarios). **Not tax or legal advice.**
A Texas tax professional must approve the rules, the code map and the seller wording before any production use, as the
source workbook itself requires. Nothing in this design may be switched on for real customers before that sign-off.

**Source:** *AfroTransact Product Categories and Rules for Creating Products* (DRAFT, prepared for the 9 Nov 2026 session;
workbook `AfroTransact_Category_Tax_Map_With_Rates.xlsm`). Texas only at launch.
**Supersedes:** the seed taxonomy in [taxonomy.md](./taxonomy.md) (7 departments, ~29 product types) and the code-map,
listing-question and "safe default = general taxable" parts of [tax-classification-design.md](./tax-classification-design.md).
It keeps that document's principles 1, 2 (sellers never type a code), 5 (provider neutral) and its staff-tool ideas.

## 1. What is decided (owner decisions, final)

1. **Tree:** `department` (13) > `category` (73, the tax-bearing level) > `product type` (kept for detail and schema, for example fashion sizing).
2. **Never guess.** A variant whose tax facts are missing cannot be published. At checkout a line without a definite tax decision is blocked with a plain message (rare, because publishing is gated). There is no default rate and no default outcome.
3. **Provider-neutral in-house classification.** Internal outcome-level tax codes plus a per-provider code map (STRIPE, AVALARA). A cutover changes the map and the method switch (`RULES | STRIPE_TAX | AVALARA`), not the rules. A **SHADOW** mode compares a provider quote with the in-house quote on real orders, logs differences and never charges the shadow. The in-house engine stays the default (rule TX-11), with a jurisdiction-resolver port so exact-address resolution can come from a provider.
4. **The seed taxonomy is replaced.** Existing data is deleted (nothing is deployed).
5. **Identity standard:** product identity is the GTIN where one exists, else our ATIN; the tax decision attaches to the **variant ATIN** (the document's "SKU"); `sellerSku` becomes optional.

## 2. The single source of truth (Phase A)

| Artefact | Purpose |
|---|---|
| `tools/taxonomy/category_tax_map.json` | Generated. 13 departments, 73 categories with stable kebab-case codes, status gate, Stripe codes, typed fact definitions, 10 reusable fact groups, the ordered decision table per category (105 rows, one per Appendix A scenario), internal outcome codes, provider codes. |
| `tools/taxonomy/scenarios.json` | Generated golden fixture: the 105 Appendix A rows with inputs (`facts`, `sellerBehaviour`, `context`) and the expected outcome, internal code, Stripe code and resolve status. |
| `tools/taxonomy/source_scenarios.json` | The Appendix A rows verbatim. Outcome, Stripe code, rate wording and condition text of every decision row come from here. |
| `tools/taxonomy/build_map.py` | Generator and validator (`--check` fails when the committed JSON is stale or invalid). Definitions (facts, groups, categories, rows) are Python data in this file; the JSON is what the services read. |
| `tools/taxonomy/evaluator.py` | Reference evaluator (`resolve(...)`), the behaviour the Java engine must reproduce. |
| `tools/taxonomy/validate.py`, `tests/` | Validator and tests. `make taxonomy-check` runs both. |

The validator asserts: 13 departments, 73 categories, 105 scenarios, 10 fact groups; codes unique and kebab-case; every fact
referenced exists and belongs to a group; every row's Stripe code is in its category's list and equals the Appendix row;
every Appendix row is covered by exactly one decision row; **for every combination of a category's facts, exactly one decision
row matches the required facts** (so tables are complete and mutually exclusive); every scenario resolves to the expected
outcome, internal code and status; status counts are as designed (57 ACTIVE, 7 NEEDS_APPROVAL, 6 REFINE_CODE, 2 CONFLICT_OPEN, 1 BLOCKED_BUNDLE).

Edit the Python data, run `python3 tools/taxonomy/build_map.py`, commit both JSON files. A rule change is a change to this
dataset plus a new `ruleVersion`; it is reviewed like code and signed off by the tax professional.

### Dataset conventions

- **Facts** (`facts[]`): `id`, `label`, `kind` (BOOLEAN, DECIMAL, INTEGER, PERCENT, CHOICE, FILE), `unit`, `owner`, `help` (plain words for sellers), `evidence`, `choices`. Owners: `VARIANT` (product facts), `OFFER` and `SELLER` (behaviour), `STAFF` (set by tax review, never shown to sellers) and `CONTEXT` (worked out by the system: `exemption_window_active` from the sale date, `gift_card_event` from the order).
- **Fact references** per category (`facts[]`): `fact`, `required`, `requiredWhen` (expression or null = always), optional narrowed `choices`. Required-when doubles as the form's show-when.
- **Expressions:** `{all:[]}`, `{any:[]}`, `{not:x}`, leaves `{op: isTrue|isFalse|eq|in|gt|gte|lt|lte, fact, value|values}`. A missing (null) fact makes every leaf false; required-ness is checked first so a decision is never evaluated over missing required facts.
- **Decision rows** (`decisionTable[]`): evaluated top to bottom, first match wins (they are also mutually exclusive). Each has `id` (`S001`..`S105` = Appendix A number), `when`, the document's `condition` text, `outcome` (EXEMPT, TAXABLE, BLOCKED), `internalTaxCode` (`AT.<CATEGORY>.<OUTCOME_SLUG>`), `stripeCode`, `providerCodes` (STRIPE filled from the document, AVALARA empty until mapped), `rateDisplay` (display only, never used to charge), and an optional `note` where the document's wording needed an interpretation.
- **Status gate** per category (`status`, `statusReasons`): ACTIVE, NEEDS_APPROVAL, BLOCKED_BUNDLE, REFINE_CODE, CONFLICT_OPEN, with precedence CONFLICT_OPEN > BLOCKED_BUNDLE > NEEDS_APPROVAL > REFINE_CODE. `defaultOutcome` is a browse hint only (EXEMPT, TAXABLE, BLOCKED or CONDITIONAL) and is never used to charge.
- **Product types** per category (`productTypes[]`): by default one product type whose code equals the category code. Fashion keeps sized types: `adult-clothing-footwear` has `dress`, `top`, `menwear`, `footwear`; `childrens-clothing` has `kids` (all with the `sizing` trait, see [size-charts.md](./size-charts.md)).
- `ruleVersion`: `tx-2026.11.09-draft.1`. Every decision, quote line and audit row records it.

### Interpretations that need the tax professional (recorded as row `note`s)

- Packaged snacks: the document words the exempt case two ways ("multi-serving pack" vs ">= 2.5 oz with > 1 serving"). Built as for nuts: exempt only when servings > 1 AND net weight >= 2.5 oz AND not a single serving.
- Juice: at 50% juice or less a drink is taxable when sweetened OR classified as a soft drink.
- Ready-to-drink coffee and tea: a drink containing milk or a milk substitute is exempt before the sweetened/hot/utensils test; the "qualifying" threshold is not defined in the workbook.
- Gift cards are held as NEEDS_APPROVAL (the workbook says "approve rule before launch" on all three rows; the document's gate list does not name them).
- The document lists ten triggers although the brief said nine; ten groups are built.

## 3. Layers

```
Browse taxonomy (catalog)          department > category > product type      13 > 73 > N
        |  category.taxProfileId = category code
Tax profile per category (tax-returns)   facts, decision table, status gate, ruleVersion
        |  decision row -> internal outcome code (AT.NUTS_SEEDS.PLAIN_MULTI_SERVING_2_5OZ_PLUS)
Provider code map (tax-returns)    internal code -> STRIPE txcd_..., AVALARA code   (per OUTCOME)
        |
Decision record (tax-returns)      variant ATIN: facts, evidence, reviewer, ruleVersion, audit; per seller: stored decision
        |
Publish gate (catalog)  --->  checkout uses the STORED decision  --->  TaxCalculator (RULES | STRIPE_TAX | AVALARA, + SHADOW)
                                                                          |  JurisdictionResolver port -> rate by exact address
```

1. **Browse taxonomy (catalog).** Three levels: department (13), category (73, carries `taxProfileId`), product type (detail and schema; fashion sizing). The old tree and its ~29 product types are deleted and re-seeded from the dataset (catalog migration below). Staff keep managing the tree in the console, except that a category node's tax profile is read-only there (changing it is a tax-returns action with a reason).
2. **Tax profile per category (tax-returns).** The dataset loaded into tables: fact definitions, fact references, decision rows, status gate, ruleVersion. Staff read it (list/get with the decision table) and change a profile's **status** with a reason. Rows are not editable in the console in v1: rules change through the dataset (code review plus sign-off) and a seed migration, which bumps `ruleVersion`.
3. **Internal outcome codes.** One per decision row (105). The code names the outcome, not the category: `AT.<CATEGORY>.<OUTCOME_SLUG>`. All reporting (liability by code, shadow differences, refunds) uses it.
4. **Provider code maps.** `provider_code_map(internal_tax_code, provider, provider_code, verified)`. **The mapping is per outcome, never per category or per provider code**, because the workbook uses one Stripe code for several outcomes (for example `txcd_41053001` appears on four outcomes of *Energy, protein & drink mixes*, and `txcd_40040000` is exempt dairy, exempt frozen meals and the exempt-food gift line). A provider decides by its own rules for a code, so it can silently disagree with our outcome. Hence `verified` and the shadow report below.
5. **Jurisdiction resolver port.** `JurisdictionResolver.resolve(shipTo, saleDate) -> {jurisdictionId, stateRate, localRate, combined, source, addressPrecision}`. Adapters: `ZipTableJurisdictionResolver` (today's Comptroller ZIP table, `addressPrecision = ZIP`) and later `StripeJurisdictionResolver` / `AvalaraJurisdictionResolver` (`addressPrecision = ADDRESS`). **Documented launch limitation:** ZIP-based local rates violate TX-03 (never calculate from city or ZIP alone), because one ZIP can span several local jurisdictions. Until a provider computes the rate from the exact address, the ZIP table is used, every quote line records `addressPrecision = ZIP`, and the limitation is listed in the launch checklist for the tax professional. It is a known gap, not a silent one.
6. **Decision record per variant ATIN.** Two stores in tax-returns: `variant_classification` (one row per variant ATIN: category, product type, variant facts, evidence references, refined provider codes, record status, classified-by/at, reviewed-by/at, ruleVersion, append-only audit) and `listing_tax_decision` (one row per variant ATIN and selling organisation: variant facts plus that seller's behaviour facts resolved to a decision with ruleVersion). Several sellers may offer the same variant; each gets its own stored decision.
7. **Publish gate (catalog).** See section 6.
8. **Checkout uses the stored decision (orders, cart, tax-returns).** See section 7.
9. **SHADOW mode and cutover.** See section 8.

## 4. Identity, SKU and offer standard

- **Product identity:** the GTIN where one exists, else our ATIN. Variants have an ATIN always; the **variant ATIN** is the tax key (the document's "SKU").
- **`sellerSku` is optional.** When empty the service generates a stable, organisation-prefixed one (`<org-prefix>-<ATIN>`), sets `sellerSkuGenerated = true`, and never regenerates it. Bulk upload and one-step-form edits match variants by `sellerSku`, else GTIN, else ATIN. `unique (owner_org, seller_sku)` stays.
- **Sellers never type a tax code.** They answer plain-word questions (facts); the tax code is derived from the decision row.
- **Offers.** An offer is a seller's price, stock, condition and fulfilment on a variant. In Seller Center the wording is **"Price & stock"** and **"Your listing"**, never "offer". Several sellers may offer the same variant.
- **Facts split.**
  - **Product facts** (`owner = VARIANT`): net weight (oz), servings, juice %, label type, candy flag, ingredients, product type, evidence files. Stored once on the variant (`taxFacts`), shared by every seller of that variant.
  - **Seller behaviour facts** (`owner = OFFER`): sold heated, heated by seller, utensils provided. Stored with the seller's listing (`listing.sellerBehaviour`). `seller_is_bakery` (`owner = SELLER`) comes from the seller profile.
  - **Tax decision = variant facts + offer behaviour facts + ship-to + sale date.** Ship-to and sale date are resolved at quote time (jurisdiction rate; `exemption_window_active` from the date).
- **Edits that can flip the outcome** (new size, serving count, reformulation, unheated to heated) re-resolve the decision: the approved record goes `STALE`, the product leaves checkout until re-reviewed if the outcome or status changed.

## 5. Rules TX-01 to TX-12

| Rule | Implemented by | Not done yet, and why |
|---|---|---|
| TX-01 taxable: 6.25% + local 0-2% (8.25% max) | tax-returns `RulesTaxCalculator` applies state rate + local rate from the jurisdiction resolver to lines whose stored outcome is TAXABLE; cap 8.25% enforced | Exact local rate needs exact-address resolution (see TX-03). |
| TX-02 exempt items 0%, overriding location | Outcome EXEMPT from the stored decision; the calculator never looks up a rate for EXEMPT lines | None. |
| TX-03 resolve jurisdiction from the exact delivery address, never ZIP/city alone | `JurisdictionResolver` port (section 3.5) | **Launch limitation:** ZIP table adapter violates TX-03 for ZIPs spanning several jurisdictions; every quote line records `addressPrecision`. Closed when a provider (Stripe Tax or Avalara) or the Comptroller address locator computes the rate. Never use GPS as the tax address (checkout already uses the delivery street address). |
| TX-04 tax flags and approved code stored on each SKU, not inferred from the parent category | `variant_classification` (variant ATIN) + `listing_tax_decision`; quote lines carry `atin` and read the stored decision | None. |
| TX-05, TX-06 delivery fee follows the sale: taxable sale = taxed at the same rate; exempt-only sale = 0% | Quote line `shippingMinor` is taxed per its line's stored outcome | Verified only for single-outcome lines. |
| TX-07 mixed baskets: legally reviewed attribution, preserve itemisation | Itemised lines are kept; shipping is attributed per line today | **Open decision 5.** The attribution method must come from the tax adviser and interacts with the unfinished delivery price. Until decided, mixed baskets use the existing per-line shipping allocation, flagged in the launch checklist. |
| TX-08 holiday or exemption only when item, date and value all qualify | Fact `holiday_eligible` (variant) + `exemption_window_active` (CONTEXT, from the sale date) for car seats | **The tax-holiday calendar is not built** (no dates, no value limits); `exemption_window_active` is always false, so such items are taxed as ordinary sales (the safe direction). Needs the calendar and value thresholds from the tax adviser. |
| TX-09 bundles: itemise, tax per component | Gift basket category is BLOCKED_BUNDLE; components must be own lines with own category and price allocation | **Component itemisation is not built.** Until it is, no bundle can be published. |
| TX-10 blocked or missing: do not guess; block publication or checkout | Resolve statuses MISSING_FACTS / BLOCKED / gates; publish gate; checkout blocks lines with no READY decision | None. |
| TX-11 provider code is classification only; the in-house engine decides the rate | `method = RULES` default; provider codes only describe the outcome | A provider may become the rate engine at cutover by owner decision (section 8). |
| TX-12 classify platform commission, platform services and delivery separately | Fee lines stay out of the SKU decision (existing fee ledger) | **Platform fee classification is not built**: the fee classification and legal obligations need the tax professional's review. |

Other workbook obligations: marketplace-provider collection and remittance exist (liability, remittance, nexus); the written
certification to sellers is a legal task; gift card redemption tax is decided per redemption through `gift_card_event`
(CONTEXT) once gift cards are approved.

## 6. Publication gates

A variant is **READY** only when the tax rules resolve to `RESOLVED` **and** the classification record is `APPROVED` by admin tax review
(section 9). Catalog blocks publication otherwise.

| Gate | Meaning | Who lifts it |
|---|---|---|
| `MISSING_FACTS` | A required fact is empty. Seller sees plain messages ("Net weight (oz) is needed. ...") per fact. | The seller fills the facts. |
| `BLOCKED` | The matched row is BLOCKED (unitemised mixed bundle) or the category is `BLOCKED_BUNDLE`. | Itemisation feature or a tax-approved bundle rule, then staff set the profile ACTIVE. |
| `NEEDS_APPROVAL` | Digital products (e-books, audiobooks, online courses, digital art and downloads), flagged services (photography and media, event tickets) and gift cards. | Tax professional approves the subcategory rule (open decision 4); staff set the profile ACTIVE with approver and reference. |
| `REFINE_CODE` | Generic Stripe code `txcd_99999999` (cleaning and paper, kitchenware, furniture and small appliances, toys and games, pet grooming and accessories, handmade; also the taxable gift-basket line). | Per variant: staff verify and record the specific provider code on the variant record (`refinedProviderCodes`), then approve. Owner and deadline: open decision 3. |
| `CONFLICT_OPEN` | Flavored water and soda (decision 1), tutoring and consulting (decision 2). | The decision is made, the dataset changed (code or category split) and the profile set ACTIVE. |
| NO DEFAULT RATE | Never 8.25% for every address, never default an unclassified product to taxable or exempt. | Not liftable. |

Seller-facing messages are plain ("We need your tax review before this can go live", "Tell us the package weight and servings").
The tax never appears as a guess.

### Catalog publish flow

1. Seller picks category (and product type), answers the category's fact questions (form built from the profile's fact definitions: `showWhen`, `requiredWhen`, units, help text), uploads evidence where the profile requires it.
2. On every save catalog calls `POST /v1/tax/classifications/resolve` per variant (variant `taxFacts` + listing `sellerBehaviour`) and stores `taxStatus` and `missingTaxFacts`; `GET /v1/products/{id}/tax-readiness` exposes it.
3. Submit for review is allowed with `MISSING_FACTS` cleared. Gate statuses other than READY keep the product in draft (or `IN_REVIEW` for the tax review) and out of search.
4. catalog publishes `catalog.product.tax-facts-changed`; tax-returns upserts the classification record and puts it in the review queue (`PENDING_REVIEW`).
5. Staff approve or reclassify. tax-returns publishes `tax.classification.changed`; catalog recomputes the gate and, when READY and moderation passes, the product goes live.

## 7. Checkout behaviour

- The cart and checkout never re-derive classification from product attributes. For each line `orders`/`cart` asks tax-returns for a quote with `atin` and `sellerOrgId`; tax-returns reads the stored `listing_tax_decision` and returns per line: `decisionStatus`, `outcome`, `internalTaxCode`, `providerTaxCode`, `ruleVersion`, plus rate and tax.
- A line whose decision is not `RESOLVED` (no record, stale, a gate re-closed after publication) is **blocked** with a plain message ("This item can't be bought right now. We're checking its tax details. Please remove it or try again later."). `blockedReason` carries the text. Checkout does not default, estimate or drop the line silently.
- The chosen `TaxCalculator` computes the amount: `RULES` (default) from the jurisdiction resolver and the outcome; `STRIPE_TAX` / `AVALARA` send the **provider code mapped to the line's internal code**. An unmapped or unverified code for the active provider blocks the line, never falls back.
- The order line snapshot stores `internalTaxCode`, `providerTaxCode`, `outcome`, `ruleVersion` and `addressPrecision`, so refunds reverse with the same decision.
- Gift cards: the sale of a multipurpose card is its own outcome; redemption lines carry `gift_card_event` and the redeemed item's own decision. (Held until approved.)
- Shadow mode never changes the charged amount.

## 8. Provider neutrality, SHADOW mode and cutover runbook

`tax method` stays a staff setting with history (`PUT /v1/tax/settings`, reason required): `RULES` (default) | `STRIPE_TAX` | `AVALARA`.
New: `shadowProvider` (null, `STRIPE`, `AVALARA`), independent of the method.

**SHADOW:** for each real quote the active method decides the charge; when a shadow provider is set, tax-returns also asks
that provider (through the same `TaxCalculator` port, same lines, mapped provider codes), stores both results in `tax_shadow_quote`
with the line's internal code and provider code, and never uses the shadow figure. `GET /v1/tax/shadow/report` groups
disagreements by internal code and provider code, classified `OUTCOME` (exempt vs taxable), `RATE`, `BOTH`, `PROVIDER_ERROR`
or `NO_DECISION`. A provider failure never affects checkout.

**Why outcome-level mapping and shadow reporting matter:** the same Stripe code carries different outcomes in different
scenarios (for example `txcd_41053001` on four outcomes), and Stripe decides by its own rules for that code. A provider can
therefore return a different outcome than our row. The shadow report surfaces exactly those internal codes so staff can
choose another provider code, ask the provider for a custom/refined code, or keep the in-house engine.

**Cutover runbook (Stripe Tax or Avalara):**
1. Fill the provider column of `provider_code_map` for all 105 internal codes (staff console, one entry per outcome, `verified = false`). Blocked internal codes (unitemised bundle) stay unmapped on purpose.
2. Turn on SHADOW for the provider. Let it run on real orders for at least one full filing month and a minimum number of orders per internal code decided with the tax adviser.
3. Review the shadow report: every OUTCOME difference is resolved (re-map, refine, or accept with the adviser's written note); every RATE difference is explained (ZIP versus exact address is expected, and is the reason to cut over).
4. Mark each mapping `verified = true` with a reason once its code shows no unexplained difference.
5. Dry run: in a lower environment set `method` to the provider and re-run the golden scenarios (`scenarios.json`) through the provider adapter; all 105 must agree on outcome.
6. Switch `method` (reason recorded). Keep SHADOW on with the in-house engine as the shadow for one more month. Roll back by switching `method` back to `RULES` (it applies to new quotes only; placed orders keep what they were charged).
7. Optionally switch the jurisdiction resolver to the provider's for the in-house engine (closes the TX-03 limitation without changing the method).
8. The tax professional signs off the cutover; the signed note is attached to the settings history entry.

## 9. Roles, workflow, audit

| Step | Who | Where |
|---|---|---|
| Create draft, category, facts, evidence | Seller (vendor) | Seller Center, "Your listing" |
| Tax review: correct category, completed facts, evidence attached, refined code if required | Staff with `tax:classify-admin` (Tax Reviewer, Catalog Manager, Super Admin) | Staff console, tax review queue |
| Tax sign-off for NEEDS_APPROVAL, CONFLICT_OPEN, BLOCKED_BUNDLE and any rule change | Texas tax professional (external), recorded by a Super Admin as `approvedBy` + `decisionRef` | Profile status change |
| Publish | Automatic when READY and moderation passes | catalog |
| Re-check on change | Automatic: STALE record, new review | catalog event, tax-returns |

New permissions: `tax:classify` (resolve, service clients), `tax:classify-admin` (approve, reclassify, change profile status, edit code map). `tax:read` already exists. See [authorisation.md](./authorisation.md) for how roles are granted.

Audit: every classification action (facts changed, submitted, approved, rejected, reclassified, marked stale, evidence added) is
an append-only row with actor, time, reason, `ruleVersion`, before and after. Profile status and code-map changes are audited the
same way and publish `tax.classification.changed`. The reviewer's decision stores who classified, which codes and conditions were used, and when.

## 10. Data model and reserved migrations

(Migration numbers read from each service's folder on 2026-11-09; the next free number is reserved here. Re-check before creating the file.)

| Service | Migration | Content |
|---|---|---|
| catalog | `V18__category_tax_tree.sql` | Delete the seed tree (categories, product types, schemas, size charts links) and data; `category.tax_profile_id`; product-type schema attribute kinds extended (BOOLEAN, DECIMAL, INTEGER, PERCENT, FILE), `unit`, `owner`, `show_when`, `required_when`, `evidence`. |
| catalog | `V19__variant_tax_facts.sql` | `variant.tax_facts jsonb`, `variant.seller_sku` nullable generation (`seller_sku_generated boolean`), `variant.tax_status`, `variant.missing_tax_facts jsonb`, `variant_tax_evidence` table, `product.tax_status`; `product.listing` keeps `sellerBehaviour`. |
| catalog | `V20__seed_category_tax_taxonomy.sql` | Generated from the dataset: 13 departments, 73 categories, product types (fashion sized types with the `sizing` trait, size-chart seed re-applied). |
| tax-returns | `V10__category_tax_profiles.sql` | `tax_profile`, `tax_fact_definition`, `tax_profile_fact`, `tax_decision_row`, `provider_code_map`, `tax_profile_audit`; `tax_settings.shadow_provider`; settings method check extended with AVALARA. |
| tax-returns | `V11__variant_classification.sql` | `variant_classification`, `variant_classification_audit`, `variant_classification_evidence`, `listing_tax_decision`, `tax_shadow_quote`; `tax_ledger_entry` gains `internal_tax_code`, `outcome`, `rule_version`, `address_precision`. |
| tax-returns | `V12__seed_category_tax_profiles.sql` | Generated from the dataset by `tools/taxonomy/gen_seed_sql.py` (to be written by the tax-returns owner): profiles, facts, rows, STRIPE codes; replaces the old `tax_code_map` / classification rules. Existing classification data deleted. |
| seller | `V89__seller_is_bakery.sql` | Seller profile flag `is_bakery` (feeds `seller_is_bakery`). |
| orders | `V10__order_line_tax_decision.sql` | Order line snapshot: `internal_tax_code`, `provider_tax_code`, `tax_outcome`, `tax_rule_version`, `tax_address_precision`; line status for blocked tax. |

Offers need no migration: behaviour facts live with the catalog listing; offers-service keeps price and stock.
Go services (cart, gateway-bff) have no migrations; they change request/response handling.

### Loading the golden fixtures from Java

`tools/taxonomy/category_tax_map.json` and `scenarios.json` are the contract. In `java/services/tax-returns/pom.xml` add a test
resource entry pointing at `${project.basedir}/../../../tools/taxonomy` that includes only those two files (copied to
`taxonomy/` on the test classpath), so no copy is committed. The golden test `CategoryTaxGoldenTest` reads `taxonomy/scenarios.json`,
for each scenario calls the engine with `inputs.facts`, `inputs.sellerBehaviour`, `inputs.context` for `categoryCode`, and
asserts `outcome`, `internalTaxCode`, `providerCodes.STRIPE` (`expect.stripeCode`) and status (`expect.resolveStatus`). A second test
loads the profile seed and asserts the same 105 rows exist in the database and that, for every category, exactly one row
matches every combination of the categories' facts (as `validate.py` does). The production seed (V12) is generated from the same JSON, and CI runs
`make taxonomy-check` so the JSON cannot drift from the generator. Catalog's form builder and the console read
fact definitions from the tax-returns API at runtime, not from the file.

## 11. Phased build plan and path ownership

Phase A (this change) is done. After it, phases B, C, D and E run in parallel against the contracts; each agent owns its
paths and does not edit the others'. Contract changes after Phase A are additive and go through the Phase A owner (`contracts/**`).

| Phase | Owner builds | Owns (exclusive) | Depends on |
|---|---|---|---|
| **B. Catalog taxonomy and forms** | New tree and seed (V18-V20), typed fact attributes in schemas, variant `taxFacts`, optional/generated `sellerSku`, bulk upload matching, readiness and publish gate (calls resolve), evidence upload, `catalog.product.tax-facts-changed`; Seller Center product form (fact questions, "Price & stock" / "Your listing" wording, preview) and category pages in staff console; search/indexer category paths and facets | `java/services/catalog/**`, `web/apps/seller-center/**`, `web/apps/staff-console/src/**/catalog/**`, `tools/seed/catalog_*.py`, `docs/engineering/taxonomy.md`, `docs/engineering/size-charts.md`, `go/services/indexer/**`, `go/services/search/**`, `web/packages/api-client/src/generated/catalog.ts` | Contract only. Stub resolve with a fake client built from `scenarios.json` until C lands. |
| **C. Tax-returns engine** | V10-V12, profile and decision-record stores, resolve endpoint, status changes, provider code map, jurisdiction-resolver port + ZIP adapter, outcome-aware `RulesTaxCalculator`, SHADOW mode, golden tests, `tax.classification.changed`, consumers of `catalog.product.tax-facts-changed`, `tools/taxonomy/gen_seed_sql.py` | `java/services/tax-returns/**`, `tools/taxonomy/gen_seed_sql.py`, `docs/engineering/tax-classification-design.md`, `docs/engineering/texas-sales-tax-rates.md`, `infra/**/tax-returns*` | Contract only. |
| **D. Staff tools** | Console pages: tax profiles (list, decision table, status change), provider code map editor, tax review queue (approve, reclassify), variant classification record with evidence and audit, shadow report, settings (method + shadow provider); MCP staff tools (read-only) | `web/apps/staff-console/src/**/tax/**`, `web/apps/staff-console/src/**/fees/**` (tax classification screens only), `java/services/mcp-server/**` tax tools, `go/services/gateway-bff/api/openapi-staff.yaml` and its staff routes, `web/packages/api-client/src/generated/tax-returns.ts` (add `tax-returns.yaml` to `SPECS`) | Contract only; live data after C. |
| **E. Checkout integration** | Quote lines read the stored decision (`atin`, `sellerOrgId`), blocked-line message, order line snapshot (orders V10), cart/BFF mapping of `decisionStatus` and `blockedReason`, storefront and mobile checkout messages, refunds reuse the snapshot | `java/services/orders/**`, `go/services/cart/**`, `go/services/gateway-bff/**` except `openapi-staff.yaml`, `web/apps/storefront/**` checkout, `mobile/apps/customer/**` checkout | Contract only; end-to-end after B and C. |

Shared rules: `contracts/**` and `tools/taxonomy/**` are Phase A owner's; `docs/engineering/category-tax-design.md` is edited only by
the Phase A owner (others propose changes in their PR descriptions). Generated clients: each agent regenerates only the file it owns.
Backlog changes are proposed in PR text, not edited (backlog.csv is a single file).

Order within the build: B and C can start at once; D and E start at once with mocked responses and finish when C is deployed in
dev; integration test (all four) once B, C and E are on `develop`; then SHADOW in dev against Stripe test mode.

## 12. What stays blocked until decisions and approvals

| Blocked item | Until |
|---|---|
| Flavored water and soda; tutoring and consulting (CONFLICT_OPEN) | Open decisions 1 and 2 are made and the dataset updated. |
| Digital products (e-books, audiobooks, online courses, digital art), photography and media, event tickets, gift cards (NEEDS_APPROVAL) | Open decision 4: named approver per subcategory and written sign-off. |
| Generic-code categories (REFINE_CODE): cleaning and paper, kitchenware, furniture and small appliances, toys and games, pet grooming, handmade | Open decision 3: owner and deadline; per-variant verified codes. |
| Gift baskets and bundles (BLOCKED_BUNDLE) | Component itemisation built and a tax-approved bundle rule. |
| Mixed-basket delivery attribution (TX-07) | Open decision 5 and the final delivery price. |
| Tax holiday exemptions (car seats) | Calendar and value limits from the tax adviser; until then always taxed as ordinary sales. |
| Platform commission, platform services, delivery fee classification (TX-12) | Tax professional's fee classification. |
| Exact-address local rates (TX-03) | A provider (or the Comptroller locator) computing the rate; the ZIP limitation is documented. |
| Every production launch | **Texas tax professional sign-off** on this design, the dataset (`ruleVersion`), the interpretations in section 2 and the seller wording (terms in [tax-classification-design.md](./tax-classification-design.md), needs legal review). |

Verification of the whole build, per category and decision row (publish gate, resolve, Texas quote, order snapshot), the defects it found and the human checklist: [category-tax-verification.md](./category-tax-verification.md) (`make category-tax-verify`).
