-- S216: hide / restore of a seller's catalog is processed in pages (default 500 products per transaction) instead of
-- loading every product in one transaction. One run per enforcement event (event_id = the seller-service event-id),
-- with a keyset cursor on product.id, so a crashed or redelivered run resumes after its last committed page and never
-- republishes a page. A newer decision for the same organisation supersedes a run still in progress.
create table listing_enforcement_run (
    event_id          uuid         primary key,
    org_id            varchar(80)  not null,
    action            varchar(10)  not null,
    reason            varchar(40)  not null,
    status            varchar(12)  not null,
    cursor_product_id uuid,
    processed         integer      not null default 0,
    pages             integer      not null default 0,
    started_at        timestamptz  not null,
    updated_at        timestamptz  not null,
    finished_at       timestamptz,
    version           bigint       not null default 0,
    constraint listing_enforcement_run_action check (action in ('HIDE', 'RESTORE')),
    constraint listing_enforcement_run_status check (status in ('RUNNING', 'DONE', 'SUPERSEDED'))
);
create index listing_enforcement_run_running on listing_enforcement_run (org_id, updated_at) where status = 'RUNNING';

-- Keyset pagination through one organisation's products.
create index product_owner_id on product (owner_org, id);
-- Variants are loaded per page with product_id in (...): without this index every page scanned product_variant.
create index product_variant_product on product_variant (product_id, position);
