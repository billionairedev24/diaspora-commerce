-- The seller's branded storefront (/b/{slug}): how it looks and which sections it has. The settings are one JSON
-- document (gateway-bff owns and validates its schema); the seller edits a DRAFT and publishes a copy, so the live page
-- only changes on Publish. `version` is the optimistic-lock counter of the draft (every save adds one).
create table store_page (
    org_id            varchar(40) primary key references organisation (id),
    draft             jsonb       not null,
    published         jsonb,
    version           bigint      not null default 1,
    published_version bigint,
    updated_at        timestamptz not null,
    updated_by        varchar(80) not null,
    published_at      timestamptz,
    check (jsonb_typeof(draft) = 'object' and pg_column_size(draft) <= 32768),
    check (published is null or (jsonb_typeof(published) = 'object' and pg_column_size(published) <= 32768))
);

-- Who saved, published and unpublished, kept for ever (the page is public).
create table store_page_audit (
    id      bigserial primary key,
    org_id  varchar(40) not null references organisation (id),
    at      timestamptz not null,
    action  varchar(20) not null check (action in ('DRAFT_SAVED', 'PUBLISHED', 'UNPUBLISHED')),
    actor   varchar(80) not null,
    version bigint      not null
);
create index store_page_audit_org_at on store_page_audit (org_id, at desc);
