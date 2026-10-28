-- S265: the driver cost model behind run economics (cost per hour and per mile, the time model) is staff-editable
-- configuration, not only environment config. Until staff save once, the environment's defaults
-- (at.delivery-runs.economics.*) apply and there is no row; every save makes the next version and is audited.

create table economics_settings (
    id         smallint     primary key check (id = 1),
    config     jsonb        not null,
    version    bigint       not null,
    updated_at timestamptz  not null,
    updated_by varchar(120) not null,
    reason     varchar(500) not null
);

-- Append-only audit of every save: the settings before (the environment defaults for the first save) and after,
-- who, when, why.
create table economics_settings_change (
    id         uuid         primary key,
    version    bigint       not null unique,
    before     jsonb        not null,
    after      jsonb        not null,
    actor      varchar(120) not null,
    reason     varchar(500) not null,
    changed_at timestamptz  not null
);
create index economics_settings_change_at on economics_settings_change (changed_at desc);
