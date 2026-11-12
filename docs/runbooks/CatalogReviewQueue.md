# CatalogReviewQueue

**Severity:** `ticket` · **Defined in:** `infra/observability/rules/catalog-review-queue.yaml` · **Design:** [bulk-upload-and-images.md](../engineering/bulk-upload-and-images.md) sections 3.5 to 3.7

## What it means
Sellers cannot change a product page they did not create. They file a **change request** (suggest an edit, wrong match, duplicate page, new size or colour) or, for an item without a barcode in a brand-gated category or of a brand that carries barcodes elsewhere, a **no-barcode exemption**. Staff decide with a reason. The service level is two business days (a setting, **Catalog settings**).

`CatalogReviewQueueOverdue` fires when the oldest request still waiting is older than the **alert limit** (3 business days by default, Monday to Friday, set by staff in Catalog settings) for 30 minutes. It reads `catalog_change_request_alert_breached`, which is 1 in that case. `catalog_change_request_oldest_age_seconds` shows the same age in calendar seconds, and `catalog_change_request_open` how many wait.

`CatalogReviewQueueMetricMissing`: the gauges report nothing (the catalog service is down, or its database read fails).

## Impact
- Sellers see "Waiting" on their request (and, for an exemption, "We are checking that this item has no barcode" with the page off sale). Nothing is lost and nothing is applied before a person decides.
- An exemption that waits keeps a new page out of review, so the seller cannot sell the item.

## Diagnose
1. Staff console, **Catalog > Review queue**. The lists are oldest first: change requests, wrong-match and duplicate reports, no-barcode exemptions, held images. Rows older than the service level are marked **Overdue**.
2. Is anyone with `catalog:moderate` on shift? Most of the time this is a staffing problem, not a fault.
3. A request that cannot be approved is usually waiting for a reason shown on the row when the approval was refused:
   - `catalog.change-request-image-held`: the picture is in the **Held images** list; decide it there first, then approve the request.
   - `catalog.change-request-needs-tax-review`: the change would alter a tax fact or tax profile, or a new variant would join a page that is on sale. Reject with that reason, or approve with "take the page off sale until tax review approves it".
   - `catalog.merge-unmapped-variant`: say which variant of the surviving page each variant of the merged page becomes.
4. Gauge `NaN` or missing: check the catalog pods and the database (`product_change_request`, `gtin_exemption_request`).

## Mitigate
Decide the oldest rows first, or add reviewers. Raising the alert limit in Catalog settings hides the symptom; do it only when the service level itself changed.

## Before deploying `V5__gtin14_and_mpn.sql`: the duplicate barcode check
V5 puts one unique claim on every 14-digit barcode (UPC-A, EAN-13 and GTIN-14 of one item are the same key). It **never deletes or changes a product**: if two live pages already share a barcode it stops with an error that lists them, and the whole migration rolls back. Run the same check first, against the catalog database, read-only:

```bash
psql "$CATALOG_DB_URL" -f tools/catalog/gtin-duplicates.sql
```

No rows: deploy. Rows: each row is a barcode used by two or more variants (`variant ATIN / page ATIN / seller / status`). Fix them before the deploy:
- two pages for one item: staff file or use a **Duplicate page** request and approve it with `MERGE` (offers move to the surviving page, the old ATIN redirects);
- one page has the wrong number: approve a **Wrong match** request with `CORRECT_GTIN` (or reject the stale page);
- rejected and merged pages and no-barcode variants do not count.

Run the query again until it returns no rows. If the migration was started anyway and stopped, nothing changed: fix the pages and start the service again.
