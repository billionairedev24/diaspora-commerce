create table if not exists storefront (
    org_id       text primary key,
    kind         text        not null,
    slug         text        not null unique,
    draft        jsonb       not null,
    published    jsonb,
    pending_links boolean    not null default false,
    version      bigint      not null,
    published_at timestamptz,
    updated_at   timestamptz not null default now()
);
create index if not exists storefront_pending on storefront (updated_at) where pending_links;
