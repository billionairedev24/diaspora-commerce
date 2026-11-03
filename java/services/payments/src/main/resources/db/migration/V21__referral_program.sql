-- Referral program (S322), ADR 0006. The platform funds it: rewards are granted through the credit ledger (source
-- REFERRAL, one idempotency key per referral and side) and booked as program cost. Nothing here pays cash.

-- One stable code per customer. Generated from a consonant-and-digit alphabet with a CSPRNG (no vowels, so a code cannot
-- spell a word), never derived from the customer id.
create table referral_code (
    customer_id varchar(120) primary key,
    code        varchar(16)  not null unique check (code = upper(code)),
    created_at  timestamptz  not null
);

-- Staff-configurable program settings (one row), each side separately, and their history with the reason.
create table referral_settings (
    id                        smallint primary key check (id = 1),
    enabled                   boolean      not null,
    referrer_reward_type      varchar(16)  not null check (referrer_reward_type in ('CREDIT', 'MEMBERSHIP_DAYS')),
    referrer_credit_minor     bigint       not null check (referrer_credit_minor >= 0),
    referrer_membership_days  integer      not null check (referrer_membership_days >= 0),
    referred_reward_type      varchar(16)  not null check (referred_reward_type in ('CREDIT', 'MEMBERSHIP_DAYS')),
    referred_credit_minor     bigint       not null check (referred_credit_minor >= 0),
    referred_membership_days  integer      not null check (referred_membership_days >= 0),
    qualifying_event          varchar(24)  not null check (qualifying_event in ('FIRST_ORDER_DELIVERED')),
    min_order_minor           bigint       not null check (min_order_minor >= 0),
    return_window_days        integer      not null check (return_window_days between 0 and 180),
    max_rewarded_per_referrer integer      not null check (max_rewarded_per_referrer >= 0),
    max_credit_per_customer_minor bigint   not null check (max_credit_per_customer_minor >= 0),
    validity_days             integer      not null check (validity_days between 1 and 730),
    updated_by                varchar(120) not null,
    updated_at                timestamptz  not null,
    version                   bigint       not null default 0
);
-- Defaults of ADR 0006: 10.00 credit to each side after the friend's first delivered order of at least 30.00 and its
-- 30-day return window; a customer can earn at most 20 rewarded referrals and 500.00 of referral credit; a friend has
-- 90 days from sign-up to qualify.
insert into referral_settings (id, enabled, referrer_reward_type, referrer_credit_minor, referrer_membership_days,
                               referred_reward_type, referred_credit_minor, referred_membership_days, qualifying_event,
                               min_order_minor, return_window_days, max_rewarded_per_referrer,
                               max_credit_per_customer_minor, validity_days, updated_by, updated_at)
values (1, true, 'CREDIT', 1000, 0, 'CREDIT', 1000, 0, 'FIRST_ORDER_DELIVERED', 3000, 30, 20, 50000, 90, 'system',
        now());

create table referral_settings_change (
    id         uuid primary key,
    changed_by varchar(120) not null,
    changed_at timestamptz  not null,
    reason     text         not null,
    before     jsonb        not null,
    after      jsonb        not null
);
create index referral_settings_change_at on referral_settings_change (changed_at desc);

-- One referral per referred customer (a customer is referred at most once, at sign-up).
create table referral (
    id                 uuid primary key,
    referrer_id        varchar(120) not null,
    referred_id        varchar(120) not null unique,
    code               varchar(16)  not null,
    -- A snapshot taken at sign-up so the referrer sees names, not ids (first name and last initial only).
    referred_name      varchar(120) not null,
    status             varchar(20)  not null check (status in
        ('PENDING', 'WAITING', 'IN_REVIEW', 'PARTIALLY_REWARDED', 'REWARDED', 'REJECTED', 'EXPIRED')),
    flags              text[]       not null default '{}',
    signed_up_at       timestamptz  not null,
    expires_at         timestamptz  not null,
    -- The qualifying order (first one delivered with at least the minimum value) and when its reward is due.
    qualifying_order_id varchar(80),
    qualifying_minor   bigint,
    delivered_at       timestamptz,
    eligible_at        timestamptz,
    -- Staff review of a flagged referral, or the system's reason for a rejection.
    decision           varchar(10) check (decision in ('APPROVED', 'REJECTED', 'SYSTEM')),
    decision_reason    text,
    decided_by         varchar(120),
    decided_at         timestamptz,
    created_at         timestamptz  not null,
    updated_at         timestamptz  not null,
    version            bigint       not null default 0,
    check (referrer_id <> referred_id)
);
create index referral_referrer on referral (referrer_id, created_at desc);
create index referral_status on referral (status, created_at desc);
create index referral_due on referral (eligible_at) where status = 'WAITING';
create index referral_expiring on referral (expires_at) where status = 'PENDING';

-- What each side earns, decided when the friend qualifies (the settings in force then) and granted once per side.
create table referral_reward (
    referral_id      uuid         not null references referral (id),
    side             varchar(10)  not null check (side in ('REFERRER', 'REFERRED')),
    customer_id      varchar(120) not null,
    reward_type      varchar(16)  not null check (reward_type in ('CREDIT', 'MEMBERSHIP_DAYS')),
    credit_minor     bigint       not null default 0 check (credit_minor >= 0),
    membership_days  integer      not null default 0 check (membership_days >= 0),
    currency         char(3)      not null,
    -- DEFERRED: the membership program is not available yet; the job grants it as soon as it is. CAPPED: the customer
    -- reached a per-customer cap. VOID: the referral was rejected.
    status           varchar(10)  not null check (status in ('PENDING', 'GRANTED', 'DEFERRED', 'CAPPED', 'VOID')),
    entry_id         uuid,
    granted_at       timestamptz,
    idempotency_key  varchar(200) not null unique,
    primary key (referral_id, side)
);
create index referral_reward_customer on referral_reward (customer_id, granted_at desc);

-- Tracking of a referred customer's orders until one qualifies (shipments delivered or cancelled).
create table referral_order (
    order_id      varchar(80)  primary key,
    referral_id   uuid         not null references referral (id),
    placed_at     timestamptz  not null,
    total_minor   bigint       not null,
    delivered_at  timestamptz,
    delivered_minor bigint     not null default 0,
    qualified     boolean      not null default false
);
create index referral_order_referral on referral_order (referral_id);

create table referral_shipment (
    shipment_id  varchar(80)  primary key,
    order_id     varchar(80)  not null references referral_order (order_id),
    total_minor  bigint       not null,
    state        varchar(10)  not null check (state in ('OPEN', 'DELIVERED', 'CANCELLED')),
    at           timestamptz
);
create index referral_shipment_order on referral_shipment (order_id);

-- Fingerprints of a customer (HMAC-SHA256 hashes, never the value): the device and network of the sign-up, the
-- delivery addresses of orders. Compared between referrer and referred to flag self-referral.
create table referral_signal (
    customer_id varchar(120) not null,
    kind        varchar(10)  not null check (kind in ('DEVICE', 'NETWORK', 'ADDRESS')),
    hash        char(64)     not null,
    seen_at     timestamptz  not null,
    primary key (customer_id, kind, hash)
);
create index referral_signal_hash on referral_signal (kind, hash);
