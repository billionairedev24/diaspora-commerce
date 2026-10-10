-- Orders service: orders, per-seller shipments (sub-orders), lines, the shipment status history, checkout idempotency
-- and follow-up tasks (capture / settle / cancel). Money is bigint minor units.

create table customer_order (
    id             uuid primary key,
    number         varchar(20)  not null unique,
    customer_id    varchar(80)  not null,
    currency       char(3)      not null,
    status         varchar(20)  not null,
    items_minor    bigint       not null check (items_minor >= 0),
    shipping_minor bigint       not null check (shipping_minor >= 0),
    tax_minor      bigint       not null check (tax_minor >= 0),
    discount_minor bigint       not null check (discount_minor >= 0),
    total_minor    bigint       not null check (total_minor >= 0),
    ship_to        jsonb,
    payment_id     varchar(80),
    workflow_id    varchar(120),
    cancel_reason  text,
    placed_at      timestamptz,
    created_at     timestamptz  not null,
    updated_at     timestamptz  not null,
    version        bigint       not null default 0,
    constraint customer_order_total check (total_minor = items_minor + shipping_minor + tax_minor - discount_minor)
);
create index customer_order_customer on customer_order (customer_id, created_at desc);
create index customer_order_status on customer_order (status, created_at desc);

create table shipment (
    id                     uuid primary key,
    order_id               uuid         not null references customer_order (id),
    seq                    int          not null,
    seller_org_id          varchar(80)  not null,
    seller_name            varchar(200),
    fulfilment_type        varchar(30)  not null,
    node_id                varchar(40),
    delivery_method        varchar(30)  not null,
    service_level          varchar(40),
    pickup_location_id     varchar(80),
    preferences            jsonb        not null,
    perishable             boolean      not null default false,
    handover_required      boolean      not null default false,
    handover_code_hash     varchar(64),
    handover_code_sealed   text,
    status                 varchar(30)  not null,
    shipping_minor         bigint       not null check (shipping_minor >= 0),
    carrier                varchar(20),
    tracking_number        varchar(80),
    promised_from          date,
    promised_by            date,
    capture_id             varchar(80),
    captured_at            timestamptz,
    delivered_at           timestamptz,
    last_external_event_at timestamptz,
    status_changed_at      timestamptz,
    created_at             timestamptz  not null,
    updated_at             timestamptz  not null,
    version                bigint       not null default 0,
    unique (order_id, seq),
    constraint shipment_handover_hash check (not handover_required or handover_code_hash is not null)
);
create index shipment_order on shipment (order_id);
create index shipment_seller_queue on shipment (seller_org_id, status, created_at desc);
create unique index shipment_tracking on shipment (carrier, tracking_number) where tracking_number is not null;

create table order_line (
    id                uuid primary key,
    shipment_id       uuid         not null references shipment (id),
    order_id          uuid         not null references customer_order (id),
    line_no           int          not null,
    variant_atin      varchar(64)  not null,
    product_atin      varchar(64),
    title             varchar(300),
    image_url         varchar(1000),
    quantity          int          not null check (quantity > 0),
    unit_price_minor  bigint       not null check (unit_price_minor >= 0),
    tax_minor         bigint       not null check (tax_minor >= 0),
    discount_minor    bigint       not null default 0 check (discount_minor >= 0),
    category          varchar(80),
    perishable        boolean      not null default false,
    tax_jurisdiction  varchar(40),
    unique (order_id, line_no)
);
create index order_line_shipment on order_line (shipment_id);

-- Every applied transition; (shipment_id, event_id) makes transitions idempotent per event.
create table shipment_status_change (
    id           bigserial primary key,
    shipment_id  uuid         not null references shipment (id),
    order_id     uuid         not null references customer_order (id),
    event_id     varchar(200) not null,
    from_status  varchar(30),
    to_status    varchar(30)  not null,
    actor_type   varchar(30)  not null,
    actor_id     varchar(120) not null,
    actor_org    varchar(80),
    reason       text,
    source       varchar(20)  not null,
    proof        jsonb,
    occurred_at  timestamptz  not null,
    recorded_at  timestamptz  not null default now(),
    unique (shipment_id, event_id)
);
create index shipment_status_change_order on shipment_status_change (order_id, id);

-- POST /v1/checkout idempotency: one row per (customer, Idempotency-Key), fixing order id / number / workflow id.
create table checkout_request (
    customer_id      varchar(80)  not null,
    idem_key         varchar(160) not null,
    fingerprint      varchar(64)  not null,
    order_id         uuid         not null unique,
    order_number     varchar(20)  not null unique,
    workflow_id      varchar(120) not null,
    status           varchar(20)  not null,
    payment_id       varchar(80),
    client_secret    varchar(300),
    failure_code     varchar(80),
    failure_message  text,
    created_at       timestamptz  not null,
    updated_at       timestamptz  not null,
    primary key (customer_id, idem_key)
);

-- Side effects of transitions, written in the same transaction and executed with retries.
create table follow_up_task (
    id               uuid primary key,
    task_key         varchar(200) not null unique,
    kind             varchar(20)  not null,
    order_id         uuid         not null,
    shipment_id      uuid,
    status           varchar(10)  not null default 'PENDING',
    attempts         int          not null default 0,
    next_attempt_at  timestamptz  not null,
    last_error       text,
    created_at       timestamptz  not null,
    updated_at       timestamptz  not null
);
create index follow_up_task_due on follow_up_task (next_attempt_at) where status = 'PENDING';
