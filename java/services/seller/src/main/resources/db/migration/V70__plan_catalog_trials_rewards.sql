-- V70 (after V60, connect_account_payout_bank).
-- S292–S297: AfroTransact owns the plan catalogue, trials, credits and rewards; Stripe Billing only executes charges.
--
-- Plans keep their metadata (name, tagline, features, limits, visibility, sort, status) on `plan`. Prices and the
-- default trial live on immutable `plan_version`s: changing a price or the trial creates a new version, subscribers
-- stay on theirs (`subscription.plan_version`) until staff migrate them. On publish the seller service creates a
-- Stripe Product per plan and a Price per version and cadence (`plan_version_price.stripe_price_id`); a Price is never
-- edited: a new one is created (moving the `<plan>_<cadence>` lookup key with transfer_lookup_key) and the old one is
-- archived. Existing Prices made by `make stripe-setup` are adopted by lookup key when they match.

-- ── Plan metadata ─────────────────────────────────────────────────────────────────────────────────────────────────
alter table plan add column visibility varchar(16) not null default 'PUBLIC'
    check (visibility in ('PUBLIC', 'HIDDEN', 'INVITE_ONLY'));
alter table plan add column status varchar(16) not null default 'ACTIVE'
    check (status in ('DRAFT', 'ACTIVE', 'RETIRED'));
alter table plan add column limits jsonb not null default '{}';
alter table plan add column current_version int;
alter table plan add column stripe_product_id varchar(80) unique;
alter table plan add column updated_by varchar(80);
alter table plan add column updated_at timestamptz;
alter table plan add column version bigint not null default 0;
update plan set status = case when active then 'ACTIVE' else 'RETIRED' end, updated_at = now(), updated_by = 'system';
alter table plan alter column updated_at set not null;

-- ── Versions (prices per cadence + default trial) ────────────────────────────────────────────────────────────────
create table plan_version (
    plan_code    varchar(40) not null references plan (code),
    version      int         not null check (version > 0),
    status       varchar(16) not null check (status in ('DRAFT', 'PUBLISHED', 'SUPERSEDED')),
    trial_days   int         not null default 0 check (trial_days between 0 and 365),
    note         text,
    created_by   varchar(80) not null,
    created_at   timestamptz not null,
    published_at timestamptz,
    primary key (plan_code, version)
);
-- At most one draft and one published (current) version per plan.
create unique index plan_version_one_draft on plan_version (plan_code) where status = 'DRAFT';
create unique index plan_version_one_published on plan_version (plan_code) where status = 'PUBLISHED';

create table plan_version_price (
    plan_code       varchar(40) not null,
    version         int         not null,
    cadence         varchar(16) not null check (cadence in ('MONTHLY', 'QUARTERLY', 'ANNUAL')),
    amount_cents    bigint      not null check (amount_cents >= 0),
    -- <plan>_<cadence>: points at the current version's Stripe Price (moved with transfer_lookup_key).
    lookup_key      varchar(80) not null,
    -- A version whose amounts did not change (only the trial did) reuses the previous version's Price.
    stripe_price_id varchar(80),
    synced_at       timestamptz,
    sync_error      text,
    primary key (plan_code, version, cadence),
    foreign key (plan_code, version) references plan_version (plan_code, version)
);

create index plan_version_price_stripe on plan_version_price (stripe_price_id) where stripe_price_id is not null;

-- Version 1 of the six seeded plans: quarterly = 3 months less 5 %, annual = 10 months (the old PlanPricing rule),
-- with the lookup keys `make stripe-setup` created, so their Prices are adopted on the first sync.
insert into plan_version (plan_code, version, status, trial_days, note, created_by, created_at, published_at)
select code, 1, 'PUBLISHED', trial_days, 'Initial catalogue', 'system', now(), now() from plan;
insert into plan_version_price (plan_code, version, cadence, amount_cents, lookup_key)
select p.code, 1, c.cadence,
       case c.cadence when 'MONTHLY' then p.monthly_cents
                      when 'QUARTERLY' then round(p.monthly_cents * 3 * 0.95)::bigint
                      else p.monthly_cents * 10 end,
       pp.stripe_lookup_key
from plan p
cross join (values ('MONTHLY'), ('QUARTERLY'), ('ANNUAL')) as c (cadence)
join plan_price pp on pp.plan_code = p.code and pp.cadence = c.cadence;
update plan set current_version = 1;

alter table plan drop column monthly_cents;
alter table plan drop column trial_days;
alter table plan drop column active;
drop table plan_price;

-- Organisations that may choose an INVITE_ONLY plan.
create table plan_invite (
    plan_code  varchar(40) not null references plan (code),
    org_id     varchar(40) not null references organisation (id),
    created_by varchar(80) not null,
    created_at timestamptz not null,
    primary key (plan_code, org_id)
);

-- Staff migrations of subscribers from an older version to the current one (one row per organisation and target).
create table plan_migration (
    org_id       varchar(40) not null references organisation (id),
    plan_code    varchar(40) not null,
    from_version int         not null,
    to_version   int         not null,
    actor_id     varchar(80) not null,
    status       varchar(12) not null check (status in ('MIGRATED', 'FAILED')),
    error        text,
    migrated_at  timestamptz not null,
    primary key (org_id, plan_code, to_version)
);

-- ── Trials ──────────────────────────────────────────────────────────────────────────────────────────────────────
-- A campaign overrides the plan's default trial for sign-ups in a window and/or with a promo code.
create table trial_campaign (
    id              uuid         primary key,
    name            varchar(120) not null,
    promo_code      varchar(40)  unique,
    trial_days      int          not null check (trial_days between 1 and 365),
    signup_from     timestamptz,
    signup_to       timestamptz,
    org_kind        varchar(16)  check (org_kind is null or org_kind in ('SELLER', 'PROVIDER')),
    eligible_plans  jsonb        not null default '[]',
    max_redemptions int          check (max_redemptions is null or max_redemptions > 0),
    redemptions     int          not null default 0,
    status          varchar(12)  not null check (status in ('ACTIVE', 'PAUSED', 'ENDED')),
    created_by      varchar(80)  not null,
    created_at      timestamptz  not null,
    updated_by      varchar(80)  not null,
    updated_at      timestamptz  not null,
    version         bigint       not null default 0,
    check (signup_to is null or signup_from is null or signup_to > signup_from)
);

alter table subscription add column plan_version int not null default 1;
alter table subscription add column trial_days_override int check (trial_days_override between 0 and 365);
alter table subscription add column trial_override_reason varchar(40);
alter table subscription add column trial_override_note text;
alter table subscription add column trial_override_by varchar(80);
alter table subscription add column campaign_id uuid references trial_campaign (id);
alter table subscription add column trial_source varchar(16)
    check (trial_source is null or trial_source in ('PLAN', 'CAMPAIGN', 'OVERRIDE', 'NONE'));
-- Customer credit balance mirrored from Stripe (positive = credit the next invoices use).
alter table subscription add column credit_balance_cents bigint not null default 0;
alter table subscription add column collect_from_earnings boolean not null default false;
alter table subscription add constraint subscription_plan_version_fk
    foreign key (plan_code, plan_version) references plan_version (plan_code, version);

-- Onboarding promo code (S295): kept on the application's plan step, redeemed at activation.

-- ── Rewards and grants ──────────────────────────────────────────────────────────────────────────────────────────
create table reward_rule (
    id                      uuid         primary key,
    name                    varchar(120) not null,
    description             text,
    status                  varchar(12)  not null check (status in ('DRAFT', 'ACTIVE', 'PAUSED', 'ENDED')),
    org_kind                varchar(16)  check (org_kind is null or org_kind in ('SELLER', 'PROVIDER')),
    metric                  varchar(32)  not null check (metric in ('GROSS_SALES', 'ACTIVE_LISTINGS',
                                'COMPLETED_ORDERS', 'COMPLETED_BOOKINGS', 'DAYS_SINCE_APPROVAL')),
    threshold               bigint       not null check (threshold > 0),
    window_days             int          check (window_days is null or window_days > 0),
    within_days_of_approval int          check (within_days_of_approval is null or within_days_of_approval > 0),
    reward_type             varchar(16)  not null check (reward_type in ('FREE_DAYS', 'CREDIT', 'PERCENT_OFF')),
    free_days               int          check (free_days is null or free_days between 1 and 365),
    credit_cents            bigint       check (credit_cents is null or credit_cents > 0),
    percent_off             numeric(5, 2) check (percent_off is null or (percent_off > 0 and percent_off <= 100)),
    duration_invoices       int          check (duration_invoices is null or duration_invoices between 1 and 36),
    eligible_plans          jsonb        not null default '[]',
    starts_at               timestamptz,
    ends_at                 timestamptz,
    max_grants              int          check (max_grants is null or max_grants > 0),
    grants_count            int          not null default 0,
    created_by              varchar(80)  not null,
    created_at              timestamptz  not null,
    updated_by              varchar(80)  not null,
    updated_at              timestamptz  not null,
    version                 bigint       not null default 0
);
create index reward_rule_active on reward_rule (metric) where status = 'ACTIVE';

-- Every credit, free period, discount, waived invoice and trial change: rule rewards and staff adjustments.
create table billing_grant (
    id                            uuid         primary key,
    org_id                        varchar(40)  not null references organisation (id),
    source                        varchar(12)  not null check (source in ('RULE', 'MANUAL')),
    rule_id                       uuid         references reward_rule (id),
    request_key                   varchar(120),
    type                          varchar(16)  not null check (type in ('FREE_DAYS', 'CREDIT', 'PERCENT_OFF',
                                      'WAIVE_INVOICE', 'TRIAL_END')),
    credit_cents                  bigint,
    free_days                     int,
    percent_off                   numeric(5, 2),
    duration_invoices             int,
    invoice_id                    varchar(80),
    trial_end                     timestamptz,
    status                        varchar(12)  not null check (status in ('PENDING', 'APPLIED', 'FAILED')),
    reason_code                   varchar(40),
    note                          text,
    summary                       text         not null,
    actor_id                      varchar(80)  not null,
    metric                        varchar(32),
    metric_value                  bigint,
    stripe_customer_id            varchar(80),
    stripe_subscription_id        varchar(80),
    stripe_balance_transaction_id varchar(80),
    stripe_coupon_id              varchar(80),
    previous_trial_end            timestamptz,
    new_trial_end                 timestamptz,
    attempts                      int          not null default 0,
    last_error                    text,
    created_at                    timestamptz  not null,
    applied_at                    timestamptz,
    version                       bigint       not null default 0
);
-- Idempotent per (rule, seller) and per staff request.
create unique index billing_grant_rule_once on billing_grant (rule_id, org_id) where rule_id is not null;
create unique index billing_grant_request on billing_grant (org_id, request_key) where request_key is not null;
create index billing_grant_org on billing_grant (org_id, created_at desc);
create index billing_grant_created on billing_grant (created_at desc);
create index billing_grant_retry on billing_grant (created_at) where status in ('PENDING', 'FAILED');

create table billing_reason_code (
    code       varchar(40)  primary key,
    label      varchar(120) not null,
    sort_order int          not null
);
insert into billing_reason_code (code, label, sort_order) values
 ('GOODWILL', 'Goodwill gesture', 10),
 ('SERVICE_ISSUE', 'Service issue or outage on our side', 20),
 ('BILLING_ERROR', 'Billing error or double charge', 30),
 ('PROMOTION', 'Promotion or partnership agreement', 40),
 ('RETENTION', 'Retention offer', 50),
 ('ONBOARDING_DELAY', 'Onboarding or verification delay', 60),
 ('OTHER', 'Other (see note)', 999);

-- Metrics the rules read: daily sums from events (gross sales in cents, completed orders and bookings) and the live
-- listing state per offer (active listings).
create table seller_metric_daily (
    org_id varchar(40) not null,
    metric varchar(32) not null check (metric in ('GROSS_SALES', 'COMPLETED_ORDERS', 'COMPLETED_BOOKINGS')),
    day    date        not null,
    value  bigint      not null default 0,
    primary key (org_id, metric, day)
);
create table seller_listing_state (
    offer_id   varchar(80) primary key,
    org_id     varchar(40) not null,
    active     boolean     not null,
    updated_at timestamptz not null
);
create index seller_listing_state_org on seller_listing_state (org_id) where active;

-- ── Optional collection from earnings (feature flag, default off) ───────────────────────────────────────────────
create table earnings_collection (
    stripe_invoice_id             varchar(80) primary key,
    org_id                        varchar(40) not null references organisation (id),
    amount_cents                  bigint      not null check (amount_cents > 0),
    -- REQUESTED (invoice.created seen) → CREDITED (payments withheld it, balance credit posted) or DECLINED / FAILED.
    status                        varchar(12) not null check (status in ('REQUESTED', 'WITHHELD', 'CREDITED',
                                      'DECLINED', 'FAILED')),
    payments_reference            varchar(80),
    stripe_balance_transaction_id varchar(80),
    reason                        text,
    attempts                      int         not null default 0,
    created_at                    timestamptz not null,
    updated_at                    timestamptz not null
);
create index earnings_collection_open on earnings_collection (created_at) where status in ('REQUESTED', 'WITHHELD');
