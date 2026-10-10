-- S235: the customer's street address and per-line discount / tax details from orders.order.placed (or the orders
-- lookup), so returns never ask the app for an address; driver pickups scheduled with fulfilment-service.
alter table order_snapshot
    add column ship_to_name  varchar(200),
    add column ship_to_line1 varchar(200),
    add column ship_to_line2 varchar(200),
    add column ship_to_phone varchar(40),
    add column payment_id    varchar(80);

alter table order_line_snapshot
    add column discount_minor         bigint      not null default 0,
    add column tax_jurisdiction       varchar(40),
    add column local_tax_jurisdiction varchar(40),
    add column shipping_tax_minor     bigint      not null default 0;

create table rma_pickup (
    rma_id      uuid primary key references rma (id),
    pickup_id   varchar(64),
    status      varchar(20)  not null,
    attempts    int          not null default 0,
    last_error  varchar(500),
    updated_at  timestamptz  not null
);
