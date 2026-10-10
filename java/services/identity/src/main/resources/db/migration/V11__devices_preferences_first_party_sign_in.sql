-- Service lookups for notifications (users:read), push devices, notification preferences, and the first-party
-- sign-in API for gateway-bff (challenges, one-time login tickets, brute-force throttling and its audit trail).

-- ---------------------------------------------------------------- permissions
-- users:read is in the V2 catalogue already; the upsert keeps this migration self-contained and widens its description
-- to the service lookups (GET /v1/internal/users/{id}/contact, /v1/internal/orgs/{orgId}/owners).
insert into permission (key, module, description) values
    ('users:read', 'support', 'Look up customer accounts; services: contact details and organisation owners'),
    ('auth:first-party', 'access', 'Service access to the first-party sign-in API (gateway-bff only)'),
    ('sellers:enforce', 'trust', 'Suspend, reinstate and recompute seller health'),
    ('delivery:dispatch:read', 'delivery', 'View dispatch boards, routes and driver assignments (read-only)')
on conflict (key) do update set description = excluded.description;

insert into role_permission (role_key, permission_key) values
    ('platform.super_admin', 'sellers:enforce'),
    ('platform.trust_safety', 'sellers:enforce'),
    ('platform.operations_manager', 'sellers:enforce'),
    ('platform.customer_support', 'delivery:dispatch:read')
on conflict do nothing;

-- ---------------------------------------------------------------- push devices
create table push_device (
    id           uuid primary key,
    user_id      uuid          not null references user_account (id) on delete cascade,
    app          varchar(20)   not null check (app in ('CUSTOMER', 'DRIVER')),
    platform     varchar(20)   not null check (platform in ('IOS', 'ANDROID', 'WEB')),
    provider     varchar(10)   not null check (provider in ('FCM', 'APNS')),
    token        varchar(4096) not null,
    token_hash   char(64)      not null,
    device_name  varchar(120),
    created_at   timestamptz   not null,
    last_seen_at timestamptz   not null
);
-- A token identifies one app install: it belongs to one account at a time (re-registering moves it).
create unique index push_device_token_uq on push_device (token_hash);
create index push_device_user_idx on push_device (user_id, app, last_seen_at desc);

-- ---------------------------------------------------------------- notification preferences
create table notification_preference (
    user_id             uuid primary key references user_account (id) on delete cascade,
    email_enabled       boolean     not null default true,
    sms_enabled         boolean     not null default true,
    push_enabled        boolean     not null default true,
    marketing_opt_in    boolean     not null default false,
    marketing_opt_in_at timestamptz,
    locale              varchar(35),
    time_zone           varchar(64),
    updated_at          timestamptz not null,
    version             integer     not null default 0
);

-- ---------------------------------------------------------------- first-party sign-in (gateway-bff)
create table sign_in_challenge (
    id                uuid primary key,
    kind              varchar(10)  not null check (kind in ('MFA', 'PASSKEY')),
    user_id           uuid references user_account (id) on delete cascade,
    client_id         varchar(100) not null,
    passkey_challenge varchar(200),
    rp_id             varchar(253),
    attempts          integer      not null default 0,
    created_at        timestamptz  not null,
    expires_at        timestamptz  not null,
    completed_at      timestamptz,
    check (kind = 'PASSKEY' or user_id is not null),
    check (kind = 'MFA' or passkey_challenge is not null)
);
create index sign_in_challenge_expiry_idx on sign_in_challenge (expires_at);

-- One-time proof of a completed sign-in, redeemed once on /oauth2/authorize?login_ticket=… (SHA-256 stored only).
create table login_ticket (
    token_hash char(64) primary key,
    user_id    uuid         not null references user_account (id) on delete cascade,
    client_id  varchar(100) not null,
    method     varchar(20)  not null check (method in ('PASSWORD', 'PASSWORD_MFA', 'PASSKEY')),
    created_at timestamptz  not null,
    expires_at timestamptz  not null,
    used_at    timestamptz
);
create index login_ticket_expiry_idx on login_ticket (expires_at);

-- ---------------------------------------------------------------- brute-force protection
create table sign_in_throttle (
    scope             varchar(10)  not null check (scope in ('ACCOUNT', 'IP')),
    key               varchar(254) not null,
    failures          integer      not null default 0,
    window_started_at timestamptz  not null,
    locked_until      timestamptz,
    lockouts          integer      not null default 0,
    updated_at        timestamptz  not null,
    primary key (scope, key)
);
create index sign_in_throttle_locked_idx on sign_in_throttle (locked_until) where locked_until is not null;

-- Append-only: updates and deletes are rejected by a trigger.
create table sign_in_audit (
    id          bigserial primary key,
    occurred_at timestamptz  not null,
    user_id     uuid,
    email       varchar(254),
    client_id   varchar(100),
    channel     varchar(20)  not null check (channel in ('first-party', 'hosted')),
    method      varchar(30)  not null,
    outcome     varchar(30)  not null,
    reason      varchar(80),
    ip          varchar(64),
    user_agent  varchar(300)
);
create index sign_in_audit_user_idx on sign_in_audit (user_id, id desc);
create index sign_in_audit_ip_idx on sign_in_audit (ip, occurred_at desc);

create function sign_in_audit_append_only() returns trigger language plpgsql as $$
begin
    raise exception 'sign_in_audit is append-only';
end;
$$;
create trigger sign_in_audit_no_update before update or delete on sign_in_audit
    for each row execute function sign_in_audit_append_only();
