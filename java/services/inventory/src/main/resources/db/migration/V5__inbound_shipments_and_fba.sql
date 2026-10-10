-- S257 / S226 / S067 / S066: inbound shipments (seller FBA plans and AfroTransact Retail ASNs) with boxes, unit labels,
-- tracking and receiving against the plan; fulfilment-centre addresses for inbound labels; projection of
-- Fulfilled-by-AfroTransact offers to flag stranded FBA stock.

alter table node
    add column address_line1  varchar(200),
    add column address_line2  varchar(200),
    add column address_city   varchar(100),
    add column address_region varchar(60),
    add column address_postal varchar(20),
    add column address_phone  varchar(40);

-- Fulfilment centres of design/at-inventory-hub.js (street lines are set by staff: PUT /v1/nodes/{id}/address).
update node set address_city = 'Houston', address_region = 'TX', address_postal = '77032' where id = 'HOU-FC';
update node set address_city = 'Edison', address_region = 'NJ', address_postal = '08837' where id = 'EWR-FC';
update node set address_city = 'Atlanta', address_region = 'GA', address_postal = '30349' where id = 'ATL-FC';
update node set address_city = 'Ontario', address_region = 'CA', address_postal = '91761' where id = 'LAX-FC';
update node set address_city = 'Tema', address_region = 'Greater Accra' where id = 'TEM-HUB';
update node set address_city = 'Lagos', address_region = 'Lagos' where id = 'LOS-HUB';

create sequence inbound_shipment_seq start with 6600;

create table inbound_shipment (
    id                 uuid         primary key,
    number             varchar(80)  not null unique,
    source             varchar(12)  not null check (source in ('FBA', 'RETAIL_ASN')),
    external_ref       varchar(120),
    owner_org          varchar(80)  not null,
    node_id            varchar(20)  not null references node (id),
    requested_node_id  varchar(20),
    assignment_reason  varchar(200),
    name               varchar(120),
    status             varchar(12)  not null
        check (status in ('WORKING', 'SHIPPED', 'RECEIVING', 'CLOSED', 'CANCELLED')),
    ship_from          jsonb,
    carrier            varchar(40),
    tracking_number    varchar(60),
    tracking_source    varchar(20) check (tracking_source in ('SELLER', 'CARRIER_GATEWAY')),
    carrier_label_id   varchar(80),
    label_cost_minor   bigint,
    label_currency     char(3),
    label_document_url text,
    expected_arrival   date,
    receipt_count      int          not null default 0,
    created_by         varchar(120) not null,
    created_at         timestamptz  not null,
    shipped_at         timestamptz,
    closed_at          timestamptz,
    updated_at         timestamptz  not null,
    version            bigint       not null default 0
);
create unique index inbound_shipment_external on inbound_shipment (source, external_ref) where external_ref is not null;
create index inbound_shipment_owner on inbound_shipment (owner_org, created_at desc);
create index inbound_shipment_open on inbound_shipment (node_id, status) where status not in ('CLOSED', 'CANCELLED');

create table inbound_shipment_line (
    shipment_id      uuid        not null references inbound_shipment (id) on delete cascade,
    line_no          int         not null,
    sku              varchar(64) not null,
    atin             varchar(20),
    unit_label       varchar(20),
    expected_qty     int         not null check (expected_qty >= 0),
    received_good    int         not null default 0 check (received_good >= 0),
    received_damaged int         not null default 0 check (received_damaged >= 0),
    inbound_open     int         not null default 0 check (inbound_open >= 0),
    primary key (shipment_id, line_no),
    constraint inbound_line_sku_unique unique (shipment_id, sku)
);
create index inbound_line_sku on inbound_shipment_line (sku);

create table inbound_box (
    shipment_id uuid          not null references inbound_shipment (id) on delete cascade,
    box_no      int           not null,
    lpn         varchar(20)   not null unique,
    contents    jsonb         not null,
    weight_lb   numeric(8, 2),
    length_in   numeric(8, 2),
    width_in    numeric(8, 2),
    height_in   numeric(8, 2),
    received_at timestamptz,
    primary key (shipment_id, box_no)
);

-- Projection of offers.offer.* (offers-service): which FBA stock (owner, inventory SKU) has an open
-- Fulfilled-by-AfroTransact offer. Stock without an ACTIVE one is stranded (S067).
create table fba_offer_link (
    offer_id   uuid         primary key,
    owner_org  varchar(80)  not null,
    sku        varchar(64)  not null,
    atin       varchar(20),
    status     varchar(20)  not null,
    live       boolean      not null,
    updated_at timestamptz  not null
);
create index fba_offer_link_owner_sku on fba_offer_link (owner_org, sku);
