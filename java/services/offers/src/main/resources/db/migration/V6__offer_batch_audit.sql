-- S393, S394: one audit row per offer a bulk upload creates, changes or holds, with the upload (import) id and the file row.
-- Written by the batch endpoints (POST /v1/offers/batch, PUT /v1/offers/prices-and-stock). Nothing here changes an offer.
CREATE TABLE offer_change_audit (
    id           uuid         PRIMARY KEY,
    offer_id     uuid         NOT NULL,
    seller_org   varchar(80)  NOT NULL,
    atin         varchar(10)  NOT NULL,
    action       varchar(20)  NOT NULL CHECK (action IN ('CREATED', 'UPDATED', 'HELD')),
    source       varchar(20)  NOT NULL CHECK (source IN ('IMPORT_OFFER', 'IMPORT_PRICE_QTY')),
    import_id    uuid,
    row_key      varchar(80),
    actor        varchar(120) NOT NULL,
    on_behalf_of varchar(120),
    changes      jsonb        NOT NULL DEFAULT '{}'::jsonb,
    at           timestamptz  NOT NULL
);
CREATE INDEX offer_change_audit_offer ON offer_change_audit (offer_id, at DESC);
CREATE INDEX offer_change_audit_import ON offer_change_audit (import_id) WHERE import_id IS NOT NULL;
