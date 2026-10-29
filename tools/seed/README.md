# tools/seed: local demo data and test users

`make seed` (`python3 tools/seed/seed.py`, Python 3 standard library only) fills a local (or dev) stack through the
services' public APIs: sellers and providers, ~75 products with real photos, offers, stock, home content, sponsored
products, pre-order windows, the providers' services, the operations data (below), and a check of the test users. Every
step is idempotent, so re-run it any time; `make up` runs what is missing by itself (everything on an empty catalogue,
else the operations data). Full guide: [docs/engineering/local-development.md](../../docs/engineering/local-development.md).

```
python3 tools/seed/seed.py                      # every step
python3 tools/seed/seed.py --only ops           # just the operations data (make seed ARGS="--only ops")
python3 tools/seed/seed.py --only users         # just check the test users
python3 tools/seed/seed.py --skip products      # everything else
make seed-test                                  # the seeder's unit tests
```

Steps: `sellers`, `products`, `content`, `ads`, `preorders`, `pickups` (AfroTransact's customer pickup points in
carrier-gateway, CARRIER_GATEWAY_URL), `bookings`, `ops`, `users`. The datasets are
`seedlib/dataset.py` and `seedlib/ops_dataset.py`; photo sources, attribution and service URLs are described in
`seed.py` and the guide.

## The category tree is not seeded here

The department > category > product type tree and each product type's details are the catalog service's initial
data (`V2__seed_taxonomy.sql`, generated once by `catalog_taxonomy.py`). They are managed by staff in the console
(Catalog > Categories) from then on, so this seeder only reads the tree to place its demo products and never edits it.
See [docs/engineering/taxonomy.md](../../docs/engineering/taxonomy.md).

## Operations data (`ops`)

Needs the catalogue (the `products` step) and, besides the catalogue services, retail, orders, payments, fulfilment,
cart, geo, seller, tax-returns and trust-ops (`make up` starts them all). It goes through the APIs the apps use: the
test customers check out with their own tokens, Mama Put's owner packs as the seller and the driver drives with the
driver's token (locally the services act as the customer, driver or organisation member whose token a call carries;
staff work runs without a token, as the local super admin). Payments are authorised by the payments service's local
test stand-in for Stripe (only allowed on the local profile), or by Stripe test mode when `.local/secrets.env` has a key.

| Screen | What `ops` puts there |
|---|---|
| Staff › AfroTransact Retail | 4 suppliers (Lagos, Kumasi, Addis Ababa, Kingston; one on watch), 8 items linked to AfroTransact Brand ATINs with costs and case packs, purchase orders in every state (draft, pending approval in the Finance tier, approved, sent and in the air, confirmed and at the dock, partially received, received with a matched invoice, received with a 4 % price exception, closed with the supplier payment released, cancelled), their shipments, customs documents and goods receipts |
| Staff › Inventory | FBA inbound shipments of Mama Put, Lagos Larder and Accra Market in every stage (draft, shipped in transit, arrived awaiting receiving, receiving, closed) with lot, made-on and expiry dates; short-dated lots released for markdown (31–60 days), a QC hold, damaged units, a lot that expires tomorrow (EXPIRED after the nightly run; receiving refuses expired lots), a cycle count waiting for review and a posted one, a transfer HOU-FC → ATL-FC |
| Staff › Orders & shipments | 14 orders of `customer.houston` and `customer.lubbock`: placed, picking, packed, shipped with UPS tracking, out for delivery, delivered (carrier and AfroTransact Delivery), cancelled, a Lubbock pre-order, a two-package order (AfroTransact + Bonwire Weavers), seller-fulfilled orders (Diaspora Books waiting, Mama Put's kitchen packed for pickup). Seeded orders carry the attribution `src=demo-seed` |
| Staff › Delivery dispatch | Houston FC as the depot and a Houston delivery zone; a completed returns run, today's route in progress for `driver@afrotransact.test` (one stop delivered with a photo, one out for delivery, an open SOS), tomorrow's route blocked by a vehicle defect, Mama Put's seller pickup waiting for a route |
| Staff › Disputes & returns | a driver-pickup return collected, inspected and refunded; two drop-off returns approved (their carrier labels need carrier-gateway with carrier sandbox credentials, not in the local stack); an A-to-z claim waiting for Tamale Naturals |
| Staff › Payouts | Mama Put Pantry's payout request: its sale's settlement hold is started as matured and a test Connect account (`acct_1LocalTest…`) is registered, on the local stand-in only. The settlement job runs every minute locally; the step waits up to two minutes for the balance |
| Seller Center (seller.owner) | Mama Put's orders (FBA and its own), inbound shipments, lots and expiry |
| Storefront (the two customers) | Your orders, with tracking, returns and the claim |

Every record is found by a stable key before anything is created (suppliers by code, items by ATIN, purchase orders by
supplier and lines, inbound shipments by name, orders by attribution, routes by name), so a re-run creates nothing; a
scenario an interrupted run left half-way is taken on to its target state.

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
