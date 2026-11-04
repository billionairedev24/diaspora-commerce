-- Commission on product sales and connection fees on service bookings, on top of the plan fees sellers and providers
-- subscribe to: plan rates, a separate connection-fee section, negotiated rates with dates and a reason, the
-- organisations' current plans (projection of seller.subscription.changed) and the rate snapshotted on every line.
-- Precedence (first match wins): active negotiated rate (category, then *) → plan rate (category, then the plan's *)
-- → category rate → default. AfroTransact Retail never pays either.

-- ---------------------------------------------------------------- 1. fee-schedule sections (immutable with the version)
-- Plan rates: plan code → category → bps per section. Category '*' is the plan's rate for categories it does not list.
create table fee_schedule_plan_rate (
    schedule_id uuid        not null references fee_schedule (id),
    section     varchar(20) not null check (section in ('COMMISSION', 'CONNECTION_FEE')),
    plan_code   varchar(80) not null check (length(trim(plan_code)) > 0),
    category    varchar(80) not null check (length(trim(category)) > 0),
    rate_bps    integer     not null check (rate_bps between 0 and 10000),
    primary key (schedule_id, section, plan_code, category)
);

-- The service connection-fee section: a version without a row prices services like before (the 'services'
-- commission rate), so versions created before this migration keep their meaning.
create table fee_schedule_connection_fee (
    schedule_id uuid    primary key references fee_schedule (id),
    default_bps integer not null check (default_bps between 0 and 10000)
);

create table fee_schedule_connection_fee_rate (
    schedule_id uuid        not null references fee_schedule_connection_fee (schedule_id),
    category    varchar(80) not null,
    rate_bps    integer     not null check (rate_bps between 0 and 10000),
    primary key (schedule_id, category)
);

create trigger fee_schedule_plan_rate_immutable before update or delete on fee_schedule_plan_rate
    for each row execute function ledger_reject_change();
create trigger fee_schedule_connection_fee_immutable before update or delete on fee_schedule_connection_fee
    for each row execute function ledger_reject_change();
create trigger fee_schedule_connection_fee_rate_immutable before update or delete on fee_schedule_connection_fee_rate
    for each row execute function ledger_reject_change();

-- Seed, like V9 did for delivery pricing: every existing version gets the rates the plan catalogue advertises
-- (docs/engineering/commission-and-connection-fees.md › Seeded plan rates). Lines created before this migration keep
-- the rate of their version without plans (they carry no snapshot and are priced the old way), so nothing already
-- placed is re-priced. Staff change the rates by creating a new version.
--   Seller Essentials "15% referral fee"            → * 15 %
--   Seller Growth     "8–12% referral fee by category" → groceries 8 %, beauty / books / health / party 10 %,
--                                                       fashion / home & art 12 %, * 10 %
--   Seller Pro        "6–10% referral fee"          → groceries 6 %, beauty / books / health / party 8 %,
--                                                       fashion / home & art 10 %, * 8 %
--   Provider Solo / Studio / Business "10% / 8% / 6% booking fee" → connection fee * 10 % / 8 % / 6 %
insert into fee_schedule_plan_rate (schedule_id, section, plan_code, category, rate_bps)
select s.id, 'COMMISSION', r.plan_code, r.category, r.rate_bps
  from fee_schedule s
 cross join (values ('seller_essentials', '*', 1500),
                    ('seller_growth', 'groceries', 800), ('seller_growth', 'beauty', 1000),
                    ('seller_growth', 'books', 1000), ('seller_growth', 'health', 1000),
                    ('seller_growth', 'party', 1000), ('seller_growth', 'fashion', 1200),
                    ('seller_growth', 'home & art', 1200), ('seller_growth', '*', 1000),
                    ('seller_pro', 'groceries', 600), ('seller_pro', 'beauty', 800),
                    ('seller_pro', 'books', 800), ('seller_pro', 'health', 800),
                    ('seller_pro', 'party', 800), ('seller_pro', 'fashion', 1000),
                    ('seller_pro', 'home & art', 1000), ('seller_pro', '*', 800))
       as r (plan_code, category, rate_bps);

-- The connection fee starts where services were: the version's 'services' commission (10 % at launch), or its default.
insert into fee_schedule_connection_fee (schedule_id, default_bps)
select s.id, coalesce((select r.commission_bps from fee_schedule_rate r
                        where r.schedule_id = s.id and r.category = 'services'), s.default_commission_bps)
  from fee_schedule s;

insert into fee_schedule_plan_rate (schedule_id, section, plan_code, category, rate_bps)
select s.id, 'CONNECTION_FEE', r.plan_code, '*', r.rate_bps
  from fee_schedule s
 cross join (values ('provider_solo', 1000), ('provider_studio', 800), ('provider_business', 600))
       as r (plan_code, rate_bps);

-- ---------------------------------------------------------------- 2. negotiated rates (staff, audited)
create table negotiated_rate (
    id            uuid primary key,
    org_id        varchar(80)  not null,
    type          varchar(20)  not null check (type in ('COMMISSION', 'CONNECTION_FEE')),
    category      varchar(80)  not null default '*',
    rate_bps      integer      not null check (rate_bps between 0 and 10000),
    starts_at     timestamptz  not null,
    ends_at       timestamptz,
    reason        text         not null check (length(trim(reason)) > 0),
    negotiated_by varchar(120) not null,
    reference     varchar(120),
    created_by    varchar(120) not null,
    created_at    timestamptz  not null,
    ended_at      timestamptz,
    ended_by      varchar(120),
    end_reason    text,
    expired_at    timestamptz,
    version       bigint       not null default 0,
    check (ends_at is null or ends_at > starts_at),
    check ((ended_at is null) = (ended_by is null) and (ended_at is null) = (end_reason is null))
);
create index negotiated_rate_org on negotiated_rate (org_id, type, category);
create index negotiated_rate_due on negotiated_rate (ends_at) where expired_at is null and ended_at is null;

-- Who created, ended or expired a negotiated rate, when and why (append-only; also logged to
-- audit.payments.negotiated-rates).
create table negotiated_rate_audit (
    id       uuid primary key,
    rate_id  uuid         not null references negotiated_rate (id),
    action   varchar(20)  not null check (action in ('CREATED', 'ENDED', 'EXPIRED')),
    actor    varchar(120) not null,
    reason   text,
    at       timestamptz  not null
);
create index negotiated_rate_audit_rate on negotiated_rate_audit (rate_id, at);
create trigger negotiated_rate_audit_immutable before update or delete on negotiated_rate_audit
    for each row execute function ledger_reject_change();

-- ---------------------------------------------------------------- 3. organisations' current plans
-- Projection of seller.subscription.changed (source EVENT), or of the seller service's billing API while no event
-- arrived yet (source API). plan_code null = no plan (cancelled, not activated, or the seller service has none).
create table org_plan (
    org_id      varchar(80) primary key,
    plan_code   varchar(80),
    status      varchar(40),
    source      varchar(10) not null check (source in ('EVENT', 'API')),
    occurred_at timestamptz not null,
    updated_at  timestamptz not null
);

-- ---------------------------------------------------------------- 4. the rate snapshotted on every line
-- fee_kind PRODUCT pays commission (4000), SERVICE a connection fee (4050). rate_* are null on lines created before
-- this migration: those keep the commission of their fee-schedule version (payment.fee_schedule_id).
alter table payment_line add column fee_kind varchar(10) not null default 'PRODUCT'
    check (fee_kind in ('PRODUCT', 'SERVICE'));
alter table payment_line add column rate_bps integer check (rate_bps between 0 and 10000);
alter table payment_line add column rate_source varchar(20)
    check (rate_source in ('OVERRIDE', 'PLAN', 'CATEGORY', 'DEFAULT', 'RETAIL'));
alter table payment_line add column rate_plan_code varchar(80);
alter table payment_line add column negotiated_rate_id uuid;
alter table payment_line add column negotiated_until timestamptz;
alter table payment_line add constraint payment_line_rate_snapshot_check
    check ((rate_bps is null) = (rate_source is null));
