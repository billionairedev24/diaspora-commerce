-- Offers: many sellers per variant ATIN; AfroTransact Retail (PLATFORM) competes like any seller.
create table offer (
    id               uuid primary key,
    atin             char(10)     not null,
    product_atin     char(10)     not null,
    product_id       uuid,
    gtin             varchar(14),
    category_id      uuid,
    seller_org       varchar(80)  not null,
    seller_kind      varchar(10)  not null check (seller_kind in ('PLATFORM', 'SELLER')),
    seller_sku       varchar(40)  not null,
    inventory_sku    varchar(40)  not null,
    item_condition   varchar(20)  not null,
    price_minor      bigint       not null check (price_minor > 0),
    list_price_minor bigint check (list_price_minor is null or list_price_minor >= price_minor),
    currency         char(3)      not null,
    shipping_minor   bigint       not null default 0 check (shipping_minor >= 0),
    fulfilment       varchar(20)  not null check (fulfilment in ('AFROTRANSACT', 'SELLER')),
    seller_quantity  int          not null default 0 check (seller_quantity >= 0),
    handling_days    int          not null check (handling_days between 0 and 30),
    ship_from        jsonb,
    status           varchar(20)  not null check (status in ('ACTIVE', 'PAUSED', 'WITHDRAWN')),
    product_live     boolean      not null,
    created_at       timestamptz  not null,
    updated_at       timestamptz  not null,
    version          bigint       not null default 0
);
-- One open offer per seller, variant and condition; seller SKUs unique among open offers.
create unique index offer_open_seller_atin_condition on offer (seller_org, atin, item_condition)
    where status <> 'WITHDRAWN';
create unique index offer_open_seller_sku on offer (seller_org, seller_sku) where status <> 'WITHDRAWN';
create index offer_atin on offer (atin) where status <> 'WITHDRAWN';
create index offer_product_atin on offer (product_atin) where status <> 'WITHDRAWN';
create index offer_inventory_sku on offer (seller_org, inventory_sku) where status <> 'WITHDRAWN';
create index offer_seller_created on offer (seller_org, created_at desc);

-- Projection of inventory.availability.changed (key sku + node); ignore older versions.
create table stock_level (
    sku        varchar(40)  not null,
    node_id    varchar(40)  not null,
    owner_org  varchar(80)  not null,
    available  int          not null,
    version    bigint       not null,
    updated_at timestamptz  not null,
    primary key (sku, node_id)
);
create index stock_level_owner_sku on stock_level (owner_org, sku);

-- Seller names (seller.org.*), enforcement (seller.listings.*) and performance inputs (staff / health job).
create table seller_standing (
    org_id          varchar(80)  primary key,
    display_name    varchar(200),
    rating          numeric(3, 2) check (rating between 0 and 5),
    on_time_rate    numeric(5, 4) check (on_time_rate between 0 and 1),
    defect_rate     numeric(5, 4) check (defect_rate between 0 and 1),
    restricted      boolean      not null default false,
    listings_hidden boolean      not null default false,
    updated_at      timestamptz  not null
);

-- Current buy box per ATIN and currency.
create table buy_box (
    atin                   char(10)     not null,
    currency               char(3)      not null,
    winner_offer_id        uuid,
    winner_seller_org      varchar(80),
    winner_price_minor     bigint,
    winner_landed_minor    bigint,
    offer_count            int          not null,
    min_landed_minor       bigint,
    score                  double precision not null,
    reasons                jsonb        not null,
    computed_at            timestamptz  not null,
    primary key (atin, currency)
);

-- Audit trail: every winner change with the full evaluation that produced it (append-only).
create table buy_box_decision (
    id                    uuid primary key,
    atin                  char(10)     not null,
    currency              char(3)      not null,
    previous_winner_id    uuid,
    winner_offer_id       uuid,
    trigger_event         varchar(80)  not null,
    offers                jsonb        not null,
    reasons               jsonb        not null,
    decided_at            timestamptz  not null
);
create index buy_box_decision_atin on buy_box_decision (atin, decided_at desc);

create table coupon (
    id                 uuid primary key,
    code               varchar(20)  not null unique,
    description        varchar(300),
    type               varchar(20)  not null check (type in ('PERCENT', 'FIXED', 'FREE_SHIPPING')),
    value              bigint       not null,
    currency           char(3)      not null,
    max_discount_minor bigint,
    scope              varchar(40)  not null,
    scope_ref          varchar(80),
    min_spend_minor    bigint       not null default 0,
    total_limit        int          not null default 0,
    per_customer_limit int          not null default 0,
    clip_required      boolean      not null default false,
    funding            varchar(10)  not null check (funding in ('PLATFORM', 'SELLER')),
    funded_by_org      varchar(80),
    starts_at          timestamptz  not null,
    ends_at            timestamptz,
    status             varchar(10)  not null check (status in ('ACTIVE', 'PAUSED', 'EXPIRED')),
    redeemed_count     bigint       not null default 0,
    created_by         varchar(120) not null,
    created_at         timestamptz  not null,
    updated_at         timestamptz  not null,
    version            bigint       not null default 0,
    check (funding = 'PLATFORM' or funded_by_org is not null)
);
create index coupon_funded_by on coupon (funded_by_org) where funded_by_org is not null;
create index coupon_clippable on coupon (currency) where clip_required and status = 'ACTIVE';

create table coupon_clip (
    coupon_id   uuid         not null references coupon (id),
    customer_id varchar(120) not null,
    clipped_at  timestamptz  not null,
    primary key (coupon_id, customer_id)
);

create table coupon_redemption (
    id                      uuid primary key,
    coupon_id               uuid         not null references coupon (id),
    code                    varchar(20)  not null,
    customer_id             varchar(120) not null,
    order_id                varchar(80)  not null,
    idempotency_key         varchar(120) not null unique,
    fingerprint             char(64)     not null,
    currency                char(3)      not null,
    discount_minor          bigint       not null,
    shipping_discount_minor bigint       not null,
    funding                 varchar(10)  not null,
    funded_by_org           varchar(80),
    lines                   jsonb        not null,
    status                  varchar(10)  not null check (status in ('REDEEMED', 'RELEASED')),
    created_at              timestamptz  not null,
    released_at             timestamptz
);
create index coupon_redemption_coupon_customer on coupon_redemption (coupon_id, customer_id) where status = 'REDEEMED';
create index coupon_redemption_order on coupon_redemption (order_id);
