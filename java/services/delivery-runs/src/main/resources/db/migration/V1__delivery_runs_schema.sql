-- delivery-runs (S260–S265): locations, runs, run slots, subscriptions with per-channel consent.
-- Areas are not stored as geometry here: polygon areas are geo-service zones (geo_zone_id), radius areas are a centre
-- and a radius; coverage is resolved through geo-service.

create table location (
    id                        uuid primary key,
    slug                      varchar(80)   not null unique,
    name                      varchar(120)  not null,
    market                    varchar(16)   not null,
    state                     varchar(8),
    time_zone                 varchar(64)   not null,
    area_kind                 varchar(16)   not null check (area_kind in ('POLYGON', 'RADIUS')),
    geo_zone_id               varchar(80),
    center_lat                double precision not null,
    center_lng                double precision not null,
    radius_miles              double precision,
    origin_lat                double precision,
    origin_lng                double precision,
    default_fee_minor         bigint        not null check (default_fee_minor >= 0),
    currency                  char(3)       not null,
    default_min_orders        integer       not null check (default_min_orders >= 1),
    default_capacity_orders   integer,
    default_capacity_packages integer,
    drop_mode                 varchar(16)   not null check (drop_mode in ('DOORSTEP', 'PICKUP_POINT')),
    pickup_point              jsonb,
    whatsapp_invite_url       varchar(500),
    social_links              jsonb         not null default '{}'::jsonb,
    default_cutoff_policy     varchar(16)   not null check (default_cutoff_policy in ('EXTEND', 'CANCEL')),
    default_extend_days       integer       not null,
    active                    boolean       not null default true,
    created_at                timestamptz   not null,
    updated_at                timestamptz   not null,
    version                   bigint        not null default 0,
    check ((area_kind = 'POLYGON' and geo_zone_id is not null)
        or (area_kind = 'RADIUS' and radius_miles is not null and radius_miles > 0))
);
create index location_active on location (active, name);

create table run (
    id                   uuid primary key,
    location_id          uuid          not null references location (id),
    status               varchar(24)   not null,
    window_start         timestamptz   not null,
    window_end           timestamptz   not null,
    cutoff_at            timestamptz   not null,
    original_cutoff_at   timestamptz   not null,
    opens_at             timestamptz,
    seller_pickup_at     timestamptz,
    fee_minor            bigint        not null check (fee_minor >= 0),
    currency             char(3)       not null,
    min_orders           integer       not null check (min_orders >= 1),
    capacity_orders      integer,
    capacity_packages    integer,
    drop_mode            varchar(16)   not null,
    pickup_point         jsonb,
    cutoff_policy        varchar(16)   not null,
    extend_days          integer       not null,
    extensions           integer       not null default 0,
    reserved_orders      integer       not null default 0 check (reserved_orders >= 0),
    reserved_packages    integer       not null default 0 check (reserved_packages >= 0),
    cancel_reason        varchar(500),
    reminder_sent_for    timestamptz,
    est_road_miles       double precision,
    est_drive_minutes    double precision,
    est_method           varchar(32),
    announced_at         timestamptz,
    opened_at            timestamptz,
    cut_off_at           timestamptz,
    confirmed_at         timestamptz,
    collecting_at        timestamptz,
    out_for_delivery_at  timestamptz,
    completed_at         timestamptz,
    cancelled_at         timestamptz,
    created_at           timestamptz   not null,
    updated_at           timestamptz   not null,
    version              bigint        not null default 0,
    check (window_end > window_start),
    check (cutoff_at < window_start)
);
create index run_location_window on run (location_id, window_start desc);
-- The cutoff job and the reminder scan only look at runs that take orders.
create index run_accepting_cutoff on run (cutoff_at) where status in ('OPEN', 'EXTENDED');
create index run_announced_opens on run (opens_at) where status = 'ANNOUNCED' and opens_at is not null;
create index run_status on run (status);

create table run_order (
    id               uuid primary key,
    run_id           uuid          not null references run (id),
    order_id         varchar(80)   not null,
    customer_id      varchar(120)  not null,
    packages         integer       not null check (packages between 1 and 50),
    status           varchar(16)   not null check (status in ('RESERVED', 'CONFIRMED', 'RELEASED', 'CANCELLED')),
    handover_code    varchar(12),
    requested_run_id uuid,
    reserved_at      timestamptz   not null,
    updated_at       timestamptz   not null,
    version          bigint        not null default 0
);
-- One active slot per order, across runs.
create unique index run_order_active_order on run_order (order_id) where status in ('RESERVED', 'CONFIRMED');
create index run_order_run on run_order (run_id, reserved_at);
create index run_order_order on run_order (order_id);

create table subscription (
    id                 uuid primary key,
    location_id        uuid          not null references location (id),
    user_id            varchar(120),
    email              varchar(254),
    status             varchar(24)   not null check (status in ('PENDING_CONFIRMATION', 'ACTIVE', 'CANCELLED')),
    confirm_token_hash char(64),
    confirm_expires_at timestamptz,
    unsubscribe_token  varchar(64)   not null unique,
    created_at         timestamptz   not null,
    confirmed_at       timestamptz,
    updated_at         timestamptz   not null,
    version            bigint        not null default 0,
    check ((user_id is null) <> (email is null))
);
create unique index subscription_location_user on subscription (location_id, user_id) where user_id is not null;
create unique index subscription_location_email on subscription (location_id, email) where email is not null;
create unique index subscription_confirm_token on subscription (confirm_token_hash) where confirm_token_hash is not null;
create index subscription_user on subscription (user_id) where user_id is not null;
create index subscription_location_created on subscription (location_id, created_at);

-- Current consent per channel.
create table subscription_consent (
    subscription_id uuid         not null references subscription (id),
    channel         varchar(16)  not null check (channel in ('EMAIL', 'SMS', 'PUSH', 'WHATSAPP')),
    consented_at    timestamptz  not null,
    source          varchar(80)  not null,
    withdrawn_at    timestamptz,
    primary key (subscription_id, channel)
);
create index subscription_consent_channel on subscription_consent (channel) where withdrawn_at is null;

-- Append-only consent record (who agreed to what, when and where; never updated or deleted).
create table consent_log (
    id              bigserial primary key,
    subscription_id uuid         not null references subscription (id),
    channel         varchar(16)  not null,
    action          varchar(16)  not null check (action in ('GRANTED', 'WITHDRAWN', 'CONFIRMED')),
    source          varchar(80)  not null,
    at              timestamptz  not null
);
create index consent_log_subscription on consent_log (subscription_id, at);
