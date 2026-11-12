-- Read-only. Lists product variants that share one barcode once UPC-A, EAN-13 and GTIN-14 are put on the same 14-digit key
-- (S381). Run this against the catalog database BEFORE deploying V5__gtin14_and_mpn.sql: the migration stops, and changes
-- nothing, when this query returns rows. Rejected and merged pages and no-barcode (exempt) variants do not count.
--
--   psql "$CATALOG_DB_URL" -f tools/catalog/gtin-duplicates.sql
--
-- Fix by merging the duplicate pages (staff: Review queue, "Duplicate page", resolution MERGE) or by correcting the wrong
-- barcode on one of them; nothing here deletes anything.
SELECT lpad(v.gtin, 14, '0')                        AS gtin14,
       count(*)                                     AS variants,
       string_agg(v.atin || ' / page ' || p.atin || ' / ' || p.owner_org || ' / ' || p.status, E'\n' ORDER BY v.atin) AS used_by
  FROM product_variant v
  JOIN product p ON p.id = v.product_id
 WHERE v.gtin IS NOT NULL AND v.gtin <> '' AND NOT v.gtin_exempt AND p.status NOT IN ('REJECTED', 'MERGED')
 GROUP BY lpad(v.gtin, 14, '0')
HAVING count(*) > 1
 ORDER BY 1;
