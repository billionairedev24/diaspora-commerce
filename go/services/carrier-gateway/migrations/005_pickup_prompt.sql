-- Who switched a pickup place on, and when (audit), plus the one-time "let customers collect here" card state per seller
-- organisation and the staff-editable settings of carrier-gateway.
alter table pickup_location add column if not exists enabled_by text not null default '';
alter table pickup_location add column if not exists enabled_at timestamptz;

create table if not exists pickup_prompt (
    owner_org     text primary key,
    state         text        not null check (state in ('SNOOZED', 'NEVER')),
    snoozed_until timestamptz,
    decided_by    text        not null,
    decided_at    timestamptz not null
);

create table if not exists carrier_setting (
    key        text primary key,
    int_value  int         not null,
    updated_by text        not null,
    updated_at timestamptz not null
);
