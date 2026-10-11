# tools/seed: local demo data and test users

`make seed` (`python3 tools/seed/seed.py`, Python 3 standard library only) fills a local (or dev) stack through the
services' public APIs: sellers and providers, ~75 products with real photos, offers, stock, home content, sponsored
products, pre-order windows, the providers' services, and a check of the test users. Every step is idempotent, so
re-run it any time; `make up` runs it by itself when the catalogue is empty. Full guide:
[docs/engineering/local-development.md](../../docs/engineering/local-development.md).

```
python3 tools/seed/seed.py                      # every step
python3 tools/seed/seed.py --only users         # just check the test users
python3 tools/seed/seed.py --skip products      # everything else
make seed-test                                  # the seeder's unit tests
```

Steps: `sellers`, `products`, `content`, `ads`, `preorders`, `bookings`, `users`. The dataset is
`seedlib/dataset.py`; photo sources, attribution and service URLs are described in `seed.py` and the guide.

## Test users

Identity's local profile creates these at start-up (`TestPersonas.java`); `make seed` creates the organisations the
seller, provider and retail personas belong to (identity links them as they arrive), and its `users` step signs every
one in to prove it. Password for all of them: **`Test1234!Local`**.

| Email | Persona | App |
|---|---|---|
| `customer.houston@afrotransact.test` | Customer, Houston 77042 address | Storefront |
| `customer.lubbock@afrotransact.test` | Customer, Lubbock 79401 address (open pre-order) | Storefront |
| `seller.owner@afrotransact.test` | Owner, Mama Put Pantry | Seller Center |
| `seller.staff@afrotransact.test` | Catalog manager, Mama Put Pantry | Seller Center |
| `provider.owner@afrotransact.test` | Owner, Aminata's Braids | Seller Center (Provider Hub) |
| `retail.operator@afrotransact.test` | Operator, AfroTransact Retail (1P) | Seller Center |
| `staff.admin@afrotransact.test` | Super Admin | Staff console |
| `staff.ops@afrotransact.test` | Operations Manager | Staff console |
| `staff.trust@afrotransact.test` | Trust & Safety | Staff console |
| `staff.finance@afrotransact.test` | Finance | Staff console |
| `staff.support@afrotransact.test` | Customer Support | Staff console |
| `staff.marketing@afrotransact.test` | Marketing (content, ads) | Staff console |
| `staff.catalog@afrotransact.test` | Catalog Manager | Staff console |
| `staff.retail@afrotransact.test` | Retail Buyer (1P) | Staff console |
| `staff.fulfilment@afrotransact.test` | Fulfilment Ops | Staff console |
| `staff.inventory@afrotransact.test` | Inventory Manager | Staff console |
| `staff.dispatch@afrotransact.test` | Dispatcher (delivery runs) | Staff console |
| `staff.product@afrotransact.test` | Product Owner | Staff console |
| `staff.developer@afrotransact.test` | Developer | Staff console |
| `staff.auditor@afrotransact.test` | Auditor (read-only) | Staff console |
| `driver@afrotransact.test` | Driver | Driver app |

Customers, sellers, providers and the driver sign in with email and password only. Staff keep their mandatory two-step
code: it is a text message, which identity delivers to Mailpit locally (http://localhost:8025, subject
`[SMS to +1713555…] AfroTransact code …`). The list lives in `seedlib/dataset.py` (`TEST_USERS`); `make seed-test`
checks it names the same accounts as identity, `tools/dev/local.sh` and the guide. None of this exists on uat or prod:
identity refuses to start there if it is configured.
