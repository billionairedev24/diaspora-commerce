-- Identity admin console (S291): OAuth clients, sessions and tokens, signing keys, auth settings.
-- identity:admin is a Super Admin power; every change is written to access_change_log (append-only).
insert into permission (key, module, description) values
    ('identity:admin', 'identity', 'Identity admin console: OAuth clients, sessions and tokens, signing keys, token inspector and auth settings')
on conflict (key) do nothing;

insert into role_permission (role_key, permission_key) values
    ('platform.super_admin', 'identity:admin')
on conflict do nothing;

-- The audit detail carries the reason and the before/after values of a change.
-- (widening a varchar is a catalogue change: no rows are rewritten and the append-only trigger stays on)
alter table access_change_log alter column detail type varchar(2000);

-- ---------------------------------------------------------------- OAuth clients (state beside oauth2_registered_client)
-- A disabled client is refused at the token endpoint. A rotated confidential client keeps accepting its previous secret
-- (hash) until previous_secret_expires_at. Start-up provisioning only creates clients that do not exist yet.
create table oauth_client_state (
    client_id                  varchar(100) primary key,
    enabled                    boolean      not null default true,
    previous_secret_hash       varchar(200),
    previous_secret_expires_at timestamptz,
    secret_rotated_at          timestamptz,
    updated_at                 timestamptz  not null default now(),
    updated_by                 varchar(100)
);

-- ---------------------------------------------------------------- signing keys
-- ACTIVE signs new tokens; RETIRING stays published (public part only) until retire_after, the end of the longest access
-- token issued under it; RETIRED is no longer published. The private key is sealed with the data-protection cipher and
-- erased the moment a key stops being active.
create table signing_key (
    kid                varchar(100) primary key,
    algorithm          varchar(20)  not null,
    status             varchar(12)  not null check (status in ('ACTIVE', 'RETIRING', 'RETIRED')),
    public_key_pem     text         not null,
    private_key_sealed bytea,
    seal_key_id        varchar(100),
    created_at         timestamptz  not null,
    rotated_at         timestamptz,
    retire_after       timestamptz,
    retired_at         timestamptz,
    created_by         varchar(100)
);
create unique index signing_key_one_active on signing_key (status) where status = 'ACTIVE';

-- ---------------------------------------------------------------- auth settings
-- One row. A null column means "use the configured default" (application.yml / environment).
create table auth_settings (
    id                               smallint primary key check (id = 1),
    password_min_length              integer,
    mfa_required_account             boolean,
    mfa_required_seller              boolean,
    mfa_required_provider            boolean,
    session_idle_timeout_minutes     integer,
    session_absolute_timeout_minutes integer,
    access_token_ttl_minutes         integer,
    refresh_token_ttl_minutes        integer,
    updated_at                       timestamptz not null default now(),
    updated_by                       varchar(100),
    version                          integer     not null default 0
);
insert into auth_settings (id) values (1);
