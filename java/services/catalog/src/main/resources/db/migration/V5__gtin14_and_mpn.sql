-- S381: one match key per barcode, enforced by the database.
--
-- UPC-A (12 digits), EAN-13 and GTIN-14 of one item are the same product, so every variant gets gtin14 (the barcode
-- left-padded with zeros to 14 digits) and the database allows only one CLAIM per gtin14. A claim exists for a variant that
-- is not exempt and whose product is not REJECTED or MERGED (a rejected or merged page gives its barcode back).
--
-- This migration never deletes or changes a product. If two claiming variants already share a gtin14 it STOPS with an
-- error that lists them (the whole migration rolls back); staff merge or fix those pages first. The same list is produced
-- by tools/catalog/gtin-duplicates.sql, which can be run before deploying.

ALTER TABLE product_variant ADD COLUMN gtin14 character(14);
ALTER TABLE product_variant ADD COLUMN gtin_claim character(14);

UPDATE product_variant SET gtin14 = lpad(gtin, 14, '0') WHERE gtin IS NOT NULL AND gtin <> '';

DO $$
DECLARE
    dup text;
BEGIN
    SELECT string_agg(d.gtin14 || ' used by ' || d.atins, '; ' ORDER BY d.gtin14)
      INTO dup
      FROM (SELECT v.gtin14,
                   string_agg(v.atin || ' (page ' || p.atin || ', seller ' || p.owner_org || ')', ', ' ORDER BY v.atin) AS atins
              FROM product_variant v
              JOIN product p ON p.id = v.product_id
             WHERE v.gtin14 IS NOT NULL AND NOT v.gtin_exempt AND p.status NOT IN ('REJECTED', 'MERGED')
             GROUP BY v.gtin14
            HAVING count(*) > 1
             ORDER BY v.gtin14
             LIMIT 100) d;
    IF dup IS NOT NULL THEN
        RAISE EXCEPTION 'V5 stopped, nothing was changed or deleted. Variants share a barcode (GTIN-14); merge or correct these pages and run the migration again (the full list: tools/catalog/gtin-duplicates.sql): %', dup;
    END IF;
END
$$;

UPDATE product_variant v
   SET gtin_claim = v.gtin14
  FROM product p
 WHERE p.id = v.product_id AND v.gtin14 IS NOT NULL AND NOT v.gtin_exempt AND p.status NOT IN ('REJECTED', 'MERGED');

CREATE UNIQUE INDEX product_variant_gtin_claim ON product_variant (gtin_claim) WHERE gtin_claim IS NOT NULL;
CREATE INDEX product_variant_gtin14 ON product_variant (gtin14) WHERE gtin14 IS NOT NULL;

-- The keys follow gtin, gtin_exempt and the product's status without any application code having to remember them.
CREATE FUNCTION product_variant_gtin_keys() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    NEW.gtin14 := CASE WHEN NEW.gtin IS NULL OR NEW.gtin = '' THEN NULL ELSE lpad(NEW.gtin, 14, '0') END;
    IF NEW.gtin_exempt OR NEW.gtin14 IS NULL OR EXISTS (
            SELECT 1 FROM product p WHERE p.id = NEW.product_id AND p.status IN ('REJECTED', 'MERGED')) THEN
        NEW.gtin_claim := NULL;
    ELSE
        NEW.gtin_claim := NEW.gtin14;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER product_variant_gtin_keys
    BEFORE INSERT OR UPDATE OF gtin, gtin_exempt, product_id ON product_variant
    FOR EACH ROW EXECUTE FUNCTION product_variant_gtin_keys();

-- A page that is rejected or merged gives its barcodes back; a rejected page that is resubmitted claims them again
-- (and fails with a unique violation if somebody else took them meanwhile, which the writer reports as catalog.gtin-exists).
CREATE FUNCTION product_gtin_claims_follow_status() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.status IN ('REJECTED', 'MERGED') AND OLD.status NOT IN ('REJECTED', 'MERGED') THEN
        UPDATE product_variant SET gtin_claim = NULL WHERE product_id = NEW.id AND gtin_claim IS NOT NULL;
    ELSIF OLD.status IN ('REJECTED', 'MERGED') AND NEW.status NOT IN ('REJECTED', 'MERGED') THEN
        UPDATE product_variant SET gtin_claim = gtin14
         WHERE product_id = NEW.id AND gtin14 IS NOT NULL AND NOT gtin_exempt;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER product_gtin_claims_follow_status
    AFTER UPDATE OF status ON product
    FOR EACH ROW EXECUTE FUNCTION product_gtin_claims_follow_status();

-- Brand plus manufacturer part number: the key for items with no barcode. The part number lives in a product attribute
-- (default key "model", see at.catalog.policy / catalog_setting.mpnAttributeKey); only letters and digits count.
CREATE FUNCTION mpn_key(value text) RETURNS text
    LANGUAGE sql IMMUTABLE
    AS $$
    SELECT nullif(regexp_replace(upper(value), '[^A-Z0-9]', '', 'g'), '')
$$;

CREATE INDEX product_brand_mpn ON product ((attributes ->> 'brand_id'), (mpn_key(attributes ->> 'model')))
    WHERE (attributes ->> 'brand_id') IS NOT NULL AND mpn_key(attributes ->> 'model') IS NOT NULL;
