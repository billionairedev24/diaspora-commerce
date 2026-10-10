-- fulfilment-service: fulfilment centres, pick/pack tasks, the dispatch pool, delivery routes with stops and packages,
-- vehicle inspections and defects, driver access, the offline driver-event log, SOS alerts and the orders-service
-- command queue. Outbox / inbox tables come from classpath:db/platform.

create table fulfilment_centre (
    id           varchar(20)  primary key,
    name         varchar(120) not null,
    kind         varchar(30)  not null default 'FULFILMENT_CENTRE',
    timezone     varchar(60)  not null default 'America/Chicago',
    address_line varchar(200),
    city         varchar(100),
    state        varchar(60),
    postal_code  varchar(20),
    country      varchar(2)   not null default 'US',
    lat          double precision,
    lon          double precision,
    cold_chain   boolean      not null default false,
    active       boolean      not null default true,
    created_at   timestamptz  not null default now(),
    updated_at   timestamptz  not null default now()
);

-- Pick and pack of one FBA / Retail 1P shipment.
create table fc_task (
    id               uuid         primary key,
    shipment_id      varchar(64)  not null unique,
    order_id         varchar(64)  not null,
    order_number     varchar(40)  not null,
    fulfilment_type  varchar(30)  not null,
    delivery_method  varchar(30)  not null,
    node_id          varchar(20),
    status           varchar(20)  not null,
    lines            jsonb        not null,
    package_label    varchar(12),
    weight_lb        numeric(6, 2),
    carrier          varchar(30),
    tracking_number  varchar(64),
    exception_reason varchar(500),
    operator         varchar(80),
    created_at       timestamptz  not null default now(),
    updated_at       timestamptz  not null,
    version          integer      not null default 0,
    constraint fc_task_status_chk check (status in ('PICK_PENDING', 'PICKING', 'PICK_EXCEPTION', 'PICKED', 'PACKED',
                                                    'READY', 'CANCELLED'))
);
create index fc_task_queue on fc_task (node_id, status, created_at);

-- AfroTransact Delivery shipments (dispatch pool).
create table delivery_shipment (
    shipment_id       varchar(64)  primary key,
    order_id          varchar(64)  not null,
    order_number      varchar(40)  not null,
    customer_id       varchar(80),
    seller_org_id     varchar(80),
    seller_name       varchar(200),
    fulfilment_type   varchar(30)  not null,
    origin            varchar(10)  not null,
    node_id           varchar(20),
    label             varchar(12)  unique,
    city              varchar(100),
    state             varchar(60),
    postal_code       varchar(20),
    country           varchar(2),
    lat               double precision,
    lon               double precision,
    perishable        boolean      not null default false,
    handover_required boolean      not null default false,
    units             integer      not null default 1,
    status            varchar(20)  not null,
    route_id          varchar(20),
    promised_by       date,
    created_at        timestamptz  not null,
    updated_at        timestamptz  not null default now(),
    constraint delivery_shipment_origin_chk check (origin in ('FC', 'SELLER')),
    constraint delivery_shipment_status_chk check (status in ('AWAITING_ROUTE', 'ROUTED', 'DELIVERED', 'FAILED',
                                                              'CANCELLED'))
);
create index delivery_shipment_pool on delivery_shipment (status, node_id, postal_code);

create table delivery_route (
    id                     varchar(20)  primary key,
    name                   varchar(120) not null,
    zone                   varchar(120) not null,
    service_date           date         not null,
    time_window            varchar(60),
    depot_node_id          varchar(20)  not null references fulfilment_centre (id),
    driver_id              varchar(80),
    vehicle_id             varchar(60),
    status                 varchar(20)  not null,
    inspection             varchar(30),
    blocked                boolean      not null default false,
    blocked_reason         varchar(500),
    start_odometer         integer,
    end_odometer           integer,
    load_out_completed_at  timestamptz,
    started_at             timestamptz,
    ended_at               timestamptz,
    created_at             timestamptz  not null default now(),
    updated_at             timestamptz  not null default now(),
    version                integer      not null default 0,
    constraint delivery_route_status_chk check (status in ('PLANNED', 'ASSIGNED', 'IN_PROGRESS', 'COMPLETED',
                                                           'CANCELLED'))
);
create index delivery_route_day on delivery_route (service_date, status);
create index delivery_route_driver on delivery_route (driver_id, status);

create table route_stop (
    id                  uuid         primary key,
    route_id            varchar(20)  not null references delivery_route (id),
    seq                 integer      not null,
    kind                varchar(10)  not null,
    name                varchar(200) not null,
    seller_org_id       varchar(80),
    order_number        varchar(40),
    address_line        varchar(200),
    city                varchar(100),
    postal_code         varchar(20),
    lat                 double precision,
    lon                 double precision,
    time_window         varchar(60),
    handover_required   boolean      not null default false,
    safe_place_allowed  boolean      not null default false,
    status              varchar(20)  not null,
    reattempts          integer      not null default 0,
    contacts            jsonb        not null default '[]'::jsonb,
    partial_accepted    boolean      not null default false,
    handed_over_by      varchar(120),
    note                varchar(1000),
    handover_method     varchar(20),
    pod_distance_m      double precision,
    reassigned_to       uuid,
    updated_at          timestamptz  not null,
    constraint route_stop_kind_chk check (kind in ('PICKUP', 'DROPOFF'))
);
create index route_stop_route on route_stop (route_id, seq);

create table route_package (
    id               uuid         primary key,
    route_id         varchar(20)  not null references delivery_route (id),
    label            varchar(12)  not null,
    shipment_id      varchar(64)  not null,
    order_number     varchar(40),
    origin           varchar(10)  not null,
    pickup_stop_id   uuid,
    dropoff_stop_id  uuid         not null,
    perishable       boolean      not null default false,
    status           varchar(20)  not null,
    pickup_scanned   boolean      not null default false,
    drop_scanned     boolean      not null default false,
    manual_entry     boolean      not null default false,
    updated_at       timestamptz  not null
);
create index route_package_route on route_package (route_id);
create index route_package_shipment on route_package (shipment_id);
-- A label is active on at most one route stop at a time (reassigned / cancelled copies stay for history).
create unique index route_package_active_label on route_package (route_id, label)
    where status not in ('REASSIGNED', 'CANCELLED');

create table vehicle_inspection (
    id           uuid         primary key,
    event_id     varchar(80)  not null unique,
    route_id     varchar(20)  not null references delivery_route (id),
    driver_id    varchar(80)  not null,
    vehicle_id   varchar(60)  not null,
    result       varchar(30)  not null,
    odometer     integer      not null,
    fuel_percent integer      not null,
    cold_box_f   double precision,
    report       jsonb        not null,
    captured_at  timestamptz,
    recorded_at  timestamptz  not null
);
create index vehicle_inspection_vehicle on vehicle_inspection (vehicle_id, recorded_at desc);

create table vehicle_odometer (
    vehicle_id  varchar(60) not null,
    route_id    varchar(20) not null,
    odometer    integer     not null,
    recorded_at timestamptz not null
);
create index vehicle_odometer_vehicle on vehicle_odometer (vehicle_id, odometer desc);

create table vehicle_defect (
    id            uuid          primary key,
    inspection_id uuid          references vehicle_inspection (id),
    route_id      varchar(20)   not null,
    vehicle_id    varchar(60)   not null,
    driver_id     varchar(80)   not null,
    critical      boolean       not null,
    items         jsonb         not null,
    note          varchar(1000),
    status        varchar(10)   not null default 'OPEN',
    reported_at   timestamptz   not null,
    resolved_by   varchar(80),
    resolved_note varchar(500),
    resolved_at   timestamptz
);
create index vehicle_defect_open on vehicle_defect (reported_at) where status = 'OPEN';

create table driver_access (
    driver_id  varchar(80)  primary key,
    status     varchar(10)  not null,
    changed_at timestamptz  not null,
    reason     varchar(300),
    source     varchar(10)  not null,
    updated_at timestamptz  not null default now()
);

-- Per-device sync cursor: events are consumed strictly in sequence.
create table driver_device (
    device_id     varchar(120) primary key,
    driver_id     varchar(80)  not null,
    last_sequence bigint       not null default 0,
    last_seen_at  timestamptz  not null
);

-- Every driver event received, with its outcome (idempotency by event id). Handover codes are redacted.
create table driver_event (
    event_id    varchar(80)  primary key,
    device_id   varchar(120) not null,
    sequence    bigint       not null,
    driver_id   varchar(80)  not null,
    type        varchar(40),
    route_id    varchar(20),
    stop_id     varchar(64),
    captured_at timestamptz,
    received_at timestamptz  not null,
    outcome     varchar(10)  not null,
    code        varchar(80),
    message     varchar(1000),
    payload     jsonb        not null,
    constraint driver_event_device_seq unique (device_id, sequence)
);
create index driver_event_route on driver_event (route_id, received_at desc);
create index driver_event_driver on driver_event (driver_id, received_at desc);

create table sos_alert (
    event_id        varchar(80)  primary key,
    driver_id       varchar(80)  not null,
    route_id        varchar(20),
    kind            varchar(20)  not null,
    lat             double precision,
    lon             double precision,
    note            varchar(500),
    raised_at       timestamptz  not null,
    received_at     timestamptz  not null,
    acknowledged_by varchar(80),
    acknowledged_at timestamptz
);
create index sos_alert_open on sos_alert (received_at) where acknowledged_at is null;

-- Calls to orders-service, retried with backoff; one shipment's commands run in id order.
create table orders_command (
    id              bigserial    primary key,
    shipment_id     varchar(64)  not null,
    kind            varchar(12)  not null,
    idempotency_key varchar(200) not null,
    target          varchar(40),
    detail          varchar(500),
    courier         jsonb,
    status          varchar(10)  not null default 'PENDING',
    attempts        integer      not null default 0,
    next_attempt_at timestamptz  not null,
    last_error      varchar(1000),
    created_at      timestamptz  not null,
    updated_at      timestamptz  not null,
    constraint orders_command_kind_chk check (kind in ('TRANSITION', 'COURIER', 'TRACKING')),
    constraint orders_command_status_chk check (status in ('PENDING', 'DONE', 'FAILED'))
);
create index orders_command_due on orders_command (next_attempt_at, id) where status = 'PENDING';
create index orders_command_shipment on orders_command (shipment_id, id) where status = 'PENDING';
