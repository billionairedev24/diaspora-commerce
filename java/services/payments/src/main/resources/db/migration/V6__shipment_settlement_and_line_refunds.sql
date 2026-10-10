-- S218: the settlement hold can start per shipment; S235: tax on shipping is kept apart on payment lines.
create table shipment_settlement (
    order_id      varchar(80) not null,
    shipment_id   varchar(80) not null,
    kind          varchar(20) not null,
    delivered_at  timestamptz not null,
    release_at    timestamptz not null,
    status        varchar(20) not null,
    next_check_at timestamptz not null,
    updated_at    timestamptz not null,
    primary key (order_id, shipment_id)
);
create index shipment_settlement_due on shipment_settlement (next_check_at) where status = 'OPEN';

alter table payment_line add column shipping_tax_minor bigint not null default 0;
alter table payment_line add constraint payment_line_shipping_tax_check
    check (shipping_tax_minor between 0 and tax_minor);
