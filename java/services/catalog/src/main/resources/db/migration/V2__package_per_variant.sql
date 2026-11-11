-- Listing rules (docs/engineering/listing-rules.md D8): the package (size and weight of the box as shipped) is kept
-- per variant. The listing-level package in product.listing -> 'packaging' stays as the default for variants without
-- their own and is the back-fill for variants saved before this migration.
ALTER TABLE product_variant
    ADD COLUMN package_length_in double precision,
    ADD COLUMN package_width_in double precision,
    ADD COLUMN package_height_in double precision,
    ADD COLUMN package_weight_lb double precision;

UPDATE product_variant v
SET package_length_in = (p.listing -> 'packaging' ->> 'lengthIn')::double precision,
    package_width_in = (p.listing -> 'packaging' ->> 'widthIn')::double precision,
    package_height_in = (p.listing -> 'packaging' ->> 'heightIn')::double precision,
    package_weight_lb = (p.listing -> 'packaging' ->> 'weightLb')::double precision
FROM product p
WHERE p.id = v.product_id
  AND jsonb_typeof(p.listing -> 'packaging') = 'object'
  AND (p.listing -> 'packaging' ->> 'lengthIn') IS NOT NULL
  AND (p.listing -> 'packaging' ->> 'widthIn') IS NOT NULL
  AND (p.listing -> 'packaging' ->> 'heightIn') IS NOT NULL
  AND (p.listing -> 'packaging' ->> 'weightLb') IS NOT NULL
  AND (p.listing -> 'packaging' ->> 'lengthIn')::double precision BETWEEN 0.0001 AND 108
  AND (p.listing -> 'packaging' ->> 'widthIn')::double precision BETWEEN 0.0001 AND 108
  AND (p.listing -> 'packaging' ->> 'heightIn')::double precision BETWEEN 0.0001 AND 108
  AND (p.listing -> 'packaging' ->> 'weightLb')::double precision BETWEEN 0.0001 AND 150;

-- All four together, or none (an item that ships nothing, or a listing saved before packages were per variant).
ALTER TABLE product_variant
    ADD CONSTRAINT product_variant_package_check CHECK (
        (package_length_in IS NULL AND package_width_in IS NULL AND package_height_in IS NULL
            AND package_weight_lb IS NULL)
        OR (package_length_in > 0 AND package_length_in <= 108 AND package_width_in > 0 AND package_width_in <= 108
            AND package_height_in > 0 AND package_height_in <= 108 AND package_weight_lb > 0
            AND package_weight_lb <= 150));
