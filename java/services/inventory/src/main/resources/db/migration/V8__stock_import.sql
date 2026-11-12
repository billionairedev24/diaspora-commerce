-- S395: stock CSV import (bulk counts). A seller uploads a file of counts for its own stock; the file is validated row by
-- row, shown as a preview and applied in the background, one row per transaction, as ADJUST movements in the ledger with
-- the reason BULK_COUNT and the import id. Rows are never applied to AfroTransact Retail's own stock, and a count can never
-- take a lot below its reserved units. This migration only adds tables: it moves no stock.

create sequence stock_import_seq start with 1 increment by 1;

create table stock_import (
    id             uuid         primary key,
    reference      varchar(20)  not null,
    owner_org      varchar(80)  not null,
    uploaded_by    varchar(120) not null,
    file_name      varchar(200) not null,
    file_sha256    char(64)     not null,
    same_file_as   uuid,
    mode           varchar(10)  not null check (mode in ('ABSOLUTE', 'DELTA')),
    header         jsonb        not null,
    status         varchar(20)  not null check (status in ('VALIDATED', 'QUEUED', 'RUNNING', 'COMPLETED', 'FAILED')),
    total_rows     integer      not null,
    valid_rows     integer      not null,
    warning_rows   integer      not null,
    error_rows     integer      not null,
    processed_rows integer      not null default 0,
    applied_rows   integer      not null default 0,
    unchanged_rows integer      not null default 0,
    failed_rows    integer      not null default 0,
    created_at     timestamptz  not null,
    started_at     timestamptz,
    finished_at    timestamptz,
    heartbeat_at   timestamptz,
    version        bigint       not null default 0
);
create index stock_import_org on stock_import (owner_org, created_at desc);
create index stock_import_org_hash on stock_import (owner_org, mode, file_sha256, created_at desc);
create index stock_import_active on stock_import (owner_org) where status in ('QUEUED', 'RUNNING');

create table stock_import_row (
    import_id   uuid         not null references stock_import (id) on delete cascade,
    row_number  integer      not null,
    sku         varchar(60)  not null,
    node_id     varchar(20)  not null,
    lot_no      varchar(60),
    quantity    integer,
    expiry      date,
    status      varchar(10)  not null check (status in ('VALID', 'WARNING', 'ERROR')),
    messages    jsonb        not null,
    cells       jsonb        not null,
    outcome     varchar(10)  not null default 'PENDING'
                check (outcome in ('PENDING', 'APPLIED', 'UNCHANGED', 'FAILED', 'SKIPPED')),
    outcome_note text,
    qty_before  integer,
    qty_after   integer,
    primary key (import_id, row_number)
);
create index stock_import_row_pending on stock_import_row (import_id, row_number) where outcome = 'PENDING';
