-- S225: sending purchase orders to suppliers.
-- EDI trading-partner settings per supplier (only EDI-enabled suppliers receive an X12 850).
create table supplier_edi_profile (
    supplier_id    uuid         primary key references supplier (id),
    enabled        boolean      not null,
    protocol       varchar(10)  not null check (protocol in ('SFTP', 'AS2')),
    interchange_id varchar(15)  not null,
    endpoint       varchar(500) not null,
    updated_by     varchar(120),
    updated_at     timestamptz  not null,
    version        bigint       not null default 0
);

-- One delivery per channel when a PO is sent: EMAIL (PDF stored in media, signed link mailed via notifications) and,
-- for EDI-enabled suppliers, EDI (X12 850 through the EdiTransport port). Worked off by a job with retries.
create table po_dispatch (
    id              uuid         primary key,
    po_id           uuid         not null references purchase_order (id),
    po_number       varchar(30)  not null,
    channel         varchar(10)  not null check (channel in ('EMAIL', 'EDI')),
    status          varchar(10)  not null check (status in ('PENDING', 'DONE', 'FAILED')),
    attempts        int          not null default 0,
    next_attempt_at timestamptz  not null,
    last_error      text,
    recipient       varchar(320),
    asset_id        uuid,
    link_expires_at timestamptz,
    control_number  varchar(20),
    transmission_id varchar(200),
    created_at      timestamptz  not null,
    updated_at      timestamptz  not null,
    version         bigint       not null default 0,
    unique (po_id, channel)
);
create index po_dispatch_due on po_dispatch (next_attempt_at) where status = 'PENDING';
