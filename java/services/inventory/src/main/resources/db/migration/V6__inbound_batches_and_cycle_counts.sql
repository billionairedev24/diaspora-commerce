-- S281: the packing list of an inbound line (case pack, lot, made and expiry dates) and cycle counts.

alter table inbound_shipment_line
    add column case_pack integer check (case_pack between 1 and 10000),
    add column lot_no    varchar(59),
    add column mfg_date  date,
    add column exp_date  date;

-- A cycle count of one node, narrowed to a SKU and / or a bin prefix. Lines are the lots counted, with what the
-- system expected when the count started (blind counts hide it from the counter), the counted quantity and, for
-- variances, the reason code posted as an ADJUST movement.
create table cycle_count (
    id          uuid primary key,
    number      varchar(30)  not null unique,
    node_id     varchar(20)  not null references node (id),
    sku         varchar(64),
    bin_prefix  varchar(30),
    owner_org   varchar(80),
    blind       boolean      not null default false,
    status      varchar(12)  not null check (status in ('OPEN', 'COUNTED', 'POSTED', 'CANCELLED')),
    note        varchar(500),
    created_by  varchar(120) not null,
    created_at  timestamptz  not null,
    counted_at  timestamptz,
    posted_by   varchar(120),
    posted_at   timestamptz,
    updated_at  timestamptz  not null,
    version     bigint       not null default 0
);

create index cycle_count_node_status on cycle_count (node_id, status, created_at desc);

create table cycle_count_line (
    count_id     uuid         not null references cycle_count (id) on delete cascade,
    line_no      integer      not null,
    lot_id       uuid         not null references lot (id),
    sku          varchar(64)  not null,
    lot_no       varchar(64)  not null,
    bin          varchar(30),
    exp_date     date,
    expected_qty integer      not null check (expected_qty >= 0),
    counted_qty  integer check (counted_qty >= 0),
    reason_code  varchar(20),
    note         varchar(500),
    counted_by   varchar(120),
    counted_at   timestamptz,
    posted_delta integer,
    primary key (count_id, line_no)
);

create sequence cycle_count_seq;
