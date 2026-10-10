-- Pre-orders (S270, S271): pre-order windows with targets, creatives and comms, platform settings with coverage
-- areas, and the orders projection. Events go through the shared platform_outbox (db/platform).

create table preorder_window (
    id                        uuid primary key,
    code                      varchar(16)   not null unique,
    name                      varchar(120)  not null default '',
    opens_at                  timestamptz   not null,
    closes_at                 timestamptz   not null,
    delivers_on               date          not null,
    time_zone                 varchar(64)   not null,
    ribbon                    varchar(80)   not null default '',
    headline                  varchar(140)  not null default '',
    sms_copy                  varchar(320)  not null default '',
    categories                jsonb         not null default '[]'::jsonb,
    door                      boolean       not null default true,
    pickup                    boolean       not null default false,
    pickup_point_id           varchar(80),
    pickup_address            varchar(200),
    show_count                boolean       not null default true,
    social_budget_minor       bigint        not null default 0 check (social_budget_minor >= 0),
    currency                  char(3)       not null,
    comms                     jsonb         not null default '[]'::jsonb,
    door_fee_override_minor   bigint        check (door_fee_override_minor between 0 and 10000),
    pickup_fee_override_minor bigint        check (pickup_fee_override_minor between 0 and 10000),
    location_id               uuid          references location (id),
    draft                     boolean       not null default true,
    active                    boolean       not null default true,
    run_id                    uuid          references run (id),
    created_at                timestamptz   not null,
    updated_at                timestamptz   not null,
    version                   bigint        not null default 0
);
create index preorder_window_published on preorder_window (closes_at) where draft = false;

-- Where a window is offered: country/region (whole region) or country/region/city.
create table preorder_window_target (
    window_id uuid         not null references preorder_window (id) on delete cascade,
    position  integer      not null,
    country   char(2)      not null,
    region    varchar(3)   not null,
    city      varchar(80),
    primary key (window_id, position)
);
create index preorder_window_target_place on preorder_window_target (country, region, lower(city));

-- Advertorial images per slot (media-service assets, measured when attached).
create table preorder_creative (
    window_id  uuid          not null references preorder_window (id) on delete cascade,
    slot       varchar(16)   not null check (slot in ('HERO', 'PDP', 'STORY', 'FEED', 'EMAIL')),
    asset_id   uuid          not null,
    url        varchar(2000),
    width      integer       not null,
    height     integer       not null,
    updated_at timestamptz   not null,
    primary key (window_id, slot)
);

-- Scheduler steps done per window (comms due at opening, close − 48 h, close − 6 h, closing; lifecycle markers):
-- one row per (window, step), so every step happens once across replicas.
create table preorder_comms_log (
    window_id uuid         not null references preorder_window (id) on delete cascade,
    step      varchar(32)  not null,
    due_at    timestamptz  not null,
    done_at   timestamptz  not null,
    primary key (window_id, step)
);

-- Platform settings: a single row.
create table preorder_settings (
    id         smallint     primary key check (id = 1),
    enabled    boolean      not null,
    fallback   varchar(16)  not null check (fallback in ('SHIP', 'WAITLIST')),
    placements jsonb        not null,
    payment    jsonb        not null,
    updated_at timestamptz  not null,
    version    bigint       not null default 0
);
insert into preorder_settings (id, enabled, fallback, placements, payment, updated_at)
values (1, true, 'SHIP', '["RIBBON", "HERO", "BADGE", "PDP", "CART", "FAMILY"]'::jsonb,
        '{"doorFees": {"USD": 799, "CAD": 999}, "pickupFees": {}, "allowedMethods": ["CARD", "WALLET", "ACH"],
          "cancelUntil": "CLOSE", "lateAdd": {"savedCard": true, "paymentLink": true, "linkExpiryHours": 24},
          "missedDelivery": {"offerRefund": true, "offerNewDate": true}, "pickupWindow": "10am–2pm"}'::jsonb,
        now());

-- Coverage switches: a city row overrides its region row; a region row covers every city in it.
create table preorder_area (
    id         bigserial    primary key,
    country    char(2)      not null,
    region     varchar(3)   not null,
    city       varchar(80),
    enabled    boolean      not null default true,
    created_at timestamptz  not null,
    updated_at timestamptz  not null
);
create unique index preorder_area_key on preorder_area (country, region, lower(coalesce(city, '')));

-- Orders projection (minimum PII for dispatch; deleted 90 days after delivery).
create table preorder_order (
    order_id      varchar(80)   primary key,
    number        varchar(32)   not null,
    window_id     uuid          not null references preorder_window (id),
    customer_id   varchar(120),
    customer_name varchar(200),
    phone         varchar(40),
    email         varchar(254),
    address_line  varchar(300),
    city          varchar(120),
    region        varchar(40),
    zip           varchar(20),
    delivery_note varchar(500),
    placed_at     timestamptz   not null,
    channel       varchar(16)   not null,
    tracking_tag  varchar(200),
    packages      jsonb         not null default '[]'::jsonb,
    fulfilment    varchar(8)    not null check (fulfilment in ('DOOR', 'PICKUP')),
    currency      char(3)       not null,
    status        varchar(16)   not null check (status in ('PAID', 'LATE_ADD', 'CANCELLED')),
    note          varchar(500),
    total_minor   bigint        not null default 0,
    units         integer       not null default 0,
    created_at    timestamptz   not null,
    updated_at    timestamptz   not null,
    version       bigint        not null default 0
);
create unique index preorder_order_number on preorder_order (window_id, number);
create index preorder_order_window_placed on preorder_order (window_id, placed_at desc);

-- Waitlist sign-ups ("email me when a pre-order opens here"); email only, one per address and area.
create table preorder_waitlist (
    id                uuid primary key,
    country           char(2)       not null,
    region            varchar(3)    not null,
    city              varchar(80),
    email             varchar(254)  not null,
    source            varchar(80)   not null,
    active            boolean       not null default true,
    unsubscribe_token varchar(64)   not null unique,
    created_at        timestamptz   not null,
    updated_at        timestamptz   not null,
    version           bigint        not null default 0
);
create unique index preorder_waitlist_email_area
    on preorder_waitlist (email, country, region, lower(coalesce(city, '')));
create index preorder_waitlist_active on preorder_waitlist (country, region) where active;

-- Counters: window codes per prefix (LBK → LBK-7), order numbers per window (1001…, late adds L90…).
create table preorder_sequence (
    name       varchar(80)  primary key,
    last_value integer      not null
);
