-- tax-returns: order snapshots (from orders events), RMAs, refunds, seller return policies, tax rate table and
-- tax ledger. Money is bigint minor units with an ISO currency; tax rates are numeric fractions (0.062500 = 6.25 %).

create table order_snapshot (
    order_id            uuid primary key,
    order_number        varchar(40)  not null unique,
    customer_id         varchar(120) not null,
    currency            char(3)      not null,
    ship_to_country     varchar(2)   not null,
    ship_to_state       varchar(10)  not null,
    ship_to_postal_code varchar(20)  not null,
    ship_to_city        varchar(120),
    placed_at           timestamptz  not null,
    recorded_at         timestamptz  not null default now()
);
create index order_snapshot_customer on order_snapshot (customer_id);

create table shipment_snapshot (
    shipment_id     uuid primary key,
    order_id        uuid         not null references order_snapshot (order_id),
    position        int          not null,
    seller_org_id   varchar(80)  not null,
    seller_name     varchar(200),
    fulfilment_type varchar(30)  not null,
    delivery_method varchar(30)  not null,
    total_minor     bigint       not null,
    shipping_minor  bigint       not null check (shipping_minor >= 0),
    delivered_at    timestamptz
);
create index shipment_snapshot_order on shipment_snapshot (order_id);

create table order_line_snapshot (
    line_id          varchar(80) primary key,
    shipment_id      uuid        not null references shipment_snapshot (shipment_id),
    order_id         uuid        not null references order_snapshot (order_id),
    position         int         not null,
    variant_atin     varchar(40),
    title            varchar(300),
    quantity         int         not null check (quantity > 0),
    unit_price_minor bigint      not null check (unit_price_minor >= 0),
    tax_minor        bigint      not null check (tax_minor >= 0),
    product_type     varchar(80),
    perishable       boolean     not null default false
);
create index order_line_snapshot_order on order_line_snapshot (order_id);

create table seller_return_policy (
    org_id               varchar(80) primary key,
    window_days          int         not null check (window_days between 0 and 365),
    restocking_fee_bps   int         not null check (restocking_fee_bps between 0 and 10000),
    accepts_returns      boolean     not null,
    refund_at_first_scan boolean     not null,
    return_address       jsonb,
    updated_at           timestamptz not null,
    version              bigint      not null default 0
);

create table rma (
    id                   uuid primary key,
    number               varchar(20)  not null unique,
    order_id             uuid         not null references order_snapshot (order_id),
    order_number         varchar(40)  not null,
    shipment_id          uuid         not null references shipment_snapshot (shipment_id),
    customer_id          varchar(120) not null,
    seller_org_id        varchar(80)  not null,
    fulfilment_type      varchar(30)  not null,
    delivery_method      varchar(30)  not null,
    method               varchar(20)  not null,
    status               varchar(20)  not null,
    currency             char(3)      not null,
    estimate_minor       bigint       not null,
    auto_approved        boolean      not null default false,
    approval_due_at      timestamptz,
    refund_at_first_scan boolean      not null default false,
    refund_state         varchar(20)  not null default 'NONE',
    label_id             varchar(80),
    carrier              varchar(20),
    tracking_number      varchar(80),
    label_url            varchar(1000),
    qr_payload           varchar(200),
    handover_code        varchar(10),
    return_from          jsonb,
    drop_off_by          timestamptz,
    decision_note        varchar(1200),
    idempotency_key      varchar(160) not null,
    created_at           timestamptz  not null,
    updated_at           timestamptz  not null,
    version              bigint       not null default 0,
    constraint rma_idempotency unique (customer_id, idempotency_key)
);
create index rma_order on rma (order_id);
create index rma_customer on rma (customer_id, created_at desc);
create index rma_seller_status on rma (seller_org_id, status);
create index rma_status_due on rma (status, approval_due_at);
create index rma_tracking on rma (carrier, tracking_number) where tracking_number is not null;

create table rma_line (
    rma_id           uuid         not null references rma (id),
    line_id          varchar(80)  not null,
    position         int          not null,
    atin             varchar(40),
    title            varchar(300),
    quantity         int          not null check (quantity > 0),
    unit_price_minor bigint       not null,
    reason           varchar(40)  not null,
    comment          varchar(500),
    evidence         jsonb        not null default '[]',
    outcome          varchar(10),
    item_condition   varchar(10),
    stock_status     varchar(20)  not null default 'NOT_TRACKED',
    inspection_note  varchar(1000),
    primary key (rma_id, line_id)
);

create table rma_status_change (
    id          bigserial primary key,
    rma_id      uuid        not null references rma (id),
    from_status varchar(20),
    to_status   varchar(20) not null,
    actor       varchar(160) not null,
    note        varchar(1200),
    occurred_at timestamptz not null
);
create index rma_status_change_rma on rma_status_change (rma_id, id);

create table refund (
    id                 uuid primary key,
    kind               varchar(20)  not null,
    rma_id             uuid references rma (id),
    order_id           uuid         not null,
    payment_id         uuid         not null,
    currency           char(3)      not null,
    status             varchar(20)  not null,
    reason             varchar(500),
    request_key        varchar(220) not null unique,
    payments_refund_id varchar(80),
    failure            varchar(1000),
    attempts           int          not null default 0,
    items_minor        bigint       not null,
    shipping_minor     bigint       not null,
    tax_minor          bigint       not null,
    fee_minor          bigint       not null,
    total_minor        bigint       not null check (total_minor = items_minor + shipping_minor + tax_minor),
    created_at         timestamptz  not null,
    issued_at          timestamptz,
    version            bigint       not null default 0
);
create index refund_order on refund (order_id);
create index refund_pending on refund (created_at) where status = 'PENDING';

create table refund_line (
    refund_id            uuid        not null references refund (id),
    line_id              varchar(80) not null,
    position             int         not null,
    quantity             int         not null check (quantity >= 0),
    items_minor          bigint      not null check (items_minor >= 0),
    shipping_minor       bigint      not null check (shipping_minor >= 0),
    tax_minor            bigint      not null check (tax_minor >= 0),
    restocking_fee_minor bigint      not null check (restocking_fee_minor >= 0),
    primary key (refund_id, line_id)
);

create table tax_state_rule (
    country            varchar(2)   not null,
    state              varchar(10)  not null,
    name               varchar(80)  not null,
    general_rate       numeric(8,6) not null check (general_rate >= 0 and general_rate < 1),
    grocery_rate       numeric(8,6) not null check (grocery_rate >= 0 and grocery_rate < 1),
    prepared_food_rate numeric(8,6) not null check (prepared_food_rate >= 0 and prepared_food_rate < 1),
    shipping_taxable   boolean      not null,
    facilitator        boolean      not null,
    updated_at         timestamptz  not null default now(),
    version            bigint       not null default 0,
    primary key (country, state)
);

create table tax_local_rate (
    state              varchar(10)  not null,
    zip_prefix         varchar(5)   not null check (zip_prefix ~ '^([0-9]{3}|[0-9]{5})$'),
    name               varchar(120) not null,
    rate               numeric(8,6) not null check (rate >= 0 and rate < 1),
    applies_to_grocery boolean      not null default false,
    updated_at         timestamptz  not null default now(),
    primary key (state, zip_prefix)
);

create table tax_transaction (
    id                 uuid primary key,
    kind               varchar(10)  not null,
    order_id           uuid         not null,
    order_number       varchar(40),
    line_id            varchar(80)  not null,
    source_ref         varchar(80)  not null,
    seller_org_id      varchar(80),
    jurisdiction       varchar(20)  not null,
    local_jurisdiction varchar(30),
    postal_code        varchar(20),
    currency           char(3)      not null,
    taxable_minor      bigint       not null,
    tax_minor          bigint       not null,
    occurred_at        timestamptz  not null,
    period             date         not null,
    voided             boolean      not null default false,
    constraint tax_transaction_source unique (kind, source_ref, line_id)
);
create index tax_transaction_period on tax_transaction (period, jurisdiction);
create index tax_transaction_seller on tax_transaction (seller_org_id, period);
create index tax_transaction_order on tax_transaction (order_id, line_id);
