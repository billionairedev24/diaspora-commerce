-- S263 remainder: the orders of a delivery run are credited to the advertorial that led to them (the order's
-- attribution cr=adv-... arrives with orders.order.placed), and a location advertorial can feature up to four products
-- staff chose. S262 remainder: attributed visits and sign-ups are counted per location, run and UTM tags.

alter table local_advertorial add column featured_products jsonb not null default '[]'::jsonb;

-- The creative an order was attributed to (any order; the run it rides on is found through run_order).
create table order_attribution (
    order_id  varchar(80)  primary key,
    creative  varchar(64)  not null,
    src       varchar(16),
    placed_at timestamptz  not null
);
create index order_attribution_creative on order_attribution (creative);

-- Visits and sign-ups per UTC day, location, run and UTM tags. Counters only: no visitor, session, IP or address is
-- stored, so there is nothing to erase. run_key is the run id, or the nil UUID for the location page itself.
create table run_attribution_stat (
    day          date          not null,
    location_id  uuid          not null references location (id),
    run_key      uuid          not null,
    utm_source   varchar(40)   not null,
    utm_medium   varchar(40)   not null,
    utm_campaign varchar(80)   not null,
    kind         varchar(8)    not null check (kind in ('VISIT', 'SIGNUP')),
    n            bigint        not null default 0,
    primary key (day, location_id, run_key, utm_source, utm_medium, utm_campaign, kind)
);
create index run_attribution_stat_run on run_attribution_stat (run_key);

-- The UTM tags a sign-up arrived with, kept until the subscription becomes active (an email subscriber confirms later),
-- then counted once and removed.
create table subscription_attribution (
    subscription_id uuid         primary key references subscription (id) on delete cascade,
    run_key         uuid         not null,
    utm_source      varchar(40)  not null,
    utm_medium      varchar(40)  not null,
    utm_campaign    varchar(80)  not null
);
