-- S226: AfroTransact Retail supplier payments. retail-service publishes retail.payment.released when a purchase order's
-- invoices pass the three-way match; payments books the payable (Dr 1400 / Cr 2500:<supplier>) and schedules the
-- payment, which Finance pays from the operating bank account (Dr 2500:<supplier> / Cr 1050). Retail is the merchant
-- of record: these are AfroTransact's own debts, never seller payables, transfers or Connect payouts.
-- One row per purchase order; the inbox de-duplicates events and the unique po_id makes a redelivery a no-op.
create table supplier_payment (
    id                 uuid primary key,
    po_id              uuid         not null unique,
    po_number          varchar(40),
    supplier_id        uuid         not null,
    supplier_code      varchar(40),
    currency           char(3)      not null,
    amount_minor       bigint       not null check (amount_minor > 0),
    invoices           jsonb        not null default '[]',
    status             varchar(20)  not null check (status in ('SCHEDULED', 'ON_HOLD', 'PAID')),
    due_on             date         not null,
    payable_journal_id uuid         not null references journal_entry (id) deferrable initially deferred,
    payment_journal_id uuid references journal_entry (id) deferrable initially deferred,
    payment_reference  varchar(120),
    hold_reason        varchar(500),
    paid_by            varchar(120),
    source_event_id    uuid,
    scheduled_at       timestamptz  not null,
    paid_at            timestamptz,
    updated_at         timestamptz  not null,
    version            bigint       not null default 0,
    check (status <> 'PAID' or (payment_journal_id is not null and payment_reference is not null))
);
create index supplier_payment_due on supplier_payment (status, due_on);
create index supplier_payment_supplier on supplier_payment (supplier_id, scheduled_at desc);
