-- Inventory: nodes, positions per SKU × node, lots with expiry, reservations and the append-only stock ledger.
-- Invariants (checked nightly by the reconciliation job):
--   position.on_hand  = Σ lot.qty_on_hand  of SELLABLE lots      = Σ ledger.delta_on_hand
--   position.reserved = Σ lot.qty_reserved of SELLABLE lots      = Σ ledger.delta_reserved
--   position.damaged  = Σ lot.qty_on_hand  of QUARANTINE / DAMAGED / EXPIRED lots = Σ ledger.delta_damaged
--   available         = on_hand − reserved

create table node (
    id                varchar(20)  primary key,
    name              varchar(120) not null,
    kind              varchar(30)  not null check (kind in ('FULFILMENT_CENTRE', 'ORIGIN_HUB', 'WAREHOUSE')),
    country           char(2)      not null,
    timezone          varchar(60)  not null,
    temperature_zones jsonb        not null default '[]',
    active            boolean      not null default true,
    created_at        timestamptz  not null default now()
);

create table inventory_position (
    sku        varchar(64) not null,
    node_id    varchar(20) not null references node (id),
    owner_org  varchar(80) not null,
    on_hand    int         not null default 0 check (on_hand >= 0),
    reserved   int         not null default 0 check (reserved >= 0),
    inbound    int         not null default 0 check (inbound >= 0),
    damaged    int         not null default 0 check (damaged >= 0),
    version    bigint      not null default 0,
    updated_at timestamptz not null,
    primary key (sku, node_id),
    constraint position_reserved_within_on_hand check (reserved <= on_hand)
);
create index inventory_position_owner on inventory_position (owner_org, sku);

create table lot (
    id                     uuid         primary key,
    sku                    varchar(64)  not null,
    lot_no                 varchar(64)  not null,
    node_id                varchar(20)  not null,
    owner_org              varchar(80)  not null,
    bin                    varchar(30),
    qty_on_hand            int          not null check (qty_on_hand >= 0),
    qty_reserved           int          not null default 0 check (qty_reserved >= 0),
    mfg_date               date,
    exp_date               date,
    received_at            timestamptz  not null,
    unit_landed_cost_minor bigint       not null check (unit_landed_cost_minor >= 0),
    currency               char(3)      not null,
    status                 varchar(20)  not null
        check (status in ('SELLABLE', 'QUARANTINE', 'EXPIRED', 'WRITTEN_OFF', 'DAMAGED')),
    reason                 text,
    markdown_flag          varchar(12)  not null default 'NONE' check (markdown_flag in ('NONE', 'WITHIN_60', 'WITHIN_30')),
    receipt_ref            varchar(80),
    version                bigint       not null default 0,
    updated_at             timestamptz  not null,
    constraint lot_unique_per_node unique (sku, lot_no, node_id),
    constraint lot_reserved_within_on_hand check (qty_reserved <= qty_on_hand),
    constraint lot_reserved_only_when_sellable check (qty_reserved = 0 or status = 'SELLABLE'),
    foreign key (sku, node_id) references inventory_position (sku, node_id)
);
create index lot_fefo on lot (sku, node_id, exp_date nulls last, received_at) where status = 'SELLABLE';
create index lot_expiry on lot (node_id, exp_date) where status in ('SELLABLE', 'QUARANTINE', 'DAMAGED');
create index lot_owner on lot (owner_org, sku);

create table reservation (
    id              uuid         primary key,
    order_id        varchar(80)  not null unique,
    idempotency_key varchar(160),
    status          varchar(12)  not null check (status in ('HELD', 'COMMITTED', 'RELEASED', 'EXPIRED')),
    expires_at      timestamptz,
    created_at      timestamptz  not null,
    updated_at      timestamptz  not null,
    version         bigint       not null default 0
);
create index reservation_expiry on reservation (expires_at) where status = 'HELD' and expires_at is not null;

create table reservation_line (
    reservation_id uuid        not null references reservation (id) on delete cascade,
    line_no        int         not null,
    sku            varchar(64) not null,
    node_id        varchar(20) not null,
    qty            int         not null check (qty > 0),
    primary key (reservation_id, line_no)
);

create table reservation_allocation (
    reservation_id         uuid        not null,
    line_no                int         not null,
    lot_id                 uuid        not null references lot (id),
    lot_no                 varchar(64) not null,
    qty                    int         not null check (qty > 0),
    exp_date               date,
    unit_landed_cost_minor bigint      not null,
    currency               char(3)     not null,
    primary key (reservation_id, line_no, lot_id),
    foreign key (reservation_id, line_no) references reservation_line (reservation_id, line_no) on delete cascade
);

-- Append-only stock ledger. One row per position (and lot) touched by a movement, with deltas and before/after values.
create table stock_movement (
    id              bigint       generated always as identity primary key,
    occurred_at     timestamptz  not null,
    type            varchar(24)  not null check (type in ('RECEIVE', 'PUT_AWAY', 'RESERVE', 'RELEASE', 'SHIP',
        'RETURN_RESTOCK', 'RETURN_DAMAGED', 'ADJUST', 'QUARANTINE', 'RELEASE_QUARANTINE', 'EXPIRE', 'WRITE_OFF',
        'TRANSFER', 'INBOUND')),
    sku             varchar(64)  not null,
    node_id         varchar(20)  not null,
    owner_org       varchar(80)  not null,
    lot_id          uuid,
    lot_no          varchar(64),
    qty             int          not null,
    delta_on_hand   int          not null,
    delta_reserved  int          not null,
    delta_damaged   int          not null,
    delta_inbound   int          not null,
    on_hand_before  int          not null,
    on_hand_after   int          not null,
    reserved_before int          not null,
    reserved_after  int          not null,
    damaged_before  int          not null,
    damaged_after   int          not null,
    inbound_before  int          not null,
    inbound_after   int          not null,
    reference       varchar(120),
    actor           varchar(120) not null,
    reason          text,
    correlation_id  varchar(120)
);
create index stock_movement_position on stock_movement (sku, node_id, id);
create index stock_movement_lot on stock_movement (lot_id, id) where lot_id is not null;
create index stock_movement_reference on stock_movement (reference) where reference is not null;
create index stock_movement_owner on stock_movement (owner_org, id);

create function stock_movement_append_only() returns trigger
    language plpgsql as
$$
begin
    raise exception 'stock_movement is append-only: % is not allowed', tg_op using errcode = '55000';
end;
$$;
create trigger stock_movement_no_update_delete
    before update or delete on stock_movement
    for each row execute function stock_movement_append_only();
create trigger stock_movement_no_truncate
    before truncate on stock_movement
    for each statement execute function stock_movement_append_only();

-- Responses of idempotent writes keyed by the Idempotency-Key header.
create table idempotency_record (
    scope       varchar(40)  not null,
    idem_key    varchar(160) not null,
    fingerprint varchar(64)  not null,
    response    jsonb,
    created_at  timestamptz  not null,
    primary key (scope, idem_key)
);

create table reconciliation_run (
    id                uuid        primary key,
    ran_at            timestamptz not null,
    positions_checked int         not null,
    drift_units       bigint      not null,
    drifts            jsonb       not null
);
create index reconciliation_run_recent on reconciliation_run (ran_at desc);
