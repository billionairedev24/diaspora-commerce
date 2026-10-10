create table if not exists cart (
    owner_key  text primary key,
    body       jsonb       not null,
    version    bigint      not null,
    updated_at timestamptz not null default now()
);
