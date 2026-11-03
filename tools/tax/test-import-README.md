# Staff CSV import — test datasets

Import via **Staff Console → Fees & commissions → Tax rates → Import tax rates (CSV)**, or `POST /v1/staff/tax-rates/import` with `{ "csv": "<contents>" }`.

| File | Scope |
|------|--------|
| [`test-import-texas.csv`](./test-import-texas.csv) | Texas state rule + local ZIP prefixes for major metros (matches [`V4__texas_comptroller_tax_rates.sql`](../../java/services/tax-returns/src/main/resources/db/migration/V4__texas_comptroller_tax_rates.sql), plus Arlington, El Paso, Corpus Christi, Lubbock, McAllen, Waco, Amarillo; Fort Worth local **2.5%** from Comptroller 2025 Q4 `taxrates254.txt`) |
| [`test-import-pennsylvania.csv`](./test-import-pennsylvania.csv) | Pennsylvania state rule + **all PA local sales taxes** (Philadelphia 191* +2%, Allegheny 152* / 151* +1%; every other ZIP is state 6% only) |
| [`us-rates-template.csv`](./us-rates-template.csv) | Minimal two-row template |

Not tax advice. Reconcile against [Texas Comptroller](https://comptroller.texas.gov/taxes/sales/index.php) and [PA Revenue](https://www.revenue.pa.gov/Taxes/Sales-Use-Tax/Pages/default.aspx) before production use.

## Sanity checks after import

**Texas**

- 77019 + general merchandise → **8.25%** (6.25% + 2% local).
- 78201 + general → **8.0%** (6.25% + 1.75% San Antonio).
- 76102 + general → **8.75%** (6.25% + 2.5% Fort Worth).
- Grocery / `pantry` product type → **$0** merchandise tax (TX exempt; locals do not apply to grocery).

**Pennsylvania**

- 19104 + general → **8%** (6% + 2% Philadelphia).
- 15213 + general → **7%** (6% + 1% Allegheny).
- 17101 Harrisburg + general → **6%** (no local row).
- Grocery / exempt food → **$0** state tax; locals do not apply to exempt grocery.
