-- AfroTransact Retail (1P) purchasing: suppliers, product master, purchase orders, inbound shipments / ASN,
-- goods receipts, supplier invoices (three-way match) and the GL journal. Child collections that are always read and
-- written with their aggregate (PO lines, ASN lines, documents, receipt lines …) are jsonb documents.

create sequence if not exists po_number_seq start with 323;
create sequence if not exists shipment_seq start with 1;
create sequence if not exists receipt_seq start with 1;
create sequence if not exists journal_seq start with 24118;
create sequence if not exists item_reference_seq start with 200100;

create table supplier (
    id             uuid primary key,
    code           varchar(20)  not null unique,
    name           varchar(200) not null,
    country        char(2)      not null,
    city           varchar(200),
    currency       char(3)      not null,
    payment_terms  varchar(200),
    lead_time_days int          not null,
    certifications jsonb        not null,
    contacts       jsonb        not null,
    status         varchar(20)  not null check (status in ('ONBOARDING', 'APPROVED', 'ON_WATCH', 'INACTIVE')),
    status_reason  text,
    sc_receipts    int          not null default 0,
    sc_on_time     int          not null default 0,
    sc_units       bigint       not null default 0,
    sc_defective   bigint       not null default 0,
    created_at     timestamptz  not null,
    updated_at     timestamptz  not null,
    version        bigint       not null default 0
);
create index supplier_status on supplier (status);

create table retail_item (
    id                  uuid primary key,
    sku                 varchar(40)  not null unique,
    item_reference      bigint       not null unique,
    gtin                varchar(14)  not null unique,
    name                varchar(200) not null,
    category            varchar(120),
    atin                char(10) unique,
    primary_supplier_id uuid         not null references supplier (id),
    supplier_items      jsonb        not null,
    origin_country      char(2)      not null,
    hs_code             varchar(20)  not null,
    duty_rate_percent   numeric(7, 3) not null,
    shelf_life_days     int          not null,
    storage             varchar(10)  not null,
    reorder_point       int          not null,
    pack_size           varchar(60),
    inner_pack          int          not null,
    case_pack           int          not null,
    unit_weight_grams   int          not null,
    standard_cost_minor bigint       not null,
    price_minor         bigint       not null,
    currency            char(3)      not null,
    listing_status      varchar(10)  not null check (listing_status in ('DRAFT', 'LIVE', 'PAUSED')),
    created_at          timestamptz  not null,
    updated_at          timestamptz  not null,
    version             bigint       not null default 0
);
create index retail_item_supplier on retail_item (primary_supplier_id);

create table purchase_order (
    id                  uuid primary key,
    number              varchar(30)   not null unique,
    supplier_id         uuid          not null references supplier (id),
    supplier_code       varchar(20)   not null,
    currency            char(3)       not null,
    fx_rate             numeric(20, 8) not null,
    fx_base_currency    char(3)       not null,
    fx_date             date          not null,
    destination_node_id varchar(20)   not null,
    incoterm            varchar(80)   not null,
    order_date          date          not null,
    expected_date       date,
    lines               jsonb         not null,
    total_minor         bigint        not null,
    status              varchar(20)   not null,
    approval_tier       varchar(10)   not null,
    shipment_booked     boolean       not null,
    created_by          varchar(120),
    approved_by         varchar(120),
    history             jsonb         not null,
    created_at          timestamptz   not null,
    updated_at          timestamptz   not null,
    version             bigint        not null default 0
);
create index purchase_order_status on purchase_order (status, created_at desc);
create index purchase_order_supplier on purchase_order (supplier_id, created_at desc);

create table inbound_shipment (
    id                  uuid primary key,
    reference           varchar(30)  not null unique,
    po_id               uuid         not null references purchase_order (id),
    po_number           varchar(30)  not null,
    supplier_id         uuid         not null references supplier (id),
    mode                varchar(12)  not null,
    carrier             varchar(120),
    tracking_ref        varchar(120),
    bill_of_lading      varchar(120),
    origin_port         varchar(200),
    destination_port    varchar(200),
    destination_node_id varchar(20)  not null,
    etd                 date,
    eta                 date,
    stage               varchar(20)  not null,
    lines               jsonb        not null,
    cartons             jsonb        not null,
    documents           jsonb        not null,
    charges             jsonb        not null,
    milestones          jsonb        not null,
    note                text,
    created_at          timestamptz  not null,
    updated_at          timestamptz  not null,
    version             bigint       not null default 0
);
create index inbound_shipment_po on inbound_shipment (po_id);
create index inbound_shipment_stage on inbound_shipment (stage, eta);

create table goods_receipt (
    id               uuid primary key,
    number           varchar(30)  not null unique,
    po_id            uuid         not null references purchase_order (id),
    po_number        varchar(30)  not null,
    shipment_id      uuid references inbound_shipment (id),
    shipment_ref     varchar(30),
    node_id          varchar(20)  not null,
    dock             varchar(40),
    status           varchar(10)  not null check (status in ('OPEN', 'POSTING', 'COMPLETED')),
    lines            jsonb        not null,
    charges          jsonb        not null,
    over_approved_by varchar(120),
    discrepancies    jsonb        not null,
    landed_cost      jsonb,
    posted_lots      jsonb        not null,
    journal_entry_id uuid,
    opened_by        varchar(120),
    opened_at        timestamptz  not null,
    completed_by     varchar(120),
    completed_at     timestamptz,
    updated_at       timestamptz  not null,
    version          bigint       not null default 0
);
create index goods_receipt_po on goods_receipt (po_id);
create unique index goods_receipt_active_shipment on goods_receipt (shipment_id)
    where shipment_id is not null and status <> 'COMPLETED';

-- Lot numbers are unique per SKU across every receipt (S072).
create table received_lot (
    sku        varchar(40) not null,
    lot_no     varchar(20) not null,
    receipt_id uuid        not null references goods_receipt (id),
    primary key (sku, lot_no)
);
create index received_lot_receipt on received_lot (receipt_id);

create table supplier_invoice (
    id               uuid primary key,
    supplier_id      uuid           not null references supplier (id),
    po_id            uuid           not null references purchase_order (id),
    po_number        varchar(30)    not null,
    invoice_number   varchar(60)    not null,
    invoice_date     date           not null,
    currency         char(3)        not null,
    fx_rate          numeric(20, 8) not null,
    fx_base_currency char(3)        not null,
    fx_date          date           not null,
    lines            jsonb          not null,
    total_minor      bigint         not null,
    status           varchar(20)    not null,
    match            jsonb          not null,
    resolution_note  text,
    resolved_by      varchar(120),
    journal_entry_id uuid,
    received_by      varchar(120),
    created_at       timestamptz    not null,
    updated_at       timestamptz    not null,
    version          bigint         not null default 0,
    unique (supplier_id, invoice_number)
);
create index supplier_invoice_po on supplier_invoice (po_id);
create index supplier_invoice_status on supplier_invoice (status, created_at desc);

-- Append-only: entries are never updated; corrections are new entries.
create table gl_journal_entry (
    id           uuid primary key,
    number       varchar(20)  not null unique,
    entry_date   date         not null,
    source       varchar(20)  not null,
    source_ref   varchar(80)  not null,
    memo         text,
    currency     char(3)      not null,
    lines        jsonb        not null,
    amount_minor bigint       not null,
    posted_by    varchar(120),
    posted_at    timestamptz  not null
);
create index gl_journal_entry_ref on gl_journal_entry (source_ref);
create index gl_journal_entry_date on gl_journal_entry (entry_date desc, number desc);

create table idempotency_record (
    scope       varchar(40)  not null,
    idem_key    varchar(160) not null,
    fingerprint varchar(64)  not null,
    response    jsonb,
    created_at  timestamptz  not null,
    primary key (scope, idem_key)
);
