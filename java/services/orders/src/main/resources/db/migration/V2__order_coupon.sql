-- S222: coupon redeemed with offers-service for an order (Idempotency-Key = order id). Released when the checkout
-- fails or every shipment of the order is cancelled.
create table order_coupon (
    order_id                uuid         primary key references customer_order (id),
    code                    varchar(20)  not null,
    redemption_id           varchar(80)  not null,
    discount_minor          bigint       not null check (discount_minor >= 0),
    shipping_discount_minor bigint       not null check (shipping_discount_minor >= 0),
    funding                 varchar(20),
    funded_by_org           varchar(80),
    status                  varchar(10)  not null check (status in ('REDEEMED', 'RELEASED')),
    created_at              timestamptz  not null,
    released_at             timestamptz
);
