-- AfroTransact membership (S323, ADR 0006 section 4): Prime-style tiers staff configure, shopper subscriptions billed
-- on a saved card, and the benefits members use. Shopper subscriptions are a new aggregate; they reuse the plan billing
-- machinery of seller plans (a recurring charge with retries that end in a lapse) and the credit ledger for the credit
-- benefits. Nothing here converts credit to cash.

-- A tier: what staff define in Settings. Benefits are configuration from a menu (jsonb list of {code, params}).
create table membership_tier (
    code                varchar(40)  primary key check (code ~ '^[a-z][a-z0-9-]{1,38}$'),
    name                varchar(80)  not null,
    description         text         not null default '',
    status              varchar(12)  not null check (status in ('DRAFT', 'PUBLISHED', 'RETIRED')),
    sort_order          integer      not null,
    currency            char(3)      not null,
    monthly_price_minor bigint       not null check (monthly_price_minor > 0),
    yearly_price_minor  bigint       not null check (yearly_price_minor > 0),
    trial_days          integer      not null default 0 check (trial_days between 0 and 90),
    benefits            jsonb        not null default '[]'::jsonb,
    created_at          timestamptz  not null,
    updated_by          varchar(120) not null,
    updated_at          timestamptz  not null,
    version             bigint       not null default 0
);

-- Append-only history of every tier change with the reason (audit).
create table membership_tier_change (
    id         uuid primary key,
    tier_code  varchar(40)  not null,
    action     varchar(12)  not null check (action in ('CREATED', 'UPDATED', 'PUBLISHED', 'RETIRED', 'DRAFTED')),
    changed_by varchar(120) not null,
    changed_at timestamptz  not null,
    reason     text         not null,
    before     jsonb,
    after      jsonb        not null
);
create index membership_tier_change_tier on membership_tier_change (tier_code, changed_at desc);

-- Billing rules staff set (one row) with their history.
create table membership_settings (
    id                 smallint primary key check (id = 1),
    refund_window_days integer      not null check (refund_window_days between 0 and 60),
    -- days after a failed renewal to try again; after the last retry fails the membership lapses
    retry_days         jsonb        not null,
    updated_by         varchar(120) not null,
    updated_at         timestamptz  not null,
    version            bigint       not null default 0
);
insert into membership_settings (id, refund_window_days, retry_days, updated_by, updated_at)
values (1, 7, '[1, 3, 5]'::jsonb, 'system', now());

create table membership_settings_change (
    id         uuid primary key,
    changed_by varchar(120) not null,
    changed_at timestamptz  not null,
    reason     text         not null,
    before     jsonb        not null,
    after      jsonb        not null
);

-- A shopper's subscription. At most one live one (trialling, active or past due) per customer.
create table membership (
    id                    uuid primary key,
    customer_id           varchar(120) not null,
    tier_code             varchar(40)  not null references membership_tier (code),
    billing_interval      varchar(8)   not null check (billing_interval in ('MONTHLY', 'YEARLY')),
    -- PAID: billed on the saved card. COMPLIMENTARY: free days granted by a program (referral), no card, ends at the date
    source                varchar(14)  not null check (source in ('PAID', 'COMPLIMENTARY')),
    status                varchar(10)  not null
        check (status in ('TRIALING', 'ACTIVE', 'PAST_DUE', 'CANCELED', 'LAPSED', 'EXPIRED')),
    -- the price this member pays each period; later tier price changes do not change it until the member changes plan
    price_minor           bigint       not null check (price_minor >= 0),
    currency              char(3)      not null,
    started_at            timestamptz  not null,
    current_period_start  timestamptz  not null,
    current_period_end    timestamptz  not null,
    trial_end             timestamptz,
    cancel_at_period_end  boolean      not null default false,
    canceled_at           timestamptz,
    ended_at              timestamptz,
    end_reason            varchar(24),
    pending_tier_code     varchar(40) references membership_tier (code),
    pending_interval      varchar(8) check (pending_interval in ('MONTHLY', 'YEARLY')),
    payment_method_id     varchar(120),
    retry_count           integer      not null default 0,
    next_retry_at         timestamptz,
    free_days_granted     integer      not null default 0,
    created_at            timestamptz  not null,
    updated_at            timestamptz  not null,
    version               bigint       not null default 0,
    check ((pending_tier_code is null) = (pending_interval is null))
);
create unique index membership_one_live on membership (customer_id) where status in ('TRIALING', 'ACTIVE', 'PAST_DUE');
create index membership_customer on membership (customer_id, started_at desc);
create index membership_due on membership (current_period_end)
    where status in ('TRIALING', 'ACTIVE', 'PAST_DUE');
create index membership_tier_status on membership (tier_code, status);

-- Every charge of the membership fee (join, renewal, upgrade, retry) and its refund.
create table membership_charge (
    id                       uuid primary key,
    membership_id            uuid         not null references membership (id),
    customer_id              varchar(120) not null,
    tier_code                varchar(40)  not null,
    kind                     varchar(10)  not null check (kind in ('JOIN', 'RENEWAL', 'UPGRADE')),
    attempt                  integer      not null default 1,
    amount_minor             bigint       not null check (amount_minor > 0),
    currency                 char(3)      not null,
    status                   varchar(10)  not null check (status in ('SUCCEEDED', 'FAILED', 'REFUNDED')),
    stripe_payment_intent_id varchar(80),
    stripe_charge_id         varchar(80),
    stripe_refund_id         varchar(80),
    failure_code             varchar(80),
    failure_message          text,
    period_start             timestamptz,
    period_end               timestamptz,
    idempotency_key          varchar(200) not null unique,
    refunded_minor           bigint       not null default 0 check (refunded_minor >= 0),
    refund_reason            text,
    refunded_by              varchar(120),
    refunded_at              timestamptz,
    created_at               timestamptz  not null
);
create index membership_charge_membership on membership_charge (membership_id, created_at desc);
create index membership_charge_customer on membership_charge (customer_id, created_at desc);
create index membership_charge_status on membership_charge (status, created_at desc);

-- Benefits a member used: a fee waived on an order, cashback granted, monthly credit granted. The cost of the benefits.
create table membership_benefit_use (
    id            uuid primary key,
    membership_id uuid         not null references membership (id),
    customer_id   varchar(120) not null,
    tier_code     varchar(40)  not null,
    benefit       varchar(24)  not null,
    order_id      varchar(80),
    ref           varchar(120) not null default '',
    amount_minor  bigint       not null check (amount_minor >= 0),
    currency      char(3)      not null,
    note          text,
    created_at    timestamptz  not null,
    -- a benefit applies once per order and ref; the monthly credit has no order, so nulls count as equal
    unique nulls not distinct (benefit, order_id, ref)
);
create index membership_use_customer on membership_benefit_use (customer_id, created_at desc);
create index membership_use_membership on membership_benefit_use (membership_id, created_at desc);
create index membership_use_order on membership_benefit_use (order_id) where order_id is not null;

-- Trial abuse guard: a customer, and a card, gets one trial ever. card_key is a hash of brand, last four and expiry.
create table membership_trial (
    customer_id   varchar(120) primary key,
    card_key      char(64)     not null unique,
    membership_id uuid         not null,
    started_at    timestamptz  not null
);

-- Lifecycle events for churn and the member timeline.
create table membership_event (
    id            uuid primary key,
    membership_id uuid         not null references membership (id),
    customer_id   varchar(120) not null,
    type          varchar(24)  not null,
    tier_code     varchar(40)  not null,
    actor         varchar(120) not null,
    detail        text,
    occurred_at   timestamptz  not null
);
create index membership_event_at on membership_event (type, occurred_at desc);
create index membership_event_membership on membership_event (membership_id, occurred_at desc);

-- Free membership days granted by a program (referral): idempotent per key.
create table membership_grant (
    idempotency_key varchar(200) primary key,
    customer_id     varchar(120) not null,
    tier_code       varchar(40)  not null,
    days            integer      not null check (days > 0),
    reason          text         not null,
    granted_by      varchar(120) not null,
    membership_id   uuid         not null,
    created_at      timestamptz  not null
);

-- Seed tier: Plus, 9.99 a month or 99 a year, 14-day trial, free delivery and carrier shipping on orders of 25 or more.
insert into membership_tier (code, name, description, status, sort_order, currency, monthly_price_minor,
                             yearly_price_minor, trial_days, benefits, created_at, updated_by, updated_at)
values ('plus', 'Plus', 'Free delivery and shipping on orders of $25 or more.', 'PUBLISHED', 10, 'USD', 999, 9900, 14,
        '[{"code": "FREE_DELIVERY", "minOrderMinor": 2500}, {"code": "FREE_SHIPPING", "minOrderMinor": 2500}]'::jsonb,
        now(), 'system', now());
insert into membership_tier_change (id, tier_code, action, changed_by, changed_at, reason, before, after)
values ('00000000-0000-0000-0000-000000000323', 'plus', 'CREATED', 'system', now(), 'Default tier from ADR 0006', null,
        '{"code": "plus", "name": "Plus", "status": "PUBLISHED"}'::jsonb);
