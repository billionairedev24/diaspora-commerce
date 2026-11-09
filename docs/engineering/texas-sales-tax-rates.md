# Texas sales tax — categories and rates (Comptroller-sourced)

AfroTransact stores rates in the **tax-returns** PostgreSQL database (`taxreturns`): `tax_state_rule` + `tax_local_rate`. Flyway **`V4__texas_comptroller_tax_rates.sql`** refreshes Texas with Comptroller-aligned numbers; **`V2__seed_tax_rates.sql`** remains the original multi-state seed.

This is engineering reference, not tax advice. Finance should reconcile against the [Texas Comptroller](https://comptroller.texas.gov/taxes/sales/index.php) each quarter.

## Category rates (state portion only)

These are the three columns on `tax_state_rule` for **TX**. They map to `TaxCategory` in quotes (`GENERAL`, `GROCERY`, `PREPARED_FOOD`).

| Category | TX state rate | Authority / notes |
|----------|---------------|-------------------|
| **GENERAL** | **6.25%** (0.0625) | State sales and use tax — [Tax Code §151.051](https://statutes.capitol.texas.gov/?link=TD); Comptroller [Sales and Use Tax](https://comptroller.texas.gov/taxes/sales/index.php) |
| **GROCERY** | **0%** | Unprepared food for home consumption — [Tax Code §151.314](https://statutes.capitol.texas.gov/?link=TD); [Pub 96-280](https://comptroller.texas.gov/taxes/publications/96-280.php) |
| **PREPARED_FOOD** | **6.25%** | Ready-to-eat / prepared food — Comptroller Rule 34 TAC §3.293 (see [Pub 96-280](https://comptroller.texas.gov/taxes/publications/96-280.php)); same state rate as general |

**Local tax on grocery:** When state grocery rate is 0%, local sales tax generally **does not apply** ([Tax Code §321.207](https://statutes.capitol.texas.gov/?link=TD)). Local rows for TX use `applies_to_grocery = false`.

**Shipping:** Texas taxes delivery charges when the underlying sale is taxable; `tax_state_rule.shipping_taxable = true` for TX.

## How a line gets its tax (category tax build)

The three-column category (`GENERAL`, `GROCERY`, `PREPARED_FOOD`) no longer comes from the catalog product type. The **stored decision** of the
variant ATIN says EXEMPT or TAXABLE (see [category-tax-design.md](./category-tax-design.md)); this rate table only supplies the **rate**:

- EXEMPT lines are priced at zero and never reach the calculator, whatever the ZIP says (TX-02).
- TAXABLE lines are taxed at the `GENERAL` rate: the state rate plus the local rate of the ZIP.
- A line with no definite decision (missing facts, not approved, gated profile, ship-to outside Texas) gets no rate at all and comes back blocked.

Soft drinks, candy and ice are decided by their categories' decision rows (flavored water and soda are held as CONFLICT_OPEN until the
owner decides), not by mapping `beverages` to a rate column; the old "gap to watch" about `productType` mappings no longer applies to quotes.
The `GROCERY` and `PREPARED_FOOD` columns and `at.taxreturns.tax.category-by-product-type` are used only by callers that still send an explicit `taxCategory`.

## Jurisdiction resolver and the TX-03 launch limitation

`JurisdictionResolver.resolve(shipTo, saleDate)` finds the state and local rates of an address. The default adapter,
`ZipTableJurisdictionResolver`, reads this table by exact 5-digit ZIP, then by 3-digit prefix, and reports `addressPrecision = ZIP`.

**TX-03 says never to calculate from city or ZIP alone, and one ZIP can span several local jurisdictions.** Until a provider (Stripe Tax or
Avalara) or the Comptroller address locator computes the rate from the exact address (`addressPrecision = ADDRESS`), the ZIP table is used for
the in-house engine, every quote line records `addressPrecision`, and the gap is on the launch checklist for the tax professional. It is a known
limitation, not a silent one. Closing it means another adapter of the same port (cutover runbook step 7), not a change of rules.

The local rate is the table's value; the combined-rate cap of 8.25% (TX-01) is not enforced in code, only by the data (finance reconciles the
table against the Comptroller each quarter).
