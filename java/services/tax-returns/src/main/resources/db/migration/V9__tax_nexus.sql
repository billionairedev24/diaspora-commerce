-- Nexus tracker: per state, where AfroTransact stands against the state's sales threshold, and whether it has registered.
-- A row exists only for states staff have edited; every other state uses the default thresholds in the service.
create table tax_nexus_state (
    jurisdiction            varchar(20)  primary key,
    sales_threshold_minor   bigint       not null check (sales_threshold_minor >= 0),
    transactions_threshold  integer      check (transactions_threshold is null or transactions_threshold >= 0),
    registered              boolean      not null default false,
    registration_number     varchar(80),
    registered_on           date,
    note                    text,
    updated_by              varchar(120) not null,
    updated_at              timestamptz  not null
);
