-- S235: return pickups scheduled by tax-returns, collected on RETURN_PICKUP route stops.
-- S229: ship-by date per AfroTransact Delivery shipment (from orders.order.placed) for seller-health lateness.
alter table route_stop alter column kind type varchar(20);
alter table route_stop drop constraint route_stop_kind_chk;
alter table route_stop add constraint route_stop_kind_chk check (kind in ('PICKUP', 'DROPOFF', 'RETURN_PICKUP'));

alter table delivery_shipment add column ship_by date;

create table return_pickup (
    id            varchar(64)  primary key,
    rma_id        varchar(64)  not null unique,
    rma_number    varchar(40)  not null,
    order_id      varchar(64),
    order_number  varchar(40),
    shipment_id   varchar(64),
    customer_id   varchar(120),
    seller_org_id varchar(80),
    name          varchar(200),
    phone         varchar(40),
    line1         varchar(200) not null,
    line2         varchar(200),
    city          varchar(100),
    state         varchar(60),
    postal_code   varchar(20)  not null,
    country       varchar(2),
    lat           double precision,
    lon           double precision,
    depot_node_id varchar(20),
    ready_from    date,
    collect_by    date,
    units         integer      not null check (units > 0),
    status        varchar(12)  not null,
    route_id      varchar(20),
    stop_id       varchar(64),
    note          varchar(1000),
    created_at    timestamptz  not null,
    updated_at    timestamptz  not null,
    constraint return_pickup_status_chk check (status in ('REQUESTED', 'ROUTED', 'COLLECTED', 'FAILED', 'CANCELLED'))
);
create index return_pickup_open on return_pickup (depot_node_id, collect_by) where status = 'REQUESTED';
create index return_pickup_stop on return_pickup (stop_id) where stop_id is not null;
