-- S307 one-step product form: product-type traits and attribute metadata (kind, units, form section, help),
-- listing details (description, condition, keywords, fulfilment, package, handling time, ship-from), variant
-- compare-at price and images, and server-side drafts with autosave.
-- S308 bulk upload: uploads with their validated rows; the import job is resumable and idempotent per row
-- (key = upload id + row SKU).

alter table product_type add column traits jsonb not null default '{}'::jsonb;

update product_type set traits = '{"food": true, "preorder": true}'::jsonb where code = 'grocery';
update product_type set traits = '{"food": true, "perishable": true, "regulated": true, "preorder": true}'::jsonb
    where code = 'perishable';
update product_type set traits = '{"regulated": true}'::jsonb where code in ('kids', 'beauty');

create temporary table attr_meta (type_code varchar(40), key varchar(40), meta jsonb) on commit drop;
insert into attr_meta (type_code, key, meta) values
    ('grocery', 'ingredients', '{"section": "FOOD"}'),
    ('grocery', 'allergens', '{"section": "FOOD"}'),
    ('grocery', 'shelf', '{"label": "Shelf life", "kind": "NUMBER", "units": ["days", "months"], "section": "FOOD", "help": "From the date of manufacture, unopened"}'),
    ('grocery', 'storage', '{"section": "FOOD"}'),
    ('grocery', 'cert', '{"section": "FOOD"}'),
    ('perishable', 'ingredients', '{"section": "FOOD"}'),
    ('perishable', 'allergens', '{"section": "FOOD"}'),
    ('perishable', 'temp', '{"section": "FOOD", "help": "How the product must be kept until it reaches the shopper"}'),
    ('perishable', 'permit', '{"section": "REGULATED", "help": "FDA food facility registration or state permit number"}'),
    ('kids', 'safety', '{"section": "REGULATED", "help": "Children''s Product Certificate (CPSIA) reference"}'),
    ('beauty', 'mocra', '{"section": "REGULATED", "help": "FDA Cosmetics Direct product listing number (MoCRA)"}'),
    ('beauty', 'warn', '{"kind": "MULTILINE"}'),
    ('hair', 'packs', '{"kind": "NUMBER"}'),
    ('book', 'pages', '{"kind": "NUMBER"}'),
    ('home', 'dims', '{"help": "e.g. 18 × 18 in"}');

update product_type p set attributes = (
    select jsonb_agg(e.a || coalesce(m.meta, '{}'::jsonb) order by e.ord)
    from jsonb_array_elements(p.attributes) with ordinality as e(a, ord)
    left join attr_meta m on m.type_code = p.code and m.key = e.a ->> 'key')
where p.code in (select distinct type_code from attr_meta);

-- Frozen and chilled food also states its shelf life (optional, so existing products stay valid).
update product_type set attributes = attributes || '[{"key": "shelf", "label": "Shelf life", "options": [], "required": false, "multiline": false, "kind": "NUMBER", "units": ["days", "months"], "section": "FOOD", "help": "Frozen: from the date of freezing"}]'::jsonb
    where code = 'perishable';

alter table product add column listing jsonb not null default '{}'::jsonb;
alter table product_variant
    add column compare_at_minor bigint check (compare_at_minor is null or compare_at_minor > 0),
    add column images           jsonb  not null default '[]'::jsonb;

create table product_draft (
    id            uuid primary key,
    owner_org     varchar(80)  not null,
    created_by    varchar(120) not null,
    product_id    uuid references product (id) on delete cascade,
    title         varchar(200) not null default '',
    category_path varchar(400),
    payload       jsonb        not null,
    created_at    timestamptz  not null,
    updated_at    timestamptz  not null,
    version       bigint       not null default 0
);
create index product_draft_owner on product_draft (owner_org, updated_at desc);
create unique index product_draft_product on product_draft (product_id) where product_id is not null;

create table product_import (
    id               uuid primary key,
    reference        varchar(20)  not null unique,
    owner_kind       varchar(10)  not null,
    owner_org        varchar(80)  not null,
    uploaded_by      varchar(120) not null,
    uploaded_by_name varchar(200),
    file_name        varchar(200) not null,
    file_format      varchar(10)  not null check (file_format in ('XLSX', 'CSV')),
    mode             varchar(10)  not null check (mode in ('CREATE', 'UPDATE')),
    product_type     varchar(40)  references product_type (code),
    header           jsonb        not null,
    file_warnings    jsonb        not null default '[]'::jsonb,
    status           varchar(30)  not null,
    valid_only       boolean      not null default false,
    total_rows       int          not null,
    valid_rows       int          not null,
    warning_rows     int          not null,
    error_rows       int          not null,
    product_count    int          not null,
    processed_rows   int          not null default 0,
    created_products int          not null default 0,
    updated_products int          not null default 0,
    failed_rows      int          not null default 0,
    notify           boolean      not null default false,
    created_at       timestamptz  not null,
    started_at       timestamptz,
    finished_at      timestamptz,
    heartbeat_at     timestamptz,
    version          bigint       not null default 0
);
create sequence if not exists product_import_seq start with 1;
create index product_import_owner on product_import (owner_org, created_at desc);
create index product_import_running on product_import (status, heartbeat_at) where status in ('QUEUED', 'RUNNING');

create table product_import_row (
    import_id   uuid         not null references product_import (id) on delete cascade,
    row_number  int          not null,
    group_key   varchar(60)  not null,
    sku         varchar(60)  not null,
    status      varchar(10)  not null check (status in ('VALID', 'WARNING', 'ERROR')),
    messages    jsonb        not null,
    cells       jsonb        not null,
    outcome     varchar(10)  not null default 'PENDING'
        check (outcome in ('PENDING', 'CREATED', 'UPDATED', 'FAILED', 'SKIPPED')),
    outcome_note text,
    product_id  uuid,
    primary key (import_id, row_number)
);
-- The idempotency key of a row: one product variant per upload and SKU, whatever happens on retries.
create unique index product_import_row_sku on product_import_row (import_id, sku) where sku <> '' and status <> 'ERROR';
create index product_import_row_group on product_import_row (import_id, group_key);
