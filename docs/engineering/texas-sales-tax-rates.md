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

## How catalog types become categories

tax-returns resolves category at quote time (`TaxServiceImpl`):

1. Explicit `taxCategory` on the quote line, else  
2. `productType` → `at.taxreturns.tax.category-by-product-type` in `application.yml`

For Texas grocery exemption to work, staples must map to **GROCERY** (e.g. `pantry`, `fresh-produce`). Prepared items must map to **PREPARED_FOOD** or **GENERAL**.  

**Gap to watch:** Texas taxes **soft drinks, candy, and ice** as taxable items even in grocery stores (Pub 96-280). If catalog `productType` is `beverages` mapped to **GROCERY**, quotes will **under-collect** until those types are mapped to **GENERAL** or quoted with an explicit category.

## Local add-on rates (stored in `tax_local_rate`)

We store **local only** (city + special purpose districts + MTA), not the 6.25% state piece. **Combined rate** = state category rate + matching local row (exact ZIP5 beats ZIP3 prefix).

| ZIP prefix | Market | Local add-on | Typical combined (GENERAL) | Comptroller EDI basis (2025 Q4 file) |
|------------|--------|--------------|------------------------------|--------------------------------------|
| 770*, 77019, 77042 | Houston | 2.00% | **8.25%** | Houston 1% + Houston MTA 1% (Harris) |
| 752*, 750*, 751* | Dallas | 2.00% | **8.25%** | Dallas 1% + Dallas MTA 1% |
| 787*, 786* | Austin | 2.00% | **8.25%** | Austin 1% + Austin MTA 1% |
| 761* | Fort Worth | 2.00% | **8.25%** | Fort Worth 1% + Crime Control 0.5% + FW MTA 0.5% |
| 782*, 781* | San Antonio | 1.75% | **8.00%** | San Antonio 1.25% + San Antonio MTA 0.5% (Bexar) |

Official machine-readable updates: [Sales Tax Rate Files](https://comptroller.texas.gov/taxes/file-pay/edi/sales-tax-rates.php) (`taxrates254.txt` and successors).

## Example quote (matches `TaxLedgerIT`)

Ship-to **77019**, lines:

- Dress (`GENERAL`): $64.99 → tax $5.36 (825 × $64.99, half-up per line)  
- Pantry (`GROCERY`): $18.50 → **$0**  
- Prepared meal (`PREPARED_FOOD`): $12.00 → $0.99  

## Refreshing data

1. **One-off / new ZIPs:** Staff API `PUT /v1/tax/rates/local/TX/{zipPrefix}` (`tax:configure`) or a new Flyway migration.  
2. **Bulk from Comptroller:** Run `tools/tax/texas_comptroller_rates.py` (downloads the current quarterly TXT and prints local stacks for major cities — use output to draft SQL).  
3. **After migration:** Restart or migrate tax-returns; local dev: `./mvnw -pl java/services/tax-returns flyway:migrate` (or full stack seed).

## Related

- [shipping-and-tax-data.md](./shipping-and-tax-data.md) — end-to-end tax/shipping data points  
- [checkout-tax-estimate.md](./checkout-tax-estimate.md) — checkout estimate flow  
