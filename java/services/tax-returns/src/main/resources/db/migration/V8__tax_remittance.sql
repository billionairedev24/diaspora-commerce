-- Tax remittance record: what AfroTransact filed and paid to each state for a month's marketplace-facilitator tax.
-- Append-only in spirit: a mistake is voided (with a reason) and recorded again; the unique index lets one active
-- remittance exist per jurisdiction, month and currency.
create table tax_remittance (
    id               uuid primary key,
    jurisdiction     varchar(20)  not null,
    period           date         not null,
    currency         char(3)      not null,
    due_minor        bigint       not null,
    paid_minor       bigint       not null check (paid_minor >= 0),
    filed_on         date         not null,
    paid_on          date         not null,
    confirmation_ref varchar(80),
    payment_ref      varchar(80),
    note             text,
    recorded_by      varchar(120) not null,
    recorded_at      timestamptz  not null,
    voided_at        timestamptz,
    voided_by        varchar(120),
    void_reason      text,
    constraint tax_remittance_period_ck check (extract(day from period) = 1),
    constraint tax_remittance_void_ck check ((voided_at is null) = (void_reason is null))
);
create unique index tax_remittance_active on tax_remittance (jurisdiction, period, currency) where voided_at is null;
create index tax_remittance_period on tax_remittance (period desc);
