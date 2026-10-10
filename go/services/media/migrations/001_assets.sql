create table if not exists asset (
    id           uuid primary key,
    owner_org    text        not null,
    uploaded_by  text        not null,
    purpose      text        not null,
    provider     text        not null,
    object_key   text        not null unique,
    url          text,
    file_name    text        not null,
    content_type text        not null,
    bytes        bigint      not null,
    width        int,
    height       int,
    status       text        not null,
    flags        jsonb       not null default '[]',
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now()
);
create index if not exists asset_owner on asset (owner_org, purpose, created_at desc);
