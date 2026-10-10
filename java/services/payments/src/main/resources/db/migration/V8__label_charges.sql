-- S256: shipping labels bought through AfroTransact (carrier-gateway) are charged to the seller. One row per label;
-- the inbox de-duplicates events and the unique label id makes a redelivered purchase or void a no-op.
create table label_charge (
    id                uuid primary key,
    label_id          varchar(80)  not null unique,
    org_id            varchar(80)  not null,
    shipment_id       varchar(80),
    order_id          varchar(80),
    carrier           varchar(20),
    service           varchar(60),
    tracking_number   varchar(80),
    currency          char(3)      not null,
    cost_minor        bigint       not null check (cost_minor >= 0),
    handling_minor    bigint       not null check (handling_minor >= 0),
    status            varchar(20)  not null check (status in ('CHARGED', 'CREDITED', 'VOIDED')),
    charge_journal_id uuid references journal_entry (id) deferrable initially deferred,
    credit_journal_id uuid references journal_entry (id) deferrable initially deferred,
    source_event_id   uuid,
    purchased_at      timestamptz  not null,
    charged_at        timestamptz,
    credited_at       timestamptz,
    version           bigint       not null default 0,
    check (cost_minor + handling_minor > 0),
    check (status <> 'CHARGED' or charge_journal_id is not null),
    check (status <> 'CREDITED' or (charge_journal_id is not null and credit_journal_id is not null))
);
create index label_charge_org on label_charge (org_id, purchased_at desc);
create index label_charge_charge_journal on label_charge (charge_journal_id);
create index label_charge_credit_journal on label_charge (credit_journal_id);
