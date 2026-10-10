-- S255: AfroTransact Delivery from the seller's store — nearest available driver for SELLER-origin pickups.
create extension if not exists postgis;

-- Last known position per driver: from driver-app sync events (GPS on every event) and POST /v1/driver/location.
-- Newer positions only (recorded_at); positions older than the staleness window are ignored by the search.
create table driver_position (
    driver_id   varchar(80)            primary key,
    position    geography(Point, 4326) not null,
    accuracy_m  double precision,
    recorded_at timestamptz            not null,
    source      varchar(10)            not null check (source in ('SYNC', 'PING')),
    device_id   varchar(120),
    updated_at  timestamptz            not null default now()
);
create index driver_position_gix on driver_position using gist (position);
create index driver_position_fresh on driver_position (recorded_at);

-- Where the driver collects a SELLER-origin package (the seller's pickup location, from orders.order.placed).
alter table delivery_shipment add column pickup_location_id varchar(64);
alter table delivery_shipment add column pickup_lat double precision;
alter table delivery_shipment add column pickup_lon double precision;

-- Who got a pickup and why: automatic nearest-driver assignments and dispatch overrides (reason required).
create table pickup_assignment (
    id               uuid         primary key,
    shipment_id      varchar(64)  not null,
    route_id         varchar(20)  not null,
    driver_id        varchar(80),
    mode             varchar(20)  not null check (mode in ('AUTO_NEAREST', 'DISPATCH_OVERRIDE')),
    reason           varchar(500),
    previous_route_id varchar(20),
    straight_miles   double precision,
    road_miles       double precision,
    distance_method  varchar(20),
    actor            varchar(120) not null,
    created_at       timestamptz  not null,
    check (mode <> 'DISPATCH_OVERRIDE' or reason is not null)
);
create index pickup_assignment_shipment on pickup_assignment (shipment_id, created_at);
