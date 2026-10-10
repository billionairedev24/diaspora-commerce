-- V51 (V50 is ship_from_address, so new migrations continue after it).
--
-- S215: what the organisation offers. kind stays SELLER / PROVIDER (onboarding portal, role prefix, plans);
-- offering BOTH makes seller.org.* events carry kind BOTH.
alter table organisation add column offering varchar(16)
    check (offering is null or offering in ('PRODUCTS', 'SERVICES', 'BOTH'));
update organisation o set offering = a.data -> 'business' ->> 'offering'
from application a
where a.org_id = o.id and a.data -> 'business' ->> 'offering' in ('PRODUCTS', 'SERVICES', 'BOTH');

-- S236: org_member mirrors identity's identity.role.granted / revoked (orgId); updated_at orders the changes.
alter table org_member add column updated_at timestamptz;
update org_member set updated_at = created_at;
alter table org_member alter column updated_at set not null;

-- S206 metered billing: usage per organisation, meter and UTC day, pulled from inventory (FBA storage),
-- content (ads spend) and ai-gateway (AI credits). quantity is in the meter's integer unit (see UsageMeter);
-- reported_quantity is what Stripe Billing has received (sum of meter events), report_revision numbers the meter
-- event identifiers "<org>:<meter>:<day>:r<revision>" so a retried report is de-duplicated by Stripe.
create table usage_daily (
    org_id            varchar(40) not null references organisation (id),
    meter             varchar(24) not null check (meter in ('FBA_STORAGE', 'ADS_SPEND', 'AI_CREDITS')),
    day               date        not null,
    quantity          bigint      not null check (quantity >= 0),
    reported_quantity bigint      not null default 0 check (reported_quantity >= 0),
    report_revision   int         not null default 0,
    reported_at       timestamptz,
    last_error        text,
    updated_at        timestamptz not null,
    primary key (org_id, meter, day)
);
create index usage_daily_unreported on usage_daily (day) where quantity <> reported_quantity;

-- Every meter event sent to Stripe (audit).
create table usage_report (
    identifier         varchar(160) primary key,
    org_id             varchar(40)  not null references organisation (id),
    meter              varchar(24)  not null,
    day                date         not null,
    value              bigint       not null,
    stripe_customer_id varchar(80)  not null,
    created_at         timestamptz  not null
);
create index usage_report_org on usage_report (org_id, day);

-- Metered prices added to the organisation's Stripe subscription, so its invoices carry the usage lines.
create table metered_subscription_item (
    org_id                 varchar(40) not null references organisation (id),
    meter                  varchar(24) not null,
    stripe_subscription_id varchar(80) not null,
    stripe_item_id         varchar(80) not null,
    created_at             timestamptz not null,
    primary key (org_id, meter, stripe_subscription_id)
);

-- Metering runs (the daily job and staff re-runs) and the lease that keeps one run at a time across replicas.
create table usage_run (
    id               uuid        primary key,
    from_day         date        not null,
    to_day           date        not null,
    trigger          varchar(8)  not null check (trigger in ('JOB', 'STAFF')),
    actor_id         varchar(80) not null,
    status           varchar(12) not null check (status in ('RUNNING', 'COMPLETED', 'PARTIAL', 'FAILED')),
    rows_changed     int         not null default 0,
    reported         int         not null default 0,
    pending          int         not null default 0,
    corrections      int         not null default 0,
    failed_sources   jsonb       not null default '[]',
    started_at       timestamptz not null,
    finished_at      timestamptz
);
create index usage_run_started on usage_run (started_at desc);

create table usage_run_lease (
    name        varchar(40) primary key,
    holder      uuid,
    leased_until timestamptz not null
);
insert into usage_run_lease (name, holder, leased_until) values ('metering', null, '1970-01-01T00:00:00Z');

-- Invoice lines from Stripe (metered usage lines included) for the Billing page.
alter table invoice add column lines jsonb not null default '[]';
