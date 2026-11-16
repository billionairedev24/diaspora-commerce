# Catalog import proof of concept (throwaway spike)

Companion to [docs/engineering/catalog-import-spike.md](../../../docs/engineering/catalog-import-spike.md) and
[ADR 0012](../../../docs/adr/0012-seller-catalog-import.md) (proposed). **Not production code**: standard library Python,
nothing is wired into any service, nothing here opens a network connection, and every payload is a local fixture I wrote
from the platforms' documented shapes (not captured from any real shop). Names in the fixtures are invented.

```
python3 tools/spikes/catalog-import/catalog_import_poc.py      # report for every fixture
python3 tools/spikes/catalog-import/tests/test_poc.py          # 25 tests
```

| File | What it shows |
|---|---|
| `catalog_import_poc.py` | Source payload to one canonical product (Shopify `products.json`, Shopify GraphQL bulk JSONL with `__parentId`, WooCommerce Store API in minor units, schema.org JSON-LD `ProductGroup`, a spreadsheet row); deterministic category mapping onto OUR taxonomy read from `tools/listing-rules/rules.json`; the draft gate report (missing fields per kind, tax decided by the platform, icon images refused, small main image, sold out, services/gift cards skipped); the static half of the SSRF guard |
| `detect.py` | The platform fingerprint step of the detection ladder and the plug-in registry (a new platform is one class, no core change). Classifies an already-fetched response only |
| `fixtures/` | `shopify_products.json`, `shopify_bulk.jsonl`, `woo_store_products.json`, `jsonld_page.html`, `sheet.csv` |
| `tests/test_poc.py` | Normalisation, mapping, gate results (which fields were missing), SSRF refusals, detection, plug-in registration |

## What the run tells us (fixtures are small, so read it as shape, not statistics)

* Every beauty product ends `NEEDS_SELLER`: ingredients, label photo, dangerous goods, package size and shelf life are required by
  the listing rules for that kind and **no platform feed carries them**. "Publishable without edits" will be near zero for
  regulated kinds from web data alone; the review queue must be built around "fix only what is missing", not around "approve".
* Barcodes are often absent or invalid; the draft keeps no invalid GTIN (the check digit is the same rule as catalog's `Gtin`).
* Category mapping is deterministic for the common cases and says `AI_OR_PERSON` for the rest; the AI step is not implemented here.
* `Default Title` variants, minor-unit prices, `compare_at_price` and parent/child JSONL lines are the normalisation traps.
