-- Reconciliation as a workflow (each Stripe object that differs from the ledger becomes an item Finance explains or
-- corrects) and ledger adjustments (a correcting journal proposed by one person and approved by another).

create table reconciliation_item (
    id              uuid primary key,
    run_id          uuid         not null references reconciliation_run (id),
    window_start    timestamptz  not null,
    stripe_ref      varchar(120) not null,
    currency        char(3)      not null,
    ledger_minor    bigint       not null,
    stripe_minor    bigint       not null,
    status          varchar(24)  not null check (status in ('OPEN', 'EXPLAINED', 'ADJUSTED', 'SUPERSEDED')),
    note            text,
    resolved_by     varchar(120),
    resolved_at     timestamptz,
    created_at      timestamptz  not null,
    unique (run_id, stripe_ref)
);
create index reconciliation_item_status on reconciliation_item (status, created_at desc);
create index reconciliation_item_ref on reconciliation_item (window_start, stripe_ref);

create table ledger_adjustment (
    id            uuid primary key,
    item_id       uuid references reconciliation_item (id),
    stripe_ref    varchar(120),
    currency      char(3)      not null,
    reason        text         not null,
    lines         jsonb        not null,
    status        varchar(16)  not null check (status in ('PROPOSED', 'POSTED', 'REJECTED')),
    proposed_by   varchar(120) not null,
    proposed_at   timestamptz  not null,
    decided_by    varchar(120),
    decided_at    timestamptz,
    decision_note text,
    journal_id    uuid
);
create index ledger_adjustment_status on ledger_adjustment (status, proposed_at desc);
create index ledger_adjustment_item on ledger_adjustment (item_id);
