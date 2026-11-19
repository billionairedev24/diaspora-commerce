-- Picture rules: ONE platform setting with named profiles (strict, startup, testing) and per-knob overrides, staff-controlled
-- (permission catalog:picture-rules.manage) and audited (docs/engineering/picture-rules.md).
--
-- The table is created EMPTY on purpose: while there is no row, the environment default profile is in force
-- (at.catalog.picture-rules.profile in application-<env>.yml: local and dev testing, uat and prod startup). The first staff
-- save inserts the row; every change, including the first, appends to picture_rules_audit. Nothing here changes how any
-- existing listing behaves.
create table picture_rules_setting (
    id         smallint     primary key check (id = 1),
    profile    varchar(16)  not null check (profile in ('strict', 'startup', 'testing')),
    overrides  jsonb        not null default '{}'::jsonb,
    updated_by varchar(80)  not null,
    updated_at timestamptz  not null,
    version    bigint       not null
);

create table picture_rules_audit (
    id               bigserial    primary key,
    version          bigint       not null,
    previous_profile varchar(16),
    profile          varchar(16)  not null,
    overrides        jsonb        not null default '{}'::jsonb,
    reason           text         not null,
    changed_by       varchar(80)  not null,
    changed_at       timestamptz  not null
);
create index picture_rules_audit_newest on picture_rules_audit (id desc);
