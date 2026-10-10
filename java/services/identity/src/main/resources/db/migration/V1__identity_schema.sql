-- Identity service schema: accounts, RBAC, organisations, MFA, social identities, provider switches,
-- plus the official Spring Authorization Server, Spring Security WebAuthn and Spring Session JDBC tables.

-- ---------------------------------------------------------------- accounts
create table user_account (
    id                uuid primary key,
    first_name        varchar(80)  not null,
    last_name         varchar(80)  not null,
    email             varchar(254) not null,
    email_verified    boolean      not null default false,
    phone_e164        varchar(20),
    password_hash     varchar(200),
    enabled           boolean      not null default true,
    sms_consent       boolean      not null default false,
    sms_consent_at    timestamptz,
    terms_accepted_at timestamptz,
    active_org_id     uuid,
    created_at        timestamptz  not null,
    updated_at        timestamptz  not null,
    version           integer      not null default 0
);
create unique index user_account_email_uq on user_account (lower(email));
create index user_account_name_idx on user_account (lower(last_name), lower(first_name));

-- ---------------------------------------------------------------- RBAC
create table permission (
    key         varchar(80) primary key,
    module      varchar(40)  not null,
    description varchar(200) not null
);

create table role (
    key         varchar(80) primary key,
    portal      varchar(20)  not null check (portal in ('ACCOUNT', 'PLATFORM', 'SELLER', 'PROVIDER')),
    name        varchar(80)  not null,
    description varchar(300) not null default '',
    position    integer      not null default 0
);

create table role_permission (
    role_key       varchar(80) not null references role (key) on delete cascade,
    permission_key varchar(80) not null references permission (key) on delete cascade,
    primary key (role_key, permission_key)
);

create table user_role (
    user_id    uuid        not null references user_account (id) on delete cascade,
    role_key   varchar(80) not null references role (key),
    granted_at timestamptz not null default now(),
    granted_by varchar(100) not null,
    primary key (user_id, role_key)
);

create table user_permission (
    user_id        uuid        not null references user_account (id) on delete cascade,
    permission_key varchar(80) not null references permission (key),
    granted_at     timestamptz not null default now(),
    granted_by     varchar(100) not null,
    primary key (user_id, permission_key)
);

create table organization (
    id         uuid primary key,
    kind       varchar(20)  not null check (kind in ('SELLER', 'PROVIDER')),
    name       varchar(200) not null,
    status     varchar(20)  not null check (status in ('PENDING', 'APPROVED', 'SUSPENDED', 'REJECTED')),
    updated_at timestamptz  not null
);

create table org_membership (
    org_id     uuid        not null references organization (id) on delete cascade,
    user_id    uuid        not null references user_account (id) on delete cascade,
    role_key   varchar(80) not null references role (key),
    created_at timestamptz not null default now(),
    primary key (org_id, user_id)
);
create index org_membership_user_idx on org_membership (user_id);

create table access_module (
    portal   varchar(20)  not null,
    key      varchar(40)  not null,
    label    varchar(80)  not null,
    position integer      not null,
    primary key (portal, key)
);

create table module_access (
    portal     varchar(20) not null,
    module_key varchar(40) not null,
    role_key   varchar(80) not null references role (key) on delete cascade,
    level      varchar(10) not null check (level in ('NONE', 'VIEW', 'EDIT', 'APPROVE', 'FULL')),
    primary key (portal, module_key, role_key),
    foreign key (portal, module_key) references access_module (portal, key) on delete cascade
);

-- Append-only: updates and deletes are rejected by a trigger.
create table access_change_log (
    id          bigserial primary key,
    at          timestamptz  not null default now(),
    actor_id    varchar(100) not null,
    action      varchar(60)  not null,
    target_user uuid,
    target_role varchar(80),
    subject     varchar(200),
    detail      varchar(500)
);
create index access_change_log_user_idx on access_change_log (target_user, id desc);

create function access_change_log_append_only() returns trigger language plpgsql as $$
begin
    raise exception 'access_change_log is append-only';
end;
$$;
create trigger access_change_log_no_update before update or delete on access_change_log
    for each row execute function access_change_log_append_only();

-- ---------------------------------------------------------------- one-time codes
create table verification_code (
    id          uuid primary key,
    user_id     uuid         not null references user_account (id) on delete cascade,
    purpose     varchar(30)  not null,
    destination varchar(254) not null,
    code_hash   varchar(64)  not null,
    created_at  timestamptz  not null,
    expires_at  timestamptz  not null,
    consumed_at timestamptz,
    attempts    integer      not null default 0
);
create index verification_code_user_idx on verification_code (user_id, purpose, created_at desc);

-- ---------------------------------------------------------------- MFA
create table mfa_totp (
    user_id        uuid primary key references user_account (id) on delete cascade,
    secret         varchar(64) not null,
    created_at     timestamptz not null,
    confirmed_at   timestamptz,
    last_used_step bigint      not null default 0
);

create table mfa_sms (
    user_id    uuid primary key references user_account (id) on delete cascade,
    phone_e164 varchar(20) not null,
    enabled_at timestamptz not null
);

create table mfa_recovery_code (
    user_id    uuid        not null references user_account (id) on delete cascade,
    code_hash  varchar(64) not null,
    created_at timestamptz not null,
    used_at    timestamptz,
    primary key (user_id, code_hash)
);

create table trusted_browser (
    id         uuid primary key,
    user_id    uuid        not null references user_account (id) on delete cascade,
    token_hash varchar(64) not null,
    user_agent varchar(300),
    created_at timestamptz not null,
    expires_at timestamptz not null
);
create index trusted_browser_user_idx on trusted_browser (user_id, token_hash);

-- ---------------------------------------------------------------- social sign-in
create table federated_identity (
    provider  varchar(30)  not null,
    subject   varchar(255) not null,
    user_id   uuid         not null references user_account (id) on delete cascade,
    email     varchar(254),
    linked_at timestamptz  not null,
    primary key (provider, subject)
);
create unique index federated_identity_user_provider_uq on federated_identity (user_id, provider);

create table identity_provider (
    id           varchar(30) primary key,
    display_name varchar(60) not null,
    enabled      boolean     not null default false,
    position     integer     not null default 0,
    updated_at   timestamptz not null default now(),
    updated_by   varchar(100)
);

-- ---------------------------------------------------------------- Spring Authorization Server (official schema,
-- PostgreSQL variant: blob -> text, timestamp -> timestamptz)
create table oauth2_registered_client (
    id                            varchar(100)  not null,
    client_id                     varchar(100)  not null,
    client_id_issued_at           timestamptz   default current_timestamp not null,
    client_secret                 varchar(200)  default null,
    client_secret_expires_at      timestamptz   default null,
    client_name                   varchar(200)  not null,
    client_authentication_methods varchar(1000) not null,
    authorization_grant_types     varchar(1000) not null,
    redirect_uris                 varchar(1000) default null,
    post_logout_redirect_uris     varchar(1000) default null,
    scopes                        varchar(1000) not null,
    client_settings               varchar(2000) not null,
    token_settings                varchar(2000) not null,
    primary key (id)
);
create unique index oauth2_registered_client_client_id_uq on oauth2_registered_client (client_id);

create table oauth2_authorization (
    id                            varchar(100)  not null,
    registered_client_id          varchar(100)  not null,
    principal_name                varchar(200)  not null,
    authorization_grant_type      varchar(100)  not null,
    authorized_scopes             varchar(1000) default null,
    attributes                    text          default null,
    state                         varchar(500)  default null,
    authorization_code_value      text          default null,
    authorization_code_issued_at  timestamptz   default null,
    authorization_code_expires_at timestamptz   default null,
    authorization_code_metadata   text          default null,
    access_token_value            text          default null,
    access_token_issued_at        timestamptz   default null,
    access_token_expires_at       timestamptz   default null,
    access_token_metadata         text          default null,
    access_token_type             varchar(100)  default null,
    access_token_scopes           varchar(1000) default null,
    oidc_id_token_value           text          default null,
    oidc_id_token_issued_at       timestamptz   default null,
    oidc_id_token_expires_at      timestamptz   default null,
    oidc_id_token_metadata        text          default null,
    refresh_token_value           text          default null,
    refresh_token_issued_at       timestamptz   default null,
    refresh_token_expires_at      timestamptz   default null,
    refresh_token_metadata        text          default null,
    user_code_value               text          default null,
    user_code_issued_at           timestamptz   default null,
    user_code_expires_at          timestamptz   default null,
    user_code_metadata            text          default null,
    device_code_value             text          default null,
    device_code_issued_at         timestamptz   default null,
    device_code_expires_at        timestamptz   default null,
    device_code_metadata          text          default null,
    primary key (id)
);
create index oauth2_authorization_principal_idx on oauth2_authorization (principal_name);

create table oauth2_authorization_consent (
    registered_client_id varchar(100)  not null,
    principal_name       varchar(200)  not null,
    authorities          varchar(1000) not null,
    primary key (registered_client_id, principal_name)
);

-- ---------------------------------------------------------------- Spring Security WebAuthn (passkeys)
create table user_entities (
    id           varchar(1000) not null,
    name         varchar(100)  not null,
    display_name varchar(200),
    primary key (id)
);

create table user_credentials (
    credential_id                varchar(1000) not null,
    user_entity_user_id          varchar(1000) not null,
    public_key                   bytea         not null,
    signature_count              bigint,
    uv_initialized               boolean,
    backup_eligible              boolean       not null,
    authenticator_transports     varchar(1000),
    public_key_credential_type   varchar(100),
    backup_state                 boolean       not null,
    attestation_object           bytea,
    attestation_client_data_json bytea,
    created                      timestamp,
    last_used                    timestamp,
    label                        varchar(1000) not null,
    primary key (credential_id)
);

-- ---------------------------------------------------------------- Spring Session JDBC (shared login sessions)
create table spring_session (
    primary_id            char(36)     not null,
    session_id            char(36)     not null,
    creation_time         bigint       not null,
    last_access_time      bigint       not null,
    max_inactive_interval int          not null,
    expiry_time           bigint       not null,
    principal_name        varchar(100),
    constraint spring_session_pk primary key (primary_id)
);
create unique index spring_session_ix1 on spring_session (session_id);
create index spring_session_ix2 on spring_session (expiry_time);
create index spring_session_ix3 on spring_session (principal_name);

create table spring_session_attributes (
    session_primary_id char(36)     not null,
    attribute_name     varchar(200) not null,
    attribute_bytes    bytea        not null,
    constraint spring_session_attributes_pk primary key (session_primary_id, attribute_name),
    constraint spring_session_attributes_fk foreign key (session_primary_id)
        references spring_session (primary_id) on delete cascade
);
