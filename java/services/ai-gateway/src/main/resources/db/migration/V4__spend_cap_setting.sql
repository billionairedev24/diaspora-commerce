-- Runtime-editable gateway-wide daily spend cap (owner request: "the AI spend cap can be configurable").
-- AI_GLOBAL_DAILY_COST_USD stays the INITIAL default: no row means "default"; the first saved value inserts the row.
-- AI_GLOBAL_DAILY_COST_MAX_USD is the hard ceiling and is enforced in code, never stored here.
-- Values are micro-dollars like the ledger (2,000,000 = USD 2); 0 = AI switched off.
create table ai_spend_cap (
    id                 smallint     primary key check (id = 1),
    cap_micros         bigint       not null check (cap_micros >= 0),
    version            bigint       not null check (version >= 1),
    changed_by         varchar(200) not null,
    changed_at         timestamptz  not null,
    reason             varchar(300) not null check (char_length(reason) between 3 and 300)
);

-- Append-only audit of every change: who, when, why, and the value before and after. previous_micros is null when the
-- value before was the deployment default (previous_source = 'DEFAULT').
create table ai_spend_cap_change (
    id                 uuid         primary key,
    version            bigint       not null unique,
    previous_micros    bigint       check (previous_micros >= 0),
    previous_source    varchar(10)  not null check (previous_source in ('DEFAULT', 'SAVED')),
    new_micros         bigint       not null check (new_micros >= 0),
    changed_by         varchar(200) not null,
    changed_at         timestamptz  not null,
    reason             varchar(300) not null check (char_length(reason) between 3 and 300)
);
create index ai_spend_cap_change_at on ai_spend_cap_change (changed_at desc);
