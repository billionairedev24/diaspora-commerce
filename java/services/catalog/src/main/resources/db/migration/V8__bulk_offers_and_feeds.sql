-- S393, S394: bulk upload that sells existing pages (OFFER), the price and quantity feed (PRICE_QTY), the file hash that
-- recognises the same file uploaded again, the offer outcomes and the audit rows. Nothing here creates, publishes or
-- releases a product or an offer: offers are created by offers-service (which asks the tax gate) and an upload row never
-- sets a status. Existing uploads and rows keep their values.

ALTER TABLE product_import DROP CONSTRAINT product_import_mode_check;
ALTER TABLE product_import ADD CONSTRAINT product_import_mode_check
    CHECK (mode IN ('CREATE', 'UPDATE', 'OFFER', 'PRICE_QTY'));
ALTER TABLE product_import ADD COLUMN file_sha256 char(64);
ALTER TABLE product_import ADD COLUMN same_file_as uuid;
ALTER TABLE product_import ADD COLUMN offers_created integer NOT NULL DEFAULT 0;
ALTER TABLE product_import ADD COLUMN held_rows integer NOT NULL DEFAULT 0;
CREATE INDEX product_import_org_hash ON product_import (owner_org, mode, file_sha256, created_at DESC)
    WHERE file_sha256 IS NOT NULL;
CREATE INDEX product_import_org_day ON product_import (owner_org, created_at DESC);

ALTER TABLE product_import_row ALTER COLUMN outcome TYPE varchar(20);
ALTER TABLE product_import_row DROP CONSTRAINT product_import_row_outcome_check;
ALTER TABLE product_import_row ADD CONSTRAINT product_import_row_outcome_check
    CHECK (outcome IN ('PENDING', 'CREATED', 'UPDATED', 'OFFER_CREATED', 'HELD', 'FAILED', 'SKIPPED'));
ALTER TABLE product_import_row ADD COLUMN atin varchar(10);
ALTER TABLE product_import_row ADD COLUMN offer_id uuid;
-- Offer rows and feed rows are one row each; the job reads the next pending rows in file order.
CREATE INDEX product_import_row_pending ON product_import_row (import_id, row_number) WHERE outcome = 'PENDING';

-- One audit row per product or offer a bulk upload created, changed or held: who uploaded, which upload, which file row.
CREATE TABLE product_import_audit (
    id         uuid         PRIMARY KEY,
    import_id  uuid         NOT NULL REFERENCES product_import (id) ON DELETE CASCADE,
    row_number integer      NOT NULL,
    action     varchar(20)  NOT NULL CHECK (action IN ('CREATED', 'UPDATED', 'OFFER_CREATED', 'HELD')),
    actor      varchar(120) NOT NULL,
    owner_org  varchar(80)  NOT NULL,
    atin       varchar(10),
    product_id uuid,
    offer_id   uuid,
    at         timestamptz  NOT NULL
);
CREATE INDEX product_import_audit_import ON product_import_audit (import_id, row_number);

-- What a CREATE row looked like when its product was made, so the same file uploaded again is recognised row by row: a row
-- whose SKU is already listed and whose content hash is unchanged is SKIPPED instead of failing as a duplicate SKU.
CREATE TABLE product_import_row_hash (
    owner_org  varchar(80)  NOT NULL,
    sku        varchar(60)  NOT NULL,
    row_hash   char(64)     NOT NULL,
    product_id uuid         NOT NULL,
    import_id  uuid         NOT NULL,
    created_at timestamptz  NOT NULL,
    PRIMARY KEY (owner_org, sku)
);
