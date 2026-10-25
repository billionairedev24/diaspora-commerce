-- S264: delivery runs. delivery-runs-service hands a closed pre-order window's orders to a run; fulfilment plans one
-- sweep from it: a route per capacity chunk (seller pickups before drops) and follows it to the end.

-- A confirmed run as dispatch knows it (the schedule, the pickup point and the hub are copied when it is confirmed).
create table run_dispatch (
    run_id           varchar(40)      primary key,
    town             varchar(120)     not null,
    state            varchar(60),
    time_zone        varchar(60)      not null,
    service_date     date             not null,
    window_start     timestamptz      not null,
    window_end       timestamptz      not null,
    seller_pickup_at timestamptz,
    doorstep         boolean          not null default true,
    pickup_point     jsonb,
    origin_lat       double precision,
    origin_lon       double precision,
    depot_node_id    varchar(20),
    status           varchar(20)      not null,
    plan_error       varchar(500),
    planned_at       timestamptz,
    created_at       timestamptz      not null,
    updated_at       timestamptz      not null,
    version          integer          not null default 0,
    constraint run_dispatch_status_chk check (status in ('PENDING', 'PLANNED', 'CANCELLED'))
);
create index run_dispatch_day on run_dispatch (service_date desc, status);

-- The routes of a run, in the order they were planned (a run too big for one van gets several).
create table run_route (
    run_id   varchar(40) not null references run_dispatch (run_id),
    route_id varchar(20) not null references delivery_route (id),
    seq      integer     not null,
    primary key (run_id, route_id)
);
create unique index run_route_route on run_route (route_id);

-- The shipments planned on a run; a shipment rides on at most one run.
create table run_shipment (
    shipment_id varchar(64) primary key references delivery_shipment (shipment_id),
    run_id      varchar(40) not null references run_dispatch (run_id)
);
create index run_shipment_run on run_shipment (run_id);

-- A pre-order package mixes the lines of several sellers: each seller packs its own labelled bag per order.
create table shipment_bag (
    shipment_id   varchar(64)  not null references delivery_shipment (shipment_id),
    seller_org_id varchar(80)  not null,
    seller_name   varchar(200),
    label         varchar(12)  not null unique,
    units         integer      not null check (units >= 1),
    primary key (shipment_id, seller_org_id)
);
