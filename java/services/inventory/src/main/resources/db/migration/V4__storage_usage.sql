-- S206 metered billing (FBA storage): unit dimensions per SKU and a daily storage snapshot per owner and node.
-- Cubic-feet-days of a day = the day's snapshot cubic feet (the snapshot is retaken hourly; the last one of the
-- UTC day stands). Units without dimensions use at.inventory.storage.default-unit-cubic-feet and are counted.

create table sku_dimension (
    sku             varchar(64)   primary key,
    length_in       numeric(8, 2) not null check (length_in > 0),
    width_in        numeric(8, 2) not null check (width_in > 0),
    height_in       numeric(8, 2) not null check (height_in > 0),
    unit_cubic_feet numeric(14, 6) not null check (unit_cubic_feet > 0),
    updated_by      varchar(120)  not null,
    updated_at      timestamptz   not null
);

create table storage_snapshot (
    day              date           not null,
    owner_org        varchar(80)    not null,
    node_id          varchar(20)    not null references node (id),
    units            int            not null check (units >= 0),
    unmeasured_units int            not null check (unmeasured_units >= 0),
    cubic_feet       numeric(16, 4) not null check (cubic_feet >= 0),
    taken_at         timestamptz    not null,
    primary key (day, owner_org, node_id)
);
create index storage_snapshot_owner on storage_snapshot (owner_org, day);
