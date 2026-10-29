-- S291 taxonomy administration: staff create, rename, move, reorder and retire departments, categories and product
-- types in the console. V2 stays the initial data only; every later change is an audited staff action.

alter table category
    add column code           varchar(120),
    add column version        bigint      not null default 0,
    add column retired_at     timestamptz,
    add column retired_reason text,
    add column updated_at     timestamptz;

-- A stable machine code per node (lower-case words joined by hyphens), derived from the seeded path.
update category set code = left(trim(both '-' from regexp_replace(lower(path), '[^a-z0-9]+', '-', 'g')), 120);
alter table category alter column code set not null;
create unique index category_code on category (code);
create index category_parent on category (parent_id, position);
create index product_category on product (category_id) where category_id is not null;

alter table product_type
    add column version    bigint      not null default 0,
    add column updated_at timestamptz;

-- Who changed the taxonomy, what and why. One row per staff action; details hold the before/after of the change.
create table taxonomy_audit (
    id            uuid primary key,
    occurred_at   timestamptz  not null,
    actor_id      varchar(120) not null,
    action        varchar(40)  not null,
    subject_kind  varchar(20)  not null check (subject_kind in ('NODE', 'PRODUCT_TYPE', 'REQUEST')),
    subject_id    varchar(80)  not null,
    subject_label varchar(400) not null,
    reason        text         not null,
    details       jsonb        not null default '{}'::jsonb
);
create index taxonomy_audit_subject on taxonomy_audit (subject_id, occurred_at desc);
create index taxonomy_audit_time on taxonomy_audit (occurred_at desc);
